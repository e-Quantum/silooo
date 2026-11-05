package com.silooo.core.site.pages

import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.HEAD
import kotlinx.html.HTML
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
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
import kotlinx.html.script
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

/** L'image de partage (1200 sur 630), que Discord, Messenger ou X montrent sous un lien : le logo et Oooscar sur le ciel. */
internal const val SHARE_IMAGE = "/public/partage.png"
internal const val SHARE_IMAGE_ALT = "Le logo de Silooo et Oooscar, sa mascotte, sur le ciel"

/**
 * Les trois scripts du gabarit, des fichiers de `/public/` et jamais du code écrit dans la page (la politique de sécurité
 * l'interdit). `menu.js` replie la navigation sur téléphone, `oooscar.js` anime la mascotte, `direct.js` relit le bandeau.
 */
internal const val MENU_SCRIPT = "/public/menu.js"
internal const val OOOSCAR_SCRIPT = "/public/oooscar.js"
internal const val DIRECT_SCRIPT = "/public/direct.js"

/** La couleur du ciel de `design/tokens.css`, pour la barre du navigateur sur téléphone. */
private const val THEME_COLOR = "#8fb8d6"

/** L'icône du bouton Menu : trois barres, dessinées dans la page sans aucun style en ligne. */
private const val MENU_ICON =
    """<svg class="menu__icone" viewBox="0 0 24 24" width="24" height="24" aria-hidden="true" focusable="false"><rect x="2" y="4" width="20" height="3"/><rect x="2" y="10.5" width="20" height="3"/><rect x="2" y="17" width="20" height="3"/></svg>"""

/**
 * Ce que le gabarit a besoin de savoir de la page : son titre, sa description, l'adresse qu'elle occupe (pour la
 * navigation et la bulle d'Oooscar), et ce qu'Oooscar montre en bas de page quand la page n'a pas la bulle de son adresse.
 */
internal class PageFrame(
    /** Le titre court de la page ; « Silooo » seul pour l'accueil et l'attente. */
    val title: String,
    val description: String,
    /** L'adresse de la page, `null` pour une page d'erreur : aucun lien de la navigation n'est alors marqué courant. */
    val url: String? = null,
    /** Le visage d'Oooscar sous la page : content, sauf sur une page d'erreur. */
    val humeur: Humeur = Humeur.JOIE,
    /** Ce qu'Oooscar dit en arrivant ; `null` : la phrase de l'adresse (voir [Bulles.pour]). */
    val bulle: String? = null,
    /**
     * `true` : le contenu vit dans le panneau de fonte de Silooo Atelier. `false` : il se pose sur le ciel, comme les
     * maquettes SiteAttente et SiteActu, et c'est la page qui dispose ses propres panneaux.
     */
    val panneau: Boolean = true,
    /** Une classe de plus sur `<main>`, quand la page a sa propre mise en page (`attente`, `actu`) ; `null` sinon. */
    val classe: String? = null,
)

/**
 * La page entière, d'après les maquettes de Kr : l'en-tête (la marque, le menu replié sur téléphone, la navigation), le
 * bandeau direct en mode ouvert, le panneau de fonte du contenu, la scène d'Oooscar sur le sol, le pied sombre.
 * Tout le HTML passe par le DSL de kotlinx.html, qui échappe chaque valeur. Aucune page ne charge de ressource d'un autre
 * hôte (la politique de sécurité du module l'interdit). Le `h1` est posé par la page, dans [content] : il n'y en a qu'un
 * par page. `<main>` est le panneau de fonte de Silooo Atelier ; une page de lecture y pose un bloc de papier (voir [lecture]).
 */
