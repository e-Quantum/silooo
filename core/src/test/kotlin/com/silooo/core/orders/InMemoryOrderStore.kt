package com.silooo.core.orders

import com.silooo.core.db.Xuid
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.InMemorySessionStore
import java.sql.Connection
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject

/**
 * Le faux en mémoire de [OrderStore], pour les tests sans base. Les Lingots versés vont dans les soldes du faux
 * [InMemorySessionStore] ; il note les versements et les événements publiés. Il ne rejoue ni les transactions ni les
 * verrous : le SQL lui-même se teste sur MariaDB (`OrderSqlTest`).
 */
class InMemoryOrderStore(private val sessions: InMemorySessionStore) : OrderStore {
    data class Published(val kind: GameEventKind, val xuid: Xuid?, val payload: JsonObject)

    /** Un versement de Lingots : une ligne de `currency_ledger`. */
    data class Credit(val xuid: Xuid, val amount: Long, val reason: String, val refId: String)

    /** `world_orders`, par jour. */
    val orders = LinkedHashMap<LocalDate, OrderRow>()

    /** `world_order_contributions` : la part de chaque joueur, par commande et par joueur. */
    val contributions = LinkedHashMap<Pair<Long, Xuid>, Double>()

    /** Les parts dont la prime est versée (`rewarded_at`). */
    val rewarded = LinkedHashSet<Pair<Long, Xuid>>()
    val credits = mutableListOf<Credit>()
    val published = mutableListOf<Published>()

    private var nextId = 1L

    override fun find(connection: Connection, day: LocalDate): OrderRow? = orders[day]

    override fun contribution(connection: Connection, orderId: Long, xuid: Xuid): Double = contributions[orderId to xuid] ?: 0.0

    override fun lockOrCreate(connection: Connection, day: LocalDate, resourceId: String, target: Double, rewardLingots: Long): OrderRow =
        orders.getOrPut(day) { OrderRow(nextId++, day, resourceId, target, delivered = 0.0, rewardLingots = rewardLingots, completed = false) }

    override fun addContribution(connection: Connection, orderId: Long, xuid: Xuid, quantity: Double) {
        contributions.merge(orderId to xuid, quantity, Double::plus)
    }

    override fun saveProgress(connection: Connection, orderId: Long, delivered: Double, completed: Boolean) {
        val (day, row) = orders.entries.single { it.value.id == orderId }.toPair()
        orders[day] = row.copy(delivered = delivered, completed = row.completed || completed)
    }

    override fun unrewardedContributors(connection: Connection, orderId: Long): List<Xuid> =
        contributions.filter { (key, quantity) -> key.first == orderId && quantity > 0 && key !in rewarded }
            .keys.map { it.second }.sortedBy { it.number }

    override fun markRewarded(connection: Connection, orderId: Long, xuid: Xuid) {
        rewarded += orderId to xuid
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
