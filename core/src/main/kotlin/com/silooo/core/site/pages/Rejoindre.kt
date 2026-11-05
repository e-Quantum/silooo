package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import kotlinx.html.HTML

/**
 * Rejoindre : le texte de `rejoindre.md` (la marche à suivre par plate-forme) et, à la place de `{{adresse}}`,
 * l'adresse et le port de la configuration. Aucun port n'est écrit dans le contenu.
 */
internal fun HTML.rejoindre(shell: SiteShell, page: ContentPage) =
    gabarit(shell, PageFrame(page.title, page.description, page.url)) {
        lecture(page.title) {
            avecInsertions(page.html, mapOf("adresse" to { adresseDuServeur(shell.settings) }))
        }
    }
