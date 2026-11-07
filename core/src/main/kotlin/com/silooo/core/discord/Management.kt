package com.silooo.core.discord

import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Les durées de gestion sont lues dans definitions/discord.json, jamais dans l'environnement secret. */
@Serializable
data class SignalementSettings(
    @SerialName("delaiSecondes") val cooldownSeconds: Long,
    @SerialName("formulaireSecondes") val formSeconds: Long,
    @SerialName("conservationJours") val retentionDays: Long,
    @SerialName("purgeSecondes") val purgeSeconds: Long,
    @SerialName("motifMaxCaracteres") val reasonMaxLength: Int,
    @SerialName("contenuMaxCaracteres") val contentMaxLength: Int,
) {
    init {
        require(cooldownSeconds in 1..3600 && formSeconds in 1..3600)
        require(retentionDays == 30L && purgeSeconds in 60..86400)
        require(reasonMaxLength in 1..500 && contentMaxLength in 1..1000)
    }
    fun rules() = SignalementRules(Duration.ofSeconds(cooldownSeconds), Duration.ofSeconds(formSeconds),
        Duration.ofDays(retentionDays), Duration.ofSeconds(purgeSeconds), reasonMaxLength, contentMaxLength)
}

/** Même contrôle de rôles frais que les actions, avec un second contrôle natif juste avant leur effet. */
internal class ServerModerationGateway(private val context: BotContext) : ModerationGateway {
    private fun roles() = setOfNotNull(context.ids.role("staff"), context.ids.role("moderation"))
    override suspend fun check(actorId: String, targetId: String) = context.server.moderationTarget(actorId, targetId, roles())
    override suspend fun timeout(actorId: String, targetId: String, until: java.time.Instant?, reason: String) =
        context.server.timeoutMember(actorId, targetId, until, reason, roles())
    override suspend fun warn(actorId: String, targetId: String, reason: String): Boolean {
        val target = check(actorId, targetId)
        if (target == null || target.isOwner || target.isBot || !target.actorCanInteract || !target.botCanInteract || actorId == targetId) {
            throw DiscordRefusal("Les permissions ou la hiérarchie ne permettent pas cet avertissement.")
        }
        return context.server.direct(targetId, BotMessage("Avertissement sur le serveur Silooo.\nMotif : $reason\nPour le contester, ouvre un ticket dans le serveur."))
    }
}

/** Vue privée pour retrouver les outils du staff et les contrôles du serveur réellement disponibles. */
class ManagementFeature(private val context: BotContext) : Feature {
    override val name = "gestion"
    override val commands = listOf(CommandSpec("gestion", "Voir les outils du staff et l'état du Discord",
        staffOnly = true, defaultPermission = CommandPermission.MODERATE_MEMBERS))

    override suspend fun handle(event: DiscordEvent) {
        if (event !is DiscordEvent.CommandUsed || event.name != "gestion") return
        val ids = context.server.freshRolesOf(event.user.id)
        val staff = context.ids.role("staff")?.let { it in ids } == true
        val moderator = context.ids.role("moderation")?.let { it in ids } == true
        if (event.user.isBot || (!staff && !moderator)) {
            event.reply.send(BotMessage("Cette commande est réservée au staff et à la modération.")); return
        }
        val text = buildString {
            append("Gestion du Discord\nPhase : ").append(context.phase.name.lowercase()).append("\n\n")
            append("Modération : /avertir, /timeout, /retablir, /dossier.\n")
            append("Signaler : menu Applications du message, puis Signaler.\n")
            if (staff) append("Publication : /annonce, /spoil, /giveaway, /sondage, /suggestion.\n")
            append("\nSalons du staff\n")
            for (id in listOf("journal-du-bot", "admissions", "signalements", "tickets")) {
                val channel = context.ids.channel(id)
                if (channel == null) { append(id).append(" : absent du plan appliqué.\n"); continue }
                if (!context.server.canReadChannel(channel, event.user.id)) continue
                append("<#").append(channel).append(">")
                if (id != "tickets") {
                    val access = try { context.server.publicChannelAccess(channel) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    append(when (access?.view) { true -> " : visible par tout le monde, à corriger."; false -> " : accès fermé à tout le monde hors exceptions."; null -> " : contrôle des droits indisponible." })
                }
                append('\n')
            }
            val rules = try { context.server.automodRules().toSet() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            val expected = setOf(AutomodTexts.RULE_WORDS, AutomodTexts.RULE_CONTACT, AutomodTexts.RULE_MENTIONS, AutomodTexts.RULE_LINKS)
            append("\nAutoMod : ").append(if (rules == null) "lecture indisponible" else "${rules.intersect(expected).size}/${expected.size} règles Oooscar présentes").append('.').append('\n')
            val onboarding = try { context.server.onboarding() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            append("Accueil guidé : ").append(when (onboarding?.enabled) { true -> "actif"; false -> "désactivé"; null -> "lecture indisponible" }).append('.')
        }
        event.reply.send(BotMessage(text, card = true), onlyToUser = true)
    }
}
