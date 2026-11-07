package com.silooo.core.lobby

import com.silooo.core.db.Xuid
import com.silooo.core.site.NewsArticle
import java.sql.Connection
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.Serializable

@Serializable
data class LobbyEntry(val rank: Int, val gamertag: String, val score: Int)

@Serializable
data class LobbyNews(val title: String, val date: String?)

@Serializable
data class LobbyBoard(val allTime: List<LobbyEntry>, val week: List<LobbyEntry>, val pioneers: Long, val news: List<LobbyNews>)

@Serializable
data class LobbyRunResult(val best: Int, val rank: Int, val weekBest: Int, val weekRank: Int, val newRecord: Boolean, val board: LobbyBoard)

@Serializable
data class LobbyPlayer(val linked: Boolean, val pioneer: Boolean, val best: Int, val weekBest: Int)

/**
 * Les règles de la salle d'attente, sans HTTP. La semaine part du lundi à minuit dans le fuseau de attente.json ; une
 * course plus rapide que `plafondParSeconde` blocs par seconde (une seconde au moins) est hors du jeu ; les égalités
 * partagent le rang (le rang est le nombre de records strictement meilleurs, plus un).
 */
class LobbyService(
    private val rules: LobbyDefinitions,
    private val store: LobbyStore,
    private val news: () -> List<NewsArticle>,
) {
    /** La course est possible : [score] blocs en [seconds] secondes, au plafond près. */
    fun plausible(score: Int, seconds: Int): Boolean = score <= rules.parcours.maxPerSecond * maxOf(seconds, 1)

    fun weekOf(at: Instant): LocalDate =
        at.atZone(rules.zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun run(connection: Connection, xuid: Xuid, gamertag: String, score: Int, at: Instant): LobbyRunResult {
        val week = weekOf(at)
        val before = store.best(connection, xuid) ?: 0
        store.record(connection, xuid, gamertag, score, week, at)
        val best = store.best(connection, xuid) ?: score
        val weekBest = store.weekBest(connection, xuid, week) ?: score
        return LobbyRunResult(
            best = best,
            rank = store.betterThan(connection, best) + 1,
            weekBest = weekBest,
            weekRank = store.weekBetterThan(connection, week, weekBest) + 1,
            newRecord = score > before,
            board = board(connection, at),
        )
    }

    fun board(connection: Connection, at: Instant): LobbyBoard {
        val size = rules.classement.size
        val latest = news().take(rules.classement.news).map { LobbyNews(it.title, it.date?.toString()) }
        return LobbyBoard(
            allTime = ranked(store.top(connection, size)),
            week = ranked(store.weekTop(connection, weekOf(at), size)),
            pioneers = store.pioneers(connection),
            news = latest,
        )
    }

    fun player(connection: Connection, xuid: Xuid, at: Instant): LobbyPlayer {
        val identity = store.identity(connection, xuid)
        return LobbyPlayer(identity.linked, identity.pioneer, store.best(connection, xuid) ?: 0, store.weekBest(connection, xuid, weekOf(at)) ?: 0)
    }

    companion object {
        /** Les rangs d'une liste déjà triée : une égalité garde le rang de la première ligne de son score. */
        fun ranked(scores: List<LobbyScore>): List<LobbyEntry> {
            var rank = 0
            var previous: Int? = null
            return scores.mapIndexed { index, line ->
                if (line.score != previous) rank = index + 1
                previous = line.score
                LobbyEntry(rank, line.gamertag, line.score)
            }
        }
    }
}
