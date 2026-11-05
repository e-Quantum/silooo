package com.silooo.core.discord

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Les mots du bot pour le top réactions (`docs/discord.md`, « Les cartes et les tableaux d'Oooscar »), **à valider par
 * Kr**. Sans emoji, sans mot-réclame (`design/README.md`, « Écrire sans slop »). Les membres sont cités par leur
 * identifiant : aucun message ne prévient personne.
 */
object ReactionTexts {
    /** La clé du tableau dans `discord_bot_messages`. */
    const val BOARD_KEY = "pantheon-reactions"

    const val TITLE = "Top réactions de la semaine :"

    /** Aucune réaction comptée depuis lundi. */
    const val NOBODY = "Personne n'a encore réagi cette semaine."

    /** Le titre de la ligne du journal, le lundi : le classement de la semaine qui finit. */
    const val PAST_TITLE = "Top réactions de la semaine passée :"

    const val PAST_NOBODY = "Personne n'a réagi."

    /** Une ligne du classement : « 1. <@7>, 42 réactions ». */
    fun rankLine(rank: Int, userId: String, given: Int): String =
        rank.toString() + ". <@" + userId + ">, " + WelcomeTexts.thousands(given) + (if (given > 1) " réactions" else " réaction")
}

/**
 * Le top réactions de la semaine (`docs/discord.md`, « Les cartes et les tableaux d'Oooscar »). Chaque membre (les bots
 * exclus) compte pour une réaction par message où il réagit, quel que soit l'emoji, dans les [ReactionRules.channels]
 * du plan (et dans les sujets de leurs forums, pour ceux que le bot a vus passer depuis son démarrage : Discord ne dit
 * pas le forum d'une réaction). Retirer une réaction ne reprend rien.
 *
 * Le tableau vit dans `#panthéon` (clé [ReactionTexts.BOARD_KEY]), modifié sur place au plus une fois par [REFRESH] et
 * seulement s'il a changé. Le lundi à minuit, à l'heure de [zone], la ligne du classement de la semaine passée va au
 * journal du bot, les semaines et les marques d'avant sont purgées, et le tableau repart vide ; un bot éteint ce
 * lundi-là le fait à son démarrage. Sans `#panthéon` sur le serveur, la fonction ne fait rien.
 */
class ReactionFeature(
    private val context: BotContext,
    private val rules: ReactionRules,
    private val zone: ZoneId,
    private val store: ReactionStore,
    messages: BotMessageStore,
    private val journal: BotJournal,
) : Feature {
    override val name = "reactions"

    private val board = LiveMessage(context, messages, ReactionTexts.BOARD_KEY, context.ids.channel(PANTHEON))
    private val threads = ThreadParents()

    /** Une réaction a été comptée depuis le dernier affichage du tableau. */
    private var changed = false

    override suspend fun start() {
        if (context.ids.channel(PANTHEON) == null) return
        armWeekly()
        context.timer.every(REFRESH) { if (changed) show() }
        val oldest = store.oldestWeekStart()
        val current = weekStart(context.timer.now())
        if (oldest != null && oldest.isBefore(current)) closeWeek(oldest, current) else show()
    }

    override suspend fun handle(event: DiscordEvent) {
        when {
            event is DiscordEvent.MessagePosted -> threads.note(event)
            event is DiscordEvent.ReactionAdded -> count(event)
        }
    }

    private suspend fun count(event: DiscordEvent.ReactionAdded) {
        if (event.isBot || context.ids.channel(PANTHEON) == null || !counts(event.channelId)) return
        val now = context.timer.now()
        if (store.add(event.messageId, event.userId, now, weekStart(now))) changed = true
    }

    /** Le salon (ou le forum qui porte le sujet) est l'un de ceux du fichier de définitions, présents sur le serveur. */
    private fun counts(channelId: String): Boolean {
        val ids = rules.channels.mapNotNull { context.ids.channel(it) }
        val parent = threads.parentOf(channelId)
        return channelId in ids || (parent != null && parent in ids)
    }

    private suspend fun show() {
        changed = false
        try {
            val top = store.top(weekStart(context.timer.now()), rules.topSize)
            board.show(BotMessage(rankedText(ReactionTexts.TITLE, lines(top), ReactionTexts.NOBODY), card = true))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            changed = true
            throw e
        }
    }

    private fun lines(top: List<ReactionCount>): List<String> =
        top.mapIndexed { index, count -> ReactionTexts.rankLine(index + 1, count.userId, count.given) }

    /** Écrit la semaine [past] au journal, purge tout ce qui précède la semaine [current], puis remet le tableau à neuf. */
    private suspend fun closeWeek(past: LocalDate, current: LocalDate) {
        try {
            val top = store.top(past, rules.topSize)
            journal.line(rankedText(ReactionTexts.PAST_TITLE, lines(top), ReactionTexts.PAST_NOBODY))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Bot Discord, réactions, journal de la semaine : {}", e.toString())
        }
        store.purge(current, current.atStartOfDay(zone).toInstant())
        show()
    }

    /** Programme le prochain lundi à minuit ; le lundi visé est celui de la nouvelle semaine, pas l'heure du réveil. */
    private fun armWeekly() {
        val now = context.timer.now()
        val due = nextWeek(now)
        val monday = due.atZone(zone).toLocalDate()
        context.timer.after(Duration.between(now, due)) {
            try {
                closeWeek(monday.minusWeeks(1), monday)
            } finally {
                armWeekly()
            }
        }
    }

    /** Le lundi de la semaine de l'instant [at], dans le fuseau du fichier de définitions. */
    private fun weekStart(at: Instant): LocalDate =
        at.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Le prochain lundi à minuit, strictement après [at]. */
    private fun nextWeek(at: Instant): Instant =
        at.atZone(zone).toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant()

    companion object {
        /** Le tableau se modifie au plus une fois par minute : Discord limite les modifications d'un même message. */
        val REFRESH: Duration = Duration.ofSeconds(60)

        private const val PANTHEON = "pantheon"
        private val log = LoggerFactory.getLogger(ReactionFeature::class.java)
    }
}
