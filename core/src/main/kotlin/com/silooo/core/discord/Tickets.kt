package com.silooo.core.discord

import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Les mots du bot pour les tickets (`docs/discord.md`, « Le staff »), **à valider par Kr**. Sans emoji, sans
 * mot-réclame (`design/README.md`, « Écrire sans slop »). Le menu et le message d'ouverture sont lus par le membre,
 * le journal par le staff seul. Les mentions sont des identifiants Discord, jamais des pings : seul le message
 * d'ouverture prévient son membre.
 */
object TicketTexts {
    /** La clé du menu dans `discord_bot_messages`, et l'identifiant du menu de Discord. */
    const val MENU_KEY = "tickets-menu"
    const val MENU_ID = "tickets-menu"
    const val MENU_PLACEHOLDER = "Choisis ta demande"

    const val CLOSE_PREFIX = "ticket-fermer:"
    const val CLOSE_LABEL = "Fermer le ticket"
    const val CLAIM_PREFIX = "ticket-prendre:"
    const val RELEASE_PREFIX = "ticket-liberer:"
    const val CLAIM_LABEL = "Prendre en charge"
    const val RELEASE_LABEL = "Libérer le ticket"
    const val STAFF_ONLY = "La prise en charge est réservée au staff."
    const val WRONG_CHANNEL = "Utilise ces outils dans le salon du ticket."
    const val CLAIMED = "Tu prends en charge ce ticket."
    const val RELEASED = "Le ticket est à nouveau disponible."
    const val UNAVAILABLE = "Ce ticket est déjà pris en charge ou fermé."
    const val NOT_ASSIGNED = "Seul le responsable du ticket peut le libérer."
    const val ARCHIVE_FAILED = "La fermeture n'est pas terminée. Le salon est conservé ; tu peux réessayer."
    const val CLOSING = "Fermeture en cours. Le ticket passe en lecture seule pendant la préparation de l'archive."
    const val CLOSED = "Le ticket est fermé."

    fun archiveFailure(code: String): String = when (code) {
        "source-has-threads" -> "Ce ticket contient des fils de discussion. Leur contenu doit être conservé avant de fermer le salon."
        "source-threads-unverified" -> "Les fils du ticket n'ont pas pu être vérifiés. Contrôle les droits de lecture et de gestion des fils."
        "destination-not-private" -> "Le journal des archives doit être privé et correspondre au salon configuré."
        "archive-delivery-uncertain" -> "La réception d'un fichier doit être vérifiée avant un nouvel envoi."
        "archive-part-too-large" -> "Le fichier serait trop grand. Réduis le nombre de messages par page dans les réglages du bot."
        "invalid-history-cursor" -> "L'historique n'a pas pu être lu dans l'ordre. Le salon est conservé."
        "lease-lost" -> "Un autre traitement a repris cette fermeture."
        else -> "Un accès à Discord ou au stockage a échoué. La reprise conserve le salon."
    }

    /** Les valeurs du menu : [GAME] renvoie vers l'aide, les autres ouvrent un salon privé qui porte la valeur dans son nom. */
    const val GAME = "jeu"
    const val PURCHASE = "achat"
    const val ACCOUNT = "compte"
    const val SANCTION = "sanction"
    const val CREATORS = "createurs"

    const val NOT_READY = "Les tickets ne sont pas prêts sur ce serveur."
    const val ALREADY_CLOSED = "Ce ticket est déjà fermé."
    const val NOT_ALLOWED = "Ce bouton est pour le staff et l'auteur du ticket."

    val OPTIONS = listOf(
        MenuOption(GAME, "Une question de jeu", "Elle va dans le salon d'aide"),
        MenuOption(PURCHASE, "Un achat ou des Gemmes"),
        MenuOption(ACCOUNT, "Mon compte ou ma liaison"),
        MenuOption(SANCTION, "Contester une sanction"),
        MenuOption(CREATORS, "Créateurs"),
    )

    /** [help] : la mention du salon d'aide. */
    fun menu(help: String): String =
        "Pour ce qui ne se dit pas en public. Choisis, et un salon privé s'ouvre entre toi et le staff. Une question de jeu se pose dans " + help + "."

    fun gameQuestion(help: String): String =
        "Une question de jeu se pose dans " + help + " : la réponse sert à tous. Si c'est privé, choisis un autre sujet."

    /** [channel] : la mention du salon du ticket ouvert. */
    fun alreadyOpen(channel: String): String = "Tu as déjà un ticket ouvert : " + channel + "."

