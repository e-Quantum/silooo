package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import com.silooo.core.sitedata.RankingPage
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.ThScope
import kotlinx.html.a
import kotlinx.html.caption
import kotlinx.html.h2
import kotlinx.html.img
import kotlinx.html.li
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul

private const val INGOT_ICON = "/public/atelier/icones/lingot.png"
private const val RANKING_URL = "/classements/"

/** Ce qu'une page du classement montre : le titre de la semaine, la page lue et son numéro (à partir de 1). */
internal class RankingView(val weekTitle: String, val page: RankingPage, val number: Int)

/**
 * L'adresse de la fiche d'une entreprise : le classement y mène, la fiche n'est pas dans la navigation.
 * Une seule fonction, pour que le lien et la route restent d'accord.
 */
internal fun companyUrl(id: Long): String = "/entreprise/$id/"

/** L'adresse d'une page du classement ; la première est l'adresse nue. */
internal fun rankingUrl(number: Int): String = if (number <= 1) RANKING_URL else "$RANKING_URL?page=$number"

/**
 * Les classements : le contenu `classements.md` porte la phrase écrite à la main, le bloc `{{classement}}` est rendu ici depuis la
 * base (voir `RankingRoutes`). Un tableau de quatre colonnes (rang, joueur, entreprise, Lingots) qui devient une pile de fiches sous 600 px.
 */
internal fun HTML.classements(shell: SiteShell, page: ContentPage, view: RankingView) =
    gabarit(shell, PageFrame(page.title, page.description, page.url)) {
        lecture(page.title) {
            avecInsertions(page.html, mapOf("classement" to { classement(view) }))
        }
    }

private fun FlowContent.classement(view: RankingView) {
    h2 { +view.weekTitle }
    if (view.page.rows.isEmpty()) {
        p { +"Personne n'a encore gagné de Lingots cette semaine." }
        return
    }
    table(classes = "tableau") {
        caption { +view.weekTitle }
        thead {
            tr {
                for (header in HEADERS) th(scope = ThScope.col) { +header }
            }
        }
        tbody {
            for (row in view.page.rows) {
                tr {
                    th(scope = ThScope.row) { attributes["data-label"] = HEADERS[0]; +row.rank.toString() }
                    td { attributes["data-label"] = HEADERS[1]; +row.gamertag }
                    td {
                        attributes["data-label"] = HEADERS[2]
                        if (row.companyId != null && row.companyName != null) {
                            a(href = companyUrl(row.companyId)) { +row.companyName }
                        } else {
                            +"Sans entreprise"
                        }
                    }
                    td {
                        attributes["data-label"] = HEADERS[3]
                        span(classes = "sa-monnaie sa-monnaie--lingots") {
                            img(src = INGOT_ICON, alt = "Lingots", classes = "sa-icone")
                            +formatNumber(row.lingots)
                        }
                    }
                }
            }
        }
    }
    if (view.number > 1 || view.page.hasNext) {
        nav {
            attributes["aria-label"] = "Pages du classement"
            ul(classes = "liens") {
                if (view.number > 1) li { a(href = rankingUrl(view.number - 1), classes = "sa-bouton sa-bouton--laiton") { +"Page précédente" } }
                if (view.page.hasNext) li { a(href = rankingUrl(view.number + 1), classes = "sa-bouton sa-bouton--laiton") { +"Page suivante" } }
            }
        }
    }
}

private val HEADERS = listOf("Rang", "Joueur", "Entreprise", "Lingots")
