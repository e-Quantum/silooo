package com.silooo.core.site

import com.silooo.core.Config
import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.FakeDatabase
import com.silooo.core.site.pages.Navigation
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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

/** Le gabarit commun et le style de Silooo Atelier tels que le visiteur les reçoit : en-tête, navigation, pied, métadonnées. */
class SiteStyleTest {
    private fun withSite(
        mode: SiteMode,
        discordUrl: String? = null,
        siteUrl: String = Config.DEFAULT_SITE_URL,
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        application {
            siteModule(
                Dependencies(FakeDatabase(), "cle", definitions = TestDefinitions.loaded, siteMode = mode, discordUrl = discordUrl, siteUrl = siteUrl),
            )
        }
        block(createClient { followRedirects = false })
    }

    private fun navLabels(html: String): List<String> =
        Regex("""<a href="[^"]*" class="sa-bouton sa-bouton--fonte sa-bouton--nav"[^>]*>([^<]+)</a>""")
            .findAll(html).map { it.groupValues[1] }.toList()

    @Test
    @DisplayName("Chaque page porte og:title, og:description, og:image absolu, theme-color, l'icône et la feuille de style")
    fun headMetadata() = withSite(SiteMode.ATTENTE) { client ->
        for (path in listOf("/", "/mentions/", "/wiki/")) {
            val html = client.get(path).bodyAsText()
            assertTrue(Regex("""<meta property="og:title" content="[^"]+"""").containsMatchIn(html), "$path : og:title")
            assertTrue(Regex("""<meta property="og:description" content="[^"]+"""").containsMatchIn(html), "$path : og:description")
            assertTrue(html.contains("""<meta property="og:image" content="https://silooo.com/public/partage.png">"""), "$path : og:image absolu, l'image de partage")
            assertTrue(html.contains("""<meta property="og:image:width" content="1200">""") && html.contains("""<meta property="og:image:height" content="630">"""), "$path : la taille de l'image de partage")
            assertTrue(html.contains("""<meta name="twitter:card" content="summary_large_image">"""), "$path : l'aperçu en grande image")
            assertTrue(html.contains("""<meta name="theme-color" content="#8fb8d6">"""), "$path : theme-color")
            assertTrue(html.contains("""href="/public/logo/silooo-logo-64.png""""), "$path : icône du site")
            assertTrue(html.contains("""href="/public/site.css""""), "$path : feuille de style")
        }
    }

    @Test
    @DisplayName("L'og:image suit SILOOO_SITE_URL, sans barre en double")
    fun ogImageFollowsSiteUrl() = withSite(SiteMode.ATTENTE, siteUrl = "https://exemple.test/") { client ->
        val html = client.get("/").bodyAsText()
        assertTrue(html.contains("""content="https://exemple.test/public/partage.png""""), html.substringBefore("</head>"))
    }

    @Test
    @DisplayName("Navigation : en attente Pionniers puis Mentions, en ouvert les pages rendues")
    fun navigationByMode() {
        withSite(SiteMode.ATTENTE) { client ->
            for (path in listOf("/", "/mentions/", "/wiki/")) assertEquals(listOf("Pionniers", "Mentions"), navLabels(client.get(path).bodyAsText()), path)
        }
        withSite(SiteMode.OUVERT) { client ->
            val html = client.get("/mentions/").bodyAsText()
            assertEquals(listOf("Accueil", "Rejoindre", "Pionniers", "Wiki", "Classements", "Boutique", "Vote", "Mises à jour", "Mentions"), navLabels(html))
            assertEquals(1, Regex("""aria-current="page"""").findAll(html).count(), "la page courante est marquée une fois")
        }
    }

    @Test
    @DisplayName("Navigation : l'ordre est celui de la liste d'adresses, et une page absente n'a pas de lien")
    fun navigationOrder() {
        fun page(url: String) = ContentPage(url.trim('/'), "t", "d", url, SiteMode.entries.toSet(), "")
        val every = listOf("/mentions/", "/vote/", "/", "/wiki/", "/mises-a-jour/", "/boutique/", "/classements/", "/rejoindre/", "/pionniers/").map(::page)
        val all = listOf("/", "/rejoindre/", "/pionniers/", "/wiki/", "/classements/", "/boutique/", "/vote/", "/mises-a-jour/", "/mentions/")
        assertEquals(all, Navigation.items(SiteMode.OUVERT, every).map { it.url })
        assertEquals(all.drop(1), Navigation.items(SiteMode.ATTENTE, every).map { it.url })
        assertEquals(listOf("/mentions/"), Navigation.items(SiteMode.OUVERT, listOf(page("/mentions/"))).map { it.url })
    }

    @Test
    @DisplayName("La page d'attente montre Oooscar et une bulle de six mots au plus, et le gabarit a ses quatre zones")
    fun waitingPage() = withSite(SiteMode.ATTENTE) { client ->
        val html = client.get("/").bodyAsText()
        assertTrue(html.contains("""src="/public/atelier/oooscar/oooscar-joie.png""""))
        val bubble = Regex("""<p class="sa-bulle">([^<]+)</p>""").find(html)?.groupValues?.get(1) ?: error("pas de bulle")
        assertTrue(bubble.trim().split(Regex("\\s+")).size <= 6, "la bulle a plus de six mots : $bubble")
        for (tag in listOf("<header", "<nav", "<main", "<footer")) assertTrue(html.contains(tag), tag)
        assertFalse(html.contains("""class="sa-bouton">"""), "sans Discord configuré, la page n'a pas de bouton rouge")
    }

    @Test
    @DisplayName("Un seul bouton rouge par page : le Discord sur l'attente, le retour à l'accueil sur l'erreur, aucun sur les mentions")
    fun singleRedButton() = withSite(SiteMode.ATTENTE, discordUrl = "https://discord.gg/exemple") { client ->
        fun red(html: String) = Regex("""class="sa-bouton"""").findAll(html).count()
        assertEquals(1, red(client.get("/").bodyAsText()))
        assertEquals(0, red(client.get("/mentions/").bodyAsText()))
        assertEquals(1, red(client.get("/wiki/").bodyAsText()))
    }

    @Test
    @DisplayName("Les polices sont servies par le site avec le bon type et le cache d'un jour")
    fun fontsAreServed() = withSite(SiteMode.ATTENTE) { client ->
        for (path in listOf(
            "/public/polices/jersey-10/jersey-10-latin-400-normal.woff2",
            "/public/polices/pixelify-sans/pixelify-sans-latin-600-normal.woff2",
            "/public/polices/silkscreen/silkscreen-latin-400-normal.woff2",
        )) {
            val font = client.get(path)
            assertEquals(HttpStatusCode.OK, font.status, path)
            assertEquals("font/woff2", font.contentType()?.withoutParameters()?.toString(), path)
            assertEquals("public, max-age=86400", font.headers[HttpHeaders.CacheControl], path)
        }
    }
}
