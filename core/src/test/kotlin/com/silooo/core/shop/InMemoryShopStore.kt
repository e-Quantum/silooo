package com.silooo.core.shop

import com.silooo.core.api.Balances
import com.silooo.core.api.Role
import com.silooo.core.db.Currency
import com.silooo.core.db.InsufficientBalance
import com.silooo.core.db.Xuid
import com.silooo.core.economy.RequestRejected
import com.silooo.core.events.GameEventKind
import com.silooo.core.registration.SiteRegistrations
import com.silooo.core.session.InMemorySessionStore
import com.silooo.core.session.PlayerProfile
import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/**
 * Le faux en mémoire de [ShopStore], pour les tests de la boutique sans base. Les soldes vivent dans le faux
 * [InMemorySessionStore] (`balances`), que `loadBalances` lit ; les joueurs connus et les membres d'entreprise aussi (`players`,
 * `members`). Le reste est ici, comme les tables : grades, abonnements, cosmétiques, boosts, achats, livraisons, inscriptions du site.
 *
 * Il rejoue ce que le SQL tient : un grade ou un cosmétique ne se donne qu'une fois, un `requestId` ne sert qu'à un achat, une
 * livraison ne se marque qu'une fois, un pseudo se compare par sa clé (`SiteRegistrations.key` : casse et espaces) et le joueur vu en
 * dernier gagne. Les verrous, la concurrence et le
 * SQL lui-même se testent sur MariaDB (`ShopSqlTest`).
 */
class InMemoryShopStore(private val sessions: InMemorySessionStore) : ShopStore {
    /** Une ligne de `player_ranks`. */
    data class RankRow(val rankId: String, val grantedAt: Instant, val source: String)

    /** Une ligne de `shop_purchases`. */
    data class Purchase(val buyer: Xuid, val itemId: String, val gems: Long, val recipient: Xuid?, val requestId: String, val at: Instant)

    /** Une ligne de `currency_ledger` pour les Gemmes : [delta] est négatif pour une dépense. */
    data class Movement(val xuid: Xuid, val delta: Long, val reason: String, val refId: String)

    /** Une ligne de `shop_deliveries`. */
    data class Delivery(
        val orderId: String,
        val gamertag: String,
        var xuid: Xuid?,
        val gemmes: Long,
        val createdAt: Instant,
        var deliveredAt: Instant? = null,
    )

    /** Une ligne de `player_cosmetics`. */
    data class CosmeticRow(val cosmeticId: String, val acquiredAt: Instant, val source: String)

    /** Une ligne de `server_boosts`. */
    data class BoostRow(val boughtBy: Xuid, val window: BoostWindow, val bonusPercent: Int)

    /** Un événement écrit, dans l'ordre. */
    data class Published(val kind: GameEventKind, val xuid: Xuid?, val payload: JsonObject)

    val ranks = LinkedHashMap<Xuid, MutableList<RankRow>>()
    val subscriptions = LinkedHashMap<Pair<Xuid, String>, SubscriptionTerm>()
    val purchases = mutableListOf<Purchase>()
    val movements = mutableListOf<Movement>()
    val deliveries = LinkedHashMap<String, Delivery>()
    val published = mutableListOf<Published>()
    val cosmetics = LinkedHashMap<Xuid, MutableList<CosmeticRow>>()
    val boosts = mutableListOf<BoostRow>()

    /** Les clés (`SiteRegistrations.key`) des gamertags que le site a inscrits comme pionniers. */
    val pioneerKeys = linkedSetOf<String>()

    /** Le nombre de fois où la fin du dernier boost a été lue pour un achat (la lecture verrouille en SQL). */
    var boostLockReads = 0

    /** Pose un grade directement, sans payer ni noter d'achat : l'état de départ d'un test. */
    fun seedRank(xuid: Xuid, rankId: String, at: Instant = Instant.parse("2026-11-01T00:00:00Z"), source: String = "achat") {
        ranks.getOrPut(xuid) { mutableListOf() } += RankRow(rankId, at, source)
    }

    /** Pose les grades [rankIds] d'un coup, dans l'ordre donné. */
    fun seedRanks(xuid: Xuid, vararg rankIds: String) = rankIds.forEach { seedRank(xuid, it) }

    /** Pose un abonnement directement : l'état de départ d'un test. */
    fun seedSubscription(xuid: Xuid, subscriptionId: String, startedAt: Instant, endsAt: Instant, monthsTotal: Int = 1) {
        subscriptions[xuid to subscriptionId] = SubscriptionTerm(startedAt, endsAt, monthsTotal)
    }

