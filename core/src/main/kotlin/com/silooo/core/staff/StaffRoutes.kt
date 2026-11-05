package com.silooo.core.staff

import com.silooo.core.Dependencies
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.idempotent
import com.silooo.core.api.respondError
import com.silooo.core.api.respondJson
import com.silooo.core.definitions.DefinitionsChecks
import com.silooo.core.economy.FACTORY_ID_MAX_LENGTH
import com.silooo.core.economy.companyId
import com.silooo.core.economy.companyUpdated
import com.silooo.core.economy.rejectable
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** Corps de `hire` : `ActionBase` et, si le candidat doit travailler tout de suite, `factoryId`. */
@Serializable
data class HireRequest(val requestId: String, val actor: String, val factoryId: String? = null)

/** Corps de `assignStaff` : `ActionBase` et `factoryId`. */
@Serializable
data class AssignRequest(val requestId: String, val actor: String, val factoryId: String)

/** `staff_id` du chemin : un entier strictement positif (les identifiants de `staff` partent de 1). */
private fun ApplicationCall.staffId(): Long? = parameters["staffId"]?.toLongOrNull()?.takeIf { it > 0 }

/** `slot` du chemin : un entier de 1 à 5, la forme du contrat. Que la liste du jour ait autant de places, c'est au service de le dire. */
private fun ApplicationCall.slot(): Int? = parameters["slot"]?.toIntOrNull()?.takeIf { it in SLOT_RANGE }

/** `factoryId` d'un corps : non vide, et pas plus long que la colonne qui le garde. Que `usines.json` le connaisse, c'est au service de le dire. */
private fun String.isFactoryIdShape(): Boolean = isNotBlank() && length <= FACTORY_ID_MAX_LENGTH

/** Les places que l'OpenAPI accepte dans le chemin de `hire` (`minimum: 1`, `maximum: 5`, vérifié par ContractTest). */
private val SLOT_RANGE = 1..DefinitionsChecks.AGENCY_SLOTS_MAX

/**
 * Domaine « personnel » : l'agence d'emploi et les membres embauchés.
 *
 *  - `GET /v1/companies/{companyId}/agency` (`getCandidates`) : la liste du jour, la même toute la journée. Une entreprise inconnue : 400.
 *  - `POST .../agency/{slot}/hire` (`hire`) : embauche un candidat du jour, payé en Donar ; 409 `PAS_ASSEZ_DE_DONAR`, `DEJA_POSSEDE`,
 *    `USINE_VERROUILLEE`, `EMPLACEMENT_OCCUPE`.
 *  - `PATCH .../staff/{staffId}` (`assignStaff`) : affecte un membre à une usine ; 409 `USINE_VERROUILLEE`, `EMPLACEMENT_OCCUPE`.
 *  - `DELETE .../staff/{staffId}` (`fireStaff`) : renvoie un membre (le PDG seulement : 403 `PAS_LE_PDG`). Pas de corps : `requestId` et
 *    `actor` passent en paramètres.
 *
 * Les trois écritures passent par `call.idempotent` : un `requestId` rejoué rend la même réponse sans refaire l'action. Une version
 * dépassée est un 409 `CONFLIT_REESSAYER`. Une entreprise, une place, un membre ou une usine inconnus, un acteur qui n'est pas de
 * l'entreprise : 400 `REQUETE_INVALIDE` (le contrat ne déclare ni 403 ni 404 ici). Le travail est dans [StaffService].
 */
fun Route.staffRoutes(dependencies: Dependencies) {
    val service = StaffService(
        sessions = dependencies.sessionStore,
        companies = dependencies.companyStore,
        staff = dependencies.staffStore,
        engine = dependencies.economy,
        figures = dependencies.economyFigures,
        draw = AgencyDraw(dependencies.definitions.personnel),
        clock = dependencies.clock,
    )

    get("/companies/{companyId}/agency") {
        val companyId = call.companyId()
        if (companyId == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@get
        }
        val candidates = dependencies.database.transaction { service.candidates(it, companyId) }
        if (candidates == null) call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST) else call.respondJson(candidates)
    }

    post("/companies/{companyId}/agency/{slot}/hire") {
        val request = call.receive<HireRequest>()
        val companyId = call.companyId()
        val slot = call.slot()
        if (companyId == null || slot == null || request.factoryId?.isFactoryIdShape() == false) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        call.idempotent(dependencies, request.requestId, request.actor, "hire") { connection, actor ->
            rejectable { companyUpdated(service.hire(connection, companyId, slot, request.factoryId, actor)) }
        }
    }

    patch("/companies/{companyId}/staff/{staffId}") {
        val request = call.receive<AssignRequest>()
        val companyId = call.companyId()
        val staffId = call.staffId()
        if (companyId == null || staffId == null || !request.factoryId.isFactoryIdShape()) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@patch
        }
        call.idempotent(dependencies, request.requestId, request.actor, "assignStaff") { connection, actor ->
            rejectable { companyUpdated(service.assign(connection, companyId, staffId, request.factoryId, actor)) }
        }
    }

    delete("/companies/{companyId}/staff/{staffId}") {
        val companyId = call.companyId()
        val staffId = call.staffId()
        val requestId = call.request.queryParameters["requestId"]
        val actor = call.request.queryParameters["actor"]
        if (companyId == null || staffId == null || requestId == null || actor == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@delete
        }
        call.idempotent(dependencies, requestId, actor, "fireStaff") { connection, firedBy ->
            rejectable { companyUpdated(service.fire(connection, companyId, staffId, firedBy)) }
        }
    }
}
