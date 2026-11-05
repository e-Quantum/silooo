package com.silooo.core.discord

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Les mots du bot pour l'XP de tchat (`docs/discord.md`, « Faire grandir le serveur »), **à valider par Kr**. Sans emoji,
 * sans mot-réclame (`design/README.md`, « Écrire sans slop »). Aucune mention ne part : les membres sont cités par leur
 * identifiant, le message n'en prévient aucun.
 */
object XpTexts {
    const val COMMAND = "niveau"
    const val COMMAND_DESCRIPTION = "Montre ton niveau d'XP de tchat"

    /** La semaine s'est passée sans un seul point. */
    const val NOBODY = "Personne n'a écrit cette semaine."

    /** « <@7> passe au niveau 5. » : [level], le niveau atteint. */
    fun levelUp(userId: String, level: Int): String = "<@" + userId + "> passe au niveau " + level + "."

    /** La réponse de `/niveau`, au membre seul ; [next] : les points du niveau suivant. */
    fun yourLevel(level: Int, points: Int, weekPoints: Int, next: Int): String =
        "Niveau " + level + ", " + pointsOf(points) + ", " + WelcomeTexts.thousands(weekPoints) + " cette semaine. " +
            "Prochain niveau à " + pointsOf(next) + "."

    /** Un membre sans ligne ; [channel] : la mention du salon où écrire, ou son nom. */
    fun noPoints(channel: String): String = "Pas encore de points : écris dans " + channel + "."

    /** Le titre du classement du lundi ; [size] : la taille du classement du fichier de définitions. */
    fun weekTitle(size: Int): String = if (size == 10) "Les dix de la semaine :" else "Le classement de la semaine :"

    /** Une ligne du classement : « 1. <@7>, 540 points ». */
    fun rankLine(rank: Int, userId: String, points: Int): String = rank.toString() + ". <@" + userId + ">, " + pointsOf(points)

    private fun pointsOf(count: Int): String = WelcomeTexts.thousands(count) + (if (count > 1) " points" else " point")
}

/**
 * L'XP de tchat (`docs/discord.md`, « Faire grandir le serveur »). Un message écrit dans l'un des [XpRules.channels]
 * (ou dans un fil d'un forum de la liste) rapporte de [XpRules.pointsMin] à [XpRules.pointsMax] points, une fois par
 * [XpRules.intervalSeconds] au plus par membre ; le message supprimé rend ses points. Aux niveaux des [XpRules.roles],
 * le membre reçoit le rôle (un seul rôle de niveau à la fois) et Oooscar le dit dans le salon. `/niveau` montre la
 * ligne du membre, à lui seul. Le lundi à l'heure du rapport, les [XpRules.leaderboardSize] premiers de la semaine
 * sont publiés dans `#discussion` puis la semaine repart de zéro ; un bot éteint ce lundi-là publie à son démarrage.
 * La semaine change à l'heure du rapport et pas à minuit : un message du lundi matin compte pour le classement qui
 * sort le même matin.
 *
 * Un refus de Discord (le rôle, le message) n'annule jamais des points déjà écrits : il va au journal du service.
 */
