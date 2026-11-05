package com.silooo.core.economy

import com.silooo.core.api.FactoryState
import com.silooo.core.db.Xuid
import com.silooo.core.events.GameEventKind
import com.silooo.core.events.publishEvent
import java.sql.Connection
import kotlinx.serialization.json.JsonObject

/** Le SQL de [CompanyStore] sur les tables de V1. Testé sur MariaDB 12.3.3 par Testcontainers (`CompanySqlTest`). */
class JdbcCompanyStore : CompanyStore {
    override fun companyExists(connection: Connection, companyId: Long): Boolean =
        connection.prepareStatement("SELECT 1 FROM companies WHERE id = ?").use {
            it.setLong(1, companyId)
            it.executeQuery().use { rows -> rows.next() }
        }

    override fun saveFactory(connection: Connection, companyId: Long, factoryId: String, state: FactoryState, restoreStep: Int, level: Int) {
        val touched = connection.prepareStatement(
            "UPDATE company_factories SET state = ?, restore_step = ?, level = ?, updated_at = UTC_TIMESTAMP(3) " +
                "WHERE company_id = ? AND factory_id = ?",
        ).use {
            it.setString(1, state.name)
            it.setInt(2, restoreStep)
            it.setInt(3, level)
            it.setLong(4, companyId)
            it.setString(5, factoryId)
            it.executeUpdate()
        }
        check(touched > 0) { "company_factories : usine $factoryId introuvable pour l'entreprise $companyId" }
    }

    override fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long =
        publishEvent(connection, kind, xuid, payload)
}
