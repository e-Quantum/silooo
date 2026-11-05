package com.silooo.core.shop

import com.silooo.core.db.Currency
import com.silooo.core.db.Xuid
import com.silooo.core.db.creditBalance
import com.silooo.core.db.isDuplicateKey
import com.silooo.core.db.setXuid
import com.silooo.core.economy.RequestRejected
import com.silooo.core.events.GameEventKind
import com.silooo.core.events.publishEvent
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonObject

/**
 * Le SQL de la boutique sur les tables de V1 (`shop_deliveries`, `shop_purchases`, `player_ranks`, `player_subscriptions`). Testé sur
 * MariaDB par Testcontainers (`ShopSqlTest`), jamais avec un faux.
 *
 * Les dates s'écrivent avec l'horloge du service (`DATETIME(3)`, UTC), pas avec celle de la base : un test avance le temps, et la
 * fin d'un abonnement se compare à la même horloge que celle qui l'a écrite.
 */
class JdbcShopStore : ShopStore {
    override fun isKnownPlayer(connection: Connection, xuid: Xuid): Boolean =
        connection.prepareStatement("SELECT 1 FROM players WHERE xuid = ?").use {
            it.setXuid(1, xuid)
            it.executeQuery().use { rows -> rows.next() }
        }

    override fun loadHoldings(connection: Connection, xuid: Xuid): Holdings {
        val ranks = connection.prepareStatement("SELECT rank_id FROM player_ranks WHERE xuid = ?").use {
            it.setXuid(1, xuid)
            it.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
        val ends = connection.prepareStatement("SELECT subscription_id, ends_at FROM player_subscriptions WHERE xuid = ?").use {
            it.setXuid(1, xuid)
            it.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.instant("ends_at")) } }
        }
        return Holdings(ranks, ends)
    }

    override fun ceoHoldings(connection: Connection, companyId: Long): Holdings {
        // Un seul PDG par entreprise (`company_members.role`) : les jointures ne rendent que ses lignes.
        val ranks = connection.prepareStatement(
            "SELECT r.rank_id FROM company_members m JOIN player_ranks r ON r.xuid = m.xuid WHERE m.company_id = ? AND m.role = 'PDG'",
        ).use {
            it.setLong(1, companyId)
            it.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
        val ends = connection.prepareStatement(
            "SELECT s.subscription_id, s.ends_at FROM company_members m JOIN player_subscriptions s ON s.xuid = m.xuid " +
                "WHERE m.company_id = ? AND m.role = 'PDG'",
        ).use {
            it.setLong(1, companyId)
            it.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.instant("ends_at")) } }
        }
        return Holdings(ranks, ends)
    }

    override fun loadSubscription(connection: Connection, xuid: Xuid, subscriptionId: String): SubscriptionTerm? =
        connection.prepareStatement(
            "SELECT started_at, ends_at, months_total FROM player_subscriptions WHERE xuid = ? AND subscription_id = ? FOR UPDATE",
        ).use {
            it.setXuid(1, xuid)
            it.setString(2, subscriptionId)
            it.executeQuery().use { rows ->
                if (rows.next()) SubscriptionTerm(rows.instant("started_at"), rows.instant("ends_at"), rows.getInt("months_total")) else null
            }
        }

    override fun grantRank(connection: Connection, xuid: Xuid, rankId: String, source: String, at: Instant): Boolean =
        try {
            connection.prepareStatement("INSERT INTO player_ranks (xuid, rank_id, granted_at, source) VALUES (?, ?, ?, ?)").use {
                it.setXuid(1, xuid)
                it.setString(2, rankId)
                it.setObject(3, utc(at))
                it.setString(4, source)
                it.executeUpdate()
            }
            true
        } catch (e: SQLException) {
            // La clé primaire (xuid, rank_id) : le joueur a ce grade depuis une autre transaction.
            if (e.isDuplicateKey()) false else throw e
        }

    override fun saveSubscription(connection: Connection, xuid: Xuid, subscriptionId: String, term: SubscriptionTerm) {
        connection.prepareStatement(
            "INSERT INTO player_subscriptions (xuid, subscription_id, started_at, ends_at, months_total) VALUES (?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE started_at = VALUES(started_at), ends_at = VALUES(ends_at), months_total = VALUES(months_total)",
        ).use {
            it.setXuid(1, xuid)
            it.setString(2, subscriptionId)
            it.setObject(3, utc(term.startedAt))
            it.setObject(4, utc(term.endsAt))
            it.setInt(5, term.monthsTotal)
            it.executeUpdate()
        }
    }

    override fun recordPurchase(connection: Connection, buyer: Xuid, itemId: String, gems: Long, recipient: Xuid?, requestId: String, at: Instant) {
        try {
            connection.prepareStatement(
                "INSERT INTO shop_purchases (xuid, item_id, gemmes, for_xuid, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            ).use {
                it.setXuid(1, buyer)
                it.setString(2, itemId)
                it.setLong(3, gems)
                it.setXuid(4, recipient)
                it.setString(5, requestId)
                it.setObject(6, utc(at))
                it.executeUpdate()
            }
        } catch (e: SQLException) {
            // `uq_purchases_request` : ce requestId a déjà servi (sa réponse gardée dans api_requests est partie depuis).
            if (e.isDuplicateKey()) throw RequestRejected("Le requestId $requestId a déjà servi à un achat") else throw e
        }
    }

    override fun lockGemmes(connection: Connection, xuid: Xuid): Long =
        // Un mouvement de zéro n'écrit rien (ni solde, ni journal) mais verrouille la ligne et rend le solde.
        creditBalance(connection, xuid, Currency.GEMMES, 0L, "boutique", null)

    override fun spendGemmes(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long =
        creditBalance(connection, xuid, Currency.GEMMES, -amount, reason, refId)

    override fun creditGemmes(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long =
        creditBalance(connection, xuid, Currency.GEMMES, amount, reason, refId)

    override fun pendingDeliveries(connection: Connection, limit: Int): List<PendingDelivery> =
        connection.prepareStatement(
            "SELECT d.order_id, d.gamertag, CAST(d.xuid AS CHAR) AS xuid, d.gemmes FROM shop_deliveries d " +
                "WHERE d.delivered_at IS NULL AND (" +
                "EXISTS (SELECT 1 FROM players p WHERE p.xuid = d.xuid) OR EXISTS (SELECT 1 FROM players p WHERE p.gamertag = d.gamertag)) " +
                "ORDER BY d.created_at, d.order_id LIMIT ?",
        ).use {
            it.setInt(1, limit)
            it.executeQuery().use { rows -> deliveries(rows) }
        }

    override fun pendingDeliveriesFor(connection: Connection, xuid: Xuid, gamertag: String): List<PendingDelivery> =
        connection.prepareStatement(
            "SELECT order_id, gamertag, CAST(xuid AS CHAR) AS xuid, gemmes FROM shop_deliveries " +
                "WHERE delivered_at IS NULL AND (xuid = ? OR gamertag = ?) ORDER BY created_at, order_id",
        ).use {
            it.setXuid(1, xuid)
            it.setString(2, gamertag)
            it.executeQuery().use { rows -> deliveries(rows) }
        }

    override fun recipientOf(connection: Connection, delivery: PendingDelivery): Xuid? {
        delivery.xuid?.let { if (isKnownPlayer(connection, it)) return it }
        // Le pseudo se compare sans tenir compte de la casse (collation de `players.gamertag`) ; le joueur vu en dernier gagne.
        return connection.prepareStatement("SELECT CAST(xuid AS CHAR) FROM players WHERE gamertag = ? ORDER BY last_seen_at DESC, xuid LIMIT 1").use {
            it.setString(1, delivery.gamertag)
            it.executeQuery().use { rows -> if (rows.next()) checkNotNull(Xuid.parse(rows.getString(1))) else null }
        }
    }

    override fun markDelivered(connection: Connection, orderId: String, xuid: Xuid, at: Instant): Boolean =
        connection.prepareStatement("UPDATE shop_deliveries SET delivered_at = ?, xuid = ? WHERE order_id = ? AND delivered_at IS NULL").use {
            it.setObject(1, utc(at))
            it.setXuid(2, xuid)
            it.setString(3, orderId)
            it.executeUpdate() == 1
        }

    override fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long =
        publishEvent(connection, kind, xuid, payload)

    private fun deliveries(rows: ResultSet): List<PendingDelivery> = buildList {
        while (rows.next()) {
            add(
                PendingDelivery(
                    orderId = rows.getString("order_id"),
                    gamertag = rows.getString("gamertag"),
                    // CAST en texte : un BIGINT UNSIGNED ne tient pas dans un Long. Le XUID de la ligne est rempli ou nul.
                    xuid = rows.getString("xuid")?.let { checkNotNull(Xuid.parse(it)) },
                    gemmes = rows.getLong("gemmes"),
                ),
            )
        }
    }

    private fun utc(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)

    private fun ResultSet.instant(column: String): Instant =
        checkNotNull(getObject(column, LocalDateTime::class.java)) { "$column : date absente" }.toInstant(ZoneOffset.UTC)
}
