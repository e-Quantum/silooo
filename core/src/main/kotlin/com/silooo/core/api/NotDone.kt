package com.silooo.core.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

/**
 * Toute route `/v1/...` qui n'est pas encore écrite répond 501 avec le code `PAS_FAIT`.
 *
 * Ktor choisit toujours la route la plus précise : dès qu'une session écrit
 * `post("/session/join")`, cette route-ci cesse de répondre pour elle, sans rien retirer ici. À
 * enregistrer en dernier dans le module, pour qu'on le lise comme le filet du bas.
 *
 * Ce filet couvre aussi les routes que la direction a mises en attente, comme le marché d'annonces
 * et les coffres à clés (docs/passation.md, décision 7) : elles restent en 501 tant que Kr n'a pas
 * tranché.
 */
fun Route.notDoneRoutes() {
    route("{...}") {
        handle {
            call.respondError(HttpStatusCode.NotImplemented, ErrorCodes.NOT_DONE)
        }
    }
}
