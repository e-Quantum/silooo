package com.silooo.core.mission

import com.silooo.core.db.Currency
import com.silooo.core.db.Xuid
import com.silooo.core.db.creditBalance
import com.silooo.core.db.setXuid
import com.silooo.core.events.GameEventKind
import com.silooo.core.events.publishEvent
import java.sql.Connection
import java.sql.SQLException
import kotlinx.serialization.json.JsonObject

/** `ER_NO_REFERENCED_ROW_2` : la clé étrangère ne trouve pas la ligne parente (le joueur). */
private const val NO_REFERENCED_ROW = 1452

/** Le SQL de [MissionStore] sur `player_missions` (V1). Testé sur MariaDB 12.3.3 par Testcontainers (`MissionSqlTest`). */
class JdbcMissionStore : MissionStore {
    override fun playerExists(connection: Connection, xuid: Xuid): Boolean =
        connection.prepareStatement("SELECT 1 FROM players WHERE xuid = ?").use {
            it.setXuid(1, xuid)
            it.executeQuery().use { rows -> rows.next() }
        }

    override fun load(connection: Connection, xuid: Xuid): Map<String, MissionRow> =
        connection.prepareStatement("SELECT mission_id, progress, completed_at IS NOT NULL FROM player_missions WHERE xuid = ?").use {
            it.setXuid(1, xuid)
            it.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) put(rows.getString(1), MissionRow(rows.getInt(2), rows.getBoolean(3)))
                }
            }
        }

    override fun lock(connection: Connection, xuid: Xuid, missionId: String): MissionRow {
        fun read(): MissionRow? =
            connection.prepareStatement(
                "SELECT progress, completed_at IS NOT NULL FROM player_missions WHERE xuid = ? AND mission_id = ? FOR UPDATE",
            ).use {
                it.setXuid(1, xuid)
                it.setString(2, missionId)
                it.executeQuery().use { rows -> if (rows.next()) MissionRow(rows.getInt(1), rows.getBoolean(2)) else null }
            }

        read()?.let { return it }
        // INSERT IGNORE : une autre transaction a pu créer la ligne entre-temps ; la relecture verrouillée tranche.
        // Joueur inconnu : la clé étrangère refuse la ligne (en erreur, ou en simple avertissement avec IGNORE selon
        // la version) ; dans les deux cas la relecture ne trouve rien.
        try {
            connection.prepareStatement("INSERT IGNORE INTO player_missions (xuid, mission_id, progress) VALUES (?, ?, 0)").use {
                it.setXuid(1, xuid)
                it.setString(2, missionId)
                it.executeUpdate()
            }
        } catch (e: SQLException) {
            if (e.errorCode != NO_REFERENCED_ROW) throw e
        }
        return read() ?: throw IllegalArgumentException("Joueur inconnu : $xuid")
    }

    override fun save(connection: Connection, xuid: Xuid, missionId: String, progress: Int, completed: Boolean) {
        val touched = connection.prepareStatement(
            "UPDATE player_missions SET progress = ?, completed_at = IF(?, UTC_TIMESTAMP(3), NULL) WHERE xuid = ? AND mission_id = ?",
        ).use {
            it.setInt(1, progress)
            it.setBoolean(2, completed)
            it.setXuid(3, xuid)
            it.setString(4, missionId)
            it.executeUpdate()
        }
        check(touched > 0) { "player_missions : mission $missionId introuvable pour le joueur $xuid" }
    }

    override fun creditLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long =
        creditBalance(connection, xuid, Currency.LINGOTS, amount, reason, refId)

    override fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long =
        publishEvent(connection, kind, xuid, payload)
}
