package com.silooo.core.events

import com.silooo.core.Dependencies
import io.ktor.server.routing.Route

/**
 * Domaine « events » : ce que le service a à dire au jeu.
 *
 * Ce que la session « routes de session » (ou celle des événements) met ici :
 *  - `GET /v1/events?after=<curseur>` (`pollEvents`) : lit `game_events` après le curseur, du plus
 *    ancien au plus récent, pour tous les joueurs à la fois (règle 8 du socle). `limit` vaut 100
 *    par défaut et 500 au plus, comme l'OpenAPI.
 *  - Une fonction pour écrire un événement (`GEMMES_RECUES`, `INCIDENT`, `BOOST_COLLECTIF`…) dans la
 *    transaction de ce qui le cause. Les types viennent du schéma `GameEvent` de l'OpenAPI ; un
 *    type nouveau s'ajoute d'abord au contrat.
 *  - Plus tard (phase 5) : le relevé des livraisons de Gemmes (`shop_deliveries`) qui écrit
 *    `GEMMES_RECUES`.
 */
@Suppress("UNUSED_PARAMETER")
fun Route.eventsRoutes(dependencies: Dependencies) {
    // Rien pour l'instant : voir le commentaire ci-dessus. Le filet 501 couvre /v1/events.
}
