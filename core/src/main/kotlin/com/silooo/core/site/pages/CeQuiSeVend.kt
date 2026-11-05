package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import kotlinx.html.HTML

/**
 * Ce qui se vend : la promesse, la liste de ce qui n'est jamais en vente et les phrases sont dans
 * `ce-qui-se-vend.md` ; les packs de Gemmes, les grades et les abonnements sont rendus d'après les définitions de la
 * boutique (`definitions/boutique.json`) à la place de `{{packs}}`, `{{grades}}` et `{{abonnements}}`. Aucun prix,
 * aucun bonus n'est écrit dans le contenu ni dans le code.
 */
internal fun HTML.ceQuiSeVend(shell: SiteShell, page: ContentPage) =
    gabarit(shell, PageFrame(page.title, page.description, page.url)) {
        lecture(page.title) {
            avecInsertions(
                page.html,
                mapOf(
                    "packs" to { packsDeGemmes(shell.shop) },
                    "grades" to { grades(shell.shop) },
                    "abonnements" to { abonnements(shell.shop) },
                ),
            )
        }
    }
