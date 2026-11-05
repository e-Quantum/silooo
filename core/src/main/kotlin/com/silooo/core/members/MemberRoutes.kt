package com.silooo.core.members

import com.silooo.core.Dependencies
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.idempotent
import com.silooo.core.api.respondError
import com.silooo.core.db.Xuid
import com.silooo.core.economy.companyId
import com.silooo.core.economy.companyUpdated
import com.silooo.core.economy.rejectable
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

/** Corps de `invite` : `ActionBase` et `targetXuid`, le joueur invité. */
@Serializable
data class InviteRequest(val requestId: String, val actor: String, val targetXuid: String)

/** Corps de `acceptInvite` : `ActionBase` seul (l'acteur est l'invité). */
@Serializable
data class AcceptRequest(val requestId: String, val actor: String)

/** Un élément de `shares` dans `setShares` : la part d'un membre, en pourcent entier. */
@Serializable
data class ShareEntry(val xuid: String, val percent: Int)

/** Corps de `setShares` : `ActionBase` et `shares`. */
@Serializable
data class SetSharesRequest(val requestId: String, val actor: String, val shares: List<ShareEntry>)

/**
 * Domaine « entreprise partagée » : le PDG invite des associés et fixe les parts, l'invité accepte.
 *
 *  - `POST /v1/companies/{companyId}/members` (`invite`) : le PDG invite un joueur ; 403 `PAS_LE_PDG`, 409 `DEJA_POSSEDE`,
 *    `ENTREPRISE_PLEINE`, `ENTREPRISE_EN_COURS`. Publie un événement `INVITATION` pour l'invité.
 *  - `POST .../members/accept` (`acceptInvite`) : l'invité entre dans l'entreprise, comme associé à 0 % ; 409 `DEJA_POSSEDE`,
 *    `ENTREPRISE_PLEINE`, `ENTREPRISE_EN_COURS`. Une invitation absente ou périmée : 400.
 *  - `PUT .../members` (`setShares`) : le PDG fixe la part de chaque membre, 100 en tout ; 403 `PAS_LE_PDG`. Une liste qui ne
 *    nomme pas exactement les membres, ou qui ne fait pas 100 : 400.
 *
 * Les trois écritures passent par `call.idempotent` : un `requestId` rejoué rend la même réponse sans refaire l'action. Une version
 * dépassée est un 409 `CONFLIT_REESSAYER`. Une entreprise inconnue, un acteur qui n'en est pas membre (sauf `acceptInvite`, où
 * l'acteur est l'invité), un joueur inconnu : 400 `REQUETE_INVALIDE`. Le travail est dans [MemberService].
 */
fun Route.memberRoutes(dependencies: Dependencies) {
    val service = MemberService(
        sessions = dependencies.sessionStore,
        companies = dependencies.companyStore,
        members = dependencies.memberStore,
        figures = dependencies.economyFigures,
        bootstrap = dependencies.companyBootstrap,
        limits = dependencies.definitions.world.company,
        online = dependencies.serverPresence,
        clock = dependencies.clock,
    )

    post("/companies/{companyId}/members") {
        val request = call.receive<InviteRequest>()
        val companyId = call.companyId()
        val target = Xuid.parse(request.targetXuid)
        if (companyId == null || target == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        call.idempotent(dependencies, request.requestId, request.actor, "invite") { connection, actor ->
            rejectable { companyUpdated(service.invite(connection, companyId, target, actor)) }
        }
    }

    post("/companies/{companyId}/members/accept") {
        val request = call.receive<AcceptRequest>()
        val companyId = call.companyId()
        if (companyId == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        call.idempotent(dependencies, request.requestId, request.actor, "acceptInvite") { connection, actor ->
            rejectable { companyUpdated(service.accept(connection, companyId, actor)) }
        }
    }

    put("/companies/{companyId}/members") {
        val request = call.receive<SetSharesRequest>()
        val companyId = call.companyId()
        val shares = request.shares.map { entry -> Xuid.parse(entry.xuid)?.let { it to entry.percent } }
        if (companyId == null || shares.any { it == null || it.second !in 0..MemberService.FULL_SHARE }) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@put
        }
        call.idempotent(dependencies, request.requestId, request.actor, "setShares") { connection, actor ->
            rejectable { companyUpdated(service.setShares(connection, companyId, shares.filterNotNull(), actor)) }
        }
    }
}
