package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import com.silooo.core.site.NEWS_URL
import com.silooo.core.site.NewsArticle
import java.time.LocalDate
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.time

private val MONTHS = listOf(
    "janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre",
)

/** Une date en français : « 5 novembre 2026 », « 1er décembre 2026 ». */
internal fun frenchDate(date: LocalDate): String {
    val day = if (date.dayOfMonth == 1) "1er" else date.dayOfMonth.toString()
    return "$day ${MONTHS[date.monthValue - 1]} ${date.year}"
}

/**
 * La liste des actus (docs/site.md, « Actus »), dans les deux modes : le texte de `actus.md`, puis une ligne par actu
 * publiée, la plus récente en tête (la liste arrive déjà triée). Chaque ligne porte le titre, la date et, pour une
 * étape du chantier, la marque « Étape ». Le texte se pose sur le ciel, comme dans les maquettes SiteAttente et SiteActu.
 */
internal fun HTML.actus(shell: SiteShell, page: ContentPage, articles: List<NewsArticle>) =
    gabarit(shell, PageFrame(page.title, page.description, page.url, panneau = false, classe = "actus")) {
        h1(classes = "sa-plaque") { +page.title }
        div(classes = "actus__texte") { rendered(page.html) }
        if (articles.isEmpty()) {
            p(classes = "actus__vide") { +"Rien à lire pour l'instant. La première actu sort quand une étape du chantier est franchie." }
        } else {
            div(classes = "actus__liste") {
                for (article in articles) {
                    a(href = article.url, classes = "rang") {
                        span(classes = "rang__titre") { +article.title }
                        article.date?.let { date ->
                            span(classes = "rang__date") { time { attributes["datetime"] = date.toString(); +frenchDate(date) } }
                        }
                        if (article.step) span(classes = "rang__etape") { +"Étape" }
                    }
                }
            }
        }
    }

/**
 * Une actu, d'après la maquette SiteActu : le fil d'Ariane, le titre, la date et le temps de lecture, le sommaire chiffré
 * (un lien par partie écrite, avec le nombre de points), l'introduction, puis les parties du plan fixe dans leur ordre.
 * La couleur d'une partie (vert, laiton, rouge) est portée par une classe de partie : le style n'est jamais en ligne.
 */
internal fun HTML.actusArticle(shell: SiteShell, article: NewsArticle) =
    gabarit(shell, PageFrame(article.title, article.description, article.url, humeur = Humeur.FIER, panneau = false, classe = "actu")) {
        nav(classes = "fil") {
            attributes["aria-label"] = "Fil d'Ariane"
            a(href = NEWS_URL) { +"Actus" }
            span(classes = "fil__sep") {
                attributes["aria-hidden"] = "true"
                +"›"
            }
            span { +article.title }
        }
        h1(classes = "sa-plaque") { +article.title }
        p(classes = "actu__meta") {
            article.date?.let { date ->
                time { attributes["datetime"] = date.toString(); +frenchDate(date) }
                +" · "
            }
            +"${article.readingMinutes} min de lecture"
        }
        if (article.sections.isNotEmpty()) {
            section(classes = "sa-panneau sommaire-actu") {
                attributes["aria-label"] = "Sommaire"
                for (written in article.sections) {
                    a(href = "#${written.part.anchor}", classes = "rang") {
                        span(classes = "rang__numero rang__numero--${written.part.anchor}") { +written.count.toString() }
                        span { +written.part.heading }
                    }
                }
            }
        }
        if (article.intro.isNotBlank()) {
            div(classes = "actu__intro") { rendered(article.intro) }
        }
        for (written in article.sections) {
            section(classes = "actu__partie actu__partie--${written.part.anchor}") {
                id = written.part.anchor
                h2 { +written.part.heading }
                div(classes = "actu__points") { rendered(written.html) }
            }
        }
        shell.discordUrl?.let { discord ->
            p(classes = "actu__bug") { a(href = discord) { +"Un bug ? Signale-le sur le Discord." } }
        }
    }
