package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import kotlinx.html.HTML

/**
 * L'accueil minimal du mode ouvert : le même texte que l'attente, sans l'annonce d'ouverture ni Oooscar. L'étape S2
 * le remplace par le vrai accueil (adresse du serveur, joueurs en ligne, vidéo).
 */
internal fun HTML.accueil(shell: SiteShell, page: ContentPage) =
    gabarit(shell, PageFrame(page.title, page.description, page.url)) { presentation(page, shell) }
