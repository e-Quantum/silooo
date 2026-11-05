package com.silooo.core.vote

import com.silooo.core.Dependencies
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.respondError
import com.silooo.core.api.respondJson
import com.silooo.core.db.Xuid
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Domaine « votes » : ce que l'add-on affiche de la Vote party.
 *
 *  - `GET /v1/votes/today` (`todayVotes`) : les votes du serveur aujourd'hui (UTC), l'objectif de la Vote party, si elle a eu lieu, et
 *    les chiffres de `definitions/vote.json` que l'add-on montre. Avec `?xuid=`, `mine` donne les votes du jour de ce joueur et sa série ;
 *    sans, ou pour un joueur qui n'a jamais voté, zéro. Un `xuid` mal formé : 400 `REQUETE_INVALIDE`. Rien n'est écrit.
 *
 * Les votes eux-mêmes n'arrivent pas par cette API : Hubyy les envoie par Votifier (TCP), voir [VotifierServer]. Le travail est dans [VoteService].
 */
fun Route.voteRoutes(dependencies: Dependencies) {
    get("/votes/today") {
        val raw = call.request.queryParameters["xuid"]
        val viewer = raw?.let { Xuid.parse(it) }
        if (raw != null && viewer == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@get
        }
        call.respondJson(dependencies.database.transaction { dependencies.voteService.today(it, viewer) })
    }
}
