package com.silooo.core.site

import com.silooo.core.site.pages.accueil
import com.silooo.core.site.pages.attente
import com.silooo.core.site.pages.SiteShell
import com.silooo.core.site.pages.mentions
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.HTML

/**
 * Les routes du site : une page par contenu du mode, les fichiers de `site/public/` sous `/public/`. Tout le reste
 * tombe dans le 404 du module. Les pages du mode `ouvert` ne sont pas enregistrées en mode `attente` : leur
 * adresse y répond 404, comme n'importe quelle adresse inconnue.
 */
internal fun Route.siteRoutes(pages: List<ContentPage>, shell: SiteShell) {
    for (page in pages) {
        val render = renderer(page, shell)
        get(page.url) { call.respondHtml(HttpStatusCode.OK) { render() } }
        // /mentions mène à /mentions/ : une seule adresse par page, pour les liens et pour les moteurs de recherche.
        if (page.url != "/") {
            get(page.url.trimEnd('/')) { call.respondRedirect(page.url, permanent = true) }
        }
    }
    staticResources(PUBLIC_PREFIX.trimEnd('/'), "site/public")
}

/** La fonction de rendu d'un contenu, choisie par son nom de fichier. Un contenu sans page fait échouer le démarrage. */
private fun renderer(page: ContentPage, shell: SiteShell): HTML.() -> Unit = when (page.name) {
    "attente" -> { { attente(shell, page) } }
    "accueil" -> { { accueil(shell, page) } }
    "mentions" -> { { mentions(shell, page) } }
    else -> throw SiteContentException("Contenu ${page.name}.md : aucune page ne sait l'afficher")
}
