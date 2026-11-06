package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** La preuve Minecraft seule ; aucun identifiant saisi sur Discord ne crée une liaison. */
interface DiscordLinkGateway {
    suspend fun redeem(discordId: String, typed: String): LinkOutcome
    suspend fun findByDiscord(discordId: String): LinkIdentity?
    /** Supprime atomiquement seulement la liaison dont le XUID et linkedAt correspondent à [expected]. */
    suspend fun unlink(discordId: String, expected: LinkIdentity): LinkIdentity?
}

data class LinkIdentity(val xuid: String, val gamertag: String, val linkedAt: Instant)
data class LinkOutcome(val status: LinkStatus, val link: LinkIdentity? = null)
enum class LinkStatus { LINKED, INVALID_CODE, EXPIRED_CODE, DISCORD_TAKEN, PLAYER_TAKEN, RATE_LIMITED }

/** Tous ces textes restent à valider par Kr. Ils sont visibles du seul membre qui agit. */
object LinkingTexts {
    const val FORM_ID = "liaison-code"
    const val CODE_FIELD = "code"
    const val CONFIRM_PREFIX = "liaison-delier:"
    const val CODE_HELP = "Dans Minecraft Bedrock, ouvre Menu > Mon compte > Relier Discord pour obtenir un code de six caractères, valable dix minutes."
    const val WAIT_HELP = "Le jeu est encore fermé : tu pourras obtenir ce code quand tu auras accès au jeu."
    const val INVALID = "Ce code n'est pas reconnu. Recopie le code affiché dans Minecraft, sans le partager."
    const val EXPIRED = "Ce code a expiré. Demande un nouveau code dans Minecraft."
    const val DISCORD_TAKEN = "Ton compte Discord est déjà relié. Utilise /delier avant de changer de compte Minecraft."
    const val PLAYER_TAKEN = "Ce compte Minecraft est déjà relié à un autre compte Discord."
    const val RATE_LIMITED = "Trop de tentatives. Attends quelques minutes avant de réessayer."
    const val UNAVAILABLE = "La liaison est indisponible pour le moment. Réessaie plus tard."
    const val NOT_LINKED = "Ton compte Discord n'est pas relié à un compte Minecraft."
    const val STALE = "Cette confirmation n'est plus valable. Utilise /delier pour recommencer."
    const val OTHER_USER = "Cette confirmation appartient à un autre membre."
    const val UNLINKED = "Ton compte Minecraft est délié. Ta progression dans le jeu et ton XP Discord sont conservés."
    const val CLEANUP_FAILED = "Ton compte est délié, mais Discord a refusé de retirer certains rôles ou le surnom. Préviens le staff."
    const val PROFILE_FAILED = "Ton compte est relié, mais Discord a refusé une partie de la mise à jour du surnom ou des rôles. Préviens le staff."

    fun linked(gamertag: String): String = "Ton compte Minecraft est relié : " + gamertag + "."
    fun confirm(gamertag: String): String = "Délier le compte Minecraft « " + gamertag + " » ? Les rôles du jeu et le surnom seront retirés. Ta progression et ton XP Discord sont conservés."
}

/**
 * La liaison ne publie rien dans un salon et n'envoie aucun message privé spontané. Le service vérifie le code,
 * sa durée et les conflits. [nickname] modifie le surnom du membre (null le retire) ; cette petite frontière
 * permet de vérifier le parcours sans JDA. Les rôles de jeu supplémentaires attendent des données prouvées.
 */
