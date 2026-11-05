package com.silooo.core.site.pages

import com.silooo.core.definitions.Definitions
import com.silooo.core.definitions.ShopDefinitions
import com.silooo.core.site.ContentPage
import com.silooo.core.site.SiteMode
import com.silooo.core.site.SiteSettings

/** La forme d'une entrée de navigation : un lien simple, ou un bouton de fonte (le compte, « Me prévenir »). */
internal enum class NavKind { LIEN, BOUTON }

/** Un lien de la navigation : l'adresse de la page et le mot qu'on lit. */
internal class NavItem(val url: String, val label: String, val kind: NavKind = NavKind.LIEN)

/**
 * La navigation des maquettes de Kr (docs/references/site-kit/maquettes/, SiteVitrine et SiteAttente), écrite en une liste
 * par mode. Une adresse n'apparaît que si le mode rend vraiment la page (la liste des pages vient de `SiteContent`) : aucun
 * lien ne mène à un 404. Le logo mène à l'accueil, les mentions sont au pied : ni l'un ni l'autre n'est dans la navigation.
 * La bêta a la liste de l'ouverture. « Mon compte » mène à `/compte/` ; « Me prévenir », en attente, à l'inscription des pionniers.
 */
internal object Navigation {
    private val OUVERT = listOf(
        NavItem("/rejoindre/", "Rejoindre"),
        NavItem("/boutique/", "Boutique"),
        NavItem("/classements/", "Classements"),
        NavItem("/vote/", "Vote"),
        NavItem("/wiki/", "Guide"),
        NavItem("/actus/", "Actus"),
        NavItem("/compte/", "Mon compte", NavKind.BOUTON),
    )

    // L'entrée Discord mène à la page du Discord (demande de Kr du 5 novembre 2026), qui porte l'invitation quand elle existe.
    private val ATTENTE = listOf(
        NavItem("/actus/", "Actus"),
        NavItem("/discord/", "Discord"),
        NavItem("/pionniers/", "Me prévenir", NavKind.BOUTON),
    )

    fun items(mode: SiteMode, pages: List<ContentPage>): List<NavItem> {
        val rendered = pages.map { it.url }.toSet()
        val order = if (mode == SiteMode.ATTENTE) ATTENTE else OUVERT
        return order.filter { it.url in rendered }
    }
}

/** Le bandeau direct du gabarit : l'adresse que `direct.js` relit toutes les trente secondes, et les joueurs en ligne pour le premier rendu. */
internal class DirectBand(val url: String, val online: () -> PlayersOnline)

/**
 * Ce que chaque page reçoit du site : l'adresse publique (pour `og:image`), le Discord s'il existe, la navigation,
 * ce que la configuration dit du jeu ([settings]), la boutique des définitions ([shop], d'où viennent tous les prix),
 * le mode (la bulle d'Oooscar en dépend) et le bandeau direct ([direct], en mode ouvert seulement).
 */
internal class SiteShell(
    siteUrl: String,
    val discordUrl: String?,
    val nav: List<NavItem>,
    val settings: SiteSettings,
    val shop: ShopDefinitions,
    /** Toutes les définitions chargées : le wiki en tire ses tableaux (usines, raretés, incidents, entreprise). */
    val definitions: Definitions,
    val mode: SiteMode,
    val direct: DirectBand? = null,
) {
    /** L'adresse publique sans barre finale, prête à recevoir un chemin qui commence par `/`. */
    val siteUrl: String = siteUrl.trimEnd('/')
}
