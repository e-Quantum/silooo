package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.site.pages.erreurInterne
import com.silooo.core.site.pages.introuvable
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.routing.routing

/** Le préfixe des fichiers statiques : le seul endroit où une réponse peut se garder en cache. */
internal const val PUBLIC_PREFIX = "/public/"

/**
 * Ce que le navigateur peut savoir du site : aucun script, aucune ressource d'un autre hôte. Le site ne charge
 * rien depuis l'extérieur (c'est écrit dans les mentions), la politique l'impose.
 */
internal const val CONTENT_SECURITY_POLICY =
    "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'none'; frame-ancestors 'none'"

/**
 * Les en-têtes de toutes les réponses du site. Les pages ne se gardent pas (le mode peut changer au
 * redémarrage, une page d'attente ne doit pas survivre à l'ouverture) ; les fichiers de `/public/` se gardent un jour.
 */
private val SiteHeaders = createApplicationPlugin("SiteHeaders") {
    onCallRespond { call, _ ->
        val headers = call.response.headers
        headers.append("X-Content-Type-Options", "nosniff")
        headers.append("Referrer-Policy", "same-origin")
        headers.append("Content-Security-Policy", CONTENT_SECURITY_POLICY)
        if (headers[HttpHeaders.CacheControl] == null) {
            val cache = if (call.request.uri.startsWith(PUBLIC_PREFIX)) "public, max-age=86400" else "no-store"
            headers.append(HttpHeaders.CacheControl, cache)
        }
    }
}

/**
 * Le module Ktor du site public silooo.com. Il tourne sur son propre port, à côté de [com.silooo.core.siloooModule] :
 * pas de `/v1`, pas de clé, pas de JSON. Il ne sert que des pages HTML et des fichiers.
 *
 * Les contenus se lisent ici, une fois : un contenu cassé fait échouer le démarrage avec le nom du fichier.
 * [content] ne change que dans les tests.
 */
fun Application.siteModule(dependencies: Dependencies, content: SiteContent = SiteContent.fromClasspath()) {
    install(SiteHeaders)

    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, _ -> call.respondHtml(HttpStatusCode.NotFound) { introuvable() } }
        // Le détail va dans les journaux, la page ne dit rien du code.
        exception<Throwable> { call, cause ->
            call.application.log.error("Erreur non prévue sur ${call.request.httpMethod.value} ${call.request.uri}", cause)
            call.respondHtml(HttpStatusCode.InternalServerError) { erreurInterne() }
        }
    }

    // Valide les contenus du mode au démarrage (une adresse prise deux fois, un contenu sans page).
    val pages = content.forMode(dependencies.siteMode)
    routing { siteRoutes(pages, dependencies.discordUrl) }
}
