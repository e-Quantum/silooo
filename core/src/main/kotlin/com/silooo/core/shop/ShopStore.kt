package com.silooo.core.shop

import com.silooo.core.db.Xuid
import com.silooo.core.events.GameEventKind
import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/**
 * Une ligne de `shop_deliveries` pas encore versée : la commande du site, le pseudo saisi, le XUID s'il est rempli, et les Gemmes
 * achetées.
 */
data class PendingDelivery(val orderId: String, val gamertag: String, val xuid: Xuid?, val gemmes: Long)

/**
 * Les accès SQL de la boutique (`shop_deliveries`, `shop_purchases`, `player_ranks`, `player_subscriptions`) et les mouvements de
 * Gemmes, derrière une interface : les tests de route tournent avec un faux en mémoire, le SQL avec la vraie base (Testcontainers,
 * `ShopSqlTest`). Les soldes se lisent par `SessionStore.loadBalances`.
 *
 * Tout ce qui écrit passe par la transaction de la [Connection] reçue : un achat ou une livraison part en entier ou pas du tout.
 */
interface ShopStore {
    // ---------------------------------------------------------------- joueurs, possessions

    /** Vrai si [xuid] a une ligne `players` : un joueur que le service connaît (il est déjà passé par `join`). */
    fun isKnownPlayer(connection: Connection, xuid: Xuid): Boolean

    /** Les grades et les abonnements de [xuid], les abonnements échus compris (voir [Holdings]). */
    fun loadHoldings(connection: Connection, xuid: Xuid): Holdings

    /** Les mêmes possessions du PDG de l'entreprise [companyId] (`company_members.role = 'PDG'`) : la matière des bonus de Donar. */
    fun ceoHoldings(connection: Connection, companyId: Long): Holdings

    /** L'abonnement [subscriptionId] de [xuid], ou `null` s'il n'en a jamais eu. La ligne est verrouillée : deux achats se suivent. */
    fun loadSubscription(connection: Connection, xuid: Xuid, subscriptionId: String): SubscriptionTerm?

    /**
     * Donne le grade [rankId] à [xuid] à l'instant [at], avec la source `achat` ou `cadeau` (`player_ranks.source`). Rend faux si le
     * joueur l'a déjà (deux achats du même grade en même temps : le second ne l'écrit pas).
     */
    fun grantRank(connection: Connection, xuid: Xuid, rankId: String, source: String, at: Instant): Boolean

    /** Crée l'abonnement [subscriptionId] de [xuid] ou remplace celui qu'il a (une ligne par joueur et abonnement). */
    fun saveSubscription(connection: Connection, xuid: Xuid, subscriptionId: String, term: SubscriptionTerm)

    // ---------------------------------------------------------------- achats

    /**
     * Note l'achat dans `shop_purchases` : l'acheteur [buyer], l'article, son prix, le destinataire [recipient] si c'est un cadeau
     * (`null` : pour soi), et le `requestId` (clé unique). Lève `RequestRejected` si ce `requestId` a déjà servi à un achat.
     */
    fun recordPurchase(connection: Connection, buyer: Xuid, itemId: String, gems: Long, recipient: Xuid?, requestId: String, at: Instant)

    // ---------------------------------------------------------------- Gemmes

    /** Verrouille la ligne de soldes de [xuid] et rend ses Gemmes : deux achats du même joueur se suivent au lieu de se mélanger. */
    fun lockGemmes(connection: Connection, xuid: Xuid): Long

    /**
     * Retire [amount] Gemmes à [xuid] : `player_balances` et une ligne de `currency_ledger`, dans la transaction de [connection]
     * (voir `creditBalance`). [reason] tient en 32 caractères, [refId] en 64. Lève `InsufficientBalance` si le solde ne couvre pas la
     * dépense, avant toute écriture. Rend le solde après la dépense.
     */
    fun spendGemmes(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long

    /** Verse [amount] Gemmes à [xuid], avec une ligne de `currency_ledger`. Rend le solde après le versement. */
    fun creditGemmes(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long

    // ---------------------------------------------------------------- livraisons

    /**
     * Les livraisons pas encore versées dont le joueur est connu (par XUID s'il est rempli, sinon par pseudo), les plus anciennes
     * d'abord, [limit] au plus. Celles dont personne n'est connu restent en attente et ne sont pas rendues : elles ne bouchent pas le
     * relevé des autres.
     */
    fun pendingDeliveries(connection: Connection, limit: Int): List<PendingDelivery>

    /** Les livraisons pas encore versées qui visent [xuid] ou le pseudo [gamertag], les plus anciennes d'abord. */
    fun pendingDeliveriesFor(connection: Connection, xuid: Xuid, gamertag: String): List<PendingDelivery>

    /**
     * Le joueur à qui revient [delivery] : son XUID s'il est rempli et connu de `players`, sinon le joueur de ce pseudo vu en dernier ;
     * `null` si personne ne correspond (la livraison reste en attente).
     */
    fun recipientOf(connection: Connection, delivery: PendingDelivery): Xuid?

    /**
     * Marque la livraison [orderId] versée à [at] à [xuid] : `UPDATE ... WHERE order_id = ? AND delivered_at IS NULL`. Rend faux si elle
     * n'a pas été touchée (déjà versée par un autre passage) : l'appelant ne verse alors rien.
     */
    fun markDelivered(connection: Connection, orderId: String, xuid: Xuid, at: Instant): Boolean

    // ---------------------------------------------------------------- événements

    /** Écrit un événement dans `game_events`, dans la transaction de [connection] (voir `publishEvent`). Rend son identifiant. */
    fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long
}
