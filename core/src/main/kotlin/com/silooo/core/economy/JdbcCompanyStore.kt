package com.silooo.core.economy

import com.silooo.core.api.FactoryState
import com.silooo.core.api.Role
import com.silooo.core.db.Currency
import com.silooo.core.db.Xuid
import com.silooo.core.db.creditBalance
import com.silooo.core.db.setXuid
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

    override fun loadShares(connection: Connection, companyId: Long): List<MemberShare> =
        // CAST en texte : un BIGINT UNSIGNED ne tient pas dans un Long, et Xuid.parse le relit sans perdre de chiffre.
        connection.prepareStatement("SELECT CAST(xuid AS CHAR), role, share_percent FROM company_members WHERE company_id = ? ORDER BY xuid").use {
            it.setLong(1, companyId)
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(MemberShare(checkNotNull(Xuid.parse(rows.getString(1))), Role.valueOf(rows.getString(2)), rows.getInt(3)))
                    }
                }
            }
        }

    override fun creditLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long =
        creditBalance(connection, xuid, Currency.LINGOTS, amount, reason, refId)

    override fun restartFactories(connection: Connection, companyId: Long, starting: Map<String, Int>) {
        connection.prepareStatement(
            "UPDATE company_factories SET state = 'EN_RUINE', restore_step = 0, level = 0, updated_at = UTC_TIMESTAMP(3) WHERE company_id = ?",
        ).use {
            it.setLong(1, companyId)
            it.executeUpdate()
        }
        for ((factoryId, level) in starting) {
            val touched = connection.prepareStatement(
                "UPDATE company_factories SET state = 'ACTIVE', restore_step = 0, level = ?, updated_at = UTC_TIMESTAMP(3) " +
                    "WHERE company_id = ? AND factory_id = ?",
            ).use {
                it.setInt(1, level)
                it.setLong(2, companyId)
                it.setString(3, factoryId)
                it.executeUpdate()
            }
            check(touched > 0) { "company_factories : usine de départ $factoryId introuvable pour l'entreprise $companyId" }
        }
        connection.prepareStatement(
            "UPDATE factory_incidents SET resolved_at = UTC_TIMESTAMP(3) WHERE company_id = ? AND resolved_at IS NULL",
        ).use {
            it.setLong(1, companyId)
            it.executeUpdate()
        }
    }

    override fun recordPrestige(connection: Connection, record: PrestigeRecord) {
        connection.prepareStatement(
            "INSERT INTO prestige_history (company_id, course_number, era_before, stars_after, threshold, donar_earned, " +
                "lingots_total, duration_hours, decided_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use {
            it.setLong(1, record.companyId)
            it.setInt(2, record.courseNumber)
            it.setInt(3, record.eraBefore)
            it.setInt(4, record.starsAfter)
            it.setDouble(5, record.threshold)
            it.setDouble(6, record.donarEarned)
            it.setLong(7, record.lingotsTotal)
            it.setBigDecimal(8, record.durationHours)
            it.setXuid(9, record.decidedBy)
            it.executeUpdate()
        }
    }
}
