package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class ChannelModerationRules(
    @SerialName("salons") val channels: List<String>,
    @SerialName("purgeMaxMessages") val purgeMax: Int,
    @SerialName("rechercheMessages") val scanLimit: Int,
    @SerialName("ancienneteMaxJours") val maxAgeDays: Int,
    @SerialName("modesLentsSecondes") val slowModes: List<Int>,
    @SerialName("repriseVerrouSecondes") val recoverySeconds: Long,
) {
    init {
        require(channels.isNotEmpty() && channels.distinct().size == channels.size)
        require(purgeMax in 1..100 && scanLimit in purgeMax..100)
        require(maxAgeDays in 1..13)
        require(slowModes.isNotEmpty() && slowModes.distinct().size == slowModes.size && slowModes.all { it in 0..21600 })
        require(recoverySeconds in 60..3600)
    }
}

@Serializable enum class ChannelAction { PURGE, SLOW, LOCK, UNLOCK }
enum class ChannelCaseStatus { RESERVED, ATTEMPTED, SUCCEEDED, FAILED, UNCERTAIN }
@Serializable data class ChannelPermissionValue(val exists: Boolean, val allow: Long = 0, val deny: Long = 0) {
    init { require(allow and deny == 0L && (exists || (allow == 0L && deny == 0L))) }
}
@Serializable data class ChannelModerationSnapshot(val everyone: ChannelPermissionValue, val slowSeconds: Int, val lockable: Boolean)
@Serializable data class ChannelActionPlan(
    val action: ChannelAction,
    val before: ChannelModerationSnapshot,
    val after: ChannelModerationSnapshot,
    val messageIds: List<String> = emptyList(),
    val lockVersion: Long? = null,
    val lockId: String? = null,
)
data class ChannelModerationCase(
    val id: String, val actorId: String, val channelId: String, val reason: String,
    val createdAt: Instant, val expiresAt: Instant, val plan: ChannelActionPlan,
    val status: ChannelCaseStatus = ChannelCaseStatus.RESERVED, val affected: Int? = null,
)
data class ChannelModerationLock(
    val channelId: String, val original: ChannelPermissionValue, val locked: ChannelPermissionValue,
    val version: Long, val availableAfter: Instant, val id: String,
)
/** Ce refus prouve qu'aucune requête de mutation n'a été envoyée. Un autre échec reste incertain. */
class ChannelActionRefused(message: String) : DiscordRefusal(message)

interface ChannelModerationGateway {
    suspend fun snapshot(actorId: String, channelId: String, action: ChannelAction): ChannelModerationSnapshot
    suspend fun candidates(actorId: String, channelId: String, count: Int, now: Instant): List<String>
    suspend fun execute(case: ChannelModerationCase, now: Instant): Int
}

/** N'altère que les bits d'écriture ; les changements manuels étrangers à ce masque restent en place. */
fun restoreChannelWriting(current: ChannelPermissionValue, original: ChannelPermissionValue, locked: ChannelPermissionValue, mask: Long): ChannelPermissionValue {
    fun bits(value: ChannelPermissionValue) = (value.allow and mask) to (value.deny and mask)
    if (bits(current) != bits(locked) && bits(current) != bits(original)) throw ChannelActionRefused("Les droits d'écriture ont changé manuellement. Vérifie les permissions du salon.")
    if (!current.exists && bits(current) == bits(original)) return current
    val allow = (current.allow and mask.inv()) or (original.allow and mask)
    val deny = (current.deny and mask.inv()) or (original.deny and mask)
    return ChannelPermissionValue(original.exists || allow != 0L || deny != 0L, allow, deny)
}

