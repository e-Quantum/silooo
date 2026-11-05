package com.silooo.core

import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.ServerKeyConfig
import com.silooo.core.api.ServerKeyPlugin
import com.silooo.core.api.SiloooJson
import com.silooo.core.api.notDoneRoutes
import com.silooo.core.api.respondError
import com.silooo.core.db.Database
import com.silooo.core.economy.economyRoutes
import com.silooo.core.events.eventsRoutes
import com.silooo.core.server.ServerPresence
import com.silooo.core.server.serverRoutes
import com.silooo.core.session.sessionRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.application.log
import io.ktor.server.request.ContentTransformationException
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * Tout ce que les routes ont le droit d'utiliser, passé au module Ktor en paramètre : pas de
 * singleton, pas de variable globale. Un test sans Docker construit son propre [Dependencies]
 * avec un faux [Database] ; la production construit le sien dans [main].
 *
 * Une session qui ajoute un service (Redis, relevé des votes, horloge de jeu…) l'ajoute ici, avec une
 * valeur par défaut si elle veut ménager les tests existants.
 */
class Dependencies(
    val database: Database,
    /** La clé attendue dans `X-Silooo-Key`. */
    val apiKey: String,
    val serverPresence: ServerPresence = ServerPresence(),
)

/**
 * Le module Ktor de silooo-core. Il ne lit ni l'environnement ni le disque : tout vient de
 * [dependencies].
 */
fun Application.siloooModule(dependencies: Dependencies) {
    install(ContentNegotiation) { json(SiloooJson) }

    install(StatusPages) {
        // Corps illisible ou qui n'a pas la forme du contrat (JSON cassé, champ obligatoire absent).
        exception<BadRequestException> { call, _ -> call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST) }
        exception<ContentTransformationException> { call, _ -> call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST) }
        // Le reste : le détail va dans les journaux, la réponse ne dit rien du code.
        exception<Throwable> { call, cause ->
            call.application.log.error("Erreur non prévue sur ${call.request.local.method.value} ${call.request.local.uri}", cause)
            call.respondError(HttpStatusCode.InternalServerError, ErrorCodes.INTERNAL)
        }
    }

    routing {
        route("/v1") {
            // Toutes les routes de /v1, y compris celles qui ne sont pas écrites, exigent la clé :
            // un appel sans clé ne doit même pas apprendre quelles routes existent.
            install(ServerKeyPlugin) { apiKey = dependencies.apiKey }

            serverRoutes(dependencies)
            sessionRoutes(dependencies)
            eventsRoutes(dependencies)
            economyRoutes(dependencies)

            notDoneRoutes() // en dernier : le filet 501 pour tout ce qui n'est pas écrit
        }
    }
}
