package com.silooo.core.city

import com.silooo.core.db.Currency
import com.silooo.core.db.Xuid
import com.silooo.core.db.creditBalance
import com.silooo.core.db.setXuid
import java.sql.Connection
import java.sql.Statement

/**
 * Le SQL de la ville sur `city_zones` et `city_buildings` (V1, plus la clé unique de V6 : une parcelle, un bâtiment). Testé sur
 * MariaDB par Testcontainers (`CitySqlTest`), jamais avec un faux.
 */
class JdbcCityStore : CityStore {
    override fun loadBuildings(connection: Connection, companyId: Long): List<CityBuildingRecord> =
        connection.prepareStatement(
            "SELECT id, building_type, level, zone_index, x, z, rotation FROM city_buildings WHERE company_id = ? ORDER BY id",
        ).use {
            it.setLong(1, companyId)
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(record(rows))
                }
            }
        }

    override fun findBuilding(connection: Connection, companyId: Long, buildingId: Long): CityBuildingRecord? =
        connection.prepareStatement(
            "SELECT id, building_type, level, zone_index, x, z, rotation FROM city_buildings WHERE id = ? AND company_id = ?",
        ).use {
            it.setLong(1, buildingId)
            it.setLong(2, companyId)
            it.executeQuery().use { rows -> if (rows.next()) record(rows) else null }
        }

    override fun insertZone(connection: Connection, companyId: Long, zoneIndex: Int, boughtBy: Xuid, lingotsPaid: Long) {
        connection.prepareStatement("INSERT INTO city_zones (company_id, zone_index, bought_by, lingots_paid) VALUES (?, ?, ?, ?)").use {
            it.setLong(1, companyId)
            it.setInt(2, zoneIndex)
            it.setXuid(3, boughtBy)
            it.setLong(4, lingotsPaid)
            it.executeUpdate()
        }
    }

    override fun insertBuilding(connection: Connection, companyId: Long, buildingType: String, level: Int, zoneIndex: Int, x: Int, z: Int, rotation: Int): Long =
        connection.prepareStatement(
            "INSERT INTO city_buildings (company_id, building_type, level, zone_index, x, z, rotation) VALUES (?, ?, ?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setLong(1, companyId)
            it.setString(2, buildingType)
            it.setInt(3, level)
            it.setInt(4, zoneIndex)
            it.setInt(5, x)
            it.setInt(6, z)
            it.setInt(7, rotation)
            it.executeUpdate()
            it.generatedKeys.use { keys ->
                check(keys.next()) { "city_buildings : aucun identifiant rendu" }
                keys.getLong(1)
            }
        }

    override fun setBuildingLevel(connection: Connection, companyId: Long, buildingId: Long, level: Int) {
        val touched = connection.prepareStatement("UPDATE city_buildings SET level = ? WHERE id = ? AND company_id = ?").use {
            it.setInt(1, level)
            it.setLong(2, buildingId)
            it.setLong(3, companyId)
            it.executeUpdate()
        }
        // Le service a lu le bâtiment juste avant : une ligne absente est une erreur de programmation, pas un cas de jeu.
        check(touched == 1) { "city_buildings : bâtiment $buildingId absent de l'entreprise $companyId" }
    }

    override fun spendLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long =
        creditBalance(connection, xuid, Currency.LINGOTS, -amount, reason, refId)

    private fun record(rows: java.sql.ResultSet) = CityBuildingRecord(
        id = rows.getLong("id"),
        buildingType = rows.getString("building_type"),
        level = rows.getInt("level"),
        zoneIndex = rows.getInt("zone_index"),
        x = rows.getInt("x"),
        z = rows.getInt("z"),
        rotation = rows.getInt("rotation"),
    )
}