internal fun HTML.gabarit(shell: SiteShell, frame: PageFrame, content: MAIN.() -> Unit) {
    lang = "fr"
    val bulle = frame.bulle ?: Bulles.pour(frame.url, shell.mode, shell.definitions)
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
        property("og:image", shell.siteUrl + SHARE_IMAGE)
        property("og:image:width", "1200")
        property("og:image:height", "630")
        property("og:image:alt", SHARE_IMAGE_ALT)
        meta(name = "twitter:card", content = "summary_large_image")
        link(rel = "stylesheet", href = STYLESHEET)
        link(rel = "icon", href = LOGO_64, type = "image/png")
        // menu.js d'abord et sans defer : il pose la classe « js » avant le premier rendu, le menu replié n'apparaît pas déplié un instant.
        script(src = MENU_SCRIPT) {}
        script(src = OOOSCAR_SCRIPT) { attributes["defer"] = "defer" }
        if (shell.direct != null) script(src = DIRECT_SCRIPT) { attributes["defer"] = "defer" }
    }
    body {
        a(href = "#contenu", classes = "passer") { +"Aller au contenu" }
        header(classes = "entete") {
            div(classes = "boite entete__boite") {
                a(href = "/", classes = "marque") {
                    img(src = LOGO_SVG, alt = LOGO_ALT, classes = "marque__logo") {
                        width = "40"
                        height = "48"
                    }
                    span(classes = "marque__nom") { +SITE_NAME }
                }
                if (shell.nav.isNotEmpty()) {
                    menuButton()
                    navigation(shell, frame)
                }
            }
        }
        shell.direct?.let { direct(it) }
        main(classes = listOfNotNull(if (frame.panneau) "sa-panneau" else "ciel", frame.classe).joinToString(" ")) {
            id = "contenu"
            content()
        }
        scene(frame.humeur, bulle)
        footer(classes = "pied") {
            div(classes = "boite pied__boite") {
                p(classes = "pied__mojang") { +MOJANG_NOTICE }
                // Les quatre pages légales du kit (docs/site.md, « Pied de page »), dans les deux modes.
                p(classes = "pied__liens") {
                    // La page du Discord existe dans les deux modes ; c'est elle qui porte l'invitation quand elle est configurée.
                    a(href = "/discord/") { +"Discord Silooo" }
                    a(href = "/mentions-legales/") { +"Mentions légales" }
                    a(href = "/cgv/") { +"CGV" }
                    a(href = "/confidentialite/") { +"Confidentialité" }
                    a(href = "/parents/") { +"Parents" }
                }
                // Les sites frères, quand leurs adresses sont configurées (SILOOO_SITE_URL_SILENEMC, SILOOO_SITE_URL_HUBYY).
                val sisters = listOfNotNull(
                    shell.settings.sileneMcUrl?.let { it to "SileneMC" },
                    shell.settings.hubyyUrl?.let { it to "Hubyy" },
                )
                if (sisters.isNotEmpty()) {
                    p(classes = "pied__voir") {
                        +"Voir aussi : "
                        sisters.forEachIndexed { index, (url, name) ->
                            if (index > 0) +" · "
                            a(href = url) { +name }
                        }
                    }
                }
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

/**
 * Le bouton qui déplie la navigation sous 700 px. Il naît caché : sans script, la navigation est dépliée et le bouton
 * n'a pas lieu d'être ; `menu.js` le montre et le fait agir.
 */
private fun FlowContent.menuButton() {
    button(type = ButtonType.button, classes = "sa-bouton sa-bouton--fonte menu__bouton") {
        attributes["aria-expanded"] = "false"
        attributes["aria-controls"] = "menu"
        attributes["hidden"] = "hidden"
        unsafe { +MENU_ICON }
        span { +"Menu" }
    }
}

private fun FlowContent.navigation(shell: SiteShell, frame: PageFrame) {
    nav(classes = "navigation") {
        id = "menu"
        attributes["aria-label"] = "Pages du site"
        ul {
            for (item in shell.nav) {
                li {
                    val classes = if (item.kind == NavKind.BOUTON) "sa-bouton sa-bouton--fonte navigation__bouton" else "navigation__lien"
                    a(href = item.url, classes = classes) {
                        if (item.url == frame.url) attributes["aria-current"] = "page"
                        +item.label
                    }
                }
            }
        }
    }
}

/**
 * Le bandeau direct (docs/site.md) : les joueurs en ligne et l'état au premier rendu, la version de Bedrock et la Vote
 * party cachées tant que `direct.js` ne les a pas lues sur `/site/direct.json`. Chaque mot est ici, le script ne fait
 * que montrer, cacher et remplacer des nombres ; si silooo-core ne répond plus, le script cache tout le bandeau.
 */
private fun FlowContent.direct(band: DirectBand) {
    val online = band.online()
    val count = (online as? PlayersOnline.Count)?.count
    div(classes = "direct") {
        attributes["role"] = "status"
        attributes["data-direct"] = band.url
        div(classes = "boite direct__boite") {
            item("joueurs", shown = count != null) {
                span(classes = "direct__point") { attributes["aria-hidden"] = "true" }
                span(classes = "direct__nombre") {
                    attributes["data-valeur"] = "joueurs"
                    +(count?.toString() ?: "")
                }
                +" en ligne"
            }
            item("etat", shown = count != null) {
                attributes["data-etat"] = "ouvert"
                +"Ouvert"
            }
            item("etat", shown = count == null) {
                attributes["data-etat"] = "ferme"
                +"Fermé"
            }
            item("version", shown = false) {
                +"Bedrock "
                span(classes = "direct__nombre") { attributes["data-valeur"] = "version" }
            }
            item("voteParty", shown = false) {
                +"Vote party "
                span(classes = "direct__objectif") {
                    span { attributes["data-valeur"] = "votes" }
                    +"/"
                    span { attributes["data-valeur"] = "objectif" }
                }
            }
        }
    }
}

private fun DIV.item(field: String, shown: Boolean, block: kotlinx.html.SPAN.() -> Unit) {
    span(classes = "direct__item") {
        attributes["data-champ"] = field
        if (!shown) attributes["hidden"] = "hidden"
        block()
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
