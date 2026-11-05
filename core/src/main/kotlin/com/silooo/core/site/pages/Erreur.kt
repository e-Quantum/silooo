package com.silooo.core.site.pages

import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.p

/** La page 404 : une page Silooo, jamais du JSON, et rien de technique. Le retour à l'accueil est son bouton rouge. */
internal fun HTML.introuvable(shell: SiteShell) = gabarit(shell, PageFrame("Page introuvable", "Cette adresse ne mène à rien.")) {
    lecture("Page introuvable") {
        p { +"Cette adresse ne mène à rien." }
        p { a(href = "/", classes = "sa-bouton") { +"Retour à l'accueil" } }
    }
}

/** La page 500 : le détail va dans les journaux, le visiteur ne voit que cette phrase. */
internal fun HTML.erreurInterne(shell: SiteShell) = gabarit(shell, PageFrame("Erreur", "Le site a eu un problème.")) {
    lecture("Erreur") {
        p { +"Le site a eu un problème. Réessaie dans un moment." }
        p { a(href = "/", classes = "sa-bouton") { +"Retour à l'accueil" } }
    }
}
