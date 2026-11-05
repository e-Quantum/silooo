package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.FakeDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
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

/** Le site public sans base ni Docker : le module du site reçoit un faux [FakeDatabase] et ne s'en sert pas. */
class SiteModuleTest {
    private val mojang = "NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT."

    private fun withSite(
        mode: SiteMode,
        discordUrl: String? = null,
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        application {
            siteModule(Dependencies(FakeDatabase(), "cle", definitions = TestDefinitions.loaded, siteMode = mode, discordUrl = discordUrl))
        }
        // Pas de suivi des redirections : un test regarde le 301 lui-même.
        block(createClient { followRedirects = false })
    }

    private fun assertWellFormedPage(html: String, where: String) {
        assertTrue(Regex("""<html[^>]*\blang="fr"""").containsMatchIn(html), "$where : lang=\"fr\" manque")
        assertEquals(1, Regex("<h1[ >]").findAll(html).count(), "$where : il faut exactement un h1")
        assertTrue(Regex("<title>[^<]+</title>").containsMatchIn(html), "$where : title vide")
        assertTrue(Regex("""<meta name="description" content="[^"]+"""").containsMatchIn(html), "$where : meta description vide")
        for (img in Regex("<img[^>]*>").findAll(html)) {
            assertTrue(Regex("""\balt="[^"]+"""").containsMatchIn(img.value), "$where : image sans alt : ${img.value}")
        }
        assertFalse(html.contains("<script", ignoreCase = true), "$where : aucun script dans cette étape")
    }

    private suspend fun HttpResponse.html(): String {
        assertTrue(contentType()?.match(ContentType.Text.Html) == true, "type reçu : ${contentType()}")
        return bodyAsText()
    }

    @Test
    @DisplayName("Attente : / montre la phrase d'attente, sans adresse de serveur")
    fun waitingHome() = withSite(SiteMode.ATTENTE) { client ->
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val html = response.html()
        // L'annonce est la bulle d'Oooscar (six mots au plus) ; le texte ne la répète pas.
        assertTrue(html.substringAfter("<main").contains("Le serveur ouvre bientôt !"))
        assertFalse(html.substringAfter("<main").contains("Le serveur ouvre bientôt."))
        assertTrue(html.contains("Un tycoon d&#39;usines dans le ciel") || html.contains("Un tycoon d'usines dans le ciel"))
        assertFalse(html.contains("play.silooo.com"))
        assertFalse(Regex("""19132|:\d{4,5}\b""").containsMatchIn(html.substringAfter("<main")), "pas de port de serveur")
        assertWellFormedPage(html, "/")
    }

    @Test
    @DisplayName("Le lien Discord n'apparaît que s'il est configuré")
    fun discordLink() {
        withSite(SiteMode.ATTENTE) { client -> assertFalse(client.get("/").bodyAsText().contains("Discord")) }
        withSite(SiteMode.ATTENTE, discordUrl = "https://discord.gg/exemple") { client ->
            val html = client.get("/").bodyAsText()
            assertTrue(Regex("""<a href="https://discord.gg/exemple"[^>]*>Rejoins le Discord</a>""").containsMatchIn(html))
        }
    }

    @Test
    @DisplayName("Les mentions répondent dans les deux modes, avec la mention de Mojang")
    fun mentions() {
        for (mode in SiteMode.entries) {
            withSite(mode) { client ->
                val response = client.get("/mentions/")
                assertEquals(HttpStatusCode.OK, response.status, mode.word)
                val html = response.html()
                assertTrue(html.contains(mojang), mode.word)
                assertTrue(html.contains("Silooo est un serveur indépendant."), mode.word)
                assertTrue(html.contains("À compléter par Kr."), mode.word)
                assertWellFormedPage(html, "/mentions/ ($mode)")
            }
        }
    }

    @Test
    @DisplayName("Chaque page porte la mention de Mojang dans son pied")
    fun footerOnEveryPage() = withSite(SiteMode.ATTENTE) { client ->
        for (path in listOf("/", "/mentions/", "/wiki/")) {
            val html = client.get(path).bodyAsText()
            assertTrue(html.substringAfter("<footer").contains(mojang), path)
            assertTrue(html.substringAfter("<footer").contains("""href="/mentions/""""), path)
        }
    }

    @Test
    @DisplayName("/mentions redirige en 301 vers /mentions/")
    fun redirectsWithoutTrailingSlash() = withSite(SiteMode.ATTENTE) { client ->
        val response = client.get("/mentions")
        assertEquals(HttpStatusCode.MovedPermanently, response.status)
        assertEquals("/mentions/", response.headers[HttpHeaders.Location])
    }

    @Test
    @DisplayName("Une adresse inconnue répond 404 avec une page Silooo, en HTML")
    fun notFoundIsAPage() = withSite(SiteMode.ATTENTE) { client ->
        for (path in listOf("/wiki/", "/public/absent.css", "/public/")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.NotFound, response.status, path)
            val html = response.html()
            assertTrue(html.contains("Page introuvable"), path)
            // Oooscar, l'air surpris, porte la phrase du kit dans sa bulle.
            assertTrue(html.contains("/public/atelier/oooscar/oooscar-oups.png"), "$path : le visage oups")
            assertTrue(html.contains("Cette page s'est envolée.") || html.contains("Cette page s&#39;est envolée."), path)
            assertTrue(html.contains("Retour à l'accueil") || html.contains("Retour à l&#39;accueil"), path)
            assertWellFormedPage(html, "404 $path")
        }
    }

    @Test
    @DisplayName("robots.txt autorise tout et nomme le sitemap ; sitemap.xml liste les pages du mode, une fois chacune")
    fun robotsAndSitemap() {
        fun locations(xml: String) = Regex("<loc>([^<]+)</loc>").findAll(xml).map { it.groupValues[1] }.toList()
        withSite(SiteMode.ATTENTE) { client ->
            val robots = client.get("/robots.txt")
            assertEquals(HttpStatusCode.OK, robots.status)
            assertEquals(ContentType.Text.Plain, robots.contentType()?.withoutParameters())
            assertEquals("User-agent: *\nAllow: /\nSitemap: https://silooo.com/sitemap.xml\n", robots.bodyAsText())
            assertEquals("public, max-age=3600", robots.headers[HttpHeaders.CacheControl])
            val sitemap = client.get("/sitemap.xml")
            assertEquals(HttpStatusCode.OK, sitemap.status)
            assertEquals(ContentType.Application.Xml, sitemap.contentType()?.withoutParameters())
            val xml = sitemap.bodyAsText()
            assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">"), xml)
            val locs = locations(xml)
            for (expected in listOf("https://silooo.com/", "https://silooo.com/mentions/", "https://silooo.com/pionniers/")) assertTrue(expected in locs, "$expected manque : $locs")
            assertTrue(locs.none { it.contains("/wiki/") || it.contains("/classements/") }, "en attente, ni wiki ni classements : $locs")
            assertEquals(locs.size, locs.toSet().size, "chaque adresse une fois")
        }
        withSite(SiteMode.OUVERT) { client ->
            val locs = locations(client.get("/sitemap.xml").bodyAsText())
            for (expected in listOf("https://silooo.com/wiki/", "https://silooo.com/classements/", "https://silooo.com/boutique/", "https://silooo.com/rejoindre/")) assertTrue(expected in locs, "$expected manque : $locs")
            assertTrue(locs.none { it.contains("/entreprise/") }, "les fiches d'entreprise ne sont pas listées")
        }
    }

    @Test
    @DisplayName("Le site ne connaît pas l'API : /v1 répond 404")
    fun noApiOnTheSite() = withSite(SiteMode.OUVERT) { client ->
        val response = client.get("/v1/server/heartbeat")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.html().contains("Page introuvable"))
    }

    @Test
    @DisplayName("Ouvert : / est l'accueil minimal, sans l'annonce d'ouverture")
    fun openHome() = withSite(SiteMode.OUVERT) { client ->
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val html = response.html()
        assertFalse(html.contains("ouvre bientôt"))
        assertTrue(html.contains("Tu hérites d&#39;une carrière abandonnée") || html.contains("Tu hérites d'une carrière abandonnée"))
        assertWellFormedPage(html, "/ (ouvert)")
    }

    @Test
    @DisplayName("Le CSS et les logos sont servis avec le bon type et un cache d'un jour")
    fun staticFiles() = withSite(SiteMode.ATTENTE) { client ->
        val css = client.get("/public/site.css")
        assertEquals(HttpStatusCode.OK, css.status)
        assertEquals(ContentType.Text.CSS, css.contentType()?.withoutParameters())
        assertEquals("public, max-age=86400", css.headers[HttpHeaders.CacheControl])
        for (name in listOf("silooo-logo-128.png", "silooo-logo-64.png")) {
            val logo = client.get("/public/logo/$name")
            assertEquals(HttpStatusCode.OK, logo.status, name)
            assertEquals(ContentType.Image.PNG, logo.contentType()?.withoutParameters(), name)
        }
    }

    @Test
    @DisplayName("Les en-têtes de sécurité et de cache sont posés sur les pages et sur les fichiers")
    fun headers() = withSite(SiteMode.ATTENTE) { client ->
        for (path in listOf("/", "/mentions/", "/public/site.css", "/wiki/")) {
            val response = client.get(path)
            assertEquals("nosniff", response.headers["X-Content-Type-Options"], path)
            assertEquals("same-origin", response.headers["Referrer-Policy"], path)
            val csp = response.headers["Content-Security-Policy"] ?: ""
            assertEquals(
                "default-src 'self'; img-src 'self' data:; style-src 'self'; font-src 'self'; script-src 'self'; frame-ancestors 'none'",
                csp,
                path,
            )
            val cache = response.headers[HttpHeaders.CacheControl]
            assertEquals(if (path.startsWith("/public/")) "public, max-age=86400" else "no-store", cache, path)
        }
    }
}
