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
        "Ticket n°" + number + " (" + kind + ") de <@" + authorId + ">, fermé par <@" + byId + ">."

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
 * Le bouton « Fermer le ticket », pour l'auteur et le staff, ferme le ticket en base, recopie les 200 derniers
 * messages du salon dans `#journal-du-bot` (ceux du bot compris, en messages de 2 000 caractères au plus, coupés entre
 * deux lignes), puis supprime le salon. Si le salon n'a pas pu être relu, il reste. Le message du menu est retrouvé
 * par sa clé ([BotMessageStore]) : modifié sur place à chaque démarrage et après chaque choix, reposté seulement s'il a disparu. Au
 * démarrage, un ticket ouvert dont le salon a été supprimé à la main est fermé.
 */
class TicketFeature(
    private val context: BotContext,
    private val store: TicketStore,
    private val messages: BotMessageStore,
    private val journal: BotJournal,
    private val zone: ZoneId = ZoneId.of("Europe/Paris"),
) : Feature {
    override val name = "tickets"

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
    }

    override suspend fun handle(event: DiscordEvent) {
        when {
            event is DiscordEvent.MenuChosen && event.id == TicketTexts.MENU_ID -> choose(event)
            event is DiscordEvent.ButtonPressed && event.id.startsWith(TicketTexts.CLOSE_PREFIX) -> close(event)
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
                buttons = listOf(ButtonSpec(TicketTexts.CLOSE_PREFIX + number, TicketTexts.CLOSE_LABEL, ButtonStyle.DANGER)),
                mentionUserIds = setOf(event.user.id),
            ),
        )
        journal.line(TicketTexts.journalOpened(number, event.user.id, kind))
    }

    private suspend fun close(event: DiscordEvent.ButtonPressed) {
        val ticket = event.id.removePrefix(TicketTexts.CLOSE_PREFIX).toIntOrNull()?.let { store.byId(it) }
        if (ticket == null || ticket.closedAt != null) return tell(event.reply, TicketTexts.ALREADY_CLOSED)
        val staff = staffRoles()
        if (event.user.id != ticket.userId && staff.none { it in event.user.roleIds }) return tell(event.reply, TicketTexts.NOT_ALLOWED)

        event.reply.acknowledge()
        store.close(ticket.id, event.user.id, context.timer.now())
        journal.line(TicketTexts.journalClosed(ticket.id, ticket.kind, ticket.userId, event.user.id))
        val lines = try {
            context.server.history(ticket.channelId, HISTORY).filter { it.text.isNotBlank() }.map { TicketTexts.archiveLine(it, zone) }
        } catch (e: DiscordRefusal) {
            log.warn("Bot Discord, tickets : le salon du ticket {} n'a pas pu être relu : {}", ticket.id, e.message)
            return journal.line(TicketTexts.journalUnread(ticket.id))
        }
        pack(lines).forEach { journal.line(it) }
        try {
            context.server.deleteChannel(ticket.channelId)
        } catch (e: DiscordRefusal) {
            log.warn("Bot Discord, tickets : le salon du ticket {} n'a pas pu être supprimé : {}", ticket.id, e.message)
        }
    }

    /** Les lignes de l'archive en messages de [BotMessage.MAX_TEXT] caractères au plus, coupés entre deux lignes. */
    private fun pack(lines: List<String>): List<String> {
        val packed = mutableListOf<String>()
        val current = StringBuilder()
        for (line in lines.flatMap { it.chunked(BotMessage.MAX_TEXT) }) {
            if (current.isNotEmpty() && current.length + 1 + line.length > BotMessage.MAX_TEXT) {
                packed += current.toString()
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
        }
        if (current.isNotEmpty()) packed += current.toString()
        return packed
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

        /** Les derniers messages du salon que l'archive recopie. */
        const val HISTORY = 200

        val log = LoggerFactory.getLogger(TicketFeature::class.java)
    }
}
