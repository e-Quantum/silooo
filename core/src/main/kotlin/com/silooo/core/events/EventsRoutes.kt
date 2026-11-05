package com.silooo.core.events

import com.silooo.core.Dependencies
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.EventsPage
import com.silooo.core.api.respondError
import com.silooo.core.api.respondJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Valeurs de `limit` dans l'OpenAPI (opération `pollEvents`) : `default: 100`, `maximum: 500`. `ContractTest` les compare au contrat. */
const val EVENTS_DEFAULT_LIMIT = 100
const val EVENTS_MAX_LIMIT = 500

/**
 * Domaine « events » : ce que le service a à dire au jeu.
 *
 * `GET /v1/events?after=<curseur>&limit=<n>` (`pollEvents`) : les événements d'identifiant strictement
 * supérieur à `after`, du plus ancien au plus récent, pour tous les joueurs à la fois (règle 8 du socle).
 * `cursor` est l'identifiant du dernier événement rendu, ou `after` s'il n'y en a pas : l'add-on le renvoie
 * tel quel au prochain appel. `after` manquant ou négatif, `limit` hors de 1 à 500 : 400 `REQUETE_INVALIDE`.
 *
 * Pour écrire un événement, une route appelle [publishEvent] dans la transaction de ce qui le cause.
 * Le relevé des livraisons de Gemmes (`shop_deliveries`, voir `shop/DeliveryRelief`) écrit `GEMMES_RECUES` dans la transaction du versement.
 *
 * Limite connue : un identifiant se réserve à l'écriture mais n'est visible qu'à la validation. Une
 * transaction lente pourrait donc montrer l'événement 12 après que l'add-on a lu le 13 et passé le curseur.
 * Les transactions des routes durent quelques millisecondes et l'add-on interroge toutes les quelques
 * secondes : le risque est faible, pas nul. Ne jamais garder une transaction ouverte pendant un appel
 * réseau quand elle a publié un événement.
 */
fun Route.eventsRoutes(dependencies: Dependencies) {
    get("/events") {
        val after = call.request.queryParameters["after"]?.toLongOrNull()
        val limitText = call.request.queryParameters["limit"]
        val limit = if (limitText == null) EVENTS_DEFAULT_LIMIT else limitText.toIntOrNull()

        if (after == null || after < 0 || limit == null || limit !in 1..EVENTS_MAX_LIMIT) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@get
        }

        val events = dependencies.database.transaction { connection ->
            dependencies.eventStore.readAfter(connection, after, limit)
        }
        call.respondJson(EventsPage(cursor = events.lastOrNull()?.id ?: after, events = events))
    }
}
