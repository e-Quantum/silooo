package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Final Discord-side identity and hierarchy snapshot. Mutations must check again immediately before acting. */
data class ModerationTarget(
    val id: String,
    val name: String,
    val isBot: Boolean,
    val isOwner: Boolean,
    val actorCanInteract: Boolean,
    val botCanInteract: Boolean,
)

interface ModerationGateway {
    suspend fun check(actorId: String, targetId: String): ModerationTarget?
    suspend fun timeout(actorId: String, targetId: String, until: Instant?, reason: String)
    suspend fun warn(actorId: String, targetId: String, reason: String): Boolean
}

data class ModerationRules(
    val confirmationSeconds: Long,
    val retentionDays: Int,
    val historyPageSize: Int,
    val maxReasonLength: Int,
    val timeoutHours: List<Int> = listOf(1, 24),
) {
    init {
        require(confirmationSeconds in 1..3600)
        require(retentionDays in 1..3650)
        require(historyPageSize in 1..100)
        require(maxReasonLength in 1..500)
        require(timeoutHours.isNotEmpty() && timeoutHours.all { it in 1..24 } && timeoutHours.distinct().size == timeoutHours.size)
    }
}

object ModerationTexts {
    const val COMMAND_WARN = "avertir"
    const val COMMAND_TIMEOUT = "timeout"
    const val COMMAND_RESTORE = "retablir"
    const val COMMAND_CASE = "dossier"
    const val CONFIRM_PREFIX = "moderation-confirm:"
    const val CANCEL_PREFIX = "moderation-cancel:"
    const val HISTORY_PREFIX = "moderation-history:"
    const val CONFIRM_LABEL = "Confirmer"
    const val CANCEL_LABEL = "Annuler"
    const val NOT_STAFF = "Cette commande est réservée au Staff et à Modération."
    const val BAD_TARGET = "Membre invalide ou absent du serveur."
    const val BAD_REASON = "Indique un motif de longueur valide."
    const val CANNOT_ACT = "Cette action n'est pas autorisée pour cette cible."
    const val EXPIRED = "Cette confirmation a expiré ou a déjà été utilisée."
    const val DONE = "Action exécutée et enregistrée."
    const val FAILED = "Discord a refusé l'action ; l'échec est enregistré."
    const val UNKNOWN = "Dossier introuvable ou déjà traité."
}

