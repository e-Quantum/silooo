package com.silooo.core.vote

import com.silooo.core.api.Balances
import com.silooo.core.db.Xuid
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.InMemorySessionStore
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonObject

/**
 * Le faux en mémoire de [VoteStore], pour les tests sans base. Un joueur existe quand le faux [InMemorySessionStore] le connaît ;
 * son pseudo est celui de `sessions.players`, et le vu en dernier gagne, comme dans la base. Les Lingots versés vont dans ses soldes.
 * Il note les versements et les événements publiés. Le SQL lui-même se teste sur MariaDB (`VoteSqlTest`).
 */
class InMemoryVoteStore(private val sessions: InMemorySessionStore) : VoteStore {
    data class Published(val kind: GameEventKind, val xuid: Xuid?, val payload: JsonObject)

    /** Un versement de Lingots : une ligne de `currency_ledger`. */
    data class Credit(val xuid: Xuid, val amount: Long, val reason: String, val refId: String)

    /** `server_votes`, dans l'ordre d'arrivée. */
    val rows = mutableListOf<VoteRow>()
    val credits = mutableListOf<Credit>()
    val published = mutableListOf<Published>()
    private var nextId = 1L

    private fun dayOf(instant: Instant): LocalDate = instant.atOffset(ZoneOffset.UTC).toLocalDate()

    fun row(id: Long): VoteRow = rows.single { it.id == id }

    override fun playerByGamertag(connection: Connection, gamertag: String): Xuid? =
        sessions.players.entries.filter { it.value.gamertag.equals(gamertag, ignoreCase = true) }.maxByOrNull { it.value.lastSeenAt }?.key

    override fun insertVote(connection: Connection, gamertag: String, site: String, votedAt: Instant, xuid: Xuid?): Long {
        val id = nextId++
        rows += VoteRow(id, xuid, gamertag, site, votedAt, rewarded = false)
        return id
    }

    override fun markRewarded(connection: Connection, id: Long, xuid: Xuid, at: Instant): Boolean {
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0 || rows[index].rewarded) return false
        rows[index] = rows[index].copy(xuid = xuid, rewarded = true)
        return true
    }

    override fun pendingFor(connection: Connection, xuid: Xuid, gamertag: String, limit: Int): List<VoteRow> =
        rows.filter { !it.rewarded && (it.xuid == xuid || (it.xuid == null && it.gamertag.equals(gamertag, ignoreCase = true))) }
            .sortedBy { it.id }
            .take(limit)

    override fun countOn(connection: Connection, day: LocalDate): Int = rows.count { dayOf(it.votedAt) == day }

    override fun countOfPlayerOn(connection: Connection, xuid: Xuid, day: LocalDate): Int =
        rows.count { it.xuid == xuid && dayOf(it.votedAt) == day }

    override fun votedDays(connection: Connection, xuid: Xuid, through: LocalDate): Set<LocalDate> =
        rows.filter { it.xuid == xuid }.map { dayOf(it.votedAt) }.filter { !it.isAfter(through) }.toSet()

    override fun firstVoteIdOn(connection: Connection, xuid: Xuid, day: LocalDate): Long? =
        rows.filter { it.xuid == xuid && dayOf(it.votedAt) == day }.minOfOrNull { it.id }

    override fun knownPlayers(connection: Connection, xuids: Collection<Xuid>): List<Xuid> =
        xuids.filter { it in sessions.players }.distinct().sortedBy { it.number }

    override fun creditLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long {
        val before = sessions.balances[xuid] ?: Balances(0, 0, 0, 0)
        val after = before.copy(lingots = before.lingots + amount)
        sessions.balances[xuid] = after
        credits += Credit(xuid, amount, reason, refId)
        return after.lingots
    }

    override fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long {
        published += Published(kind, xuid, payload)
        return published.size.toLong()
    }
}
