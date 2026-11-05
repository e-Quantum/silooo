package com.silooo.core.incident

import com.silooo.core.Dependencies
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.idempotent
import com.silooo.core.api.respondError
import com.silooo.core.economy.companyId
import com.silooo.core.economy.companyUpdated
import com.silooo.core.economy.rejectable
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** Corps de `resolveIncident` : `ActionBase`, rien de plus. */
@Serializable
data class ResolveIncidentRequest(val requestId: String, val actor: String)

/** `incidentId` du chemin : un entier strictement positif (les identifiants de `factory_incidents` partent de 1). */
private fun ApplicationCall.incidentId(): Long? = parameters["incidentId"]?.toLongOrNull()?.takeIf { it > 0 }

/**
 * Domaine « incidents d'usine » : l'ouverture se fait toute seule (voir [IncidentTicker]) ; la seule route est la réparation.
 *
 *  - `POST /v1/companies/{companyId}/incidents/{incidentId}/resolve` (`resolveIncident`) : le mini-jeu de réparation est
 *    réussi, l'usine reprend son rythme et l'entreprise reçoit la petite récompense. Un incident déjà clos rend 200 sans rien
 *    changer. Une entreprise ou un incident inconnus, un incident d'une autre entreprise, un acteur qui n'est pas de l'entreprise :
 *    400 `REQUETE_INVALIDE` (le contrat ne déclare ni 403 ni 404 ici, et aucun refus du jeu : réparer ne se refuse jamais).
 *
 * L'écriture passe par `call.idempotent` : un `requestId` rejoué rend la même réponse sans refaire l'action. Une version dépassée
 * est un 409 `CONFLIT_REESSAYER`. Le travail est dans [IncidentService].
 */
fun Route.incidentRoutes(dependencies: Dependencies) {
    val service = incidentService(dependencies)

    post("/companies/{companyId}/incidents/{incidentId}/resolve") {
        val request = call.receive<ResolveIncidentRequest>()
        val companyId = call.companyId()
        val incidentId = call.incidentId()
        if (companyId == null || incidentId == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        call.idempotent(dependencies, request.requestId, request.actor, "resolveIncident") { connection, actor ->
            rejectable { companyUpdated(service.resolve(connection, companyId, incidentId, actor)) }
        }
    }
}

/** Le service des incidents, bâti sur ce que [dependencies] donne : les routes et le minuteur partagent la même construction. */
internal fun incidentService(dependencies: Dependencies): IncidentService = IncidentService(
    sessions = dependencies.sessionStore,
    companies = dependencies.companyStore,
    incidents = dependencies.incidentStore,
    engine = dependencies.economy,
    figures = dependencies.economyFigures,
    draw = IncidentDraw(dependencies.definitions.incidents),
    presence = dependencies.serverPresence,
    clock = dependencies.clock,
)
