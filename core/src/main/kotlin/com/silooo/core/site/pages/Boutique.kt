package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.p

/**
 * La boutique : les grades et les abonnements (mêmes définitions, même rendu que « Ce qui se vend »), puis, à la place
 * de `{{achat}}`, le bouton vers l'adresse où s'achètent les Gemmes (`SILOOO_SITE_URL_BOUTIQUE`). Tant que Kr ne l'a pas
 * donnée, la page ne montre aucun lien et le dit en une phrase.
 */
internal fun HTML.boutique(shell: SiteShell, page: ContentPage) =
    gabarit(shell, PageFrame(page.title, page.description, page.url)) {
        lecture(page.title) {
            avecInsertions(
                page.html,
                mapOf(
                    "grades" to { grades(shell.shop) },
                    "abonnements" to { abonnements(shell.shop) },
                    "achat" to {
                        val url = shell.settings.shopUrl
                        if (url != null) {
                            p { a(href = url, classes = "sa-bouton sa-bouton--laiton") { +"Acheter des Gemmes" } }
                        } else {
                            p { +"La boutique ouvre avec le serveur." }
                        }
                    },
                ),
            )
        }
    }
