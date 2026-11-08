package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.FakeDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
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
        // Chaque image dit ce qu'elle est, ou porte alt="" quand elle est décorative (les icônes des rangs : le titre dit déjà tout).
        for (img in Regex("<img[^>]*>").findAll(html)) {
            assertTrue(Regex("""\balt="[^"]*"""").containsMatchIn(img.value), "$where : image sans alt : ${img.value}")
        }
        assertOnlySiteScripts(html, where)
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
        // L'annonce est la bulle d'Oooscar (six mots au plus) ; le texte ne la répète pas, et l'ancienne phrase n'existe plus.
        val bubble = Regex("""<p class="sa-bulle"[^>]*>([^<]+)</p>""").find(html)?.groupValues?.get(1)
        assertEquals("On construit. Reviens vite !", bubble)
        assertFalse(html.substringAfter("<main").contains("ouvre bientôt"))
        assertFalse(html.contains("Le serveur ouvre bientôt !"))
        // L'accroche de la maquette, et « tycoon d'usines dans le ciel » dans la description du contenu.
        assertTrue(html.contains("Produis de jour comme de nuit"))
        assertTrue(Regex("""tycoon d(&#39;|')usines dans le ciel""").containsMatchIn(html))
        assertFalse(html.contains("play.silooo.com"))
        assertFalse(Regex("""19132|:\d{4,5}\b""").containsMatchIn(html.substringAfter("<main")), "pas de port de serveur")
        assertWellFormedPage(html, "/")
    }

    @Test
    @DisplayName("Le Discord : la navigation de l'attente et le pied mènent à sa page ; l'invitation n'est pas sur l'accueil")
    fun discordLink() {
        for (discordUrl in listOf(null, "https://discord.gg/exemple")) {
            withSite(SiteMode.ATTENTE, discordUrl = discordUrl) { client ->
                val html = client.get("/").bodyAsText()
                // La page du Discord (demande de Kr du 5 novembre 2026) porte l'invitation : l'accueil n'en cite pas l'adresse.
                assertFalse(html.contains("discord.gg"), "l'invitation est sur /discord/, pas sur l'accueil")
                assertTrue(Regex("""<a href="/discord/" class="navigation__lien">Discord</a>""").containsMatchIn(html))
                assertTrue(html.substringAfter("<footer").contains("""<a href="/discord/">Discord Silooo</a>"""))
            }
        }
    }

    @Test
    @DisplayName("Les mentions légales répondent dans les deux modes, avec la mention de Mojang et l'hébergeur")
    fun legalMentions() {
        for (mode in SiteMode.entries) {
            withSite(mode) { client ->
                val response = client.get("/mentions-legales/")
                assertEquals(HttpStatusCode.OK, response.status, mode.word)
                val html = response.html()
                assertTrue(html.contains(mojang), mode.word)
                assertTrue(html.contains("Silooo est un serveur indépendant."), mode.word)
                assertTrue(html.contains("OVH SAS"), mode.word)
                assertTrue(html.contains("À compléter par Kr."), mode.word)
                assertWellFormedPage(html, "/mentions-legales/ ($mode)")
            }
        }
    }

    @Test
    @DisplayName("Chaque page porte la mention de Mojang et les quatre liens légaux dans son pied")
    fun footerOnEveryPage() = withSite(SiteMode.ATTENTE) { client ->
        for (path in listOf("/", "/mentions-legales/", "/cgv/", "/confidentialite/", "/parents/", "/wiki/")) {
            val html = client.get(path).bodyAsText()
            val footer = html.substringAfter("<footer")
            assertTrue(footer.contains(mojang), path)
            assertTrue(footer.contains("""href="/mentions-legales/""""), path)
            assertTrue(footer.contains(">Mentions légales</a>"), path)
            assertTrue(footer.contains("""<a href="/cgv/">CGV</a>"""), path)
            assertTrue(footer.contains("""<a href="/confidentialite/">Confidentialité</a>"""), path)
            assertTrue(footer.contains("""<a href="/parents/">Parents</a>"""), path)
        }
    }

    @Test
    @DisplayName("/mentions-legales redirige en 301 vers /mentions-legales/, et de même pour les trois autres pages légales")
    fun redirectsWithoutTrailingSlash() = withSite(SiteMode.ATTENTE) { client ->
        for (path in listOf("/mentions-legales", "/cgv", "/confidentialite", "/parents")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.MovedPermanently, response.status, path)
            assertEquals("$path/", response.headers[HttpHeaders.Location], path)
        }
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
            assertTrue(html.contains("""data-humeur="oups""""), "$path : l'humeur oups")
            val bubble = Regex("""<p class="sa-bulle"[^>]*>([^<]+)</p>""").find(html)?.groupValues?.get(1)
            assertEquals("Cette page s'est envolée.", bubble?.replace("&#39;", "'"), path)
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
            for (expected in listOf("https://silooo.com/", "https://silooo.com/mentions-legales/", "https://silooo.com/cgv/", "https://silooo.com/confidentialite/", "https://silooo.com/parents/", "https://silooo.com/pionniers/")) assertTrue(expected in locs, "$expected manque : $locs")
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
    @DisplayName("Bêta : / reste la page d'attente, les pages de l'ouverture répondent, le sitemap les liste, /classements/ lit la base")
    fun betaShowsEverythingButTheHome() = withSite(SiteMode.BETA) { client ->
        val home = client.get("/")
        assertEquals(HttpStatusCode.OK, home.status)
        val html = home.html()
        assertEquals("On construit. Reviens vite !", Regex("""<p class="sa-bulle"[^>]*>([^<]+)</p>""").find(html)?.groupValues?.get(1))
        assertTrue(html.contains("Produis de jour comme de nuit"))
        assertFalse(html.contains("Tu hérites d&#39;une carrière abandonnée") || html.contains("Tu hérites d'une carrière abandonnée"), "ce n'est pas l'accueil")
        assertWellFormedPage(html, "/ (bêta)")
        for (path in listOf("/rejoindre/", "/ce-qui-se-vend/", "/boutique/", "/vote/", "/wiki/", "/actus/", "/discord/", "/mentions-legales/", "/cgv/", "/confidentialite/", "/parents/")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status, path)
            assertWellFormedPage(response.html(), "$path (bêta)")
        }
        // Les classements existent en bêta : la route lit la base, que FakeDatabase refuse (page d'erreur), au lieu d'un 404.
        assertEquals(HttpStatusCode.InternalServerError, client.get("/classements/").status)
        val locs = Regex("<loc>([^<]+)</loc>").findAll(client.get("/sitemap.xml").bodyAsText()).map { it.groupValues[1] }.toList()
        for (expected in listOf("https://silooo.com/", "https://silooo.com/wiki/", "https://silooo.com/classements/", "https://silooo.com/boutique/", "https://silooo.com/rejoindre/")) {
            assertTrue(expected in locs, "$expected manque : $locs")
        }
        assertEquals(locs.size, locs.toSet().size, "chaque adresse une fois")
    }

    @Test
    @DisplayName("Un fichier de /public/ porte l'ETag de son contenu : 304 sans corps quand il n'a pas changé, 200 sinon")
    fun publicFilesRevalidate() = withSite(SiteMode.ATTENTE) { client ->
        for (path in listOf("/public/site.css", "/public/ile/carriere.js", "/public/ile/three.module.min.js")) {
            val first = client.get(path)
            assertEquals(HttpStatusCode.OK, first.status, path)
            val etag = first.headers[HttpHeaders.ETag]
            assertTrue(etag != null && etag.length > 40, "$path : un ETag tiré du contenu, et non vide ($etag)")
            val again = client.get(path) { header(HttpHeaders.IfNoneMatch, etag) }
            assertEquals(HttpStatusCode.NotModified, again.status, "$path : inchangé, 304")
            assertTrue(again.bodyAsText().isEmpty(), "$path : un 304 n'a pas de corps")
            val stale = client.get(path) { header(HttpHeaders.IfNoneMatch, "\"ancienne-version\"") }
            assertEquals(HttpStatusCode.OK, stale.status, "$path : une autre version, le fichier entier")
        }
    }

    @Test
    @DisplayName("Le CSS et les logos sont servis avec le bon type, gardés mais revalidés à chaque visite (no-cache)")
    fun staticFiles() = withSite(SiteMode.ATTENTE) { client ->
        val css = client.get("/public/site.css")
        assertEquals(HttpStatusCode.OK, css.status)
        assertEquals(ContentType.Text.CSS, css.contentType()?.withoutParameters())
        assertEquals("no-cache", css.headers[HttpHeaders.CacheControl])
        for (name in listOf("silooo-logo-128.png", "silooo-logo-64.png")) {
            val logo = client.get("/public/logo/$name")
            assertEquals(HttpStatusCode.OK, logo.status, name)
            assertEquals(ContentType.Image.PNG, logo.contentType()?.withoutParameters(), name)
        }
    }

    @Test
    @DisplayName("Les en-têtes de sécurité et de cache sont posés sur les pages et sur les fichiers")
    fun headers() = withSite(SiteMode.ATTENTE) { client ->
        for (path in listOf("/", "/mentions-legales/", "/public/site.css", "/wiki/")) {
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
            assertEquals(if (path.startsWith("/public/")) "no-cache" else "no-store", cache, path)
        }
    }
}