    fun opened(channel: String): String = "Ton ticket est ouvert : " + channel + "."

    fun topic(number: Int, name: String, kind: String): String = "Ticket n°" + number + " de " + name + " : " + kind

    fun channelName(kind: String, number: Int): String = "ticket-" + kind + "-" + number

    /** Le premier message du salon : seul le membre est prévenu. */
    fun welcome(userId: String): String = "<@" + userId + "> Ton ticket est ouvert. Dis-nous tout ici : le staff lit."

    fun journalOpened(number: Int, userId: String, kind: String): String =
        "Ticket n°" + number + " ouvert par <@" + userId + "> : " + kind + "."

    fun journalClosed(number: Int, kind: String, authorId: String, byId: String): String =
        "Archive du ticket n°" + number + " (" + kind + ") de <@" + authorId + ">, fermeture demandée par <@" + byId + ">."

    fun journalVanished(number: Int): String = "Ticket n°" + number + " : salon supprimé à la main, fermé."

    /** Le salon n'a pas pu être relu : il reste, le staff l'archive à la main. */
    fun journalUnread(number: Int): String = "Ticket n°" + number + " : l'archive n'a pas pu être lue, le salon reste."

    /** Une ligne de l'archive : « 5 novembre 2026 à 18 h 10, Nom : texte », l'heure dans le fuseau [zone]. */
    fun archiveLine(message: ChannelMessage, zone: ZoneId): String =
        AdmissionTexts.moment(message.at, zone).removePrefix("le ") + ", " + message.authorName + " : " + message.text
}

/**
 * Les tickets (`docs/discord.md`, « Le staff »). `#tickets` porte un menu : une question de jeu est renvoyée vers
 * `#aide`, chacun des autres choix ouvre un salon privé (le membre, le staff et la modération le voient) dans la
 * catégorie Aide, nommé d'après le choix et le numéro du ticket. Un membre n'a qu'un ticket ouvert à la fois.
 *
 * La fermeture passe par TicketArchiver : capture paginée durable, livraison privée, puis suppression reprenable.
 * Le menu est retrouvé par BotMessageStore. Un salon disparu avec une archive en cours n'est pas fermé sans preuve.

 */