class ChannelModerationFeature(
    private val context: BotContext,
    private val rules: ChannelModerationRules,
    private val moderation: ModerationRules,
    private val store: ChannelModerationStore,
    private val gateway: ChannelModerationGateway,
    private val journal: BotJournal,
) : Feature {
    private val writingMask = CHANNEL_WRITING_MASK
    override val name = "moderation-salons"
    override val commands = listOf(
        command("purger", "Supprimer des messages après confirmation", CommandPermission.MANAGE_MESSAGES,
            CommandOption("nombre", "Nombre maximal de messages à supprimer", true)),
        command("mode-lent", "Régler le délai entre les messages", CommandPermission.MANAGE_CHANNELS,
            CommandOption("secondes", "Délai en secondes, 0 pour désactiver", true, rules.slowModes.map(Int::toString))),
        command("verrouiller", "Suspendre l'écriture publique du salon", CommandPermission.MANAGE_CHANNELS),
        command("deverrouiller", "Restaurer les droits d'écriture enregistrés", CommandPermission.MANAGE_CHANNELS),
    )
    private fun command(name: String, description: String, permission: CommandPermission, vararg options: CommandOption) =
        CommandSpec(name, description, options.toList() + CommandOption("motif", "Motif", true), true, permission)

    override suspend fun start() {
        purge()
        context.timer.every(Duration.ofSeconds(moderation.purgeSeconds)) { purge() }
    }
    private suspend fun purge() {
        try { store.purge(context.timer.now().minus(Duration.ofDays(moderation.retentionDays.toLong()))) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { log.error("Discord salons : purge de l'audit en échec ({})", e::class.simpleName) }
    }

    override suspend fun handle(event: DiscordEvent) {
        when (event) {
            is DiscordEvent.CommandUsed -> if (commands.any { it.name == event.name }) guarded(event.reply) { preview(event) }
            is DiscordEvent.ButtonPressed -> if (event.id.startsWith(CONFIRM) || event.id.startsWith(CANCEL)) guarded(event.reply) { button(event) }
            else -> Unit
        }
    }
    private suspend fun guarded(reply: Reply, action: suspend () -> Unit) {
        try { action() }
        catch (e: CancellationException) { throw e }
        catch (e: ChannelActionRefused) { say(reply, e.message ?: "Action refusée.") }
        catch (e: Exception) {
            log.error("Discord salons : résultat non confirmé ({})", e::class.simpleName)
            say(reply, "Le résultat n'a pas pu être confirmé. Vérifie le salon et le journal avant toute nouvelle décision.")
        }
    }
    private suspend fun authorized(user: DiscordUser) {
        val roles = setOfNotNull(context.ids.role("staff"), context.ids.role("moderation"))
        if (user.isBot || roles.isEmpty() || context.server.freshRolesOf(user.id).none { it in roles }) throw ChannelActionRefused(ModerationTexts.NOT_STAFF)
    }
    private fun channel(id: String?): String = id?.takeIf { candidate -> rules.channels.any { context.ids.channel(it) == candidate } }
        ?: throw ChannelActionRefused("Cette commande s'utilise dans un salon public autorisé par le plan.")

    private suspend fun preview(event: DiscordEvent.CommandUsed) {
        event.reply.acknowledge()
        authorized(event.user)
        val channel = channel(event.channelId)
        val reason = event.options["motif"]?.trim().orEmpty()
        if (reason.isBlank() || reason.length > moderation.maxReasonLength) throw ChannelActionRefused(ModerationTexts.BAD_REASON)
        val action = when (event.name) { "purger" -> ChannelAction.PURGE; "mode-lent" -> ChannelAction.SLOW; "verrouiller" -> ChannelAction.LOCK; else -> ChannelAction.UNLOCK }
        val before = gateway.snapshot(event.user.id, channel, action)
        var after = before
        var ids = emptyList<String>()
        var version: Long? = null
        var lockId: String? = null
        when (action) {
            ChannelAction.PURGE -> {
                val count = event.options["nombre"]?.toIntOrNull()?.takeIf { it in 1..rules.purgeMax }
                    ?: throw ChannelActionRefused("Choisis un nombre de 1 à ${rules.purgeMax}.")
                ids = gateway.candidates(event.user.id, channel, count, context.timer.now())
                if (ids.isEmpty()) throw ChannelActionRefused("Aucun message récent non épinglé à supprimer.")
                require(ids.distinct().size == ids.size && ids.size <= count)
            }
            ChannelAction.SLOW -> {
                val seconds = event.options["secondes"]?.toIntOrNull()?.takeIf { it in rules.slowModes }
                    ?: throw ChannelActionRefused("Choisis un délai proposé par la commande.")
                if (seconds == before.slowSeconds) throw ChannelActionRefused("Ce délai est déjà en place.")
                after = before.copy(slowSeconds = seconds)
            }
            ChannelAction.LOCK -> {
                if (store.lock(channel) != null) throw ChannelActionRefused("Un verrou est déjà enregistré. Utilise /deverrouiller pour restaurer son état initial.")
                if (!before.lockable) throw ChannelActionRefused("Des exceptions de permission contournent ce verrouillage. Vérifie les permissions du salon.")
                val locked = ChannelPermissionValue(true, before.everyone.allow and writingMask.inv(), before.everyone.deny or writingMask)
                if (locked == before.everyone) throw ChannelActionRefused("Le salon est déjà verrouillé. Aucun état manuel ne sera remplacé.")
                after = before.copy(everyone = locked)
            }
            ChannelAction.UNLOCK -> {
                val lock = store.lock(channel) ?: throw ChannelActionRefused("Aucun verrou enregistré par Oooscar pour ce salon.")
                if (lock.availableAfter > context.timer.now()) throw ChannelActionRefused("Une opération est encore en cours ou son résultat est incertain. Réessaie après ${lock.availableAfter}.")
                version = lock.version
                lockId = lock.id
                after = before.copy(everyone = restoreChannelWriting(before.everyone, lock.original, lock.locked, writingMask))
            }
        }
        val now = context.timer.now()
        val case = ChannelModerationCase(UUID.randomUUID().toString(), event.user.id, channel, reason, now,
            now.plusSeconds(moderation.confirmationSeconds), ChannelActionPlan(action, before, after, ids, version, lockId))
        if (!store.reserve(case)) throw ChannelActionRefused("La demande n'a pas été enregistrée.")
        val description = when (action) {
            ChannelAction.PURGE -> "Supprimer définitivement jusqu'à ${ids.size} messages sélectionnés ? Les messages épinglés et ceux de plus de ${rules.maxAgeDays} jours sont conservés."
            ChannelAction.SLOW -> "Passer le délai de ${before.slowSeconds} à ${after.slowSeconds} secondes ?"
            ChannelAction.LOCK -> "Suspendre l'écriture publique ? Les exceptions du staff et des administrateurs sont conservées. Les permissions initiales sont enregistrées."
            ChannelAction.UNLOCK -> "Restaurer les droits d'écriture enregistrés ? Les restrictions manuelles antérieures sont conservées."
        }
        event.reply.send(BotMessage("$description\nSalon : <#$channel>\nMotif : $reason\nConfirmation valable ${moderation.confirmationSeconds} secondes.", buttons = listOf(
            ButtonSpec(CONFIRM + case.id, "Confirmer", ButtonStyle.DANGER), ButtonSpec(CANCEL + case.id, "Annuler"))), onlyToUser = true)
    }

    private suspend fun button(event: DiscordEvent.ButtonPressed) {
        event.reply.acknowledge()
        authorized(event.user)
        val id = event.id.substringAfter(':').takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
            ?: throw ChannelActionRefused(ModerationTexts.UNKNOWN)
        val case = store.get(id) ?: throw ChannelActionRefused(ModerationTexts.UNKNOWN)
        if (case.actorId != event.user.id || case.channelId != channel(event.channelId)) throw ChannelActionRefused(ModerationTexts.UNKNOWN)
        if (event.id.startsWith(CANCEL)) {
            say(event.reply, if (store.cancel(id, event.user.id, context.timer.now())) "Action annulée." else ModerationTexts.EXPIRED)
            return
        }
        gateway.snapshot(case.actorId, case.channelId, case.plan.action)
        val claimed = store.claim(id, event.user.id, context.timer.now(), rules.recoverySeconds)
            ?: throw ChannelActionRefused("Confirmation expirée, déjà utilisée, ou verrou modifié par une autre opération.")
        val affected = try { gateway.execute(claimed, context.timer.now()) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val status = if (e is ChannelActionRefused) ChannelCaseStatus.FAILED else ChannelCaseStatus.UNCERTAIN
            store.finish(id, status, null, context.timer.now())
            if (e is ChannelActionRefused) throw e
            throw DiscordRefusal("Résultat externe incertain")
        }
        try { store.finish(id, ChannelCaseStatus.SUCCEEDED, affected, context.timer.now()) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            log.error("Discord salons : action effectuée, audit non confirmé ({})", e::class.simpleName)
            say(event.reply, "Discord a effectué l'action, mais son audit durable n'a pas été confirmé. Ne relance pas l'action ; vérifie le salon.")
            return
        }
        try { journal.line("Salon <#${case.channelId}> : ${case.plan.action.name.lowercase()}, dossier ${case.id}, staff <@${case.actorId}>.") }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { log.error("Discord salons : audit enregistré, journal en échec ({})", e::class.simpleName) }
        say(event.reply, if (case.plan.action == ChannelAction.PURGE) "Purge confirmée : $affected message(s) ciblé(s). Action enregistrée." else "État du salon modifié et enregistré.")
    }
    private suspend fun say(reply: Reply, text: String) = reply.send(BotMessage(text), onlyToUser = true)
    companion object {
        const val CONFIRM = "salon-confirmer:"
        const val CANCEL = "salon-annuler:"
        private val log = LoggerFactory.getLogger(ChannelModerationFeature::class.java)
    }
}

/** Bits du protocole Discord : envoyer, écrire dans les fils, créer des fils publics et privés. */
internal const val CHANNEL_WRITING_MASK = (1L shl 11) or (1L shl 38) or (1L shl 35) or (1L shl 36)

internal class ServerChannelModerationGateway(private val context: BotContext, private val rules: ChannelModerationRules) : ChannelModerationGateway {
    private fun roles() = setOfNotNull(context.ids.role("staff"), context.ids.role("moderation"))
    override suspend fun snapshot(actorId: String, channelId: String, action: ChannelAction) = context.server.channelModerationSnapshot(actorId, channelId, action, roles())
    override suspend fun candidates(actorId: String, channelId: String, count: Int, now: Instant) = context.server.channelPurgeCandidates(actorId, channelId, count, now, roles(), rules)
    override suspend fun execute(case: ChannelModerationCase, now: Instant) = context.server.channelModerationExecute(case, now, roles(), rules)
}
