package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import kotlinx.html.HTML

/**
 * L'accueil minimal du mode ouvert : le même texte que l'attente, sans l'annonce d'ouverture. L'étape S2 le
 * remplace par le vrai accueil (adresse du serveur, joueurs en ligne, vidéo).
 */
internal fun HTML.accueil(page: ContentPage, discordUrl: String?) = gabarit(PageFrame(page.title, page.description)) {
    presentation(page, discordUrl)
}
