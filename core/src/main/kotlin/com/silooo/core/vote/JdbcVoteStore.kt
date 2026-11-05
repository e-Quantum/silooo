package com.silooo.core.vote

import com.silooo.core.db.Currency
import com.silooo.core.db.Xuid
import com.silooo.core.db.creditBalance
import com.silooo.core.db.setXuid
import com.silooo.core.events.GameEventKind
import com.silooo.core.events.publishEvent
import java.sql.Connection
import java.sql.Statement
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonObject

/** Le SQL de [VoteStore] sur `server_votes` (V1). Testé sur MariaDB 12.3.3 par Testcontainers (`VoteSqlTest`). */
class JdbcVoteStore : VoteStore {
    override fun playerByGamertag(connection: Connection, gamertag: String): Xuid? =
        // Le dernier vu gagne : si deux joueurs ont porté ce pseudo, celui qui joue maintenant est celui qui vote.
        connection.prepareStatement("SELECT CAST(xuid AS CHAR) FROM players WHERE gamertag = ? ORDER BY last_seen_at DESC, xuid DESC LIMIT 1").use {
            it.setString(1, gamertag)
            it.executeQuery().use { rows -> if (rows.next()) xuidOf(rows.getString(1)) else null }
        }

    override fun insertVote(connection: Connection, gamertag: String, site: String, votedAt: Instant, xuid: Xuid?): Long =
        connection.prepareStatement(
            "INSERT INTO server_votes (xuid, gamertag, site, voted_at) VALUES (?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setXuid(1, xuid)
            it.setString(2, gamertag)
            it.setString(3, site)
            it.setObject(4, utc(votedAt))
            it.executeUpdate()
            it.generatedKeys.use { keys ->
                check(keys.next()) { "server_votes : aucun identifiant rendu" }
                keys.getLong(1)
            }
        }

    override fun markRewarded(connection: Connection, id: Long, xuid: Xuid, at: Instant): Boolean =
        connection.prepareStatement("UPDATE server_votes SET xuid = ?, rewarded_at = ? WHERE id = ? AND rewarded_at IS NULL").use {
            it.setXuid(1, xuid)
            it.setObject(2, utc(at))
            it.setLong(3, id)
            it.executeUpdate() == 1
        }

    override fun pendingFor(connection: Connection, xuid: Xuid, gamertag: String, limit: Int): List<VoteRow> =
        connection.prepareStatement(
            "SELECT id, CAST(xuid AS CHAR), gamertag, site, voted_at FROM server_votes " +
                "WHERE rewarded_at IS NULL AND (xuid = ? OR (xuid IS NULL AND gamertag = ?)) ORDER BY id LIMIT ? FOR UPDATE",
        ).use {
            it.setXuid(1, xuid)
            it.setString(2, gamertag)
            it.setInt(3, limit)
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            VoteRow(
                                id = rows.getLong(1),
                                xuid = rows.getString(2)?.let { text -> xuidOf(text) },
                                gamertag = rows.getString(3),
                                site = rows.getString(4),
                                votedAt = rows.getObject(5, LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
                                rewarded = false,
                            ),
                        )
                    }
                }
            }
        }

    override fun countOn(connection: Connection, day: LocalDate): Int =
        connection.prepareStatement("SELECT COUNT(*) FROM server_votes WHERE voted_at >= ? AND voted_at < ?").use {
            it.setObject(1, startOf(day))
            it.setObject(2, startOf(day.plusDays(1)))
            it.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    override fun countOfPlayerOn(connection: Connection, xuid: Xuid, day: LocalDate): Int =
        connection.prepareStatement("SELECT COUNT(*) FROM server_votes WHERE xuid = ? AND voted_at >= ? AND voted_at < ?").use {
            it.setXuid(1, xuid)
            it.setObject(2, startOf(day))
            it.setObject(3, startOf(day.plusDays(1)))
            it.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    override fun votedDays(connection: Connection, xuid: Xuid, through: LocalDate): Set<LocalDate> =
        connection.prepareStatement("SELECT DISTINCT DATE(voted_at) FROM server_votes WHERE xuid = ? AND voted_at < ?").use {
            it.setXuid(1, xuid)
            it.setObject(2, startOf(through.plusDays(1)))
            it.executeQuery().use { rows ->
                buildSet {
                    while (rows.next()) add(rows.getObject(1, LocalDate::class.java))
                }
            }
        }

    override fun firstVoteIdOn(connection: Connection, xuid: Xuid, day: LocalDate): Long? =
        connection.prepareStatement("SELECT MIN(id) FROM server_votes WHERE xuid = ? AND voted_at >= ? AND voted_at < ?").use {
            it.setXuid(1, xuid)
            it.setObject(2, startOf(day))
            it.setObject(3, startOf(day.plusDays(1)))
            it.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1).takeUnless { rows.wasNull() }
            }
        }

    override fun knownPlayers(connection: Connection, xuids: Collection<Xuid>): List<Xuid> {
        if (xuids.isEmpty()) return emptyList()
        val marks = xuids.joinToString(",") { "?" }
        return connection.prepareStatement("SELECT CAST(xuid AS CHAR) FROM players WHERE xuid IN ($marks) ORDER BY xuid").use { statement ->
            xuids.forEachIndexed { index, xuid -> statement.setXuid(index + 1, xuid) }
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(xuidOf(rows.getString(1)))
                }
            }
        }
    }

    override fun creditLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long =
        creditBalance(connection, xuid, Currency.LINGOTS, amount, reason, refId)

    override fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long =
        publishEvent(connection, kind, xuid, payload)

    private fun utc(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)

    private fun startOf(day: LocalDate): LocalDateTime = LocalDateTime.of(day, LocalTime.MIDNIGHT)

    private fun xuidOf(text: String): Xuid = checkNotNull(Xuid.parse(text)) { "server_votes / players : XUID illisible en base" }
}
