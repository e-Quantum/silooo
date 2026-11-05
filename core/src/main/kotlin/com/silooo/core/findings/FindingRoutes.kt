package com.silooo.core.findings

import com.silooo.core.Dependencies
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.respondError
import com.silooo.core.api.respondJson
import com.silooo.core.db.Xuid
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Domaine « Carnet de trouvailles » : `GET /v1/players/{xuid}/findings` (`getFindings`), les ressources que le joueur a
 * trouvées et la prime de chacune. Un XUID mal formé, ou d'un joueur que le service ne connaît pas : 400 `REQUETE_INVALIDE`.
 * Rien n'est écrit : les trouvailles s'inscrivent d'elles-mêmes dans `collect` ([FindingService.record]), sans route pour les réclamer.
 */
fun Route.findingRoutes(dependencies: Dependencies) {
    val service = FindingService(dependencies.definitions, dependencies.findingStore)

    get("/players/{xuid}/findings") {
        val xuid = call.parameters["xuid"]?.let { Xuid.parse(it) }
        if (xuid == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@get
        }
        val book = dependencies.database.transaction { service.bookOf(it, xuid) }
        if (book == null) call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST) else call.respondJson(book)
    }
}
