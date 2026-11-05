package com.silooo.core.session

import com.silooo.core.db.Xuid
import java.sql.Connection

/**
 * Ce que `join` fait de plus une fois le joueur inscrit : verser les Gemmes que le site a vendues et qui l'attendaient (les
 * livraisons de `shop_deliveries` sans `delivered_at`, par XUID puis par pseudo). L'implémentation du service est
 * `com.silooo.core.shop.DeliveryRelief` ; `join` n'en sait que ce contrat, comme [EconomyFigures] pour les chiffres.
 *
 * Appelé dans la transaction du `join`, juste après l'inscription : si le joueur n'est pas rejoué, rien n'est versé deux fois
 * (la livraison est marquée dans la même transaction que le versement).
 */
fun interface PendingDeliveries {
    /** Verse les livraisons en attente de [xuid] (de pseudo [gamertag]) dans la transaction de [connection]. */
    fun relieve(connection: Connection, xuid: Xuid, gamertag: String)

    companion object {
        /** Aucune livraison : le comportement de `join` avant la boutique, et celui des tests qui ne s'en occupent pas. */
        val NONE = PendingDeliveries { _, _, _ -> }
    }
}
