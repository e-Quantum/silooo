package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.img
import kotlinx.html.unsafe

/**
 * L'accueil du mode ouvert : le logo, le nom sur sa plaque, le jeu en une phrase et ce qu'on y fait (le contenu
 * `accueil.md`, quatre paragraphes au plus), puis l'adresse et le port, l'emplacement des joueurs en ligne, le bouton
 * « Rejoindre », l'emplacement d'une vidéo et d'une capture, et le lien Discord s'il existe (le seul bouton rouge).
 *
 * [onlinePlayers] : le nombre de joueurs en ligne, `null` tant que la donnée manque (S5 le branche).
 */
internal fun HTML.accueil(shell: SiteShell, page: ContentPage, onlinePlayers: Int? = null) =
    gabarit(shell, PageFrame(page.title, page.description, page.url)) {
        div(classes = "presentation") {
            img(src = LOGO_LARGE, alt = LOGO_ALT, classes = "logo-page") {
                width = "169"
                height = "205"
            }
            h1(classes = "sa-plaque") { +SITE_NAME }
            div(classes = "presentation__texte") { rendered(page.html) }
            adresseDuServeur(shell.settings)
            joueursEnLigne(onlinePlayers)
            a(href = "/rejoindre/", classes = "sa-bouton sa-bouton--laiton") { +"Rejoindre" }
            // Les médias attendent Kr : une vidéo de vingt secondes en jeu et une capture du menu. Le panneau est vide et
            // la feuille de style le cache tant qu'il l'est ; poser une image ou une vidéo dedans le montre. Rien de
            // visible n'annonce un média absent.
            div(classes = "sa-panneau medias") {
                attributes["aria-hidden"] = "true"
                unsafe { +"<!-- Vidéo de vingt secondes en jeu et capture du menu : à poser par Kr (voir docs/site-passation.md). -->" }
            }
            // Le lien Discord n'apparaît que s'il est configuré (SILOOO_SITE_URL_DISCORD) : c'est le seul bouton rouge de la page.
            shell.discordUrl?.let { a(href = it, classes = "sa-bouton") { +"Rejoins le Discord" } }
        }
    }
