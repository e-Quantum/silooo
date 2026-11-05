package com.silooo.core.economy

import com.silooo.core.Dependencies
import com.silooo.core.api.ActionResult
import com.silooo.core.api.CompanyState
import com.silooo.core.api.CompanyUpdated
import com.silooo.core.api.ErrorBody
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.SiloooJson
import com.silooo.core.api.idempotent
import com.silooo.core.api.respondError
import com.silooo.core.api.respondJson
import com.silooo.core.findings.FindingService
import com.silooo.core.mission.MissionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** Corps de `collect` : `ActionBase` seul. */
@Serializable
data class CollectRequest(val requestId: String, val actor: String)

/** Corps de `prestige` : `ActionBase` seul. */
@Serializable
data class PrestigeRequest(val requestId: String, val actor: String)

/** Corps de `buyLevels` : `ActionBase` et `amount` (`1`, `10`, `palier` ou `max`). */
@Serializable
data class BuyLevelsRequest(val requestId: String, val actor: String, val amount: String)

/** Corps de `restoreStep` : `ActionBase` et `step` (1 à 4). */
@Serializable
data class RestoreRequest(val requestId: String, val actor: String, val step: Int)

/**
 * Réponse 200 de `collect`. `offlineHours` est le nom du contrat pour "heures comptées" : toutes les heures écoulées
 * depuis `last_collect_at`, car `collect` paie en ligne (revenu plein, sans plafond). Le plafond ne joue qu'à `join`,
 * quand personne n'était connecté.
 */
@Serializable
data class CollectResponse(val collected: Double, val offlineHours: Double, val state: CompanyState)

/** `company_factories.factory_id` : VARCHAR(32). */
internal const val FACTORY_ID_MAX_LENGTH = 32

/**
 * Les routes `companies/{companyId}` de la phase 3 et le prestige.
 *
 *  - `GET /v1/companies/{companyId}` (`getCompany`) : l'état de l'entreprise, calculé comme celui de `join`.
 *  - `POST .../collect` (`collect`) : encaisse la production depuis `last_collect_at`, au revenu plein.
 *  - `POST .../factories/{factoryId}/levels` (`buyLevels`) : monte une usine de 1, 10, d'un palier ou du maximum payable.
 *  - `POST .../factories/{factoryId}/restore` (`restoreStep`) : avance la remise en état d'une usine.
 *  - `GET .../prestige/preview` (`prestigePreview`) : ce que rapporterait un prestige maintenant, sans rien écrire.
 *  - `POST .../prestige` (`prestige`) : le prestige du PDG ; 403 `PAS_LE_PDG`, 409 `SEUIL_NON_ATTEINT`.
 *
 * Les quatre POST passent par `call.idempotent` : un `requestId` rejoué rend la même réponse sans refaire l'action.
 * Un refus du jeu est un 409 `Refused` ; une version dépassée, un 409 `CONFLIT_REESSAYER`. Une entreprise ou une
 * usine inconnue, un acteur qui n'est pas de l'entreprise, un `amount` ou un `step` hors contrat : 400
 * `REQUETE_INVALIDE` (le contrat ne déclare ni 403 ni 404 ici). Le travail est dans [CompanyService].
 */
fun Route.companyRoutes(dependencies: Dependencies) {
    val service = CompanyService(
        sessions = dependencies.sessionStore,
        companies = dependencies.companyStore,
        engine = dependencies.economy,
        figures = dependencies.economyFigures,
        missions = MissionService(dependencies.definitions, dependencies.missionStore),
        findings = FindingService(dependencies.definitions, dependencies.findingStore),
    )

    get("/companies/{companyId}") {
        val companyId = call.companyId()
        if (companyId == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@get
        }
        val state = dependencies.database.transaction { service.read(it, companyId) }
        if (state == null) call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST) else call.respondJson(state)
    }

    post("/companies/{companyId}/collect") {
        val request = call.receive<CollectRequest>()
        val companyId = call.companyId()
        if (companyId == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        call.idempotent(dependencies, request.requestId, request.actor, "collect") { connection, actor ->
            rejectable {
                val collected = service.collect(connection, companyId, actor)
                ActionResult.ok(SiloooJson.encodeToJsonElement(CollectResponse.serializer(), CollectResponse(collected.collected, collected.offlineHours, collected.state)))
            }
        }
    }

    get("/companies/{companyId}/prestige/preview") {
        val companyId = call.companyId()
        if (companyId == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@get
        }
        val preview = dependencies.database.transaction { service.prestigePreview(it, companyId) }
        if (preview == null) call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST) else call.respondJson(preview)
    }

    post("/companies/{companyId}/prestige") {
        val request = call.receive<PrestigeRequest>()
        val companyId = call.companyId()
        if (companyId == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        call.idempotent(dependencies, request.requestId, request.actor, "prestige") { connection, actor ->
            rejectable { companyUpdated(service.prestige(connection, companyId, actor)) }
        }
    }

    post("/companies/{companyId}/factories/{factoryId}/levels") {
        val request = call.receive<BuyLevelsRequest>()
        val companyId = call.companyId()
        val factoryId = call.factoryId()
        val amount = BuyAmount.fromWire(request.amount)
        if (companyId == null || factoryId == null || amount == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        call.idempotent(dependencies, request.requestId, request.actor, "buyLevels") { connection, actor ->
            rejectable { companyUpdated(service.buyLevels(connection, companyId, factoryId, amount, actor)) }
        }
    }

    post("/companies/{companyId}/factories/{factoryId}/restore") {
        val request = call.receive<RestoreRequest>()
        val companyId = call.companyId()
        val factoryId = call.factoryId()
        if (companyId == null || factoryId == null || request.step !in 1..CompanyService.LAST_RESTORE_STEP) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        call.idempotent(dependencies, request.requestId, request.actor, "restoreStep") { connection, actor ->
            rejectable { companyUpdated(service.restoreStep(connection, companyId, factoryId, request.step, actor)) }
        }
    }
}

/** `companyId` du chemin : un entier strictement positif (les identifiants de `companies` partent de 1). */
internal fun ApplicationCall.companyId(): Long? = parameters["companyId"]?.toLongOrNull()?.takeIf { it > 0 }

/** `factoryId` du chemin : non vide, et pas plus long que la colonne qui le garde. Que `usines.json` le connaisse, c'est au service de le dire. */
internal fun ApplicationCall.factoryId(): String? = parameters["factoryId"]?.takeIf { it.isNotBlank() && it.length <= FACTORY_ID_MAX_LENGTH }

internal fun companyUpdated(updated: CompanyUpdated): ActionResult =
    ActionResult.ok(SiloooJson.encodeToJsonElement(CompanyUpdated.serializer(), updated))

/**
 * Une [RequestRejected] devient la réponse 400 `REQUETE_INVALIDE`, rendue comme n'importe quelle réponse de l'action :
 * elle est gardée dans `api_requests` et rejouée. Rien n'a été écrit avant elle (le service vérifie d'abord).
 */
internal inline fun rejectable(action: () -> ActionResult): ActionResult =
    try {
        action()
    } catch (_: RequestRejected) {
        BAD_REQUEST
    }

internal val BAD_REQUEST = ActionResult(
    HttpStatusCode.BadRequest,
    SiloooJson.encodeToJsonElement(ErrorBody.serializer(), ErrorBody(ErrorCodes.BAD_REQUEST)),
)
