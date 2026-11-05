package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import kotlinx.html.HTML
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.h1
import kotlinx.html.img
import kotlinx.html.p
import kotlinx.html.unsafe

/** Le logo, le nom, le texte du contenu : la page de présentation, commune à l'attente et à l'accueil minimal. */
internal fun MAIN.presentation(page: ContentPage, discordUrl: String?) {
    img(src = LOGO_128, alt = LOGO_ALT, classes = "logo-page")
    h1 { +SITE_NAME }
    // Le HTML vient du rendu Markdown de nos contenus, avec le HTML brut escamoté (voir Markdown.kt).
    unsafe { +page.html }
    // Le lien Discord n'apparaît que s'il est configuré (SILOOO_SITE_URL_DISCORD).
    if (discordUrl != null) p { a(href = discordUrl) { +"Rejoins le Discord" } }
}

/**
 * La page d'attente : le jeu en une phrase et « Le serveur ouvre bientôt. » (dans le contenu). Ni adresse de
 * serveur ni date tant que Kr n'a rien annoncé.
 */
internal fun HTML.attente(page: ContentPage, discordUrl: String?) = gabarit(PageFrame(page.title, page.description)) {
    presentation(page, discordUrl)
}
