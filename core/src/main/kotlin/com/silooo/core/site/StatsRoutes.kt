package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.site.pages.RankingView
import com.silooo.core.site.pages.SiteShell
import com.silooo.core.site.pages.classements
import com.silooo.core.site.pages.companyUrl
import com.silooo.core.site.pages.entreprise
import com.silooo.core.sitedata.RankingPage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Le nom du contenu des classements : ses routes sont écrites ici, pas dans `SiteRoutes`. */
internal const val RANKING_PAGE = "classements"

/** Joueurs par page du classement : un réglage de mise en page, pas un chiffre du jeu. */
internal const val RANKING_PAGE_SIZE = 25

/** Pages du classement qu'on peut demander au plus (1 000 joueurs) : au-delà, 404, et le cache reste borné. */
internal const val RANKING_MAX_PAGES = 40

/** Un identifiant d'entreprise est un entier positif de dix-huit chiffres au plus (il tient dans un `Long`). */
private val COMPANY_ID = Regex("[0-9]{1,18}")

/**
 * Les données vivantes du mode `ouvert` : `/classements/` (la semaine en cours, les joueurs par Lingots gagnés, vingt-cinq par page) et la fiche
 * `/entreprise/{id}/`. Les lectures passent par [Dependencies.siteStatsStore], en lecture seule, et par un cache d'une minute partagé
 * entre les requêtes ([Dependencies.siteCaches]). Aucun XUID ne sort : les lignes n'en portent pas.
 */
internal fun Route.statsRoutes(dependencies: Dependencies, shell: SiteShell, page: ContentPage) {
    val store = dependencies.siteStatsStore
    val caches = dependencies.siteCaches

    get(page.url) {
        val number = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        if (number > RANKING_MAX_PAGES) return@get call.respond(HttpStatusCode.NotFound)
        val week = SiteWeek.of(dependencies.clock.instant())
        val ranking = caches.rankings.get(RankingKey(week.start, number)) {
            val rows = dependencies.database.transaction {
                store.weeklyRanking(it, week.start, week.end, RANKING_PAGE_SIZE + 1, (number - 1) * RANKING_PAGE_SIZE)
            }
            RankingPage(rows.take(RANKING_PAGE_SIZE), hasNext = rows.size > RANKING_PAGE_SIZE)
        }
        // Une page au-delà du dernier joueur n'existe pas ; la première, vide, dit que personne n'a encore gagné.
        if (number > 1 && ranking.rows.isEmpty()) return@get call.respond(HttpStatusCode.NotFound)
        call.respondHtml(HttpStatusCode.OK) { classements(shell, page, RankingView(week.title(), ranking, number)) }
    }
    get(page.url.trimEnd('/')) { call.response.header(HttpHeaders.Location, page.url); call.respond(HttpStatusCode.MovedPermanently) }

    get("/entreprise/{id}/") {
        val id = companyId(call.parameters["id"]) ?: return@get call.respond(HttpStatusCode.NotFound)
        val card = caches.companies.get(id) { dependencies.database.transaction { store.company(it, id) } }
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondHtml(HttpStatusCode.OK) { entreprise(shell, card) }
    }
    get("/entreprise/{id}") {
        val id = companyId(call.parameters["id"]) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.Location, companyUrl(id))
        call.respond(HttpStatusCode.MovedPermanently)
    }
}

private fun companyId(raw: String?): Long? = raw?.takeIf { COMPANY_ID.matches(it) }?.toLong()?.takeIf { it > 0 }
