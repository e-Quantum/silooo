package com.silooo.core.shop

import com.silooo.core.api.Balances
import com.silooo.core.api.CatalogItem
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.refuse
import com.silooo.core.db.InsufficientBalance
import com.silooo.core.db.Xuid
import com.silooo.core.economy.RequestRejected
import com.silooo.core.session.SessionStore
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * La boutique en Gemmes, sans HTTP : `catalog` et `buyItem`. Les routes valident l'entrée et répondent ; ce service lit, décide et
 * écrit dans la transaction que l'appelant lui donne ([Connection]). Il ne contient aucun chiffre du jeu (prix, durées, bonus et
 * Gemmes rendues viennent de [ShopRules], donc de `boutique.json`) ni aucun texte pour les joueurs.
 *
 * Un achat suit le déroulé de [com.silooo.core.city.CityService] :
 *  1. Les cas de 400 tombent avant toute écriture ([RequestRejected]) : article inconnu ou pas vendu, acheteur ou destinataire que
 *     le service ne connaît pas.
 *  2. La ligne de soldes de l'acheteur est verrouillée et ses Gemmes lues : deux achats du même joueur se suivent.
 *  3. Les refus du jeu (`Refusal`) tombent avant la première écriture, les Gemmes en dernier : `DEJA_POSSEDE`, `GRADE_PRECEDENT_REQUIS`,
 *     `PAS_ASSEZ_DE_GEMMES` avec ce qui manque. Les vérifications de possession portent sur le DESTINATAIRE.
 *  4. L'achat est noté (`shop_purchases`, clé unique sur le `requestId`), l'acheteur paie (`creditBalance` revérifie le solde sous
 *     verrou et reste le filet), puis le destinataire reçoit : le grade (source `achat`, ou `cadeau` pour un autre joueur), ou
 *     l'abonnement prolongé avec les Gemmes qu'il rend.
 *  5. La réponse est le solde de l'acheteur.
 *
 * Aucun événement n'est écrit pour un achat : `GEMMES_RECUES` est celui d'une livraison de commande, avec son `orderId`.
 */
class ShopService(
    private val rules: ShopRules,
    private val store: ShopStore,
    private val sessions: SessionStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Le catalogue vu par [actor]. Un joueur que le service ne connaît pas : [RequestRejected]. */
    fun catalog(connection: Connection, actor: Xuid): List<CatalogItem> {
        if (!store.isKnownPlayer(connection, actor)) throw RequestRejected("Joueur inconnu : $actor")
        return rules.catalog(store.loadHoldings(connection, actor), now())
    }

    /**
     * [actor] achète [itemId] avec ses Gemmes, pour lui, ou pour [forXuid] si c'est un autre joueur (cadeau : l'acheteur paie, le
     * destinataire reçoit). [requestId] est celui de la requête, gardé dans `shop_purchases`. Rend les soldes de l'acheteur.
     */
    fun buy(connection: Connection, actor: Xuid, requestId: String, itemId: String, forXuid: Xuid?): Balances {
        val item = rules.item(itemId) ?: throw RequestRejected("Article inconnu, ou que le jeu ne vend pas encore : $itemId")
        // Un cadeau à soi-même est un achat pour soi.
        val recipient = forXuid ?: actor
        val gift = recipient != actor
        if (!store.isKnownPlayer(connection, actor)) throw RequestRejected("Acheteur inconnu : $actor")
        if (gift && !store.isKnownPlayer(connection, recipient)) throw RequestRejected("Destinataire inconnu : $recipient")

        val held = store.lockGemmes(connection, actor)
        val now = now()
        rules.refusalFor(item, store.loadHoldings(connection, recipient))?.let { refuse(it) }
        if (held < item.gems) refuse(RefusalCodes.NOT_ENOUGH_GEMS, missing = (item.gems - held).toDouble())

        store.recordPurchase(connection, actor, item.id, item.gems, recipient.takeIf { gift }, requestId, now)
        try {
            store.spendGemmes(connection, actor, item.gems, REASON_PURCHASE, item.id)
        } catch (e: InsufficientBalance) {
            throw e.toRefusal()
        }
        when (item) {
            is ShopItem.Grade -> {
                // Faux : un autre achat du même grade pour ce joueur a passé entre-temps. L'achat est annulé, rien n'est payé.
                if (!store.grantRank(connection, recipient, item.id, if (gift) SOURCE_GIFT else SOURCE_PURCHASE, now)) refuse(RefusalCodes.ALREADY_OWNED)
            }
            is ShopItem.Subscription -> {
                val term = rules.extend(store.loadSubscription(connection, recipient, item.id), item, now)
                store.saveSubscription(connection, recipient, item.id, term)
                if (item.gemsReturned > 0L) store.creditGemmes(connection, recipient, item.gemsReturned, REASON_PILIER, item.id)
            }
        }
        return sessions.loadBalances(connection, actor)
    }

    /** La base garde la milliseconde (`DATETIME(3)`) : on écrit et on compare avec elle. */
    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MILLIS)

    companion object {
        /** Les motifs du journal des monnaies (`currency_ledger.reason`, 32 caractères au plus). */
        const val REASON_PURCHASE = "boutique"
        const val REASON_PILIER = "pilier"

        /** Les valeurs de `player_ranks.source` pour un achat. */
        const val SOURCE_PURCHASE = "achat"
        const val SOURCE_GIFT = "cadeau"
    }
}
