package com.silooo.core.site.pages

import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.h1
import kotlinx.html.p

/**
 * La page 404 : une page Silooo, jamais du JSON, et rien de technique. Oooscar, l'air surpris, porte la phrase du kit
 * (« Cette page s'est envolée. ») en bas de page ; le retour à l'accueil est le bouton rouge, le seul de la page.
 */
internal fun HTML.introuvable(shell: SiteShell) =
    gabarit(shell, PageFrame("Page introuvable", "Cette adresse ne mène à rien.", humeur = Humeur.OUPS, bulle = Bulles.INTROUVABLE)) {
        h1(classes = "sa-plaque") { +"Page introuvable" }
        p(classes = "retour") { a(href = "/", classes = "sa-bouton") { +"Retour à l'accueil" } }
    }

/** La page 500 : le détail va dans les journaux, le visiteur ne voit que cette phrase, et Oooscar surpris. */
internal fun HTML.erreurInterne(shell: SiteShell) =
    gabarit(shell, PageFrame("Erreur", "Le site a eu un problème.", humeur = Humeur.OUPS, bulle = Bulles.ERREUR)) {
        lecture("Erreur") {
            p { +"Le site a eu un problème. Réessaie dans un moment." }
            p { a(href = "/", classes = "sa-bouton") { +"Retour à l'accueil" } }
        }
    }
