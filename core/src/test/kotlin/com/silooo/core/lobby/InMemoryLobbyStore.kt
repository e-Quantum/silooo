package com.silooo.core.lobby

import com.silooo.core.db.Xuid
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate

/** Le faux de [LobbyStore] des tests de contrat : les mêmes règles que le SQL, sans base. */
class InMemoryLobbyStore(
    private val pioneerCount: Long = 0,
    private val identities: Map<String, LobbyIdentity> = emptyMap(),
) : LobbyStore {
    private data class Line(var gamertag: String, var best: Int, var at: Instant)

    private val records = linkedMapOf<String, Line>()
    private val weeks = linkedMapOf<Pair<LocalDate, String>, Line>()

    override fun record(connection: Connection, xuid: Xuid, gamertag: String, score: Int, week: LocalDate, at: Instant) {
        keep(records.getOrPut(xuid.text) { Line(gamertag, score, at) }, gamertag, score, at)
        keep(weeks.getOrPut(week to xuid.text) { Line(gamertag, score, at) }, gamertag, score, at)
    }

    private fun keep(line: Line, gamertag: String, score: Int, at: Instant) {
        line.gamertag = gamertag
        if (score > line.best) {
            line.best = score
            line.at = at
        }
    }

    override fun best(connection: Connection, xuid: Xuid): Int? = records[xuid.text]?.best

    override fun weekBest(connection: Connection, xuid: Xuid, week: LocalDate): Int? = weeks[week to xuid.text]?.best

    override fun betterThan(connection: Connection, score: Int): Int = records.values.count { it.best > score }

    override fun weekBetterThan(connection: Connection, week: LocalDate, score: Int): Int =
        weeks.filterKeys { it.first == week }.values.count { it.best > score }

    override fun top(connection: Connection, size: Int): List<LobbyScore> = sorted(records.values, size)

    override fun weekTop(connection: Connection, week: LocalDate, size: Int): List<LobbyScore> =
        sorted(weeks.filterKeys { it.first == week }.values, size)

    private fun sorted(lines: Collection<Line>, size: Int) =
        lines.sortedWith(compareByDescending<Line> { it.best }.thenBy { it.at }).take(size).map { LobbyScore(it.gamertag, it.best) }

    override fun pioneers(connection: Connection): Long = pioneerCount

    override fun identity(connection: Connection, xuid: Xuid): LobbyIdentity = identities[xuid.text] ?: LobbyIdentity(linked = false, pioneer = false)
}