class XpFeature(
    private val context: BotContext,
    private val rules: XpRules,
    private val report: ReportRules,
    private val zone: ZoneId,
    private val store: XpStore,
    private val random: Random = Random.Default,
) : Feature {
    override val name = "xp"

    override val commands = listOf(CommandSpec(XpTexts.COMMAND, XpTexts.COMMAND_DESCRIPTION))

    override suspend fun start() {
        armWeekly()
        val oldest = store.oldestWeekStart() ?: return
        val current = weekStart(context.timer.now())
        if (oldest.isBefore(current)) publishWeek(current)
    }

    override suspend fun handle(event: DiscordEvent) {
        when {
            event is DiscordEvent.MessagePosted -> count(event)
            event is DiscordEvent.MessageRemoved -> store.uncount(event.messageId)
            event is DiscordEvent.CommandUsed && event.name == XpTexts.COMMAND -> show(event)
        }
    }

    private suspend fun count(event: DiscordEvent.MessagePosted) {
        if (event.author.isBot || !counts(event)) return
        val userId = event.author.id
        val now = context.timer.now()
        val known = store.record(userId)
        val last = known?.lastCountedAt
        if (last != null && Duration.between(last, now) < Duration.ofSeconds(rules.intervalSeconds.toLong())) return
        val before = known?.level ?: 0
        val points = random.nextInt(rules.pointsMin, rules.pointsMax + 1)
        val record = store.count(userId, event.messageId, points, now, weekStart(now))
        val level = levelFor(record.points)
        if (level > before) reach(event, userId, before, level)
    }

    /** Le salon (ou le forum qui porte le fil) est l'un de ceux du fichier de définitions, présents sur le serveur. */
    private fun counts(event: DiscordEvent.MessagePosted): Boolean {
        val ids = rules.channels.mapNotNull { context.ids.channel(it) }
        return event.channelId in ids || (event.parentChannelId != null && event.parentChannelId in ids)
    }

    /** Le membre passe de [before] à [level] : le niveau s'écrit, puis le rôle du palier et la ligne dans le salon. */
    private suspend fun reach(event: DiscordEvent.MessagePosted, userId: String, before: Int, level: Int) {
        store.levelReached(userId, level)
        val earned = roleFor(level) ?: return
        val held = roleFor(before)
        if (earned == held) return
        context.ids.role(earned.role)?.let { roleId -> attempt("rôle") { context.server.giveRole(userId, roleId) } }
        held?.let { context.ids.role(it.role) }?.let { roleId -> attempt("rôle précédent") { context.server.takeRole(userId, roleId) } }
        attempt("annonce") { context.server.send(event.channelId, XpTexts.levelUp(userId, level)) }
    }

    private suspend fun show(event: DiscordEvent.CommandUsed) {
        val record = store.record(event.user.id)
        val text = if (record == null) {
            XpTexts.noPoints(context.ids.channel(DISCUSSION)?.let { "<#" + it + ">" } ?: "#discussion")
        } else {
            val next = pointsForLevel(record.level + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            XpTexts.yourLevel(record.level, record.points, record.weekPoints, next)
        }
        event.reply.send(BotMessage(text), onlyToUser = true)
    }

    /** Programme le prochain lundi à l'heure du rapport ; le lundi visé est celui du classement, pas l'heure du réveil. */
    private fun armWeekly() {
        val now = context.timer.now()
        val due = nextReport(now)
        val monday = due.atZone(zone).toLocalDate()
        context.timer.after(Duration.between(now, due)) {
            try {
                publishWeek(monday)
            } finally {
                armWeekly()
            }
        }
    }

    /** Publie le classement de la semaine qui finit puis remet la semaine à zéro, que le message soit parti ou non. */
    private suspend fun publishWeek(monday: LocalDate) {
        val channel = context.ids.channel(DISCUSSION)
        if (channel != null) {
            val top = store.weekTop(rules.leaderboardSize)
            attempt("classement") { context.server.send(channel, leaderboard(top)) }
        }
        store.resetWeek(monday)
    }

    private fun leaderboard(top: List<XpRecord>): String {
        if (top.isEmpty()) return XpTexts.NOBODY
        val lines = StringBuilder(XpTexts.weekTitle(rules.leaderboardSize))
        for ((index, record) in top.withIndex()) {
            val line = XpTexts.rankLine(index + 1, record.userId, record.weekPoints)
            if (lines.length + 1 + line.length > BotMessage.MAX_TEXT) break
            lines.append('\n').append(line)
        }
        return lines.toString()
    }

    /** Un geste vers Discord dont le refus ne défait rien : il va au journal du service. */
    private suspend fun attempt(what: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Bot Discord, XP, {} : {}", what, e.toString())
        }
    }

    /** Le palier le plus haut atteint à [level], ou `null` avant le premier. */
    private fun roleFor(level: Int): LevelRole? = rules.roles.filter { it.level <= level }.maxByOrNull { it.level }

    /** Les points du niveau [level] : base × n + croissance × n × (n - 1) / 2. */
    private fun pointsForLevel(level: Int): Long =
        rules.levelBase.toLong() * level + rules.levelGrowth.toLong() * level * (level - 1) / 2

    private fun levelFor(points: Int): Int {
        var level = 0
        while (level < MAX_LEVEL && pointsForLevel(level + 1) <= points) level++
        return level
    }

    /**
     * Le lundi de la semaine comptée à l'instant [at] : le dernier lundi dont l'heure du rapport est passée. Le lundi
     * avant cette heure appartient encore à la semaine qui finit.
     */
    private fun weekStart(at: Instant): LocalDate {
        val monday = at.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return if (reportAt(monday).isAfter(at)) monday.minusWeeks(1) else monday
    }

    /** Le prochain passage du rapport, strictement après [at]. */
    private fun nextReport(at: Instant): Instant {
        val monday = at.atZone(zone).toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        val due = reportAt(monday)
        return if (due.isAfter(at)) due else reportAt(monday.plusWeeks(1))
    }

    private fun reportAt(monday: LocalDate): Instant = monday.atTime(report.hour, 0).atZone(zone).toInstant()

    private companion object {
        const val DISCUSSION = "discussion"

        /** Garde-fou : des définitions sans croissance ni base ne bouclent pas. */
        const val MAX_LEVEL = 1000

        val log = LoggerFactory.getLogger(XpFeature::class.java)
    }
}
