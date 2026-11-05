package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.p

/**
 * La page du Discord (demande de Kr du 5 novembre 2026) : ce qu'on y trouve, écrit dans `discord.md`, et, à la place de
 * `{{rejoindre}}`, l'invitation. Elle existe dans les deux modes : la navigation de l'attente et le pied de chaque page
 * y mènent, qu'une invitation soit configurée ou non. Oooscar y dit sa phrase ([Bulles.DISCORD]).
 */
internal fun HTML.discord(shell: SiteShell, page: ContentPage) =
    gabarit(shell, PageFrame(page.title, page.description, page.url, bulle = Bulles.DISCORD)) {
        lecture(page.title) {
            avecInsertions(page.html, mapOf("rejoindre" to { invitation(shell) }))
        }
    }

/**
 * L'invitation : le bouton rouge vers l'adresse configurée (`SILOOO_SITE_URL_DISCORD`), le seul de la page. Sans adresse,
 * une phrase dit que l'invitation arrive : la page ne montre jamais un bouton qui ne mène nulle part.
 */
private fun FlowContent.invitation(shell: SiteShell) {
    val invite = shell.discordUrl
    if (invite != null) {
        p(classes = "retour") { a(href = invite, classes = "sa-bouton") { +"Rejoindre le Discord" } }
    } else {
        p { +"L'invitation au Discord arrive bientôt sur cette page." }
    }
}
