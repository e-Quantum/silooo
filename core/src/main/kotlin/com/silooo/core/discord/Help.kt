package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Les mots du bot pour l'Aide du mois (`docs/discord.md`, « Les cartes et les tableaux d'Oooscar »), **à valider par
 * Kr**. Sans emoji, sans mot-réclame (`design/README.md`, « Écrire sans slop »). Les membres sont cités par leur
 * identifiant ; seul « Résolu par <@7>. » prévient son membre, le tableau et le journal ne préviennent personne.
 */
object HelpTexts {
    /** La clé du tableau dans `discord_bot_messages`. */
    const val BOARD_KEY = "pantheon-aide"

    /** Les identifiants des gestes de Discord, suivis de l'identifiant du sujet. */
    const val BUTTON_PREFIX = "aide-resolu:"
    const val MENU_PREFIX = "aide-resolu-par:"

    const val BUTTON_LABEL = "Résolu par"
    const val MENU_PLACEHOLDER = "Choisis un membre"

    /** Le message que le bot pose dans chaque sujet neuf du forum, avec le bouton. */
    const val PROMPT = "Quand ta question est réglée, appuie sur le bouton pour dire qui t'a aidé."

    /** Au-dessus du menu, vu du membre seul. */
    const val MENU = "Choisis le membre qui a résolu ce sujet."

    const val NOT_ALLOWED = "Seul l'auteur du sujet ou le staff peut dire qui l'a résolu."
    const val ALREADY_RESOLVED = "Ce sujet est déjà résolu."
    const val NOBODY_ELSE = "Personne d'autre n'a encore écrit dans ce sujet."
    const val UNREADABLE = "Ce sujet ne peut pas être relu pour l'instant."

    /** La réponse au choix, qui prend la place du menu. */
    const val NOTED = "C'est noté."

    const val TITLE = "Aide du mois :"
    const val BOARD_NOBODY = "Personne n'a encore résolu de sujet ce mois-ci."

    /** Le titre de la ligne du journal, le premier du mois : le classement du mois qui finit. */
    const val PAST_TITLE = "Aide du mois passé :"
    const val PAST_NOBODY = "Personne n'a résolu de sujet."

    /** Le message dans le sujet résolu : il prévient le membre désigné. */
    fun resolvedBy(userId: String): String = "Résolu par <@" + userId + ">."

    /** Une ligne du classement : « 1. <@7>, 3 réponses retenues ». */
    fun rankLine(rank: Int, userId: String, answers: Int): String =
        rank.toString() + ". <@" + userId + ">, " + WelcomeTexts.thousands(answers) + (if (answers > 1) " réponses retenues" else " réponse retenue")
}

/**
 * L'Aide du mois (`docs/discord.md`, « Les cartes et les tableaux d'Oooscar »). Dans chaque sujet neuf du forum `#aide`,
 * le bot pose un message avec le bouton « Résolu par ». L'auteur du sujet et le staff (les rôles Staff et Modération)
 * peuvent l'appuyer, et eux seuls : le bot répond au membre seul, avec un menu des membres qui ont écrit dans le sujet (les
 * bots et l'auteur du sujet exclus). Le choix marque le sujet résolu (une seule fois), ajoute une réponse retenue au
 * membre pour le mois, le nomme dans le sujet (c'est le seul ping) et remet le tableau à jour.
 *
 * Le tableau vit dans `#panthéon` (clé [HelpTexts.BOARD_KEY]), modifié sur place à chaque changement. Le premier du mois à
 * minuit, à l'heure de [zone], le classement du mois qui finit va au journal du bot et le tableau repart vide ; le titre
 * en jeu du premier est l'affaire de la direction du jeu. Sans le forum `#aide` sur le serveur, la fonction ne fait
 * rien ; sans `#panthéon`, elle compte sans tableau.
 */
