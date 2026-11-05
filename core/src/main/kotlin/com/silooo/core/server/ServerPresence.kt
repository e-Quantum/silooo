package com.silooo.core.server

import com.silooo.core.db.Xuid
import com.silooo.core.session.OnlinePlayers
import java.time.Clock
import java.time.Duration
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
 *
 * C'est aussi la source de présence de `join` ([OnlinePlayers]) : un joueur est connecté s'il figure dans le dernier
 * signal de vie d'un serveur, reçu il y a moins de [maxBeatAge]. Un serveur qui ne bat plus n'a plus de joueurs en
 * ligne, au lieu de les garder connectés pour toujours.
 */
class ServerPresence(
    private val clock: Clock = Clock.systemUTC(),
    private val maxBeatAge: Duration = DEFAULT_MAX_BEAT_AGE,
) : OnlinePlayers {

    /** Un signal de vie : qui l'a envoyé, quand, et les XUID (en chaîne) des joueurs en ligne. */
    data class Beat(val serverId: String, val receivedAt: Instant, val onlineXuids: Set<String>)

    private val beats = ConcurrentHashMap<String, Beat>()

    fun record(serverId: String, onlineXuids: Collection<String>) {
        beats[serverId] = Beat(serverId, clock.instant(), onlineXuids.toSet())
    }

    fun latest(serverId: String): Beat? = beats[serverId]

    fun all(): List<Beat> = beats.values.sortedBy { it.serverId }

    override fun isOnline(xuid: Xuid): Boolean {
        val oldest = clock.instant().minus(maxBeatAge)
        return beats.values.any { beat -> !beat.receivedAt.isBefore(oldest) && beat.onlineXuids.any { Xuid.parse(it) == xuid } }
    }

    companion object {
        /**
         * Trois signaux de vie manqués : l'add-on bat toutes les 10 secondes (`heartbeatIntervalSeconds`, addon/src/config.ts).
         * Une durée technique de liaison, pas un chiffre du jeu.
         */
        val DEFAULT_MAX_BEAT_AGE: Duration = Duration.ofSeconds(30)
    }
}
