package com.silooo.core.city

import com.silooo.core.Dependencies
import com.silooo.core.api.ActionBase
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.idempotent
import com.silooo.core.api.respondError
import com.silooo.core.definitions.DefinitionsChecks
import com.silooo.core.economy.companyId
import com.silooo.core.economy.companyUpdated
import com.silooo.core.economy.rejectable
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** Corps de `placeBuilding` : `ActionBase`, le type du catalogue et la parcelle (son coin en blocs) avec la rotation. */
@Serializable
data class PlaceBuildingRequest(
    val requestId: String,
    val actor: String,
    val buildingType: String,
    val x: Int,
    val z: Int,
    val rotation: Int,
)

/** `zoneIndex` du chemin : de 1 à 16, la forme du contrat. Que le jeu ait autant de zones, c'est au service de le dire. */
private fun ApplicationCall.zoneIndex(): Int? = parameters["zoneIndex"]?.toIntOrNull()?.takeIf { it in ZONE_RANGE }

/** `buildingId` du chemin : un entier strictement positif (les identifiants de `city_buildings` partent de 1). */
private fun ApplicationCall.buildingId(): Long? = parameters["buildingId"]?.toLongOrNull()?.takeIf { it > 0 }

/** Les zones que l'OpenAPI accepte dans le chemin de `buyZone` (`minimum: 1`, `maximum: 16`, vérifié par ContractTest). */
private val ZONE_RANGE = 1..DefinitionsChecks.ZONES_MAX

/**
 * Domaine « ville » : les zones de l'île, les bâtiments, la mairie.
 *
 *  - `POST /v1/companies/{companyId}/city/zones/{zoneIndex}` (`buyZone`) : achète la zone suivante, en Lingots de celui qui agit ;
 *    409 `DEJA_POSSEDE`, `ZONE_VERROUILLEE`, `PAS_ASSEZ_DE_LINGOTS`.
 *  - `POST .../city/buildings` (`placeBuilding`) : pose un bâtiment du catalogue sur une parcelle ; 409 `ZONE_VERROUILLEE`,
 *    `EMPLACEMENT_OCCUPE`, `DEJA_POSSEDE`, `PAS_ASSEZ_DE_LINGOTS`.
 *  - `POST .../city/buildings/{buildingId}` (`upgradeBuilding`) : monte un bâtiment d'un niveau, la mairie comprise ; 409
 *    `DEJA_POSSEDE`, `ZONE_VERROUILLEE`, `PAS_ASSEZ_DE_LINGOTS`.
 *
 * Les trois écritures passent par `call.idempotent` : un `requestId` rejoué rend la même réponse sans refaire l'action. Une version
 * dépassée est un 409 `CONFLIT_REESSAYER`. Une entreprise, une zone, un bâtiment ou un type inconnus, une parcelle hors de l'île, un
 * acteur qui n'est pas de l'entreprise : 400 `REQUETE_INVALIDE` (le contrat ne déclare ni 403 ni 404 ici). Le travail est dans
 * [CityService] ; ce fichier ne valide que la forme de l'entrée.
 */
fun Route.cityRoutes(dependencies: Dependencies) {
    val service = CityService(
        rules = CityRules(dependencies.definitions),
        engine = dependencies.economy,
        sessions = dependencies.sessionStore,
        companies = dependencies.companyStore,
        city = dependencies.cityStore,
        figures = dependencies.economyFigures,
    )

    post("/companies/{companyId}/city/zones/{zoneIndex}") {
        val request = call.receive<ActionBase>()
        val companyId = call.companyId()
        val zoneIndex = call.zoneIndex()
        if (companyId == null || zoneIndex == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        call.idempotent(dependencies, request.requestId, request.actor, "buyZone") { connection, actor ->
            rejectable { companyUpdated(service.buyZone(connection, companyId, zoneIndex, actor)) }
        }
    }

    post("/companies/{companyId}/city/buildings") {
        val request = call.receive<PlaceBuildingRequest>()
        val companyId = call.companyId()
        val shapeOk = request.buildingType.isNotBlank() &&
            request.buildingType.length <= DefinitionsChecks.BUILDING_ID_MAX_LENGTH &&
            request.x >= 0 && request.z >= 0 &&
            request.rotation in CityService.ROTATIONS
        if (companyId == null || !shapeOk) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        call.idempotent(dependencies, request.requestId, request.actor, "placeBuilding") { connection, actor ->
            rejectable {
                companyUpdated(service.placeBuilding(connection, companyId, request.buildingType, request.x, request.z, request.rotation, actor))
            }
        }
    }

    post("/companies/{companyId}/city/buildings/{buildingId}") {
        val request = call.receive<ActionBase>()
        val companyId = call.companyId()
        val buildingId = call.buildingId()
        if (companyId == null || buildingId == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        call.idempotent(dependencies, request.requestId, request.actor, "upgradeBuilding") { connection, actor ->
            rejectable { companyUpdated(service.upgradeBuilding(connection, companyId, buildingId, actor)) }
        }
    }
}