class LinkingFeature(
    private val context: BotContext,
    private val links: DiscordLinkGateway,
    private val nickname: suspend (String, String?) -> Unit,
) : Feature {
    override val name = "liaison"
    override val commands = listOf(
        CommandSpec("lier", "Relier ton compte Minecraft avec le code affiché en jeu", listOf(CommandOption("code", "Le code affiché dans Minecraft", required = true))),
        CommandSpec("delier", "Délier ton compte Minecraft après confirmation"),
    )

    private data class Pending(val userId: String, val link: LinkIdentity, val expiresAt: Instant)
    private val pending = linkedMapOf<String, Pending>()

    override suspend fun handle(event: DiscordEvent) {
        when (event) {
            is DiscordEvent.CommandUsed -> when (event.name) {
                "lier" -> redeem(event.user.id, event.options[LinkingTexts.CODE_FIELD].orEmpty(), event.reply)
                "delier" -> previewUnlink(event.user.id, event.reply)
                else -> Unit
            }
            is DiscordEvent.ButtonPressed -> when {
                event.id == BienvenueTexts.LINK_BUTTON_ID -> event.reply.form(
                    FormSpec(
                        LinkingTexts.FORM_ID,
                        "Relier mon compte Minecraft",
                        listOf(
                            FormField(
                                LinkingTexts.CODE_FIELD,
                                "Code Minecraft (six caractères)",
                                maxLength = 6,
                                placeholder = "Menu > Mon compte > Relier Discord",
                            ),
                        ),
                    ),
                )
                event.id.startsWith(LinkingTexts.CONFIRM_PREFIX) -> confirmUnlink(event.id.removePrefix(LinkingTexts.CONFIRM_PREFIX), event.user.id, event.reply)
                else -> Unit
            }
            is DiscordEvent.FormSubmitted -> if (event.id == LinkingTexts.FORM_ID) {
                redeem(event.user.id, event.fields[LinkingTexts.CODE_FIELD].orEmpty(), event.reply)
            }
            else -> Unit
        }
    }

    private suspend fun redeem(userId: String, code: String, reply: Reply) {
        // Ne jamais laisser remonter une exception du service qui pourrait inclure le code saisi au journal.
        val outcome = safely { links.redeem(userId, code) }
        val message = when (outcome?.status) {
            LinkStatus.LINKED -> {
                val link = outcome.link
                if (link == null) LinkingTexts.UNAVAILABLE else {
                    pending.entries.removeAll { it.value.userId == userId }
                    val renamed = safely { nickname(userId, link.gamertag); true } == true
                    val roleApplied = safely {
                        context.ids.role("joueur-relie")?.let { context.server.giveRole(userId, it) }
                        true
                    } == true
                    if (renamed && roleApplied) LinkingTexts.linked(link.gamertag) else LinkingTexts.PROFILE_FAILED
                }
            }
            LinkStatus.INVALID_CODE -> LinkingTexts.INVALID + "\n" + LinkingTexts.CODE_HELP +
                (if (context.phase == ServerPhase.ATTENTE) "\n" + LinkingTexts.WAIT_HELP else "")
            LinkStatus.EXPIRED_CODE -> LinkingTexts.EXPIRED
            LinkStatus.DISCORD_TAKEN -> LinkingTexts.DISCORD_TAKEN
            LinkStatus.PLAYER_TAKEN -> LinkingTexts.PLAYER_TAKEN
            LinkStatus.RATE_LIMITED -> LinkingTexts.RATE_LIMITED
            null -> LinkingTexts.UNAVAILABLE
        }
        reply.send(BotMessage(message), onlyToUser = true)
    }

    private suspend fun previewUnlink(userId: String, reply: Reply) {
        val link = try {
            links.findByDiscord(userId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            reply.send(BotMessage(LinkingTexts.UNAVAILABLE), onlyToUser = true)
            return
        }
        if (link == null) {
            reply.send(BotMessage(LinkingTexts.NOT_LINKED), onlyToUser = true)
            return
        }
        val now = context.timer.now()
        pending.entries.removeAll { !it.value.expiresAt.isAfter(now) || it.value.userId == userId }
        // Les confirmations sont éphémères : un redémarrage exige un nouvel aperçu.
        if (pending.size >= MAX_PENDING) pending.remove(pending.keys.first())
        val token = UUID.randomUUID().toString()
        pending[token] = Pending(userId, link, now.plus(CONFIRM_TTL))
        reply.send(
            BotMessage(LinkingTexts.confirm(link.gamertag), buttons = listOf(ButtonSpec(LinkingTexts.CONFIRM_PREFIX + token, "Confirmer la déliaison", ButtonStyle.DANGER))),
            onlyToUser = true,
        )
    }

    private suspend fun confirmUnlink(token: String, userId: String, reply: Reply) {
        val confirmation = pending[token]
        if (confirmation == null || !confirmation.expiresAt.isAfter(context.timer.now())) {
            pending.remove(token)
            reply.send(BotMessage(LinkingTexts.STALE), onlyToUser = true)
            return
        }
        if (confirmation.userId != userId) {
            reply.send(BotMessage(LinkingTexts.OTHER_USER), onlyToUser = true)
            return
        }
        // Consommé avant le geste : un second clic n'agit jamais sur une nouvelle liaison.
        pending.remove(token)
        val removed = try {
            links.unlink(userId, confirmation.link)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            reply.send(BotMessage(LinkingTexts.UNAVAILABLE), onlyToUser = true)
            return
        }
        if (removed == null) {
            reply.send(BotMessage(LinkingTexts.STALE), onlyToUser = true)
            return
        }
        var cleaned = safely { nickname(userId, null); true } == true
        for ((key, roleId) in context.ids.roles) {
            if (key in GAME_ROLES || key.startsWith("prestige-")) {
                if (safely { context.server.takeRole(userId, roleId); true } != true) cleaned = false
            }
        }
        reply.send(BotMessage(if (cleaned) LinkingTexts.UNLINKED else LinkingTexts.CLEANUP_FAILED), onlyToUser = true)
    }

    /** Pas de détail d'exception : le code de liaison ne doit jamais entrer dans les logs. */
    private suspend fun <T> safely(action: suspend () -> T): T? = try {
        action()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        val CONFIRM_TTL: Duration = Duration.ofMinutes(10)
        const val MAX_PENDING = 1000
        val GAME_ROLES = setOf("joueur-relie", "pdg", "associe", "pionnier", "beta")
    }
}
