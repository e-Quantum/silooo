package com.silooo.core.discord.linking

import com.silooo.core.Dependencies
import com.silooo.core.api.ActionResult
import com.silooo.core.api.SiloooJson
import com.silooo.core.api.idempotent
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class IssueDiscordLinkCodeRequest(val requestId: String, val actor: String)

@Serializable
data class DiscordLinkCodeResponse(val code: String, val expiresAt: String)

/** Le seul appel HTTP émet la preuve dans le jeu ; l'identité Discord vient ensuite du bot authentifié. */
fun Route.gameDiscordLinkRoutes(dependencies: Dependencies) {
    post("/discord/link-codes") {
        val request = call.receive<IssueDiscordLinkCodeRequest>()
        call.idempotent(dependencies, request.requestId, request.actor, "issueDiscordLinkCode") { connection, actor ->
            val issued = dependencies.gameLinkIssuer(connection, actor)
            ActionResult.ok(SiloooJson.encodeToJsonElement(DiscordLinkCodeResponse(issued.code, issued.expiresAt.toString())))
        }
    }
}
