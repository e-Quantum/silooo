package com.silooo.core.site.pages

import kotlinx.html.HTML
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.footer
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.img
import kotlinx.html.lang
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.title

/** Le nom du site, dans le titre de chaque page et l'en-tête. */
internal const val SITE_NAME = "Silooo"

/**
 * La mention que Mojang demande à tout serveur indépendant, en capitales à dessein. Elle figure dans le pied de
 * chaque page. `tools/anti_ia.py` la connaît (`MENTIONS_PERMISES`) : ce texte-là, et lui seul, échappe à la règle
 * des majuscules.
 */
internal const val MOJANG_NOTICE = "NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT."

internal const val STYLESHEET = "/public/site.css"
internal const val LOGO_64 = "/public/logo/silooo-logo-64.png"
internal const val LOGO_128 = "/public/logo/silooo-logo-128.png"
internal const val LOGO_ALT = "Logo de Silooo : un silo à toit rouge sur un bloc d'herbe"

/** Ce que le gabarit a besoin de savoir de la page : son titre, sa description et le contenu de `<main>`. */
internal class PageFrame(
    /** Le titre court de la page ; « Silooo » seul pour l'accueil et l'attente. */
    val title: String,
    val description: String,
)

/**
 * La page entière : tout le HTML passe par le DSL de kotlinx.html, qui échappe chaque valeur. Aucune page ne
 * charge de script ni de ressource d'un autre hôte (la politique de sécurité du module l'interdirait).
 * Le `h1` est posé par la page, dans [content] : il n'y en a qu'un par page.
 */
internal fun HTML.gabarit(frame: PageFrame, content: MAIN.() -> Unit) {
    lang = "fr"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title(if (frame.title == SITE_NAME) SITE_NAME else "${frame.title} · $SITE_NAME")
        meta(name = "description", content = frame.description)
        link(rel = "stylesheet", href = STYLESHEET)
        link(rel = "icon", href = LOGO_64, type = "image/png")
    }
    body {
        header {
            img(src = LOGO_64, alt = LOGO_ALT)
            a(href = "/", classes = "nom") { +SITE_NAME }
        }
        main { content() }
        footer {
            p { +MOJANG_NOTICE }
            p { a(href = "/mentions/") { +"Mentions" } }
        }
    }
}
