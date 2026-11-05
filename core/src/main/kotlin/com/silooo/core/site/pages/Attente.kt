package com.silooo.core.site.pages

import com.silooo.core.site.ChantierState
import com.silooo.core.site.ChantierStep
import com.silooo.core.site.ContentPage
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.b
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.i
import kotlinx.html.img
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.unsafe

// Les textes de la page d'attente, d'après la maquette SiteAttente de Kr. Tous sont à valider par Kr (docs/passation.md) :
// ils sont ici et non dans le Markdown parce qu'ils font partie de la mise en page, pas du texte que Kr édite.
private const val TAGLINE = "Produis de jour comme de nuit"
private const val SIGN_UP_LABEL = "Me prévenir"
private const val SIGN_UP_NOTE = "Par Xbox ou Discord. Les inscrits entrent en bêta et reçoivent le titre Pionnier."
private const val CHANTIER_TITLE = "Le chantier"
private const val GAME_TITLE = "Dans le jeu"

/** L'inscription des pionniers : l'action de la page, le seul bouton rouge. Quand le compte du joueur existera, « Me prévenir » y mènera. */
private const val SIGN_UP_URL = "/pionniers/"

private const val ICON_DIR = "/public/atelier/icones"

/** « Fait » et « En cours » s'écrivent ainsi dans le HTML : c'est le CSS qui les met en capitales. */
private const val DONE_WORD = "Fait"
private const val ONGOING_WORD = "En cours"

/** La coche de l'étape franchie : huit cases de maquette posées à 16 px, dessinée dans la page sans aucun style en ligne. */
private const val CHECK_ICON =
    """<svg class="rang__coche" viewBox="0 0 8 8" width="16" height="16" aria-hidden="true" focusable="false" shape-rendering="crispEdges"><path d="M1 4h1v1h1v1h1V5h1V4h1V3h1V2h1v1H7v1H6v1H5v1H4v1H3V6H2V5H1z" fill="#fff6de"/></svg>"""

/** Un des trois rangs de « Dans le jeu » : le nom de l'icône (sans `.png`), le titre et la phrase. */
private class GamePillar(val icon: String, val title: String, val detail: String)

private val PILLARS = listOf(
    GamePillar("usine", "Usines", "Pose des usines et monte leurs niveaux. Chaque palier double leur production."),
    GamePillar("ville", "Ville", "Bâtis ta ville sur ton île, zone par zone."),
    GamePillar("etoile", "Prestige", "Repars de zéro et gagne des Lingots, qui accélèrent la course suivante."),
)

/**
 * La page d'attente, d'après les maquettes SiteAttente (téléphone) et SiteAttenteBureau (ordinateur) : le logo et le nom,
 * l'accroche, la phrase du contenu, le bouton « Me prévenir » (le seul bouton rouge), puis le panneau « Le chantier »
 * (les étapes tirées des actus, voir `NewsArticles.chantier`, absent tant qu'il n'y en a aucune) et le panneau « Dans le jeu ».
 * Le contenu se pose sur le ciel (`panneau = false`), sans le panneau de fonte du gabarit. Oooscar et sa bulle « On construit.
 * Reviens vite ! » sont ceux du gabarit, en bas de page : la page n'a pas de seconde mascotte. Ni adresse de serveur ni date
 * tant que Kr n'a rien annoncé.
 */
internal fun HTML.attente(shell: SiteShell, page: ContentPage, chantier: List<ChantierStep>) =
    gabarit(shell, PageFrame(page.title, page.description, page.url, panneau = false, classe = "attente")) {
        entry(page)
        if (chantier.isNotEmpty()) chantierPanel(chantier)
        gamePanel()
    }

/**
 * L'entrée : le logo et le nom (le `h1`), l'accroche, la phrase du contenu, le bouton et sa note. Sur ordinateur, le dessin
 * d'île prend la colonne de droite ; sur téléphone, il n'existe pas (le CSS le cache), comme dans la maquette.
 */
