package com.silooo.core.discord.linking

import com.silooo.core.Dependencies
import com.silooo.core.api.ActionResult
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.SiloooJson
import com.silooo.core.api.idempotent
import com.silooo.core.api.respondError
import com.silooo.core.session.GAMERTAG_MAX_LENGTH
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class IssueLobbyLinkCodeRequest(val requestId: String, val actor: String, val gamertag: String)

/**
 * La salle d'attente (serveur Bedrock ouvert à tous avant l'ouverture) : le visiteur demande son code, il est inscrit
 * pionnier, et le code se saisit avec `/lier` sur Discord comme celui du jeu. Le gamertag vient de BDS, donc de Xbox.
 * Gamertag vide ou de plus de 32 caractères : 400 `REQUETE_INVALIDE`, rien n'est écrit.
 */
fun Route.lobbyLinkRoutes(dependencies: Dependencies) {
    post("/lobby/link-codes") {
        val request = call.receive<IssueLobbyLinkCodeRequest>()
        val gamertag = request.gamertag.trim()
        if (gamertag.isEmpty() || gamertag.codePointCount(0, gamertag.length) > GAMERTAG_MAX_LENGTH) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        call.idempotent(dependencies, request.requestId, request.actor, "issueLobbyLinkCode") { connection, actor ->
            val issued = dependencies.lobbyLinkIssuer(connection, actor, gamertag)
            ActionResult.ok(SiloooJson.encodeToJsonElement(DiscordLinkCodeResponse(issued.code, issued.expiresAt.toString())))
        }
    }
}
