package com.silooo.core.economy

import com.silooo.core.api.FactoryState
import com.silooo.core.api.Role
import com.silooo.core.db.Xuid
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.InMemorySessionStore
import java.sql.Connection
import kotlinx.serialization.json.JsonObject

/**
 * Le faux en mémoire de [CompanyStore], pour les tests des routes d'économie sans base. Il écrit les usines dans le
 * faux [InMemorySessionStore] (à la même place dans la liste, comme `ORDER BY factory_id` la rendrait) et note les
 * événements publiés. Le SQL lui-même se teste sur MariaDB (`CompanySqlTest`).
 */
class InMemoryCompanyStore(private val sessions: InMemorySessionStore) : CompanyStore {
    data class SavedFactory(val companyId: Long, val factoryId: String, val state: FactoryState, val restoreStep: Int, val level: Int)

    data class Published(val kind: GameEventKind, val xuid: Xuid?, val payload: JsonObject)

    /** Un versement de Lingots : une ligne de `currency_ledger`. */
    data class Credit(val xuid: Xuid, val amount: Long, val reason: String, val refId: String)

    val saved = mutableListOf<SavedFactory>()
    val published = mutableListOf<Published>()
    val credits = mutableListOf<Credit>()
    val prestiges = mutableListOf<PrestigeRecord>()

    /** Les remises à zéro des usines demandées, avec les usines de départ passées (entreprise, usines de départ). */
    val restarts = mutableListOf<Pair<Long, Map<String, Int>>>()

    /** Les parts de Lingots posées par un test (`share_percent`) : celles du faux [InMemorySessionStore], qui les montre aussi dans `CompanyState.members`. */
    val sharePercents: MutableMap<Xuid, Int> get() = sessions.sharePercents

    override fun companyExists(connection: Connection, companyId: Long): Boolean = companyId in sessions.companies

    override fun saveFactory(connection: Connection, companyId: Long, factoryId: String, state: FactoryState, restoreStep: Int, level: Int) {
        val list = sessions.factories.getValue(companyId)
        val index = list.indexOfFirst { it.factoryId == factoryId }
        check(index >= 0) { "usine $factoryId absente de l'entreprise $companyId" }
        list[index] = FactoryRecord(factoryId, state, restoreStep, level, list[index].openIncidentId)
        saved += SavedFactory(companyId, factoryId, state, restoreStep, level)
    }

    override fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long {
        published += Published(kind, xuid, payload)
        return published.size.toLong()
    }

    override fun loadShares(connection: Connection, companyId: Long): List<MemberShare> =
        sessions.members.filterValues { it.companyId == companyId }.entries
            .sortedBy { it.key.number }
            .map { (xuid, membership) -> MemberShare(xuid, membership.role, sharePercents[xuid] ?: if (membership.role == Role.PDG) 100 else 0) }

    override fun creditLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long {
        val before = sessions.balances.getValue(xuid)
        val after = before.copy(lingots = before.lingots + amount)
        sessions.balances[xuid] = after
        credits += Credit(xuid, amount, reason, refId)
        return after.lingots
    }

    override fun restartFactories(connection: Connection, companyId: Long, starting: Map<String, Int>) {
        val list = sessions.factories.getValue(companyId)
        for (index in list.indices) {
            val factory = list[index]
            val level = starting[factory.factoryId]
            list[index] = if (level != null) {
                FactoryRecord(factory.factoryId, FactoryState.ACTIVE, 0, level, openIncidentId = null)
            } else {
                FactoryRecord(factory.factoryId, FactoryState.EN_RUINE, 0, 0, openIncidentId = null)
            }
        }
        restarts += companyId to starting
    }

    override fun recordPrestige(connection: Connection, record: PrestigeRecord) {
        prestiges += record
    }
}