private fun MAIN.entry(page: ContentPage) {
    section(classes = "attente__entree") {
        attributes["aria-label"] = SITE_NAME
        div(classes = "attente__colonne") {
            div(classes = "attente__marque") {
                img(src = LOGO_LARGE, alt = LOGO_ALT) {
                    width = "66"
                    height = "80"
                }
                h1 { +SITE_NAME }
            }
            p(classes = "attente__accroche") { +TAGLINE }
            div(classes = "attente__texte") { rendered(page.html) }
            a(href = SIGN_UP_URL, classes = "sa-bouton attente__bouton") { +SIGN_UP_LABEL }
            p(classes = "attente__petit") { +SIGN_UP_NOTE }
        }
        island()
    }
}

/**
 * Le dessin d'île de la maquette bureau (R33) : deux nuages, la terre en pyramide renversée (de l'herbe sur le bloc du haut),
 * les planches et le silo. Rien n'y est positionné ici : le CSS pose chaque pièce, et cache le tout sous 700 px. Pas
 * d'Oooscar : il se tient sur le sol, en bas de page.
 */
private fun FlowContent.island() {
    div(classes = "ile") {
        attributes["aria-hidden"] = "true"
        span(classes = "nuage ile__nuage-1")
        span(classes = "nuage ile__nuage-2")
        div(classes = "ile__bloc ile__bloc-1")
        div(classes = "ile__bloc ile__bloc-2")
        div(classes = "ile__bloc ile__bloc-3")
        div(classes = "ile__silo") {
            b()
            i()
        }
        div(classes = "ile__planches")
    }
}

/** Le chantier : une ligne par étape, dans l'ordre reçu (les franchies d'abord, puis l'étape en cours, puis celles à venir). */
private fun MAIN.chantierPanel(steps: List<ChantierStep>) {
    section(classes = "sa-panneau chantier") {
        attributes["aria-label"] = CHANTIER_TITLE
        h2(classes = "sa-plaque sa-plaque--petite") { +CHANTIER_TITLE }
        for (step in steps) {
            // Seule une étape franchie mène à son article ; les autres ne sont pas des liens.
            val url = step.url.takeIf { step.state == ChantierState.FAITE }
            if (url != null) {
                a(href = url, classes = "rang") { stepRow(step) }
            } else {
                div(classes = "rang") { stepRow(step) }
            }
        }
    }
}

/** Le contenu d'une ligne du chantier : la pastille d'état, le titre, et à droite « Fait » ou « En cours » (rien pour une étape à venir). */
private fun FlowContent.stepRow(step: ChantierStep) {
    span(classes = "rang__etat rang__etat--${step.state.word}") {
        when (step.state) {
            ChantierState.FAITE -> unsafe { +CHECK_ICON }
            ChantierState.EN_COURS -> stateIcon("engrenage")
            ChantierState.A_VENIR -> stateIcon("cadenas")
        }
    }
    span(classes = "rang__nom") { +step.title }
    when (step.state) {
        ChantierState.FAITE -> span(classes = "rang__fin") { +DONE_WORD }
        ChantierState.EN_COURS -> span(classes = "rang__fin") { +ONGOING_WORD }
        ChantierState.A_VENIR -> Unit
    }
}

/** Une icône de 16 pixels de maquette posée à 20 px dans la pastille d'état ; décorative, le titre dit déjà l'étape. */
private fun FlowContent.stateIcon(name: String) {
    img(src = "$ICON_DIR/$name.png", alt = "") {
        width = "20"
        height = "20"
    }
}

/** Le mot de l'état dans le nom de la classe CSS (`rang__etat--faite`). */
private val ChantierState.word: String
    get() = when (this) {
        ChantierState.FAITE -> "faite"
        ChantierState.EN_COURS -> "en-cours"
        ChantierState.A_VENIR -> "a-venir"
    }

/** « Dans le jeu » : trois rangs, une icône, un titre et une phrase chacun. */
private fun MAIN.gamePanel() {
    section(classes = "sa-panneau dans-le-jeu") {
        attributes["aria-label"] = "Ce que tu vas faire"
        h2(classes = "sa-plaque sa-plaque--petite") { +GAME_TITLE }
        for (pillar in PILLARS) {
            div(classes = "rang rang--haut") {
                img(src = "$ICON_DIR/${pillar.icon}.png", alt = "") {
                    width = "32"
                    height = "32"
                }
                span(classes = "rang__bloc") {
                    span(classes = "rang__titre") { +pillar.title }
                    span(classes = "rang__detail") { +pillar.detail }
                }
            }
        }
    }
}
