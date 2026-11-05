package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.FakeDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les quatre pages légales (docs/site.md, « Légal ») : publiques, dans les deux modes du site. Ce sont des brouillons à
 * faire relire par Kr : les trous se marquent « À compléter par Kr. » et ne restent que sur les mentions légales et les
 * conditions de vente.
 */
class SiteLegalTest {
    private val mojang = "NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT."
    private val gap = "À compléter par Kr."

    /** [phrase] : ce qui reconnaît la page ; [hasGaps] : la page garde des trous pour Kr. */
    private class LegalPage(val path: String, val title: String, val phrase: String, val hasGaps: Boolean)

    private val pages = listOf(
        LegalPage("/mentions-legales/", "Mentions légales", mojang, hasGaps = true),
        LegalPage("/cgv/", "Conditions de vente", "L221-28", hasGaps = true),
        LegalPage("/confidentialite/", "Confidentialité", "trente jours", hasGaps = false),
        LegalPage("/parents/", "Note aux parents", "13 ans", hasGaps = false),
    )

    private fun withSite(mode: SiteMode, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application {
            siteModule(Dependencies(FakeDatabase(), "cle", definitions = TestDefinitions.loaded, siteMode = mode))
        }
        // Pas de suivi des redirections : un test regarde le statut lui-même.
        block(createClient { followRedirects = false })
    }

    /** Le contenu de la page, sans l'en-tête, la scène d'Oooscar ni le pied (qui portent la mention de Mojang partout). */
    private fun mainContent(html: String): String = html.substringAfter("<main").substringBefore("</main>")

    private fun assertWellFormed(html: String, where: String) {
        assertTrue(Regex("""<html[^>]*\blang="fr"""").containsMatchIn(html), "$where : lang=\"fr\" manque")
        assertEquals(1, Regex("<h1[ >]").findAll(html).count(), "$where : il faut exactement un h1")
        assertTrue(Regex("<title>[^<]+</title>").containsMatchIn(html), "$where : title vide")
        assertTrue(Regex("""<meta name="description" content="[^"]+"""").containsMatchIn(html), "$where : meta description vide")
        for (img in Regex("<img[^>]*>").findAll(html)) {
            assertTrue(Regex("""\balt="[^"]+"""").containsMatchIn(img.value), "$where : image sans alt : ${img.value}")
        }
        assertOnlySiteScripts(html, where)
    }

    @Test
    @DisplayName("Les quatre pages légales répondent 200 dans les deux modes, avec leur titre en h1 sur une plaque, et sont bien formées")
    fun everyLegalPageAnswers() {
        for (mode in SiteMode.entries) {
            withSite(mode) { client ->
                for (page in pages) {
                    val where = "${page.path} (${mode.word})"
                    val response = client.get(page.path)
                    assertEquals(HttpStatusCode.OK, response.status, where)
                    assertTrue(response.contentType()?.match(ContentType.Text.Html) == true, "$where : type reçu ${response.contentType()}")
                    val html = response.bodyAsText()
                    assertTrue(
                        Regex("""<h1 class="sa-plaque">\s*${Regex.escape(page.title)}\s*</h1>""").containsMatchIn(html),
                        "$where : le h1 « ${page.title} » sur une plaque",
                    )
                    assertWellFormed(html, where)
                }
            }
        }
    }

    @Test
    @DisplayName("Chaque page porte sa phrase : Mojang en capitales, L221-28, trente jours, 13 ans")
    fun everyPageHasItsPhrase() {
        for (mode in SiteMode.entries) {
            withSite(mode) { client ->
                for (page in pages) {
                    val body = mainContent(client.get(page.path).bodyAsText())
                    assertTrue(body.contains(page.phrase), "${page.path} (${mode.word}) : « ${page.phrase} » manque")
                }
                val legal = mainContent(client.get("/mentions-legales/").bodyAsText())
                assertTrue(legal.contains("Silooo est un serveur indépendant."), "mentions-legales (${mode.word})")
                assertTrue(legal.contains("OVH SAS, 2 rue Kellermann, 59100 Roubaix, France"), "mentions-legales (${mode.word}) : l'hébergeur")
                for (page in pages.drop(1)) {
                    assertFalse(mainContent(client.get(page.path).bodyAsText()).contains(mojang), "${page.path} (${mode.word}) : la phrase de Mojang est sur les mentions légales")
                }
            }
        }
    }

    @Test
    @DisplayName("« À compléter par Kr. » reste sur les mentions légales et les conditions de vente, jamais sur confidentialité ni parents")
    fun gapsOnlyWhereExpected() {
        for (mode in SiteMode.entries) {
            withSite(mode) { client ->
                for (page in pages) {
                    val html = client.get(page.path).bodyAsText()
                    val where = "${page.path} (${mode.word})"
                    if (page.hasGaps) {
                        assertTrue(mainContent(html).contains(gap), "$where : un trou pour Kr est attendu")
                    } else {
                        assertFalse(html.contains(gap), "$where : aucun trou pour Kr n'est prévu ici")
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Les mentions légales renvoient aux trois autres pages, qui renvoient aux mentions légales pour le contact")
    fun pagesPointToEachOther() {
        for (mode in SiteMode.entries) {
            withSite(mode) { client ->
                val legal = mainContent(client.get("/mentions-legales/").bodyAsText())
                for (target in listOf("/cgv/", "/confidentialite/", "/parents/")) {
                    assertTrue(legal.contains("""href="$target""""), "mentions-legales (${mode.word}) : lien vers $target")
                    val other = mainContent(client.get(target).bodyAsText())
                    assertTrue(other.contains("""href="/mentions-legales/""""), "$target (${mode.word}) : lien vers les mentions légales")
                }
            }
        }
    }

    @Test
    @DisplayName("Aucun lien d'une page légale ne mène à un 404, dans l'un ou l'autre mode")
    fun noBrokenLinks() {
        for (mode in SiteMode.entries) {
            withSite(mode) { client ->
                for (page in pages) {
                    val links = Regex("""href="(/[^"]*)"""").findAll(mainContent(client.get(page.path).bodyAsText())).map { it.groupValues[1] }.toSet()
                    assertTrue(links.isNotEmpty(), "${page.path} (${mode.word}) : la page renvoie au moins vers une autre page")
                    for (link in links) {
                        assertEquals(HttpStatusCode.OK, client.get(link).status, "${page.path} (${mode.word}) : le lien $link")
                    }
                }
            }
        }
    }
}
