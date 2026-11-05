package com.silooo.core.site.pages

import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.h1
import kotlinx.html.p

/**
 * La page 404 : une page Silooo, jamais du JSON, et rien de technique. Oooscar, l'air surpris, porte la phrase du kit
 * (« Cette page s'est envolée. ») ; le retour à l'accueil est le bouton rouge, le seul de la page.
 */
internal fun HTML.introuvable(shell: SiteShell) = gabarit(shell, PageFrame("Page introuvable", "Cette adresse ne mène à rien.")) {
    h1(classes = "sa-plaque") { +"Page introuvable" }
    oooscar(OOOSCAR_OUPS, OOOSCAR_OUPS_ALT, OOOSCAR_OUPS_BULLE, width = "101")
    p(classes = "retour") { a(href = "/", classes = "sa-bouton") { +"Retour à l'accueil" } }
}

/** La page 500 : le détail va dans les journaux, le visiteur ne voit que cette phrase. */
internal fun HTML.erreurInterne(shell: SiteShell) = gabarit(shell, PageFrame("Erreur", "Le site a eu un problème.")) {
    lecture("Erreur") {
        p { +"Le site a eu un problème. Réessaie dans un moment." }
        p { a(href = "/", classes = "sa-bouton") { +"Retour à l'accueil" } }
    }
}
