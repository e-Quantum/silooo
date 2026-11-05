package com.silooo.core.server

import com.silooo.core.Dependencies
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** Le corps de `heartbeat` dans l'OpenAPI : `serverId` et `onlineXuids` (des XUID en chaîne). */
@Serializable
data class HeartbeatRequest(val serverId: String, val onlineXuids: List<String>)

/** Schéma `Xuid` de l'OpenAPI : 1 à 20 chiffres, en chaîne (un entier 64 bits dépasse les nombres JavaScript). */
private val XUID = Regex("^[0-9]{1,20}$")

/**
 * Domaine « server » : ce que le serveur Bedrock dit de lui-même.
 *
 * Aujourd'hui : `heartbeat` (204, rien dans le corps de la réponse). La session « routes de
 * session » pourra y brancher ce que le signal de vie déclenche (joueurs partis sans `leave`).
 */
fun Route.serverRoutes(dependencies: Dependencies) {
    post("/server/heartbeat") {
        val request = call.receive<HeartbeatRequest>()
        if (request.serverId.isBlank() || !request.onlineXuids.all { XUID.matches(it) }) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        dependencies.serverPresence.record(request.serverId, request.onlineXuids)
        call.respond(HttpStatusCode.NoContent)
    }
}
