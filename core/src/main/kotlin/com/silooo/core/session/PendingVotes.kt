package com.silooo.core.session

import com.silooo.core.db.Xuid
import java.sql.Connection

/**
 * Ce que `join` fait de plus pour les votes : verser ceux que Hubyy a envoyés au nom d'un pseudo que le service ne connaissait pas
 * encore (les lignes de `server_votes` sans `rewarded_at`). L'implémentation du service est `com.silooo.core.vote.VoteService` ;
 * `join` n'en sait que ce contrat, comme de [PendingDeliveries] pour les Gemmes.
 *
 * Appelé dans la transaction du `join`, juste après les livraisons : si le joueur n'est pas rejoué, rien n'est versé deux fois
 * (la ligne est marquée dans la même transaction que le versement).
 */
fun interface PendingVotes {
    /** Verse les votes en attente de [xuid] (de pseudo [gamertag]) dans la transaction de [connection]. */
    fun relieve(connection: Connection, xuid: Xuid, gamertag: String)

    companion object {
        /** Aucun vote en attente : le comportement de `join` avant les votes, et celui des tests qui ne s'en occupent pas. */
        val NONE = PendingVotes { _, _, _ -> }
    }
}
