package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import kotlinx.html.HTML

/**
 * Les quatre pages légales du kit (docs/site.md, « Légal ») : mentions légales, conditions de vente des Gemmes,
 * confidentialité, note aux parents. Le titre et les sections viennent du contenu (`site/contenu/<nom>.md`). Ce
 * sont des brouillons tant que Kr ne les a pas relus ; les trous sont marqués « À compléter par Kr. ».
 */
internal fun HTML.legal(shell: SiteShell, page: ContentPage) =
    gabarit(shell, PageFrame(page.title, page.description, page.url)) {
        lecture(page.title) { rendered(page.html) }
    }
