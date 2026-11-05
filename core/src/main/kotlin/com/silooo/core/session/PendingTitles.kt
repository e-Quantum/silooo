package com.silooo.core.session

import com.silooo.core.db.Xuid
import java.sql.Connection

/**
 * Ce que `join` fait de plus pour les titres que le site a promis : donner à un pionnier inscrit sur le site son titre (le
 * cosmétique `pionnier`), une seule fois. L'implémentation du service est `com.silooo.core.shop.PioneerTitle` ; `join` n'en sait que
 * ce contrat, comme de [PendingDeliveries] pour les Gemmes et de [PendingVotes] pour les votes.
 *
 * Appelé dans la transaction du `join`, après les votes : si le joueur est rejoué, rien n'est écrit deux fois (le cosmétique a une clé
 * primaire par joueur, et l'annonce n'est publiée que par l'appel qui l'écrit).
 */
fun interface PendingTitles {
    /** Donne à [xuid] (de pseudo [gamertag]) les titres du site qui l'attendent, dans la transaction de [connection]. */
    fun relieve(connection: Connection, xuid: Xuid, gamertag: String)

    companion object {
        /** Aucun titre en attente : le comportement de `join` avant les cosmétiques, et celui des tests qui ne s'en occupent pas. */
        val NONE = PendingTitles { _, _, _ -> }
    }
}
