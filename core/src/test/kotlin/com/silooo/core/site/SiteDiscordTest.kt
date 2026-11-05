package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.FakeDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
 * La page du Discord (`/discord/`, demande de Kr du 5 novembre 2026) : publique, dans les deux modes, elle dit ce qu'on
 * trouve sur le Discord et porte l'invitation quand l'adresse est configurée. La navigation de l'attente et le pied de
 * chaque page y mènent. La phrase d'Oooscar est à valider par Kr.
 */
class SiteDiscordTest {
    private val invitation = "https://discord.gg/exemple"
    private val bubble = "Sur Discord aussi, c'est moi."
    private val absence = "L'invitation au Discord arrive bientôt sur cette page."

    private fun withSite(mode: SiteMode, discordUrl: String? = null, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application {
            siteModule(Dependencies(FakeDatabase(), "cle", definitions = TestDefinitions.loaded, siteMode = mode, discordUrl = discordUrl))
        }
        // Pas de suivi des redirections : un test regarde le statut lui-même.
        block(createClient { followRedirects = false })
    }

    private fun unescape(text: String): String =
        text.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** Le contenu de la page, sans l'en-tête, la navigation, la scène d'Oooscar ni le pied. */
    private fun mainContent(html: String): String = html.substringAfter("<main").substringBefore("</main>")

    private fun footer(html: String): String = html.substringAfter("<footer").substringBefore("</footer>")

