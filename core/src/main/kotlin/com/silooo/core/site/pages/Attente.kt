package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import kotlinx.html.HTML
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.img

/** Le logo, le nom sur une plaque, le texte du contenu : la présentation, commune à l'attente et à l'accueil minimal. */
internal fun MAIN.presentation(page: ContentPage, shell: SiteShell) {
    div(classes = "presentation") {
        img(src = LOGO_LARGE, alt = LOGO_ALT, classes = "logo-page") {
            width = "169"
            height = "205"
        }
        h1(classes = "sa-plaque") { +SITE_NAME }
        div(classes = "presentation__texte") { rendered(page.html) }
        // Le lien Discord n'apparaît que s'il est configuré (SILOOO_SITE_URL_DISCORD) : c'est l'action de la page, le seul bouton rouge.
        shell.discordUrl?.let { a(href = it, classes = "sa-bouton") { +"Rejoins le Discord" } }
    }
}

/**
 * La page d'attente : le jeu en une phrase, et Oooscar en bas de page qui dit « On construit. Reviens vite ! »
 * (la bulle de la maquette SiteAttente, posée par le gabarit). Ni adresse de serveur ni date tant que Kr n'a rien annoncé.
 */
internal fun HTML.attente(shell: SiteShell, page: ContentPage) =
    gabarit(shell, PageFrame(page.title, page.description, page.url)) {
        presentation(page, shell)
    }
