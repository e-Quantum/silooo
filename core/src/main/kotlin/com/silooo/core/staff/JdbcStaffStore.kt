package com.silooo.core.staff

import com.silooo.core.definitions.StaffRarity
import com.silooo.core.definitions.StaffRole
import com.silooo.core.session.StaffRecord
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Statement
import java.sql.Types
import java.time.LocalDate

/**
 * Le SQL du personnel et de l'agence sur `staff` et `agency_candidates` (V1). Testé sur MariaDB par Testcontainers
 * (`StaffSqlTest`), jamais avec un faux. Aucune migration : les deux tables de V1 suffisent.
 */
class JdbcStaffStore : StaffStore {

    override fun hiredSlots(connection: Connection, companyId: Long, day: LocalDate): List<HiredSlot> =
        connection.prepareStatement(
            "SELECT slot, name, skin_id, role, rarity, hired_staff_id FROM agency_candidates " +
                "WHERE company_id = ? AND day = ? AND hired_staff_id IS NOT NULL ORDER BY slot",
        ).use {
            it.setLong(1, companyId)
            it.setObject(2, day)
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            HiredSlot(
                                candidate = Candidate(
                                    slot = rows.getInt("slot"),
                                    name = rows.getString("name"),
                                    skinId = rows.getString("skin_id"),
                                    role = StaffRole.valueOf(rows.getString("role")),
                                    rarity = StaffRarity.valueOf(rows.getString("rarity")),
                                ),
                                staffId = rows.getLong("hired_staff_id"),
                            ),
                        )
                    }
                }
            }
        }

    override fun find(connection: Connection, companyId: Long, staffId: Long): StaffRecord? =
        connection.prepareStatement(
            "SELECT id, factory_id, name, skin_id, role, rarity FROM staff WHERE id = ? AND company_id = ? AND role IN ('SALARIE', 'CHEF')",
        ).use {
            it.setLong(1, staffId)
            it.setLong(2, companyId)
            it.executeQuery().use { rows ->
                if (!rows.next()) {
                    null
                } else {
                    StaffRecord(
                        id = rows.getLong("id"),
                        factoryId = rows.getString("factory_id"),
                        name = rows.getString("name"),
                        skinId = rows.getString("skin_id"),
                        role = StaffRole.valueOf(rows.getString("role")),
                        rarity = StaffRarity.valueOf(rows.getString("rarity")),
                    )
                }
            }
        }

    override fun insert(connection: Connection, companyId: Long, candidate: Candidate, factoryId: String?, bonusPercent: Double): Long =
        connection.prepareStatement(
            "INSERT INTO staff (company_id, factory_id, name, skin_id, role, rarity, bonus_percent) VALUES (?, ?, ?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setLong(1, companyId)
            if (factoryId == null) it.setNull(2, Types.VARCHAR) else it.setString(2, factoryId)
            it.setString(3, candidate.name)
            it.setString(4, candidate.skinId)
            it.setString(5, candidate.role.name)
            it.setString(6, candidate.rarity.name)
            it.setBigDecimal(7, BigDecimal.valueOf(bonusPercent))
            it.executeUpdate()
            it.generatedKeys.use { keys ->
                check(keys.next()) { "staff : aucun identifiant rendu" }
                keys.getLong(1)
            }
        }

    override fun recordHire(connection: Connection, companyId: Long, day: LocalDate, candidate: Candidate, bonusPercent: Double, staffId: Long) {
        connection.prepareStatement(
            "INSERT INTO agency_candidates (company_id, day, slot, name, skin_id, role, rarity, bonus_percent, hired_staff_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use {
            it.setLong(1, companyId)
            it.setObject(2, day)
            it.setInt(3, candidate.slot)
            it.setString(4, candidate.name)
            it.setString(5, candidate.skinId)
            it.setString(6, candidate.role.name)
            it.setString(7, candidate.rarity.name)
            it.setBigDecimal(8, BigDecimal.valueOf(bonusPercent))
            it.setLong(9, staffId)
            it.executeUpdate()
        }
    }

    override fun assign(connection: Connection, companyId: Long, staffId: Long, factoryId: String) {
        val touched = connection.prepareStatement("UPDATE staff SET factory_id = ? WHERE id = ? AND company_id = ?").use {
            it.setString(1, factoryId)
            it.setLong(2, staffId)
            it.setLong(3, companyId)
            it.executeUpdate()
        }
        // Le service a lu le membre juste avant : une ligne absente est une erreur de programmation, pas un cas de jeu.
        check(touched == 1) { "staff : membre $staffId absent de l'entreprise $companyId" }
    }

    override fun delete(connection: Connection, companyId: Long, staffId: Long) {
        connection.prepareStatement("DELETE FROM agency_candidates WHERE company_id = ? AND hired_staff_id = ?").use {
            it.setLong(1, companyId)
            it.setLong(2, staffId)
            it.executeUpdate()
        }
        val touched = connection.prepareStatement("DELETE FROM staff WHERE id = ? AND company_id = ?").use {
            it.setLong(1, staffId)
            it.setLong(2, companyId)
            it.executeUpdate()
        }
        check(touched == 1) { "staff : membre $staffId absent de l'entreprise $companyId" }
    }
}
