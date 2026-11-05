package com.silooo.core.findings

import com.silooo.core.db.Currency
import com.silooo.core.db.Xuid
import com.silooo.core.db.creditBalance
import com.silooo.core.db.setXuid
import com.silooo.core.events.GameEventKind
import com.silooo.core.events.publishEvent
import java.sql.Connection
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonObject

/** Le SQL de [FindingStore] sur `player_findings` (V3). Testé sur MariaDB 12.3.3 par Testcontainers (`FindingSqlTest`). */
class JdbcFindingStore : FindingStore {
    override fun playerExists(connection: Connection, xuid: Xuid): Boolean =
        connection.prepareStatement("SELECT 1 FROM players WHERE xuid = ?").use {
            it.setXuid(1, xuid)
            it.executeQuery().use { rows -> rows.next() }
        }

    override fun load(connection: Connection, xuid: Xuid): List<FindingRow> =
        connection.prepareStatement("SELECT finding_id, found_at FROM player_findings WHERE xuid = ? ORDER BY found_at, finding_id").use {
            it.setXuid(1, xuid)
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(FindingRow(rows.getString(1), rows.getObject(2, LocalDateTime::class.java).toInstant(ZoneOffset.UTC)))
                    }
                }
            }
        }

    override fun add(connection: Connection, xuid: Xuid, resourceId: String): Boolean =
        // INSERT IGNORE : la clé primaire (xuid, finding_id) refuse la seconde écriture, une ligne touchée dit qui a gagné.
        connection.prepareStatement("INSERT IGNORE INTO player_findings (xuid, finding_id) VALUES (?, ?)").use {
            it.setXuid(1, xuid)
            it.setString(2, resourceId)
            it.executeUpdate() == 1
        }

    override fun creditLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long =
        creditBalance(connection, xuid, Currency.LINGOTS, amount, reason, refId)

    override fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long =
        publishEvent(connection, kind, xuid, payload)
}
