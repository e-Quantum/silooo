package com.silooo.core.orders

import com.silooo.core.db.Currency
import com.silooo.core.db.Xuid
import com.silooo.core.db.creditBalance
import com.silooo.core.db.setXuid
import com.silooo.core.events.GameEventKind
import com.silooo.core.events.publishEvent
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject

/** Le SQL de [OrderStore] sur `world_orders` et `world_order_contributions` (V1). Testé sur MariaDB 12.3.3 par Testcontainers (`OrderSqlTest`). */
class JdbcOrderStore : OrderStore {
    override fun find(connection: Connection, day: LocalDate): OrderRow? =
        connection.prepareStatement("$SELECT_ORDER WHERE day = ?").use {
            it.setObject(1, day)
            it.executeQuery().use { rows -> if (rows.next()) rows.toOrder() else null }
        }

    override fun contribution(connection: Connection, orderId: Long, xuid: Xuid): Double =
        connection.prepareStatement("SELECT quantity FROM world_order_contributions WHERE order_id = ? AND xuid = ?").use {
            it.setLong(1, orderId)
            it.setXuid(2, xuid)
            it.executeQuery().use { rows -> if (rows.next()) rows.getDouble(1) else 0.0 }
        }

    override fun lockOrCreate(connection: Connection, day: LocalDate, resourceId: String, target: Double, rewardLingots: Long): OrderRow {
        // INSERT IGNORE : une autre transaction a pu écrire la commande du jour entre-temps ; elle est identique (le plan
        // ne dépend que de la date), et la relecture verrouillée tranche. La clé unique `uq_orders_day` fait le reste.
        connection.prepareStatement(
            "INSERT IGNORE INTO world_orders (day, resource_id, target_quantity, reward_lingots) VALUES (?, ?, ?, ?)",
        ).use {
            it.setObject(1, day)
            it.setString(2, resourceId)
            it.setDouble(3, target)
            it.setLong(4, rewardLingots)
            it.executeUpdate()
        }
        return connection.prepareStatement("$SELECT_ORDER WHERE day = ? FOR UPDATE").use {
            it.setObject(1, day)
            it.executeQuery().use { rows ->
                check(rows.next()) { "world_orders : la commande du $day manque juste après son écriture" }
                rows.toOrder()
            }
        }
    }

    override fun addContribution(connection: Connection, orderId: Long, xuid: Xuid, quantity: Double) {
        connection.prepareStatement(
            "INSERT INTO world_order_contributions (order_id, xuid, quantity) VALUES (?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity)",
        ).use {
            it.setLong(1, orderId)
            it.setXuid(2, xuid)
            it.setDouble(3, quantity)
            it.executeUpdate()
        }
    }

    override fun saveProgress(connection: Connection, orderId: Long, delivered: Double, completed: Boolean) {
        val touched = connection.prepareStatement(
            "UPDATE world_orders SET delivered = ?, completed_at = IF(?, UTC_TIMESTAMP(3), completed_at) WHERE id = ?",
        ).use {
            it.setDouble(1, delivered)
            it.setBoolean(2, completed)
            it.setLong(3, orderId)
            it.executeUpdate()
        }
        check(touched > 0) { "world_orders : commande $orderId introuvable" }
    }

    override fun unrewardedContributors(connection: Connection, orderId: Long): List<Xuid> =
        connection.prepareStatement(
            "SELECT xuid FROM world_order_contributions WHERE order_id = ? AND rewarded_at IS NULL AND quantity > 0 ORDER BY xuid FOR UPDATE",
        ).use {
            it.setLong(1, orderId)
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(checkNotNull(Xuid.parse(rows.getString(1))) { "world_order_contributions.xuid illisible" })
                }
            }
        }

    override fun markRewarded(connection: Connection, orderId: Long, xuid: Xuid) {
        connection.prepareStatement("UPDATE world_order_contributions SET rewarded_at = UTC_TIMESTAMP(3) WHERE order_id = ? AND xuid = ?").use {
            it.setLong(1, orderId)
            it.setXuid(2, xuid)
            it.executeUpdate()
        }
    }

    override fun creditLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long =
        creditBalance(connection, xuid, Currency.LINGOTS, amount, reason, refId)

    override fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long =
        publishEvent(connection, kind, xuid, payload)

    private fun ResultSet.toOrder() = OrderRow(
        id = getLong("id"),
        day = getObject("day", LocalDate::class.java),
        resourceId = getString("resource_id"),
        target = getDouble("target_quantity"),
        delivered = getDouble("delivered"),
        rewardLingots = getLong("reward_lingots"),
        completed = getBoolean("is_completed"),
    )

    private companion object {
        const val SELECT_ORDER =
            "SELECT id, day, resource_id, target_quantity, delivered, reward_lingots, completed_at IS NOT NULL AS is_completed FROM world_orders"
    }
}
