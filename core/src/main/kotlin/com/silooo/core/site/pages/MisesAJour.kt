package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import com.silooo.core.site.UpdateEntry
import java.time.LocalDate
import kotlinx.html.HTML
import kotlinx.html.article
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.p
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
 * Les mises à jour : le texte d'introduction de `mises-a-jour.md`, puis les entrées de `contenu/mises-a-jour/`, la plus
 * récente en tête (la liste arrive déjà triée). Chaque entrée porte sa date, son titre en `h2` et son texte. Sans
 * entrée, la page dit que le serveur n'a pas encore ouvert.
 */
internal fun HTML.misesAJour(shell: SiteShell, page: ContentPage, entries: List<UpdateEntry>) =
    gabarit(shell, PageFrame(page.title, page.description, page.url)) {
        lecture(page.title) {
            rendered(page.html)
            if (entries.isEmpty()) {
                p { +"Le serveur n'a pas encore ouvert. Les mises à jour s'écriront ici." }
            }
            for (entry in entries) {
                article(classes = "entree") {
                    p(classes = "entree__date") { time { attributes["datetime"] = entry.date.toString(); +frenchDate(entry.date) } }
                    h2 { +entry.title }
                    div(classes = "entree__texte") { rendered(entry.html) }
                }
            }
        }
    }