    /** Pose un cosmétique directement, sans payer ni noter d'achat : l'état de départ d'un test. */
    fun seedCosmetic(xuid: Xuid, cosmeticId: String, at: Instant = Instant.parse("2026-11-01T00:00:00Z"), source: String = "boutique") {
        cosmetics.getOrPut(xuid) { mutableListOf() } += CosmeticRow(cosmeticId, at, source)
        syncProfile(xuid)
    }

    /** Pose un boost directement : l'état de départ d'un test. */
    fun seedBoost(boughtBy: Xuid, startsAt: Instant, endsAt: Instant, bonusPercent: Int = 25) {
        boosts += BoostRow(boughtBy, BoostWindow(startsAt, endsAt), bonusPercent)
    }

    /** Inscrit `gamertag` comme pionnier sur le site (clé `SiteRegistrations.key`). */
    fun seedPioneer(gamertag: String) {
        pioneerKeys += SiteRegistrations.key(gamertag)
    }

    /** Les cosmétiques de [xuid] dans l'ordre où ils ont été donnés. */
    fun cosmeticsOf(xuid: Xuid): List<CosmeticRow> = cosmetics[xuid].orEmpty()

    /** Ajoute une livraison que le site a écrite : le pseudo saisi, le XUID s'il est rempli, les Gemmes achetées. */
    fun seedDelivery(orderId: String, gamertag: String, gemmes: Long, xuid: Xuid? = null, createdAt: Instant = Instant.parse("2026-11-05T11:00:00Z")) {
        deliveries[orderId] = Delivery(orderId, gamertag, xuid, gemmes, createdAt)
    }

    /** Les grades de [xuid] dans l'ordre où ils ont été donnés. */
    fun ranksOf(xuid: Xuid): List<RankRow> = ranks[xuid].orEmpty()

    /** Les Gemmes de [xuid] dans le faux dépôt. */
    fun gemmes(xuid: Xuid): Long = sessions.balances[xuid]?.gemmes ?: 0L

    override fun isKnownPlayer(connection: Connection, xuid: Xuid): Boolean = xuid in sessions.players

    override fun loadHoldings(connection: Connection, xuid: Xuid): Holdings = Holdings(
        ranks = ranks[xuid].orEmpty().map { it.rankId }.toSet(),
        subscriptionEnds = subscriptions.filterKeys { it.first == xuid }.entries.associate { it.key.second to it.value.endsAt },
        cosmetics = cosmetics[xuid].orEmpty().map { it.cosmeticId }.toSet(),
    )

    override fun ceoHoldings(connection: Connection, companyId: Long): Holdings {
        val ceo = sessions.members.entries.firstOrNull { it.value.companyId == companyId && it.value.role == Role.PDG }?.key
            ?: return Holdings.NOTHING
        return loadHoldings(connection, ceo)
    }

    override fun loadSubscription(connection: Connection, xuid: Xuid, subscriptionId: String): SubscriptionTerm? =
        subscriptions[xuid to subscriptionId]

    override fun grantRank(connection: Connection, xuid: Xuid, rankId: String, source: String, at: Instant): Boolean {
        val owned = ranks.getOrPut(xuid) { mutableListOf() }
        if (owned.any { it.rankId == rankId }) return false
        owned += RankRow(rankId, at, source)
        return true
    }

    override fun saveSubscription(connection: Connection, xuid: Xuid, subscriptionId: String, term: SubscriptionTerm) {
        subscriptions[xuid to subscriptionId] = term
    }

    override fun grantCosmetic(connection: Connection, xuid: Xuid, cosmeticId: String, source: String, at: Instant): Boolean {
        val owned = cosmetics.getOrPut(xuid) { mutableListOf() }
        if (owned.any { it.cosmeticId == cosmeticId }) return false
        owned += CosmeticRow(cosmeticId, at, source)
        syncProfile(xuid)
        return true
    }

    override fun gamertagOf(connection: Connection, xuid: Xuid): String = checkNotNull(sessions.players[xuid]) { "Joueur inconnu : $xuid" }.gamertag

    override fun lastBoostEnd(connection: Connection, now: Instant): Instant? {
        boostLockReads++
        return boosts.map { it.window.endsAt }.filter { it.isAfter(now) }.maxOrNull()
    }

