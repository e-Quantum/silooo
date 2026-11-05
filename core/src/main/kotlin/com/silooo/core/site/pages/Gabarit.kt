package com.silooo.core.site.pages

import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.HEAD
import kotlinx.html.HTML
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.footer
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.id
import kotlinx.html.img
import kotlinx.html.lang
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe

/** Le nom du site, dans le titre de chaque page et l'en-tête. */
internal const val SITE_NAME = "Silooo"

/**
 * La mention que Mojang demande à tout serveur indépendant, en capitales à dessein. Elle figure dans le pied de
 * chaque page. `tools/anti_ia.py` la connaît (`MENTIONS_PERMISES`) : ce texte-là, et lui seul, échappe à la règle
 * des majuscules.
 */
internal const val MOJANG_NOTICE = "NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT."

internal const val STYLESHEET = "/public/site.css"
internal const val LOGO_SVG = "/public/logo/silooo-logo.svg"
internal const val LOGO_64 = "/public/logo/silooo-logo-64.png"
internal const val LOGO_128 = "/public/logo/silooo-logo-128.png"
internal const val LOGO_LARGE = "/public/logo/silooo-logo.png"
internal const val LOGO_ALT = "Logo de Silooo : un silo à toit rouge sur un bloc d'herbe"

/** La couleur du ciel de `design/tokens.css`, pour la barre du navigateur sur téléphone. */
private const val THEME_COLOR = "#8fb8d6"

/** Ce que le gabarit a besoin de savoir de la page : son titre, sa description et l'adresse qu'elle occupe (pour la navigation). */
internal class PageFrame(
    /** Le titre court de la page ; « Silooo » seul pour l'accueil et l'attente. */
    val title: String,
    val description: String,
    /** L'adresse de la page, `null` pour une page d'erreur : aucun lien de la navigation n'est alors marqué courant. */
    val url: String? = null,
)

/**
 * La page entière : tout le HTML passe par le DSL de kotlinx.html, qui échappe chaque valeur. Aucune page ne
 * charge de script ni de ressource d'un autre hôte (la politique de sécurité du module l'interdirait).
 * Le `h1` est posé par la page, dans [content] : il n'y en a qu'un par page. `<main>` est le panneau de fonte
 * de Silooo Atelier ; une page de lecture y pose un bloc de papier (voir [lecture]).
 */
internal fun HTML.gabarit(shell: SiteShell, frame: PageFrame, content: MAIN.() -> Unit) {
    lang = "fr"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title(if (frame.title == SITE_NAME) SITE_NAME else "${frame.title} · $SITE_NAME")
        meta(name = "description", content = frame.description)
        meta(name = "theme-color", content = THEME_COLOR)
        property("og:type", "website")
        property("og:site_name", SITE_NAME)
        property("og:locale", "fr_FR")
        property("og:title", frame.title)
        property("og:description", frame.description)
        property("og:image", shell.siteUrl + LOGO_LARGE)
        property("og:image:alt", LOGO_ALT)
        link(rel = "stylesheet", href = STYLESHEET)
        link(rel = "icon", href = LOGO_64, type = "image/png")
    }
    body {
        a(href = "#contenu", classes = "passer") { +"Aller au contenu" }
        header(classes = "entete") {
            a(href = "/", classes = "marque") {
                img(src = LOGO_SVG, alt = LOGO_ALT, classes = "marque__logo") {
                    width = "40"
                    height = "48"
                }
                span(classes = "marque__nom") { +SITE_NAME }
            }
            navigation(shell, frame)
        }
        main(classes = "sa-panneau") {
            id = "contenu"
            content()
        }
        footer(classes = "pied") {
            p(classes = "pied__mojang") { +MOJANG_NOTICE }
            p(classes = "pied__liens") {
                a(href = "/mentions/") { +"Mentions" }
                shell.discordUrl?.let { a(href = it) { +"Discord" } }
            }
        }
    }
}

/** `<meta property=... content=...>` : kotlinx.html n'a pas de raccourci pour `property`, que Open Graph demande. */
private fun HEAD.property(name: String, value: String) {
    meta {
        attributes["property"] = name
        attributes["content"] = value
    }
}

private fun FlowContent.navigation(shell: SiteShell, frame: PageFrame) {
    if (shell.nav.isEmpty()) return
    nav(classes = "navigation") {
        attributes["aria-label"] = "Pages du site"
        ul {
            for (item in shell.nav) {
                li {
                    a(href = item.url, classes = "sa-bouton sa-bouton--fonte sa-bouton--nav") {
                        if (item.url == frame.url) attributes["aria-current"] = "page"
                        +item.label
                    }
                }
            }
        }
    }
}

/**
 * Le style d'une page de lecture (mentions, erreurs, plus tard le wiki) : le titre sur une plaque, puis le texte en
 * `fonte-900` sur un bloc de `papier`. Le choix est justifié dans `docs/site.md` : quinze virgule huit contre six virgule six de contraste.
 */
internal fun MAIN.lecture(title: String, body: DIV.() -> Unit) {
    h1(classes = "sa-plaque") { +title }
    div(classes = "lecture") { body() }
}

/** Le HTML déjà rendu d'un contenu Markdown (HTML brut escamoté, voir Markdown.kt). */
internal fun DIV.rendered(html: String) {
    unsafe { +html }
}
