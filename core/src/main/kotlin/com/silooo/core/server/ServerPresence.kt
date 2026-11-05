package com.silooo.core.server

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Ce que le service sait des serveurs Bedrock : le dernier signal de vie de chacun et la liste de
 * ses joueurs en ligne, tels que `POST /v1/server/heartbeat` les rapporte.
 *
 * C'est en mémoire, et c'est voulu pour ce squelette : un redémarrage de silooo-core efface la liste,
 * et le prochain signal de vie (toutes les quelques secondes) la remplit de nouveau. Rien ici n'est un
 * solde ni un état du jeu. La session « routes de session » décide si cette liste doit aussi aller en
 * base ou dans Redis (clés `silooo:`) ; l'horloge est injectée pour que les tests la fixent.
 */
class ServerPresence(private val clock: Clock = Clock.systemUTC()) {

    /** Un signal de vie : qui l'a envoyé, quand, et les XUID (en chaîne) des joueurs en ligne. */
    data class Beat(val serverId: String, val receivedAt: Instant, val onlineXuids: Set<String>)

    private val beats = ConcurrentHashMap<String, Beat>()

    fun record(serverId: String, onlineXuids: Collection<String>) {
        beats[serverId] = Beat(serverId, clock.instant(), onlineXuids.toSet())
    }

    fun latest(serverId: String): Beat? = beats[serverId]

    fun all(): List<Beat> = beats.values.sortedBy { it.serverId }
}
