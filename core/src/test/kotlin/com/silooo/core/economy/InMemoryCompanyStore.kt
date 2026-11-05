package com.silooo.core.economy

import com.silooo.core.api.FactoryState
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

    val saved = mutableListOf<SavedFactory>()
    val published = mutableListOf<Published>()

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
}
