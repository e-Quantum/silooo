package com.silooo.core.mission

import com.silooo.core.db.Xuid
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.InMemorySessionStore
import java.sql.Connection
import kotlinx.serialization.json.JsonObject

/**
 * Le faux en mémoire de [MissionStore], pour les tests sans base. Un joueur existe quand le faux [InMemorySessionStore]
 * le connaît (inscrit, ou membre d'une entreprise) ; les Lingots versés vont dans ses soldes. Il note les versements et
 * les événements publiés. Le SQL lui-même se teste sur MariaDB (`MissionSqlTest`).
 */
class InMemoryMissionStore(private val sessions: InMemorySessionStore) : MissionStore {
    data class Published(val kind: GameEventKind, val xuid: Xuid?, val payload: JsonObject)

    /** Un versement de Lingots : une ligne de `currency_ledger`. */
    data class Credit(val xuid: Xuid, val amount: Long, val reason: String, val refId: String)

    /** `player_missions`, par joueur puis par mission. */
    val rows = LinkedHashMap<Pair<Xuid, String>, MissionRow>()
    val credits = mutableListOf<Credit>()
    val published = mutableListOf<Published>()

    override fun playerExists(connection: Connection, xuid: Xuid): Boolean = xuid in sessions.players || xuid in sessions.members

    override fun load(connection: Connection, xuid: Xuid): Map<String, MissionRow> =
        rows.filterKeys { it.first == xuid }.mapKeys { it.key.second }

    override fun lock(connection: Connection, xuid: Xuid, missionId: String): MissionRow {
        require(playerExists(connection, xuid)) { "Joueur inconnu : $xuid" }
        return rows.getOrPut(xuid to missionId) { MissionRow(progress = 0, completed = false) }
    }

    override fun save(connection: Connection, xuid: Xuid, missionId: String, progress: Int, completed: Boolean) {
        check((xuid to missionId) in rows) { "player_missions : mission $missionId introuvable pour le joueur $xuid" }
        rows[xuid to missionId] = MissionRow(progress, completed)
    }

    override fun creditLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long {
        val before = sessions.balances.getValue(xuid)
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
