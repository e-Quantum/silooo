package com.silooo.core.site.pages

import com.silooo.core.site.SiteSettings
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.p
import kotlinx.html.span

/**
 * L'adresse et le port à saisir dans Minecraft, dans deux tickets : ils viennent de la configuration
 * (`SILOOO_SITE_ADRESSE_JEU`, `SILOOO_SITE_PORT_JEU`), jamais d'un contenu.
 */
internal fun FlowContent.adresseDuServeur(settings: SiteSettings) {
    div(classes = "adresse") {
        p(classes = "adresse__ligne") {
            span(classes = "adresse__nom") { +"Adresse" }
            span(classes = "sa-ticket") { +settings.gameAddress }
        }
        p(classes = "adresse__ligne") {
            span(classes = "adresse__nom") { +"Port" }
            span(classes = "sa-ticket") { +settings.gamePort.toString() }
        }
    }
}

/**
 * L'emplacement des joueurs en ligne. `null` : la donnée manque, la page ne rend rien (ni zéro, ni phrase d'attente).
 * L'étape S5 branche ici le nombre lu dans la base, avec son cache.
 */
internal fun FlowContent.joueursEnLigne(count: Int?) {
    if (count == null) return
    p(classes = "joueurs") { +if (count == 1) "1 joueur en ligne" else "$count joueurs en ligne" }
}