    private fun navigation(html: String): String = html.substringAfter("""<nav class="navigation"""").substringBefore("</nav>")

    /** Les boutons rouges : `class="sa-bouton"` suivi du guillemet. La navigation et le menu portent `sa-bouton sa-bouton--fonte`. */
    private fun redButtons(html: String): Int = Regex("""class="sa-bouton"""").findAll(html).count()

    @Test
    @DisplayName("/discord/ répond 200 dans les deux modes, avec le h1 sur une plaque, sans script hors du gabarit")
    fun pageAnswersInBothModes() {
        for (mode in SiteMode.entries) {
            withSite(mode) { client ->
                val where = "/discord/ (${mode.word})"
                val response = client.get("/discord/")
                assertEquals(HttpStatusCode.OK, response.status, where)
                assertTrue(response.contentType()?.match(ContentType.Text.Html) == true, "$where : type reçu ${response.contentType()}")
                val html = response.bodyAsText()
                assertTrue(Regex("""<html[^>]*\blang="fr"""").containsMatchIn(html), "$where : lang=\"fr\" manque")
                assertEquals(1, Regex("<h1[ >]").findAll(html).count(), "$where : il faut exactement un h1")
                assertTrue(
                    Regex("""<h1 class="sa-plaque">\s*Le Discord de Silooo\s*</h1>""").containsMatchIn(html),
                    "$where : le h1 « Le Discord de Silooo » sur une plaque",
                )
                assertTrue(html.contains("<title>Le Discord de Silooo · Silooo</title>"), "$where : le title")
                assertTrue(Regex("""<meta name="description" content="[^"]+"""").containsMatchIn(html), "$where : meta description vide")
                assertOnlySiteScripts(html, where)
            }
        }
    }

    @Test
    @DisplayName("/discord redirige en 301 vers /discord/, dans les deux modes")
    fun missingSlashRedirects() {
        for (mode in SiteMode.entries) {
            withSite(mode) { client ->
                val response = client.get("/discord")
                assertEquals(HttpStatusCode.MovedPermanently, response.status, mode.word)
                assertEquals("/discord/", response.headers[HttpHeaders.Location], mode.word)
            }
        }
    }

    @Test
    @DisplayName("Sans adresse configurée : aucun bouton rouge, la phrase qui dit que l'invitation arrive, aucun lien vers l'extérieur")
    fun withoutInvitation() {
        for (mode in SiteMode.entries) {
            withSite(mode) { client ->
                val where = "/discord/ (${mode.word})"
                val html = client.get("/discord/").bodyAsText()
                val main = unescape(mainContent(html))
                assertEquals(0, redButtons(mainContent(html)), "$where : pas de bouton rouge dans <main>")
                assertEquals(0, redButtons(html), "$where : pas de bouton rouge dans la page")
                assertTrue(main.contains(absence), "$where : la phrase d'absence manque")
                assertFalse(main.contains("Rejoindre le Discord"), "$where : pas de bouton sans adresse")
                assertFalse(Regex("""href="https?://""").containsMatchIn(main), "$where : aucun lien vers l'extérieur")
            }
        }
    }

    @Test
    @DisplayName("Avec une adresse configurée : exactement un bouton rouge « Rejoindre le Discord » vers cette adresse, en haut, sans phrase d'absence")
    fun withInvitation() {
        val anchor = """<a href="${Regex.escape(invitation)}" class="sa-bouton">Rejoindre le Discord</a>"""
        for (mode in SiteMode.entries) {
            withSite(mode, discordUrl = invitation) { client ->
                val where = "/discord/ (${mode.word})"
                val html = client.get("/discord/").bodyAsText()
                val main = mainContent(html)
                assertEquals(1, redButtons(main), "$where : un seul bouton rouge dans <main>")
                assertEquals(1, redButtons(html), "$where : un seul bouton rouge dans la page")
                assertEquals(1, Regex(anchor).findAll(main).count(), "$where : le bouton mène à l'adresse configurée")
                assertTrue(Regex("""<p class="retour">\s*$anchor""").containsMatchIn(main), "$where : le bouton est dans p.retour")
                // Une seule fois, en haut : après la première phrase, avant la première section.
                val button = main.indexOf("""class="sa-bouton"""")
                val firstSentence = main.indexOf("Le Discord de Silooo est l")
                assertTrue(firstSentence in 0 until button, "$where : le bouton vient après la première phrase")
                assertTrue(button < main.indexOf("<h2"), "$where : le bouton vient avant la première section")
                assertFalse(unescape(main).contains(absence), "$where : la phrase d'absence n'a plus lieu d'être")
            }
        }
    }

    @Test
    @DisplayName("La bulle d'Oooscar est « Sur Discord aussi, c'est moi. » : data-bulle et le paragraphe de statut, six mots au plus")
    fun oooscarBubble() {
        for (mode in SiteMode.entries) {
            withSite(mode) { client ->
                val where = "/discord/ (${mode.word})"
                val html = client.get("/discord/").bodyAsText()
                val scene = html.substring(html.indexOf("""<div class="scene">"""), html.indexOf("<footer"))
                val tag = Regex("""<div class="oooscar"[^>]*>""").find(scene)?.value ?: error("$where : pas de div.oooscar")
                val data = Regex("""\bdata-bulle="([^"]*)"""").find(tag)?.groupValues?.get(1) ?: error("$where : data-bulle manque")
                assertEquals(bubble, unescape(data), "$where : data-bulle")
                val paragraph = Regex("""<p class="sa-bulle"[^>]*>([^<]+)</p>""").find(scene) ?: error("$where : pas de p.sa-bulle")
                assertEquals(bubble, unescape(paragraph.groupValues[1]).trim(), "$where : le paragraphe de la bulle")
                assertTrue(bubble.trim().split(Regex("\\s+")).size in 1..6, "$where : la bulle a plus de six mots")
            }
        }
    }

    @Test
    @DisplayName("En attente, la navigation a un lien /discord/ (marqué courant sur la page elle-même) ; l'invitation configurée n'y change rien")
    fun waitingNavigationLinksToThePage() {
        for (discordUrl in listOf(null, invitation)) {
            withSite(SiteMode.ATTENTE, discordUrl = discordUrl) { client ->
                val where = "attente, adresse ${discordUrl ?: "absente"}"
                val home = navigation(client.get("/").bodyAsText())
                assertTrue(Regex("""<a href="/discord/" class="navigation__lien">Discord</a>""").containsMatchIn(home), "$where : le lien Discord de la navigation")
                assertEquals(1, Regex("""href="/discord/"""").findAll(home).count(), "$where : un seul lien /discord/ dans la navigation")
                val own = navigation(client.get("/discord/").bodyAsText())
                assertTrue(
                    Regex("""<a href="/discord/"[^>]*aria-current="page"[^>]*>Discord</a>""").containsMatchIn(own),
                    "$where : sur sa page, le lien Discord est marqué courant",
                )
            }
        }
    }

    @Test
    @DisplayName("Le pied de l'accueil et de /discord/ a « Discord Silooo » vers /discord/, dans les deux modes, avec ou sans adresse configurée")
    fun footerLinksToThePage() {
        val link = """<a href="/discord/">Discord Silooo</a>"""
        for (mode in SiteMode.entries) {
            for (discordUrl in listOf(null, invitation)) {
                withSite(mode, discordUrl = discordUrl) { client ->
                    for (path in listOf("/", "/discord/")) {
                        val where = "$path (${mode.word}, adresse ${discordUrl ?: "absente"})"
                        val footer = footer(client.get(path).bodyAsText())
                        assertTrue(footer.contains(link), "$where : « Discord Silooo » mène à /discord/")
                        assertTrue(footer.indexOf(link) < footer.indexOf("""<a href="/mentions-legales/">"""), "$where : avant les pages légales")
                        assertFalse(footer.contains("discord.gg"), "$where : le pied ne porte pas l'adresse externe")
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("La page tient en six sections au plus, dit les 13 ans, n'a aucun marqueur resté en l'état et aucun lien mort")
    fun contentIsShortAndLinked() {
        for (mode in SiteMode.entries) {
            withSite(mode, discordUrl = invitation) { client ->
                val where = "/discord/ (${mode.word})"
                val main = mainContent(client.get("/discord/").bodyAsText())
                assertTrue(Regex("<h2[ >]").findAll(main).count() in 1..6, "$where : entre une et six sections")
                assertTrue(main.contains("13 ans"), "$where : la règle des 13 ans")
                assertFalse(main.contains("{{") || main.contains("}}"), "$where : un marqueur {{...}} est resté dans la page")
                val links = Regex("""href="(/[^"]*)"""").findAll(main).map { it.groupValues[1] }.toSet()
                assertTrue(links.contains("/parents/"), "$where : la note aux parents")
                for (link in links) assertEquals(HttpStatusCode.OK, client.get(link).status, "$where : le lien $link")
            }
        }
    }

    @Test
    @DisplayName("Aucun emoji, tiret cadratin, point de suspension ni série de « ! » dans le contenu de la page")
    fun noPlayerTextSlop() {
        val emoji = Regex("""[\x{1F000}-\x{1FAFF}☀-➿⭐⭕️‍]""")
        for (mode in SiteMode.entries) {
            for (discordUrl in listOf(null, invitation)) {
                withSite(mode, discordUrl = discordUrl) { client ->
                    val where = "/discord/ (${mode.word}, adresse ${discordUrl ?: "absente"})"
                    val main = unescape(mainContent(client.get("/discord/").bodyAsText()))
                    assertFalse(emoji.containsMatchIn(main), "$where : un emoji")
                    assertFalse(main.contains('—'), "$where : un tiret cadratin")
                    assertFalse(main.contains('…') || main.contains("..."), "$where : des points de suspension")
                    assertFalse(main.contains("!!"), "$where : une série de « ! »")
                }
            }
        }
    }
}