class HelpFeature(
    private val context: BotContext,
    private val zone: ZoneId,
    private val store: HelpStore,
    messages: BotMessageStore,
    private val journal: BotJournal,
) : Feature {
    override val name = "aide"

    private val board = LiveMessage(context, messages, HelpTexts.BOARD_KEY, context.ids.channel(PANTHEON))

    /** L'auteur de chaque sujet vu naître depuis le démarrage ; pour les autres, le bot relit le premier message. */
    private val owners = Remembered<String>()

    override suspend fun start() {
        if (context.ids.channel(FORUM) == null) return
        armMonthly()
        show()
    }

    override suspend fun handle(event: DiscordEvent) {
        when {
            event is DiscordEvent.MessagePosted -> opened(event)
            event is DiscordEvent.ButtonPressed && event.id.startsWith(HelpTexts.BUTTON_PREFIX) -> press(event)
            event is DiscordEvent.MenuChosen && event.id.startsWith(HelpTexts.MENU_PREFIX) -> choose(event)
        }
    }

    /** Un sujet neuf du forum : son premier message porte l'identifiant du sujet. */
    private suspend fun opened(event: DiscordEvent.MessagePosted) {
        val forum = context.ids.channel(FORUM) ?: return
        if (event.parentChannelId != forum || event.messageId != event.channelId || event.author.isBot) return
        owners[event.channelId] = event.author.id
        val button = ButtonSpec(HelpTexts.BUTTON_PREFIX + event.channelId, HelpTexts.BUTTON_LABEL, ButtonStyle.PRIMARY)
        context.server.post(event.channelId, BotMessage(HelpTexts.PROMPT, buttons = listOf(button)))
    }

    private suspend fun press(event: DiscordEvent.ButtonPressed) {
        if (context.ids.channel(FORUM) == null) return
        val thread = event.id.removePrefix(HelpTexts.BUTTON_PREFIX)
        if (!allowed(thread, event.user)) return tell(event.reply, HelpTexts.NOT_ALLOWED)
        if (store.isResolved(thread)) return tell(event.reply, HelpTexts.ALREADY_RESOLVED)
        val owner = ownerOf(thread)
        val written = try {
            context.server.history(thread, HISTORY)
        } catch (e: DiscordRefusal) {
            log.warn("Bot Discord, aide : le sujet {} n'a pas pu être relu : {}", thread, e.message)
            return tell(event.reply, HelpTexts.UNREADABLE)
        }
        val writers = written.filter { !it.bot && it.authorId != owner }.distinctBy { it.authorId }.take(MenuSpec.MAX_OPTIONS)
        if (writers.isEmpty()) return tell(event.reply, HelpTexts.NOBODY_ELSE)
        val options = writers.map { MenuOption(it.authorId, it.authorName.take(OPTION_LABEL_MAX)) }
        val menu = MenuSpec(HelpTexts.MENU_PREFIX + thread, HelpTexts.MENU_PLACEHOLDER, options)
        event.reply.send(BotMessage(HelpTexts.MENU, menu = menu), onlyToUser = true)
    }

    private suspend fun choose(event: DiscordEvent.MenuChosen) {
        if (context.ids.channel(FORUM) == null) return
        val thread = event.id.removePrefix(HelpTexts.MENU_PREFIX)
        val resolver = event.values.firstOrNull() ?: return
        if (!allowed(thread, event.user)) return tell(event.reply, HelpTexts.NOT_ALLOWED)
        val now = context.timer.now()
        if (!store.mark(thread, resolver, event.user.id, now, monthStart(now))) return tell(event.reply, HelpTexts.ALREADY_RESOLVED)
        event.reply.edit(BotMessage(HelpTexts.NOTED))
        attempt("annonce") { context.server.post(thread, BotMessage(HelpTexts.resolvedBy(resolver), mentionUserIds = setOf(resolver))) }
        attempt("tableau") { show() }
    }

    /** L'auteur du sujet ou le staff. */
    private suspend fun allowed(thread: String, user: DiscordUser): Boolean {
        val staff = setOfNotNull(context.ids.role(STAFF), context.ids.role(MODERATION))
        return staff.any { it in user.roleIds } || user.id == ownerOf(thread)
    }

    /** L'auteur du sujet : retenu à sa naissance, sinon le premier message du sujet (il porte l'identifiant du sujet). */
    private suspend fun ownerOf(thread: String): String? {
        owners[thread]?.let { return it }
        val first = try {
            context.server.message(thread, thread)
        } catch (e: DiscordRefusal) {
            log.warn("Bot Discord, aide : le premier message du sujet {} n'a pas pu être lu : {}", thread, e.message)
            null
        }
        return first?.authorId?.also { owners[thread] = it }
    }

    private suspend fun show() {
        val top = store.top(monthStart(context.timer.now()), TOP)
        board.show(BotMessage(rankedText(HelpTexts.TITLE, lines(top), HelpTexts.BOARD_NOBODY), card = true))
    }

    private fun lines(top: List<HelpCount>): List<String> =
        top.mapIndexed { index, count -> HelpTexts.rankLine(index + 1, count.userId, count.answers) }

    /** Écrit le mois [past] au journal puis remet le tableau à neuf pour le mois qui commence. */
    private suspend fun closeMonth(past: LocalDate) {
        attempt("journal du mois") {
            val top = store.top(past, TOP)
            journal.line(rankedText(HelpTexts.PAST_TITLE, lines(top), HelpTexts.PAST_NOBODY))
        }
        show()
    }

    /** Programme le prochain premier du mois à minuit ; le mois visé est celui qui finit, pas l'heure du réveil. */
    private fun armMonthly() {
        val now = context.timer.now()
        val first = now.atZone(zone).toLocalDate().withDayOfMonth(1).plusMonths(1)
        context.timer.after(Duration.between(now, first.atStartOfDay(zone).toInstant())) {
            try {
                closeMonth(first.minusMonths(1))
            } finally {
                armMonthly()
            }
        }
    }

    private fun monthStart(at: Instant): LocalDate = at.atZone(zone).toLocalDate().withDayOfMonth(1)

    private suspend fun tell(reply: Reply, text: String) {
        reply.send(BotMessage(text), onlyToUser = true)
    }

    /** Un geste dont l'échec ne défait rien de ce qui est marqué : il va au journal du service. */
    private suspend fun attempt(what: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Bot Discord, aide, {} : {}", what, e.toString())
        }
    }

    private companion object {
        const val FORUM = "aide"
        const val PANTHEON = "pantheon"
        const val STAFF = "staff"
        const val MODERATION = "moderation"

        /** Les derniers messages du sujet dont le menu tire les membres qui ont écrit. */
        const val HISTORY = 100

        /** Les lignes du tableau. */
        const val TOP = 10

        /** Discord coupe le libellé d'un choix de menu à cent caractères. */
        const val OPTION_LABEL_MAX = 100

        val log = LoggerFactory.getLogger(HelpFeature::class.java)
    }
}
