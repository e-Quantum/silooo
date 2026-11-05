package com.silooo.core.site.pages

import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.h1
import kotlinx.html.p

/** La page 404 : une page Silooo, jamais du JSON, et rien de technique. */
internal fun HTML.introuvable() = gabarit(PageFrame("Page introuvable", "Cette adresse ne mène à rien.")) {
    h1 { +"Page introuvable" }
    p { +"Cette adresse ne mène à rien." }
    p { a(href = "/") { +"Retour à l'accueil" } }
}

/** La page 500 : le détail va dans les journaux, le visiteur ne voit que cette phrase. */
internal fun HTML.erreurInterne() = gabarit(PageFrame("Erreur", "Le site a eu un problème.")) {
    h1 { +"Erreur" }
    p { +"Le site a eu un problème. Réessaie dans un moment." }
    p { a(href = "/") { +"Retour à l'accueil" } }
}
