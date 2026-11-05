package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.ul

/**
 * Le vote : à la place de `{{sites}}`, un bouton par site de `SILOOO_SITE_SITES_DE_VOTE`, ou une phrase tant que Kr n'en
 * a pas donné.
 *
 * La récompense par vote n'est pas chiffrée dans `definitions/` aujourd'hui : `vote.md` dit qu'elle arrive et la page ne
 * tape aucun nombre. Le jour où `definitions/economie.json` la chiffrera, elle se rend ici d'après la définition, à
 * la place d'un bloc `{{recompense}}` du contenu, avec un test qui compare la page au JSON (comme « Ce qui se vend »).
 * La jauge de la Vote party viendra avec les données vivantes (S5) ; pour l'instant la page n'a que sa phrase.
 */
internal fun HTML.vote(shell: SiteShell, page: ContentPage) =
    gabarit(shell, PageFrame(page.title, page.description, page.url)) {
        lecture(page.title) {
            avecInsertions(
                page.html,
                mapOf(
                    "sites" to {
                        val sites = shell.settings.voteSites
                        if (sites.isEmpty()) {
                            p { +"Les sites de vote arrivent avec le serveur." }
                        } else {
                            ul(classes = "liens") {
                                for (site in sites) {
                                    li { a(href = site.url, classes = "sa-bouton sa-bouton--laiton") { +site.name } }
                                }
                            }
                        }
                    },
                ),
            )
        }
    }