/** Staff-only moderation with durable case claims, private previews and fresh Discord role checks. */
class ModerationFeature(
    private val context: BotContext,
    private val rules: ModerationRules,
    private val store: ModerationStore,
    private val gateway: ModerationGateway,
    private val journal: BotJournal,
) : Feature {
    override val name = "moderation"
    override val commands = listOf(
        CommandSpec(ModerationTexts.COMMAND_WARN, "Avertir un membre", listOf(
            CommandOption("membre", "Membre à avertir", required = true, kind = OptionKind.MEMBRE),
            CommandOption("motif", "Motif", required = true),
        ), staffOnly = true, defaultPermission = CommandPermission.MODERATE_MEMBERS),
        CommandSpec(ModerationTexts.COMMAND_TIMEOUT, "Mettre un membre en timeout", listOf(
            CommandOption("membre", "Membre concerné", required = true, kind = OptionKind.MEMBRE),
            CommandOption("duree", "Durée", required = true, choices = rules.timeoutHours.map { "${it}h" }),
            CommandOption("motif", "Motif", required = true),
        ), staffOnly = true, defaultPermission = CommandPermission.MODERATE_MEMBERS),
        CommandSpec(ModerationTexts.COMMAND_RESTORE, "Retirer le timeout d'un membre", listOf(
            CommandOption("membre", "Membre concerné", required = true, kind = OptionKind.MEMBRE),
            CommandOption("motif", "Motif", required = true),
        ), staffOnly = true, defaultPermission = CommandPermission.MODERATE_MEMBERS),
        CommandSpec(ModerationTexts.COMMAND_CASE, "Consulter le dossier d'un membre", listOf(
            CommandOption("membre", "Membre concerné", required = true, kind = OptionKind.MEMBRE),
        ), staffOnly = true, defaultPermission = CommandPermission.MODERATE_MEMBERS),
    )

    override suspend fun handle(event: DiscordEvent) {
        when (event) {
            is DiscordEvent.CommandUsed -> when (event.name) {
                ModerationTexts.COMMAND_WARN -> create(event, ModerationAction.WARN)
                ModerationTexts.COMMAND_TIMEOUT -> create(event, ModerationAction.TIMEOUT)
                ModerationTexts.COMMAND_RESTORE -> create(event, ModerationAction.RESTORE)
                ModerationTexts.COMMAND_CASE -> dossier(event)
            }
            is DiscordEvent.ButtonPressed -> when {
                event.id.startsWith(ModerationTexts.CONFIRM_PREFIX) -> confirm(event)
                event.id.startsWith(ModerationTexts.CANCEL_PREFIX) -> cancel(event)
                event.id.startsWith(ModerationTexts.HISTORY_PREFIX) -> historyPage(event)
            }
            else -> Unit
        }
    }

    private suspend fun create(event: DiscordEvent.CommandUsed, action: ModerationAction) {
        if (!authorized(event.user.id, event.user.isBot)) return privateReply(event.reply, ModerationTexts.NOT_STAFF)
        val targetId = normalize(event.options["membre"]) ?: return privateReply(event.reply, ModerationTexts.BAD_TARGET)
        val reason = event.options["motif"]?.trim().orEmpty()
        if (reason.isBlank() || reason.length > rules.maxReasonLength) return privateReply(event.reply, ModerationTexts.BAD_REASON)
        val target = gateway.check(event.user.id, targetId) ?: return privateReply(event.reply, ModerationTexts.BAD_TARGET)
        if (!canAct(event.user.id, target)) return privateReply(event.reply, ModerationTexts.CANNOT_ACT)
        val durationHours = event.options["duree"]?.removeSuffix("h")?.toIntOrNull()
        val duration = durationHours?.takeIf { action == ModerationAction.TIMEOUT && it in rules.timeoutHours }?.let { Duration.ofHours(it.toLong()) }
        if (action == ModerationAction.TIMEOUT && duration == null) return privateReply(event.reply, "Durée invalide : choisis ${rules.timeoutHours.joinToString(" ou ") { "${it}h" }}.")
        val case = freshCase(event.user.id, target.id, action, reason, duration?.seconds)
        if (!store.reserve(case)) return privateReply(event.reply, ModerationTexts.UNKNOWN)
        event.reply.send(BotMessage(
            "Confirmer ${actionLabel(action)}${durationHours?.let { " ($it h)" } ?: ""} de ${target.name} (<@${target.id}>) ? Motif : $reason. La confirmation expire dans ${rules.confirmationSeconds} secondes.",
            buttons = listOf(
                ButtonSpec(ModerationTexts.CONFIRM_PREFIX + case.id, ModerationTexts.CONFIRM_LABEL, ButtonStyle.DANGER),
                ButtonSpec(ModerationTexts.CANCEL_PREFIX + case.id, ModerationTexts.CANCEL_LABEL),
            ),
        ), onlyToUser = true)
    }

    private suspend fun confirm(event: DiscordEvent.ButtonPressed) {
        if (!authorized(event.user.id, event.user.isBot)) return privateReply(event.reply, ModerationTexts.NOT_STAFF)
        event.reply.acknowledge()
        val id = event.id.removePrefix(ModerationTexts.CONFIRM_PREFIX).takeIf(::isUuid) ?: return privateReply(event.reply, ModerationTexts.UNKNOWN)
        val pending = store.get(id) ?: return privateReply(event.reply, ModerationTexts.UNKNOWN)
        if (pending.actorId != event.user.id || pending.status != ModerationStatus.RESERVED) return privateReply(event.reply, ModerationTexts.UNKNOWN)
        val target = gateway.check(event.user.id, pending.targetId) ?: return privateReply(event.reply, ModerationTexts.BAD_TARGET)
        if (!canAct(event.user.id, target)) return privateReply(event.reply, ModerationTexts.CANNOT_ACT)
        val claimed = store.claim(id, event.user.id, context.timer.now()) ?: return privateReply(event.reply, ModerationTexts.EXPIRED)
        val until = if (claimed.action == ModerationAction.TIMEOUT) {
            val seconds = claimed.timeoutDurationSeconds ?: return privateReply(event.reply, ModerationTexts.UNKNOWN)
            context.timer.now().plusSeconds(seconds)
        } else null
        execute(event.reply, claimed, until)
    }

    private suspend fun cancel(event: DiscordEvent.ButtonPressed) {
        if (!authorized(event.user.id, event.user.isBot)) return privateReply(event.reply, ModerationTexts.NOT_STAFF)
        event.reply.acknowledge()
        val id = event.id.removePrefix(ModerationTexts.CANCEL_PREFIX).takeIf(::isUuid) ?: return privateReply(event.reply, ModerationTexts.UNKNOWN)
        val pending = store.get(id) ?: return privateReply(event.reply, ModerationTexts.UNKNOWN)
        if (pending.actorId != event.user.id || pending.status != ModerationStatus.RESERVED) return privateReply(event.reply, ModerationTexts.UNKNOWN)
        if (!store.cancel(id, event.user.id, context.timer.now())) return privateReply(event.reply, ModerationTexts.EXPIRED)
        privateReply(event.reply, "Action annulée.")
    }

    private suspend fun dossier(event: DiscordEvent.CommandUsed) {
        if (!authorized(event.user.id, event.user.isBot)) return privateReply(event.reply, ModerationTexts.NOT_STAFF)
        val targetId = normalize(event.options["membre"]) ?: return privateReply(event.reply, ModerationTexts.BAD_TARGET)
        val target = gateway.check(event.user.id, targetId) ?: return privateReply(event.reply, ModerationTexts.BAD_TARGET)
        if (!canAct(event.user.id, target)) return privateReply(event.reply, ModerationTexts.CANNOT_ACT)
        renderHistory(event.reply, event.user.id, target.id, target.name, 0)
    }


    private suspend fun historyPage(event: DiscordEvent.ButtonPressed) {
        if (!authorized(event.user.id, event.user.isBot)) return privateReply(event.reply, ModerationTexts.NOT_STAFF)
        event.reply.acknowledge()
        val parts = event.id.removePrefix(ModerationTexts.HISTORY_PREFIX).split(':')
        if (parts.size != 3 || parts[1].toLongOrNull() == null || parts[2] != event.user.id) return privateReply(event.reply, ModerationTexts.UNKNOWN)
        val targetId = parts[0].takeIf { normalize(it) == it } ?: return privateReply(event.reply, ModerationTexts.BAD_TARGET)
        val page = parts[1].toIntOrNull()?.takeIf { it in 0..1000 } ?: return privateReply(event.reply, ModerationTexts.UNKNOWN)
        val target = gateway.check(event.user.id, targetId) ?: return privateReply(event.reply, ModerationTexts.BAD_TARGET)
        if (!canAct(event.user.id, target)) return privateReply(event.reply, ModerationTexts.CANNOT_ACT)
        renderHistory(event.reply, event.user.id, target.id, target.name, page)
    }

    private suspend fun renderHistory(reply: Reply, actorId: String, targetId: String, targetName: String, page: Int) {
        val since = context.timer.now().minus(Duration.ofDays(rules.retentionDays.toLong()))
        val cases = store.history(targetId, since, rules.historyPageSize + 1, page * rules.historyPageSize)
        val visible = cases.take(rules.historyPageSize)
        val body = if (visible.isEmpty()) "Aucun dossier pour $targetName." else visible.joinToString("\n") {
            "${it.createdAt} · ${it.action.name.lowercase()} · ${it.status.name.lowercase()} · <@${it.actorId}> · ${it.reason}" + (it.failure?.let { f -> " ($f)" } ?: "")
        }
        val buttons = buildList {
            if (page > 0) add(ButtonSpec(ModerationTexts.HISTORY_PREFIX + "$targetId:${page - 1}:$actorId", "Précédent"))
            if (cases.size > rules.historyPageSize) add(ButtonSpec(ModerationTexts.HISTORY_PREFIX + "$targetId:${page + 1}:$actorId", "Suivant"))
        }
        reply.send(BotMessage("Dossier de $targetName · page ${page + 1}\n$body", buttons = buttons), onlyToUser = true)
    }

    private suspend fun execute(reply: Reply, case: ModerationCase, until: Instant?) {
        try {
            when (case.action) {
                ModerationAction.WARN -> if (!gateway.warn(case.actorId, case.targetId, case.reason)) {
                    store.finish(case.id, false, "delivery-refused", context.timer.now())
                    journal.line("Avertissement non remis : dossier ${case.id}.")
                    privateReply(reply, "Avertissement non remis ; l'échec est enregistré.")
                    return
                }
                ModerationAction.TIMEOUT, ModerationAction.RESTORE -> gateway.timeout(case.actorId, case.targetId, until, case.reason)
            }
            store.finish(case.id, true, null, context.timer.now())
            journal.line("Modération ${case.action.name.lowercase()} : <@${case.targetId}>, dossier ${case.id}.")
            privateReply(reply, ModerationTexts.DONE)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            store.finish(case.id, false, e.message ?: e::class.simpleName, context.timer.now())
            journal.line("Échec modération ${case.action.name.lowercase()} : dossier ${case.id}.")
            privateReply(reply, ModerationTexts.FAILED)
        }
    }

    private suspend fun authorized(userId: String, isBot: Boolean): Boolean {
        if (isBot) return false
        val staff = context.ids.role("staff")
        val moderation = context.ids.role("moderation")
        if (staff == null && moderation == null) return false
        val roles = context.server.rolesOf(userId)
        return (staff != null && staff in roles) || (moderation != null && moderation in roles)
    }

    private fun canAct(actorId: String, target: ModerationTarget) =
        target.id != actorId && !target.isBot && !target.isOwner && target.actorCanInteract && target.botCanInteract

    private fun freshCase(actor: String, target: String, action: ModerationAction, reason: String, timeoutSeconds: Long? = null): ModerationCase {
        val now = context.timer.now()
        return ModerationCase(UUID.randomUUID().toString(), actor, target, action, reason, now,
            now.plusSeconds(rules.confirmationSeconds), ModerationStatus.RESERVED, timeoutSeconds)
    }

    private fun normalize(value: String?): String? = value?.trim()?.let { raw ->
        val id = Regex("<@!?(\\d{17,20})>").matchEntire(raw)?.groupValues?.get(1) ?: raw
        id.takeIf { it.matches(Regex("\\d{17,20}")) }
    }

    private fun isUuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    private fun actionLabel(action: ModerationAction) = when (action) { ModerationAction.TIMEOUT -> "le timeout"; ModerationAction.RESTORE -> "le retrait du timeout"; ModerationAction.WARN -> "l'avertissement" }
    private suspend fun privateReply(reply: Reply, text: String) = reply.send(BotMessage(text), onlyToUser = true)
}
