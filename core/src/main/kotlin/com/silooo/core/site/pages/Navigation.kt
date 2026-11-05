package com.silooo.core.site.pages

import com.silooo.core.definitions.ShopDefinitions
import com.silooo.core.site.ContentPage
import com.silooo.core.site.SiteMode
import com.silooo.core.site.SiteSettings

/** Un lien de la navigation : l'adresse de la page et le mot qu'on lit sur le bouton. */
internal class NavItem(val url: String, val label: String)

/**
 * L'ordre de la navigation, écrit en une liste d'adresses. Une adresse n'apparaît que si le mode rend vraiment la
 * page (la liste des pages vient de `SiteContent`) : aucun lien ne mène à un 404. En mode `attente`, `/` est la
 * page d'attente et le logo y mène déjà ; l'entrée « Accueil » n'existe qu'en mode `ouvert`.
 */
internal object Navigation {
    private val ORDER = listOf(
        NavItem("/", "Accueil"),
        NavItem("/rejoindre/", "Rejoindre"),
        NavItem("/wiki/", "Wiki"),
        NavItem("/classements/", "Classements"),
        NavItem("/boutique/", "Boutique"),
        NavItem("/vote/", "Vote"),
        NavItem("/mises-a-jour/", "Mises à jour"),
        NavItem("/mentions/", "Mentions"),
    )

    fun items(mode: SiteMode, pages: List<ContentPage>): List<NavItem> {
        val rendered = pages.map { it.url }.toSet()
        return ORDER.filter { it.url in rendered && (it.url != "/" || mode == SiteMode.OUVERT) }
    }
}

/**
 * Ce que chaque page reçoit du site : l'adresse publique (pour `og:image`), le Discord s'il existe, la navigation,
 * ce que la configuration dit du jeu ([settings]) et la boutique des définitions ([shop], d'où viennent tous les prix).
 */
internal class SiteShell(
    siteUrl: String,
    val discordUrl: String?,
    val nav: List<NavItem>,
    val settings: SiteSettings,
    val shop: ShopDefinitions,
) {
    /** L'adresse publique sans barre finale, prête à recevoir un chemin qui commence par `/`. */
    val siteUrl: String = siteUrl.trimEnd('/')
}