class TicketFeature(
    private val context: BotContext,
    private val store: TicketStore,
    private val messages: BotMessageStore,
    private val journal: BotJournal,
    private val zone: ZoneId = ZoneId.of("Europe/Paris"),
    private val archives: TicketArchiveStore,
    archiveRules: TicketArchiveRules,

) : Feature {
    private val archiver = TicketArchiver(context, store, archives, archiveRules)
    override val name = "tickets"
    override val commands = listOf(CommandSpec("ticket", "Voir ou gérer le ticket de ce salon", options = listOf(
        CommandOption("action", "Le geste à effectuer", required = true, choices = listOf("etat", "prendre", "liberer", "fermer")),
    )))

    override suspend fun start() {
        context.ids.channel(TICKETS)?.let { channel ->
            try {
                showMenu(channel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Bot Discord, tickets : le menu n'a pas pu être posé : {}", e.toString())
            }
        }
        closeVanished()
        archiver.start()
    }

    override suspend fun handle(event: DiscordEvent) {
        when {
            event is DiscordEvent.MenuChosen && event.id == TicketTexts.MENU_ID -> choose(event)
            event is DiscordEvent.ButtonPressed && event.id.startsWith(TicketTexts.CLOSE_PREFIX) -> close(event)
            event is DiscordEvent.ButtonPressed && event.id.startsWith(TicketTexts.CLAIM_PREFIX) -> assign(event, release = false)
            event is DiscordEvent.ButtonPressed && event.id.startsWith(TicketTexts.RELEASE_PREFIX) -> assign(event, release = true)
            event is DiscordEvent.CommandUsed && event.name == "ticket" -> command(event)
        }
    }

    /** Le menu, modifié sur place s'il est connu et encore là, posté (et noté) sinon. */
    private suspend fun showMenu(channel: String) {
        val message = menuMessage()
        val known = messages.find(TicketTexts.MENU_KEY)
        if (known != null && known.channelId == channel) {
            try {
                context.server.edit(known.channelId, known.messageId, message)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.info("Bot Discord, tickets : le menu a disparu, il est reposté ({})", e.toString())
            }
        }
        messages.save(TicketTexts.MENU_KEY, channel, context.server.post(channel, message))
    }

    private fun menuMessage() =
        BotMessage(TicketTexts.menu(mention(HELP)), menu = MenuSpec(TicketTexts.MENU_ID, TicketTexts.MENU_PLACEHOLDER, TicketTexts.OPTIONS))

    /** Les tickets ouverts dont le salon n'est plus sur le serveur sont fermés, le bot pour auteur. */
    private suspend fun closeVanished() {
        val open = store.openOnes()
        if (open.isEmpty()) return
        val existing = context.server.snapshot().channels.map { it.id }.toSet()
        for (ticket in open.filter { it.channelId !in existing }) {
            if (archives.find(ticket.id) != null) continue
            store.close(ticket.id, BOT, context.timer.now())
            journal.line(TicketTexts.journalVanished(ticket.id))
        }
    }

    /**
     * Répond au choix, puis remet le menu à neuf : Discord garde le choix affiché dans le menu tant que le message ne
     * change pas, et le membre ne pourrait plus choisir deux fois la même demande.
     */
    private suspend fun choose(event: DiscordEvent.MenuChosen) {
        if (event.user.isBot) return
        answer(event)
        val channel = event.channelId ?: return
        try {
            context.server.edit(channel, event.messageId, menuMessage())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.info("Bot Discord, tickets : le menu n'a pas pu être remis à neuf : {}", e.toString())
        }
    }

    private suspend fun answer(event: DiscordEvent.MenuChosen) {
        val kind = event.values.firstOrNull() ?: return
        if (kind == TicketTexts.GAME) return tell(event.reply, TicketTexts.gameQuestion(mention(HELP)))
        store.openOf(event.user.id)?.let { return tell(event.reply, TicketTexts.alreadyOpen("<#" + it.channelId + ">")) }
        val category = context.ids.category(HELP)
        if (category == null || TicketTexts.OPTIONS.none { it.value == kind }) return tell(event.reply, TicketTexts.NOT_READY)

        val now = context.timer.now()
        val number = store.open(event.user.id, kind, "", now)
        val channel = try {
            context.server.createChannel(
                category,
                ChannelSpec(
                    name = TicketTexts.channelName(kind, number),
                    kind = ChannelKind.TEXTE,
                    topic = TicketTexts.topic(number, event.user.name, kind),
                    writers = null,
                    viewers = staffRoles(),
                    locked = false,
                    memberViewers = setOf(event.user.id),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Bot Discord, tickets : le salon du ticket {} n'a pas pu être créé : {}", number, e.toString())
            store.close(number, BOT, now)
            return tell(event.reply, TicketTexts.NOT_READY)
        }
        store.channelCreated(number, channel)
        tell(event.reply, TicketTexts.opened("<#" + channel + ">"))
        context.server.post(
            channel,
            BotMessage(
                TicketTexts.welcome(event.user.id),
                buttons = listOf(
                    ButtonSpec(TicketTexts.CLAIM_PREFIX + number, TicketTexts.CLAIM_LABEL, ButtonStyle.PRIMARY),
                    ButtonSpec(TicketTexts.RELEASE_PREFIX + number, TicketTexts.RELEASE_LABEL),
                    ButtonSpec(TicketTexts.CLOSE_PREFIX + number, TicketTexts.CLOSE_LABEL, ButtonStyle.DANGER),
                ),
                mentionUserIds = setOf(event.user.id),
            ),
        )
        journal.line(TicketTexts.journalOpened(number, event.user.id, kind))
    }

    private suspend fun isStaff(user: DiscordUser): Boolean {
        if (user.isBot || staffRoles().isEmpty()) return false
        return try { context.server.freshRolesOf(user.id).any { it in staffRoles() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    private suspend fun command(event: DiscordEvent.CommandUsed) {
        if (event.user.isBot) return
        val ticket = event.channelId?.let { store.byChannel(it) }
            ?: return tell(event.reply, TicketTexts.WRONG_CHANNEL)
        if (event.user.id != ticket.userId && !isStaff(event.user)) return tell(event.reply, TicketTexts.NOT_ALLOWED)
        val prefix = when (event.options["action"]) {
            "prendre" -> TicketTexts.CLAIM_PREFIX
            "liberer" -> TicketTexts.RELEASE_PREFIX
            "fermer" -> TicketTexts.CLOSE_PREFIX
            "etat" -> {
                val archive = archives.find(ticket.id)
                val closing = if (archive?.lastError != null && isStaff(event.user))
                    TicketTexts.CLOSING + "\n" + TicketTexts.archiveFailure(archive.lastError) else TicketTexts.CLOSING
                return tell(event.reply, if (archive != null && archive.state !in setOf(TicketArchiveState.DONE, TicketArchiveState.PURGING)) closing
                    else "Ticket n°" + ticket.id + " : " + (if (ticket.closedAt != null) "fermé." else ticket.assignedTo?.let { "pris en charge par <@" + it + ">." } ?: "en attente du staff."))
            }
            else -> return tell(event.reply, TicketTexts.WRONG_CHANNEL)
        }
        val button = DiscordEvent.ButtonPressed(prefix + ticket.id, event.user, event.channelId, "", event.reply)
        if (prefix == TicketTexts.CLOSE_PREFIX) close(button, respondWhenComplete = true) else assign(button, prefix == TicketTexts.RELEASE_PREFIX)
    }

    private suspend fun assign(event: DiscordEvent.ButtonPressed, release: Boolean) {
        if (!isStaff(event.user)) return tell(event.reply, TicketTexts.STAFF_ONLY)
        val prefix = if (release) TicketTexts.RELEASE_PREFIX else TicketTexts.CLAIM_PREFIX
        val ticket = event.id.removePrefix(prefix).toIntOrNull()?.let { store.byId(it) }
            ?: return tell(event.reply, TicketTexts.ALREADY_CLOSED)
        if (event.channelId != ticket.channelId) return tell(event.reply, TicketTexts.WRONG_CHANNEL)
        if (ticket.closedAt != null) return tell(event.reply, TicketTexts.ALREADY_CLOSED)
        if (archives.find(ticket.id) != null) return tell(event.reply, TicketTexts.CLOSING)
        val changed = if (release) store.release(ticket.id, event.user.id)
            else store.claim(ticket.id, event.user.id, context.timer.now())
        if (!changed) return tell(event.reply, if (release) TicketTexts.NOT_ASSIGNED else TicketTexts.UNAVAILABLE)
        tell(event.reply, if (release) TicketTexts.RELEASED else TicketTexts.CLAIMED)
        journal.line("Ticket n°" + ticket.id + (if (release) " libéré par <@" else " pris en charge par <@") + event.user.id + ">.")
    }

    private suspend fun close(event: DiscordEvent.ButtonPressed, respondWhenComplete: Boolean = false) {
        if (event.user.isBot) return
        val ticket = event.id.removePrefix(TicketTexts.CLOSE_PREFIX).toIntOrNull()?.let { store.byId(it) }
        if (ticket == null) return tell(event.reply, TicketTexts.ALREADY_CLOSED)
        val job = archives.find(ticket.id)
        if ((ticket.closedAt != null && job == null) || job?.state in setOf(TicketArchiveState.DONE, TicketArchiveState.PURGING)) return tell(event.reply, TicketTexts.ALREADY_CLOSED)
        if (event.channelId != ticket.channelId) return tell(event.reply, TicketTexts.WRONG_CHANNEL)
        if (event.user.id != ticket.userId && !isStaff(event.user)) return tell(event.reply, TicketTexts.NOT_ALLOWED)
        event.reply.acknowledge()
        val complete = try { archiver.request(ticket, event.user.id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
        if (complete) {
            if (respondWhenComplete) tell(event.reply, TicketTexts.CLOSED)
        } else {
            val pending = archives.find(ticket.id)
            tell(event.reply, if (pending != null && pending.lastError == null) TicketTexts.CLOSING else TicketTexts.ARCHIVE_FAILED)
        }
    }

    private fun staffRoles(): Set<String> = setOfNotNull(context.ids.role(STAFF), context.ids.role(MODERATION))

    private suspend fun tell(reply: Reply, text: String) {
        reply.send(BotMessage(text), onlyToUser = true)
    }

    private fun mention(planId: String): String = context.ids.channel(planId)?.let { "<#" + it + ">" } ?: ("#" + planId)

    private companion object {
        const val TICKETS = "tickets"
        const val HELP = "aide"
        const val STAFF = "staff"
        const val MODERATION = "moderation"

        /** L'auteur d'un ticket que le bot ferme lui-même ; un identifiant Discord n'a jamais cette forme. */
        const val BOT = "bot"

        val log = LoggerFactory.getLogger(TicketFeature::class.java)
    }
}
