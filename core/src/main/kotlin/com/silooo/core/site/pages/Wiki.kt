package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import com.silooo.core.site.WikiContent
import kotlinx.html.DIV
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.li
import kotlinx.html.ol
import kotlinx.html.p

/**
 * Une page du wiki : le texte de `contenu/wiki/<sujet>.md` et, à la place d'un paragraphe seul `{{nom}}`, les blocs que
 * [wikiBlocks] tire des définitions. Aucun chiffre n'est tapé dans un contenu : un nombre vient du JSON ou n'est pas écrit.
 */
internal fun HTML.wikiPage(shell: SiteShell, page: ContentPage) =
    gabarit(shell, PageFrame(page.title, page.description, page.url)) {
        lecture(page.title) {
            avecInsertions(page.html, wikiBlocks(page.name.removePrefix("wiki/"), shell.definitions))
        }
    }

/**
 * L'index du wiki : le texte de `contenu/wiki/index.md`, puis à la place de `{{pages}}` un lien par page, dans l'ordre de
 * l'en-tête (`ordre`), chacun avec sa phrase de description. [pages] est la liste des pages du wiki que le mode rend.
 */
internal fun HTML.wikiIndex(shell: SiteShell, page: ContentPage, pages: List<ContentPage>) =
    gabarit(shell, PageFrame(page.title, page.description, page.url)) {
        lecture(page.title) {
            avecInsertions(page.html, mapOf("pages" to { sommaire(WikiContent.sorted(pages)) }))
        }
    }

private fun DIV.sommaire(pages: List<ContentPage>) {
    ol(classes = "sommaire") {
        for (page in pages) {
            li(classes = "sommaire__page") {
                a(href = page.url, classes = "sommaire__lien") { +page.title }
                p(classes = "sommaire__description") { +page.description }
            }
        }
    }
}
