package com.silooo.core.discord

import com.silooo.core.db.Database
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/** Les textes du podium de la salle d'attente. À valider par Kr. Aucune récompense n'est promise (décision de Kr). */
object LobbyPodiumTexts {
    const val TITLE = "Le saut infini de la semaine, dans la salle d'attente de Silooo :"
    const val NOBODY = "Personne n'a sauté cette semaine dans la salle d'attente."
    const val UNLINKED = "un joueur non relié"
    const val FOOTER = "Pour voir ton pseudo ici, relie ton compte avec /lier."

    /** « 1. Ana, 42 blocs » ; un joueur non relié reste anonyme. */
    fun line(rank: Int, gamertag: String?, score: Int): String =
        rank.toString() + ". " + (gamertag ?: UNLINKED) + ", " + WelcomeTexts.thousands(score) + (if (score > 1) " blocs" else " bloc")
}

/** Une place du podium : le gamertag n'est rendu que si le joueur a relié son compte Discord. */
data class PodiumLine(val linkedGamertag: String?, val score: Int)

interface LobbyPodiumStore {
    suspend fun podium(week: LocalDate, size: Int): List<PodiumLine>

    /** Réserve la semaine : `false` si elle est déjà postée (ou réservée), et rien ne part. */
    suspend fun claim(week: LocalDate): Boolean
}

class JdbcLobbyPodiumStore(private val database: Database) : LobbyPodiumStore {
    override suspend fun podium(week: LocalDate, size: Int): List<PodiumLine> = database.transaction { connection ->
        connection.prepareStatement(
            "select w.gamertag, w.best, g.player_xuid is not null from lobby_week_bests w " +
                "left join discord_game_links g on g.player_xuid = w.xuid " +
                "where w.week_start = ? order by w.best desc, w.best_at, w.xuid limit ?",
        ).use {
            it.setObject(1, week)
            it.setInt(2, size)
            it.executeQuery().use { rows ->
                buildList { while (rows.next()) add(PodiumLine(if (rows.getBoolean(3)) rows.getString(1) else null, rows.getInt(2))) }
            }
        }
    }

    override suspend fun claim(week: LocalDate): Boolean = database.transaction { connection ->
        connection.prepareStatement("insert ignore into lobby_week_posts (week_start) values (?)").use {
            it.setObject(1, week)
            it.executeUpdate() == 1
        }
    }
}

/**
 * Le podium de la salle d'attente, chaque lundi à l'heure du rapport (`definitions/discord.json`, rapport) : les [size]
 * premiers de la semaine qui vient de finir (elle part du lundi à minuit, fuseau de `definitions/attente.json`) dans
 * `#discussion`. Seuls les comptes reliés sont nommés ; les autres sont « un joueur non relié », ce qui invite à se
 * relier. Une semaine ne part qu'une fois (réservée avant l'envoi) ; un bot éteint ce lundi-là la poste au démarrage.
 * Le salon reste à confirmer dans le plan du serveur Discord (`docs/discord.md`).
 */
class LobbyPodiumFeature(
    private val context: BotContext,
    private val report: ReportRules,
    private val zone: ZoneId,
    private val size: Int,
    private val store: LobbyPodiumStore,
) : Feature {
    override val name = "podium-attente"

    override suspend fun start() {
        arm()
        val monday = latestMonday(context.timer.now())
        attempt { publish(monday) }
    }

    private fun arm() {
        val now = context.timer.now()
        val monday = latestMonday(now).plusWeeks(1)
        context.timer.after(Duration.between(now, at(monday))) {
            try { attempt { publish(monday) } } finally { arm() }
        }
    }

    /** Le podium de la semaine qui finit le lundi [monday]. */
    private suspend fun publish(monday: LocalDate) {
        val channel = context.ids.channel(DISCUSSION) ?: return
        val week = monday.minusWeeks(1)
        val lines = store.podium(week, size)
        if (lines.isEmpty()) return
        if (!store.claim(week)) return
        val text = buildString {
            append(LobbyPodiumTexts.TITLE)
            lines.forEachIndexed { index, line -> append('\n').append(LobbyPodiumTexts.line(index + 1, line.linkedGamertag, line.score)) }
            if (lines.any { it.linkedGamertag == null }) append('\n').append(LobbyPodiumTexts.FOOTER)
        }
        context.server.send(channel, text)
    }

    private suspend fun attempt(action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Bot Discord, podium de la salle d'attente : {}", e.toString())
        }
    }

    internal fun latestMonday(now: Instant): LocalDate {
        val monday = now.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return if (at(monday).isAfter(now)) monday.minusWeeks(1) else monday
    }

    private fun at(monday: LocalDate): Instant = monday.atTime(report.hour, 0).atZone(zone).toInstant()

    private companion object {
        const val DISCUSSION = "discussion"
        val log = LoggerFactory.getLogger(LobbyPodiumFeature::class.java)
    }
}
