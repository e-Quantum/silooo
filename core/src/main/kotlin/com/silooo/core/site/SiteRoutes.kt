package com.silooo.core.site

import com.silooo.core.site.pages.PlayersOnline
import com.silooo.core.site.pages.SiteShell
import com.silooo.core.site.pages.accueil
import com.silooo.core.site.pages.actus
import com.silooo.core.site.pages.actusArticle
import com.silooo.core.site.pages.attente
import com.silooo.core.site.pages.boutique
import com.silooo.core.site.pages.ceQuiSeVend
import com.silooo.core.site.pages.discord
import com.silooo.core.site.pages.legal
import com.silooo.core.site.pages.rejoindre
import com.silooo.core.site.pages.vote
import com.silooo.core.site.pages.wikiIndex
import com.silooo.core.site.pages.wikiPage
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.HTML

/**
 * Les routes du site : une page par contenu du mode, les fichiers de `site/public/` sous `/public/`. Tout le reste
 * tombe dans le 404 du module. Les pages du mode `ouvert` ne sont pas enregistrées en mode `attente` : leur
 * adresse y répond 404, comme n'importe quelle adresse inconnue.
 */
internal fun Route.siteRoutes(
    pages: List<ContentPage>,
    shell: SiteShell,
    articles: List<NewsArticle>,
    wiki: List<ContentPage>,
    direct: SiteDirectSource? = null,
    online: () -> PlayersOnline? = { null },
) {
    val published = NewsArticles.published(articles)
    for (page in pages) {
        // Les routes des pionniers (GET et POST) sont dans PioneerRoutes.kt, celles des classements dans StatsRoutes.kt.
        if (page.name == PIONEERS_PAGE || page.name == RANKING_PAGE) continue
        val render = renderer(page, shell, articles, wiki, online)
        get(page.url) { call.respondHtml(HttpStatusCode.OK) { render() } }
        // /cgv mène à /cgv/ : une seule adresse par page, pour les liens et pour les moteurs de recherche.
        if (page.url != "/") {
            get(page.url.trimEnd('/')) { call.respondRedirect(page.url, permanent = true) }
        }
    }
    // Les actus : la liste est un contenu (actus.md, dans les deux modes) ; chaque article publié a son adresse.
    for (article in published) {
        get(article.url) { call.respondHtml(HttpStatusCode.OK) { actusArticle(shell, article) } }
        get(article.url.trimEnd('/')) { call.respondRedirect(article.url, permanent = true) }
    }
    staticResources(PUBLIC_PREFIX.trimEnd('/'), "site/public")
    // Référencement : robots.txt et sitemap.xml listent les pages du mode, rien d'autre (une fiche d'entreprise vit avec
    // le jeu, elle n'est pas listée). Une heure de cache : ni le mode ni les contenus ne changent sans redémarrage.
    get("/robots.txt") {
        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=3600")
        call.respondText(robotsTxt(shell), ContentType.Text.Plain)
    }
    get("/sitemap.xml") {
        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=3600")
        call.respondText(sitemapXml(shell, pages.map { it.url } + published.map { it.url }), ContentType.Application.Xml)
    }
    // Le bandeau direct : l'état du jeu en JSON, trente secondes de cache (docs/site.md). Dans les deux modes : la
    // réponse ne dit rien de plus que la présence et les votes du jour.
    if (direct != null) {
        get(DIRECT_URL) {
            call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=30")
            call.respondText(direct.readJson(), ContentType.Application.Json)
        }
    }
}

internal fun robotsTxt(shell: SiteShell): String = "User-agent: *\nAllow: /\nSitemap: ${shell.siteUrl}/sitemap.xml\n"

/** Le plan du site : les adresses des pages du mode et des articles publiés, telles que le site les sert. */
internal fun sitemapXml(shell: SiteShell, urls: List<String>): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n")
    for (url in urls) append("  <url><loc>").append(xmlText(shell.siteUrl + url)).append("</loc></url>\n")
    append("</urlset>\n")
}

private fun xmlText(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** La fonction de rendu d'un contenu, choisie par son nom de fichier. Un contenu sans page fait échouer le démarrage. */
private fun renderer(
    page: ContentPage,
    shell: SiteShell,
    articles: List<NewsArticle>,
    wiki: List<ContentPage>,
    online: () -> PlayersOnline?,
): HTML.() -> Unit = when (page.name) {
    "attente" -> { { attente(shell, page, NewsArticles.chantier(articles)) } }
    "accueil" -> { { accueil(shell, page, online()) } }
    // Les quatre pages légales (docs/site.md, « Légal ») : des pages de lecture, leur texte est le contenu.
    "mentions-legales", "cgv", "confidentialite", "parents" -> { { legal(shell, page) } }
    // La page du Discord (demande de Kr du 5 novembre 2026) : ce qu'on y trouve, et l'invitation quand elle est configurée.
    "discord" -> { { discord(shell, page) } }
    "rejoindre" -> { { rejoindre(shell, page) } }
    "ce-qui-se-vend" -> { { ceQuiSeVend(shell, page) } }
    "boutique" -> { { boutique(shell, page) } }
    "vote" -> { { vote(shell, page) } }
    "actus" -> { { actus(shell, page, NewsArticles.published(articles)) } }
    WikiContent.INDEX -> { { wikiIndex(shell, page, wiki) } }
    else -> if (page.name.startsWith("wiki/")) { { wikiPage(shell, page) } } else throw SiteContentException("Contenu ${page.name}.md : aucune page ne sait l'afficher")
}
