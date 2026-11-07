package com.silooo.core.lobby

import com.silooo.core.Dependencies
import com.silooo.core.api.ActionResult
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.SiloooJson
import com.silooo.core.api.idempotent
import com.silooo.core.api.respondError
import com.silooo.core.api.respondJson
import com.silooo.core.db.Xuid
import com.silooo.core.discord.linking.DiscordLinkCodeResponse
import com.silooo.core.session.GAMERTAG_MAX_LENGTH
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class IssueLobbyLinkCodeRequest(val requestId: String, val actor: String, val gamertag: String)

@Serializable
data class RecordLobbyRunRequest(val requestId: String, val actor: String, val gamertag: String, val score: Int, val seconds: Int)

/** Le gamertag que BDS tient de Xbox, sans espaces aux bords ; `null` s'il est vide ou trop long (contrat : 1 à 32). */
private fun gamertagOf(raw: String): String? {
    val gamertag = raw.trim()
    return if (gamertag.isEmpty() || gamertag.codePointCount(0, gamertag.length) > GAMERTAG_MAX_LENGTH) null else gamertag
}

/**
 * La salle d'attente (serveur Bedrock ouvert à tous avant l'ouverture, `docs/deploiement.md`) :
 *  - `issueLobbyLinkCode` : le visiteur demande son code, il est inscrit pionnier, et le code se saisit avec `/lier`
 *    sur Discord comme celui du jeu ;
 *  - `recordLobbyRun`, `getLobbyBoard`, `getLobbyPlayer` : les records du saut infini (une ligne par XUID), les
 *    classements, les actus du site et l'état du visiteur. Sans `attente.json` chargé ([Dependencies.lobby] nul),
 *    ces trois routes restent « pas faites » (501).
 * Gamertag vide ou de plus de 32 caractères, score ou durée hors bornes, course trop rapide : 400 `REQUETE_INVALIDE`,
 * rien n'est écrit.
 */
fun Route.lobbyRoutes(dependencies: Dependencies) {
    post("/lobby/link-codes") {
        val request = call.receive<IssueLobbyLinkCodeRequest>()
        val gamertag = gamertagOf(request.gamertag)
        if (gamertag == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        call.idempotent(dependencies, request.requestId, request.actor, "issueLobbyLinkCode") { connection, actor ->
            val issued = dependencies.lobbyLinkIssuer(connection, actor, gamertag)
            ActionResult.ok(SiloooJson.encodeToJsonElement(DiscordLinkCodeResponse(issued.code, issued.expiresAt.toString())))
        }
    }

    val lobby = dependencies.lobby ?: return
    val service = LobbyService(lobby, dependencies.lobbyStore, dependencies.lobbyNews)

    post("/lobby/runs") {
        val request = call.receive<RecordLobbyRunRequest>()
        val gamertag = gamertagOf(request.gamertag)
        val inBounds = request.score in 0..MAX_SCORE && request.seconds in 0..MAX_SECONDS
        if (gamertag == null || !inBounds || !service.plausible(request.score, request.seconds)) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        call.idempotent(dependencies, request.requestId, request.actor, "recordLobbyRun") { connection, actor ->
            val result = service.run(connection, actor, gamertag, request.score, dependencies.clock.instant())
            ActionResult.ok(SiloooJson.encodeToJsonElement(result))
        }
    }

    get("/lobby/board") {
        call.respondJson(dependencies.database.transaction { service.board(it, dependencies.clock.instant()) })
    }

    get("/lobby/players/{xuid}") {
        val xuid = call.parameters["xuid"]?.let { Xuid.parse(it) }
        if (xuid == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@get
        }
        call.respondJson(dependencies.database.transaction { service.player(it, xuid, dependencies.clock.instant()) })
    }
}

/** Les bornes du contrat (`recordLobbyRun`). */
private const val MAX_SCORE = 1_000_000
private const val MAX_SECONDS = 86_400
