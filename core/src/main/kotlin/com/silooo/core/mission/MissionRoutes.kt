package com.silooo.core.mission

import com.silooo.core.Dependencies
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.respondError
import com.silooo.core.api.respondJson
import com.silooo.core.db.Xuid
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Domaine « missions » : la mission de bienvenue à la mairie et celles qui suivront.
 *
 * `GET /v1/players/{xuid}/missions` (`getMissions`) : la mission en cours et la liste de celles que le joueur a
 * terminées. Un XUID mal formé, ou d'un joueur que le service ne connaît pas : 400 `REQUETE_INVALIDE`. Rien n'est écrit :
 * les missions avancent et se terminent toutes seules dans `collect` ([MissionService.record]), sans route pour les réclamer.
 */
fun Route.missionRoutes(dependencies: Dependencies) {
    val service = MissionService(dependencies.definitions, dependencies.missionStore)

    get("/players/{xuid}/missions") {
        val xuid = call.parameters["xuid"]?.let { Xuid.parse(it) }
        if (xuid == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@get
        }
        val state = dependencies.database.transaction { service.stateOf(it, xuid) }
        if (state == null) call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST) else call.respondJson(state)
    }
}
