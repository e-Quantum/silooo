package com.silooo.core.session

import com.silooo.core.Dependencies
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.respondError
import com.silooo.core.api.respondJson
import com.silooo.core.db.Xuid
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** Corps de `join` dans l'OpenAPI : `xuid` et `gamertag` (32 caractères au plus). */
@Serializable
data class JoinRequest(val xuid: String, val gamertag: String)

/** Corps de `leave` dans l'OpenAPI : `xuid`. */
@Serializable
data class LeaveRequest(val xuid: String)

/** `players.gamertag` : VARCHAR(32), et `maxLength: 32` dans l'OpenAPI. */
internal const val GAMERTAG_MAX_LENGTH = 32

/**
 * Domaine « session » : l'arrivée et le départ d'un joueur.
 *
 *  - `POST /v1/session/join` (`join`) : crée le joueur au premier passage, avec son entreprise (case d'archipel,
 *    PDG, usines de départ de [CompanyBootstrap]) et ses soldes à zéro, puis rend `JoinResponse`. Les chiffres
 *    du moteur d'économie passent par [EconomyFigures]. Les Gemmes que le site a vendues à ce joueur avant qu'il ne vienne sont
 *    versées dans la même transaction (`PendingDeliveries`, branché sur `shop/DeliveryRelief`), donc déjà dans les soldes de la
 *    réponse. De même, les votes que Hubyy a envoyés sous son pseudo avant que le service le connaisse (`PendingVotes`, branché sur
 *    `vote/VoteService`) sont versés à ce moment. Sans `requestId` : un rejeu rend le même état et ne verse rien deux fois.
 *  - `POST /v1/session/leave` (`leave`) : fige `players.last_seen_at`, verse la production au revenu plein et remet
 *    `last_collect_at` à maintenant, pour que la période hors ligne commence au départ du dernier membre connecté. 204.
 *
 * XUID invalide, gamertag vide ou de plus de 32 caractères : 400 `REQUETE_INVALIDE`, rien n'est écrit.
 */
fun Route.sessionRoutes(dependencies: Dependencies) {
    val service = SessionService(
        database = dependencies.database,
        store = dependencies.sessionStore,
        economy = dependencies.economyFigures,
        bootstrap = dependencies.companyBootstrap,
        online = dependencies.serverPresence,
        deliveries = dependencies.deliveries ?: PendingDeliveries.NONE,
        votes = dependencies.pendingVotes ?: PendingVotes.NONE,
    )

    post("/session/join") {
        val request = call.receive<JoinRequest>()
        val xuid = Xuid.parse(request.xuid)
        val gamertag = request.gamertag
        if (xuid == null || gamertag.isBlank() || gamertag.codePointCount(0, gamertag.length) > GAMERTAG_MAX_LENGTH) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        call.respondJson(service.join(xuid, gamertag))
    }

    post("/session/leave") {
        val request = call.receive<LeaveRequest>()
        val xuid = Xuid.parse(request.xuid)
        if (xuid == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        service.leave(xuid)
        call.respond(HttpStatusCode.NoContent)
    }
}
