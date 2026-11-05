package com.silooo.core.session

import com.silooo.core.db.Xuid

/**
 * Qui est connecté en ce moment, tel que le service le sait. `join` en tire le régime de la production en attente :
 * si un autre membre de l'entreprise est connecté, la période depuis `last_collect_at` s'est jouée en ligne et se paie
 * au revenu plein (voir [Regime]). L'implémentation du service est `server/ServerPresence`, nourrie par `heartbeat`.
 */
fun interface OnlinePlayers {
    fun isOnline(xuid: Xuid): Boolean

    companion object {
        /** Personne n'est connecté : tout `join` paie la période au régime hors ligne. La valeur des tests sans présence. */
        val NOBODY = OnlinePlayers { false }
    }
}