    override fun insertBoost(connection: Connection, boughtBy: Xuid, window: BoostWindow, bonusPercent: Int) {
        boosts += BoostRow(boughtBy, window, bonusPercent)
    }

    override fun activeBoostPercent(connection: Connection, now: Instant): Int =
        boosts.filter { !it.window.startsAt.isAfter(now) && it.window.endsAt.isAfter(now) }.maxByOrNull { it.window.endsAt }?.bonusPercent ?: 0

    override fun recordPurchase(connection: Connection, buyer: Xuid, itemId: String, gems: Long, recipient: Xuid?, requestId: String, at: Instant) {
        if (purchases.any { it.requestId == requestId }) throw RequestRejected("Le requestId $requestId a déjà servi à un achat")
        purchases += Purchase(buyer, itemId, gems, recipient, requestId, at)
    }

    override fun lockGemmes(connection: Connection, xuid: Xuid): Long = gemmes(xuid)

    override fun spendGemmes(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long {
        require(amount >= 0L) { "Une dépense n'est pas négative : $amount" }
        val before = balancesOf(xuid)
        if (amount > before.gemmes) throw InsufficientBalance(Currency.GEMMES, amount - before.gemmes)
        sessions.balances[xuid] = before.copy(gemmes = before.gemmes - amount)
        movements += Movement(xuid, -amount, reason, refId)
        return before.gemmes - amount
    }

    override fun creditGemmes(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long {
        require(amount >= 0L) { "Un versement n'est pas négatif : $amount" }
        val before = balancesOf(xuid)
        sessions.balances[xuid] = before.copy(gemmes = before.gemmes + amount)
        movements += Movement(xuid, amount, reason, refId)
        return before.gemmes + amount
    }

    override fun pendingDeliveries(connection: Connection, limit: Int): List<PendingDelivery> =
        deliveries.values
            .filter { it.deliveredAt == null && resolve(it.xuid, it.gamertag) != null }
            .sortedWith(compareBy({ it.createdAt }, { it.orderId }))
            .take(limit)
            .map { it.toPending() }

    override fun pendingDeliveriesFor(connection: Connection, xuid: Xuid, gamertag: String): List<PendingDelivery> =
        deliveries.values
            .filter { it.deliveredAt == null && (it.xuid == xuid || SiteRegistrations.key(it.gamertag) == SiteRegistrations.key(gamertag)) }
            .sortedWith(compareBy({ it.createdAt }, { it.orderId }))
            .map { it.toPending() }

    override fun recipientOf(connection: Connection, delivery: PendingDelivery): Xuid? = resolve(delivery.xuid, delivery.gamertag)

    override fun markDelivered(connection: Connection, orderId: String, xuid: Xuid, at: Instant): Boolean {
        val row = deliveries[orderId] ?: return false
        if (row.deliveredAt != null) return false
        row.deliveredAt = at
        row.xuid = xuid
        return true
    }

    override fun isPioneer(connection: Connection, key: String): Boolean = key in pioneerKeys

    override fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long {
        published += Published(kind, xuid, payload)
        return published.size.toLong()
    }

    /** Le XUID s'il est rempli et connu ; sinon le joueur de ce pseudo (même clé que le site) vu en dernier. */
    private fun resolve(xuid: Xuid?, gamertag: String): Xuid? {
        if (xuid != null && xuid in sessions.players) return xuid
        return sessions.players.entries
            .filter { SiteRegistrations.key(it.value.gamertag) == SiteRegistrations.key(gamertag) }
            .maxByOrNull { it.value.lastSeenAt }
            ?.key
    }

    /** `join` lit les cosmétiques dans le profil du faux dépôt de joueurs : il suit ce que ce faux-ci a écrit, dans l'ordre du SQL (obtenu le, puis identifiant). */
    private fun syncProfile(xuid: Xuid) {
        val profile = sessions.profiles[xuid] ?: PlayerProfile(null, null)
        sessions.profiles[xuid] = profile.copy(
            cosmetics = cosmetics[xuid].orEmpty().sortedWith(compareBy({ it.acquiredAt }, { it.cosmeticId })).map { it.cosmeticId },
        )
    }

    private fun balancesOf(xuid: Xuid): Balances = sessions.balances[xuid] ?: Balances(0, 0, 0, 0)

    private fun Delivery.toPending() = PendingDelivery(orderId, gamertag, xuid, gemmes)
}
