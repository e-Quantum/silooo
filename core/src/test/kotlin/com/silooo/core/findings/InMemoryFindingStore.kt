package com.silooo.core.findings

import com.silooo.core.db.Xuid
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.InMemorySessionStore
import java.sql.Connection
import kotlinx.serialization.json.JsonObject

/**
 * Le faux en mémoire de [FindingStore], pour les tests sans base. Un joueur existe quand le faux [InMemorySessionStore]
 * le connaît (inscrit, ou membre d'une entreprise) ; les Lingots versés vont dans ses soldes, la date d'une trouvaille est
 * l'heure du faux (`sessions.now`). Il note les versements et les événements publiés. Le SQL lui-même se teste sur MariaDB
 * (`FindingSqlTest`).
 */
class InMemoryFindingStore(private val sessions: InMemorySessionStore) : FindingStore {
    data class Published(val kind: GameEventKind, val xuid: Xuid?, val payload: JsonObject)

    /** Un versement de Lingots : une ligne de `currency_ledger`. */
    data class Credit(val xuid: Xuid, val amount: Long, val reason: String, val refId: String)

    /** `player_findings`, dans l'ordre d'inscription. */
    val rows = LinkedHashMap<Pair<Xuid, String>, FindingRow>()
    val credits = mutableListOf<Credit>()
    val published = mutableListOf<Published>()

    override fun playerExists(connection: Connection, xuid: Xuid): Boolean = xuid in sessions.players || xuid in sessions.members

    override fun load(connection: Connection, xuid: Xuid): List<FindingRow> =
        rows.filterKeys { it.first == xuid }.values.sortedWith(compareBy({ it.foundAt }, { it.resourceId }))

    override fun add(connection: Connection, xuid: Xuid, resourceId: String): Boolean {
        require(playerExists(connection, xuid)) { "Joueur inconnu : $xuid" }
        if ((xuid to resourceId) in rows) return false
        rows[xuid to resourceId] = FindingRow(resourceId, sessions.now)
        return true
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
