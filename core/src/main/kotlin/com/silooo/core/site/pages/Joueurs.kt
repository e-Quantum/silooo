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

/** Ce que l'accueil sait des joueurs : le serveur n'a pas battu depuis deux minutes ([Closed]) ou il a [Count] joueurs. */
internal sealed interface PlayersOnline {
    data object Closed : PlayersOnline

    data class Count(val count: Int) : PlayersOnline
}

/**
 * L'emplacement des joueurs en ligne de l'accueil. `null` : la donnée manque, la page ne rend rien (ni zéro, ni phrase d'attente).
 * Sinon une phrase : le nombre, « Personne en ligne pour l'instant. » ou « Serveur fermé pour l'instant. » (aucun signal de vie récent).
 */
internal fun FlowContent.joueursEnLigne(online: PlayersOnline?) {
    val text = when (online) {
        null -> return
        PlayersOnline.Closed -> "Serveur fermé pour l'instant."
        is PlayersOnline.Count -> when (online.count) {
            0 -> "Personne en ligne pour l'instant."
            1 -> "1 joueur en ligne"
            else -> "${online.count} joueurs en ligne"
        }
    }
    p(classes = "joueurs") { +text }
}
