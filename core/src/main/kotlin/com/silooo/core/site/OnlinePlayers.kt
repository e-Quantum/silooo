package com.silooo.core.site

import com.silooo.core.server.ServerPresence
import com.silooo.core.site.pages.PlayersOnline
import java.time.Clock
import java.time.Duration

/**
 * Au-delà de ce délai sans signal de vie, l'accueil dit que le serveur est fermé. Deux minutes : douze signaux manqués (l'add-on bat
 * toutes les 10 secondes), pour qu'un redémarrage court ou un hoquet réseau ne ferme pas le serveur aux yeux des visiteurs. Une durée
 * technique du site, pas un chiffre du jeu ; elle est plus large que celle de `join` (30 secondes), qui décide de ce qui est sûr.
 */
internal val SITE_MAX_BEAT_AGE: Duration = Duration.ofMinutes(2)

/**
 * Les joueurs en ligne d'après les derniers signaux de vie (`POST /v1/server/heartbeat`, gardés en mémoire par [ServerPresence]) : l'union
 * des joueurs des serveurs qui ont battu il y a moins de [SITE_MAX_BEAT_AGE]. Aucun signal assez récent : [PlayersOnline.Closed]. Le site ne
 * touche pas à la base pour cela et ne montre que le nombre, jamais un XUID.
 */
internal fun playersOnline(presence: ServerPresence, clock: Clock): PlayersOnline {
    val oldest = clock.instant().minus(SITE_MAX_BEAT_AGE)
    val recent = presence.all().filter { !it.receivedAt.isBefore(oldest) }
    if (recent.isEmpty()) return PlayersOnline.Closed
    return PlayersOnline.Count(recent.flatMap { it.onlineXuids }.toSet().size)
}
