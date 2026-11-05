package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import kotlinx.html.HTML
import kotlinx.html.h1
import kotlinx.html.unsafe

/**
 * Les mentions : le titre vient du contenu, les sections (éditeur, hébergeur, contact, Minecraft, données) sont
 * dans `site/contenu/mentions.md`. Les trous sont marqués « À compléter par Kr. » tant qu'il ne les a pas remplis.
 */
internal fun HTML.mentions(page: ContentPage) = gabarit(PageFrame(page.title, page.description)) {
    h1 { +page.title }
    unsafe { +page.html }
}
