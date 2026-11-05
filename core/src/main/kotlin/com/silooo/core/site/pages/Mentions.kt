package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import kotlinx.html.HTML

/**
 * Les mentions : le titre vient du contenu, les sections (éditeur, hébergeur, contact, Minecraft, données) sont
 * dans `site/contenu/mentions.md`. Les trous sont marqués « À compléter par Kr. » tant qu'il ne les a pas remplis.
 */
internal fun HTML.mentions(shell: SiteShell, page: ContentPage) =
    gabarit(shell, PageFrame(page.title, page.description, page.url)) {
        lecture(page.title) { rendered(page.html) }
    }
