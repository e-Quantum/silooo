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
 * Les accès SQL de la boutique (`shop_deliveries`, `shop_purchases`, `player_ranks`, `player_subscriptions`, `player_cosmetics`,
 * `server_boosts`, et `site_registrations` en lecture pour le titre Pionnier) et les mouvements de Gemmes, derrière une interface : les tests de route tournent avec un faux en mémoire, le SQL avec la vraie base (Testcontainers,
 * `ShopSqlTest`). Les soldes se lisent par `SessionStore.loadBalances`.
 *
 * Tout ce qui écrit passe par la transaction de la [Connection] reçue : un achat ou une livraison part en entier ou pas du tout.
 */
interface ShopStore {
    // ---------------------------------------------------------------- joueurs, possessions

    /**
     * Vrai si [xuid] a une ligne `players` : un joueur que le service connaît (il est déjà passé par `join`). Lecture verrouillée en
     * partage : appelée en premier dans une transaction, elle n'ouvre pas la vue cohérente, pour que le verrou des soldes qui suit
     * serialise deux appels du même joueur au lieu de rendre un conflit à rejouer (`ER_CHECKREAD`).
     */
    fun isKnownPlayer(connection: Connection, xuid: Xuid): Boolean

    /** Les grades, les abonnements (échus compris) et les cosmétiques de [xuid] (voir [Holdings]). */
    fun loadHoldings(connection: Connection, xuid: Xuid): Holdings

    /**
     * Les grades et les abonnements du PDG de l'entreprise [companyId] (`company_members.role = 'PDG'`) : la matière des bonus de Donar.
     * Ses cosmétiques ne comptent pour aucun bonus : ils ne sont pas lus.
     */
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

    /**
     * Donne le cosmétique [cosmeticId] à [xuid] à l'instant [at], avec la source [source] (`player_cosmetics.source` : `boutique`,
     * `site`). Rend faux si le joueur l'a déjà (deux achats, ou deux arrivées, en même temps : le second n'écrit rien).
     */
    fun grantCosmetic(connection: Connection, xuid: Xuid, cosmeticId: String, source: String, at: Instant): Boolean

    /** Le gamertag que le service connaît pour [xuid] (`players.gamertag`) ; [xuid] est un joueur connu. */
    fun gamertagOf(connection: Connection, xuid: Xuid): String

    // ---------------------------------------------------------------- boost collectif

    /**
     * La fin du dernier boost réservé qui n'est pas échu à l'instant [now] (en cours ou en attente), `null` s'il n'y en a pas. La lecture
     * VERROUILLE cette fin (`FOR UPDATE`) : deux achats en même temps se suivent, ou l'un d'eux reçoit un conflit transitoire et
     * l'appelant le rejoue ; les boosts ne se chevauchent jamais.
     */
    fun lastBoostEnd(connection: Connection, now: Instant): Instant?

    /** Écrit un boost acheté par [boughtBy] sur [window], de [bonusPercent] pourcents. */
    fun insertBoost(connection: Connection, boughtBy: Xuid, window: BoostWindow, bonusPercent: Int)

    /** Les pourcents du boost qui court à l'instant [now] (début inclus, fin exclue), 0 s'il n'y en a pas. Lecture seule. */
    fun activeBoostPercent(connection: Connection, now: Instant): Int

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

    /**
     * Les livraisons pas encore versées qui visent [xuid] ou le pseudo [gamertag], les plus anciennes d'abord. Les pseudos se comparent
     * par leur clé (`SiteRegistrations.key`) : sans tenir compte de la casse ni des espaces des bords, espaces du milieu réduits à un.
     */
    fun pendingDeliveriesFor(connection: Connection, xuid: Xuid, gamertag: String): List<PendingDelivery>

    /**
     * Le joueur à qui revient [delivery] : son XUID s'il est rempli et connu de `players`, sinon le joueur de ce pseudo (même clé que
     * ci-dessus) vu en dernier ; `null` si personne ne correspond (la livraison reste en attente).
     */
    fun recipientOf(connection: Connection, delivery: PendingDelivery): Xuid?

    /**
     * Marque la livraison [orderId] versée à [at] à [xuid] : `UPDATE ... WHERE order_id = ? AND delivered_at IS NULL`. Rend faux si elle
     * n'a pas été touchée (déjà versée par un autre passage) : l'appelant ne verse alors rien.
     */
    fun markDelivered(connection: Connection, orderId: String, xuid: Xuid, at: Instant): Boolean

    // ---------------------------------------------------------------- pionniers

    /** Vrai si le site a inscrit ce gamertag comme pionnier (`site_registrations.pioneer`) ; [key] est celui de `SiteRegistrations.key`. */
    fun isPioneer(connection: Connection, key: String): Boolean

    // ---------------------------------------------------------------- événements

    /** Écrit un événement dans `game_events`, dans la transaction de [connection] (voir `publishEvent`). Rend son identifiant. */
    fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long
}
