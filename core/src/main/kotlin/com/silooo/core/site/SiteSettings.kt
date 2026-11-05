package com.silooo.core.site

import com.silooo.core.Config

/** Un site où voter pour le serveur : le nom du bouton et l'adresse où il mène. */
data class VoteSite(val name: String, val url: String)

/**
 * Ce que les pages publiques affichent du jeu et qui vient de la configuration, pas des définitions : l'adresse et le
 * port à saisir dans Minecraft, l'adresse où s'achètent les Gemmes, les sites de vote. Les défauts sont ceux du jeu
 * (voir [Config.DEFAULT_GAME_PORT_SHOWN]) ; une boutique ou des sites de vote non posés ne montrent aucun lien.
 */
data class SiteSettings(
    /** `SILOOO_SITE_ADRESSE_JEU` : l'adresse du serveur de jeu. */
    val gameAddress: String = Config.DEFAULT_GAME_ADDRESS_SHOWN,
    /** `SILOOO_SITE_PORT_JEU` : le port du serveur de jeu. */
    val gamePort: Int = Config.DEFAULT_GAME_PORT_SHOWN,
    /** `SILOOO_SITE_URL_BOUTIQUE` : où s'achètent les Gemmes ; `null` tant que Kr ne l'a pas donnée. */
    val shopUrl: String? = null,
    /** `SILOOO_SITE_SITES_DE_VOTE` : les sites de vote, dans l'ordre donné. */
    val voteSites: List<VoteSite> = emptyList(),
)
