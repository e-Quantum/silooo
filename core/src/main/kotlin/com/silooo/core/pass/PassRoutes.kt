package com.silooo.core.pass

import com.silooo.core.Dependencies
import com.silooo.core.api.ActionResult
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.PassClaimed
import com.silooo.core.api.SiloooJson
import com.silooo.core.api.idempotent
import com.silooo.core.api.respondError
import com.silooo.core.api.respondJson
import com.silooo.core.db.Xuid
import com.silooo.core.economy.RequestRejected
import com.silooo.core.economy.rejectable
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** Corps de `claimPass` : `ActionBase`, le palier (à partir de 1) et la piste (`GRATUIT` ou `PREMIUM`). */
@Serializable
data class ClaimPassRequest(val requestId: String, val actor: String, val tier: Int, val track: String)

/**
 * Domaine « pass de saison » : la progression du joueur et la réclamation de ses paliers.
 *
 *  - `GET /v1/pass?actor=` (`getPass`) : le pass de l'acteur pour la saison de `pass.json`, avec tous les paliers. Un `actor` absent
 *    ou mal formé, ou un joueur que le service ne connaît pas : 400 `REQUETE_INVALIDE`. Rien n'est écrit.
 *  - `POST /v1/pass/claims` (`claimPass`) : réclame un palier sur une piste. Passe par `call.idempotent` : un `requestId` rejoué rend la
 *    même réponse sans rien verser deux fois. Les refus du jeu sont des 409 `Refused` (`PALIER_NON_ATTEINT` avec `missing`,
 *    `PREMIUM_REQUIS`, `DEJA_RECLAME`) ; un palier qui n'existe pas, une piste sans récompense à ce palier, une piste inconnue, un
 *    acteur que le service ne connaît pas : 400. La réponse dit ce qui a été donné et rend les soldes de l'acteur.
 *
 * Les points ne passent par aucune route : le service les compte lui-même (prestige, commande du jour, missions). La piste premium
 * s'achète par `buyItem` (article `pass-premium`). Le travail est dans [PassService]. Sans [Dependencies.pass] (les tests qui n'en
 * ont pas besoin), les deux routes ne sont pas posées et le filet 501 répond.
 */
fun Route.passRoutes(dependencies: Dependencies) {
    val service = dependencies.pass ?: return

    get("/pass") {
        val actor = call.request.queryParameters["actor"]?.let { Xuid.parse(it) }
        if (actor == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@get
        }
        val state = try {
            dependencies.database.transaction { service.state(it, actor) }
        } catch (_: RequestRejected) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@get
        }
        call.respondJson(state)
    }

    post("/pass/claims") {
        val request = call.receive<ClaimPassRequest>()
        val track = PassTrack.parse(request.track)
        if (track == null || request.tier < 1) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        call.idempotent(dependencies, request.requestId, request.actor, "claimPass") { connection, actor ->
            rejectable {
                val claimed = service.claim(connection, actor, request.tier, track)
                ActionResult.ok(SiloooJson.encodeToJsonElement(PassClaimed.serializer(), claimed))
            }
        }
    }
}
