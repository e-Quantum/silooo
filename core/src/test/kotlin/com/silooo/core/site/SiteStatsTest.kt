package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.server.ServerPresence
import com.silooo.core.sitedata.CompanyCard
import com.silooo.core.sitedata.CompanyMember
import com.silooo.core.sitedata.InMemorySiteStatsStore
import com.silooo.core.sitedata.MemberRole
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Les données vivantes du site (accueil, classements, fiches) sans base ni Docker : faux dépôt, horloge à la main. */
class SiteStatsTest {
    /** Un XUID reconnaissable : il est dans le faux dépôt, aucune page ne doit le montrer. */
    private val xuid = "2535409999999999"

    private class Rig(val store: InMemorySiteStatsStore, val clock: MutableClock, val presence: ServerPresence, val client: HttpClient) {
        val seen = mutableListOf<String>()

        suspend fun page(path: String): Pair<HttpResponse, String> {
            val response = client.get(path)
            val html = response.bodyAsText().replace("&#39;", "'")
            seen += html
            return response to html
        }
    }

    private fun withRig(
        mode: SiteMode = SiteMode.OUVERT,
        now: Instant = Instant.parse("2026-11-05T12:00:00Z"),
        block: suspend ApplicationTestBuilder.(Rig) -> Unit,
    ) = testApplication {
        val store = InMemorySiteStatsStore()
        val clock = MutableClock(now)
        val presence = ServerPresence(clock)
        application {
            siteModule(
                Dependencies(
                    PassThroughDatabase(), "cle", serverPresence = presence, definitions = TestDefinitions.loaded, clock = clock,
                    siteMode = mode, siteStatsStore = store,
                ),
            )
        }
        val rig = Rig(store, clock, presence, createClient { followRedirects = false })
        block(rig)
        for (html in rig.seen) assertFalse(html.contains(xuid), "un XUID est sorti dans une page")
    }

    private fun InMemorySiteStatsStore.player(gamertag: String, lingots: Long, company: Pair<Long, String>? = null) {
        entries += InMemorySiteStatsStore.Entry(xuid, gamertag, company?.first, company?.second, lingots)
    }

    // --- l'accueil

    @Test
    @DisplayName("Accueil : un battement récent donne le nombre de joueurs en ligne, sans doublon d'un serveur à l'autre")
    fun onlineCount() = withRig { rig ->
        rig.presence.record("bds-1", listOf("1", "2", "3"))
        rig.presence.record("bds-2", listOf("3", "4"))
        assertTrue(rig.page("/").second.contains("4 joueurs en ligne"))
        rig.presence.record("bds-1", listOf("1"))
        rig.presence.record("bds-2", listOf())
        assertTrue(rig.page("/").second.contains("1 joueur en ligne"))
        rig.presence.record("bds-1", listOf())
        assertTrue(rig.page("/").second.contains("Personne en ligne pour l'instant."))
    }

    @Test
    @DisplayName("Accueil : sans battement, « Serveur fermé pour l'instant. » ; un battement vieux de plus de deux minutes compte pour rien")
    fun serverClosed() = withRig { rig ->
        assertTrue(rig.page("/").second.contains("Serveur fermé pour l'instant."))
        rig.presence.record("bds-1", listOf("1", "2"))
        rig.clock.advance(Duration.ofSeconds(110))
        // Au-delà de 30 secondes `join` ne les compte plus, mais le site garde deux minutes de tolérance.
        assertTrue(rig.page("/").second.contains("2 joueurs en ligne"))
        rig.clock.advance(Duration.ofSeconds(20))
        assertTrue(rig.page("/").second.contains("Serveur fermé pour l'instant."))
    }

    // --- la semaine

    @Test
    @DisplayName("La semaine ISO en UTC : du lundi 00:00 au lundi suivant, le titre dit les dates en français")
    fun week() {
        val thursday = SiteWeek.of(Instant.parse("2026-11-05T12:00:00Z"))
        assertEquals(Instant.parse("2026-11-02T00:00:00Z"), thursday.start)
        assertEquals(Instant.parse("2026-11-09T00:00:00Z"), thursday.end)
        assertEquals("Semaine du 2 au 8 novembre 2026", thursday.title())
        // Le dimanche 23:59:59 est encore dans la semaine, le lundi 00:00:00 ouvre la suivante.
        assertEquals(Instant.parse("2026-11-02T00:00:00Z"), SiteWeek.of(Instant.parse("2026-11-08T23:59:59Z")).start)
        assertEquals(Instant.parse("2026-11-09T00:00:00Z"), SiteWeek.of(Instant.parse("2026-11-09T00:00:00Z")).start)
        assertEquals("Semaine du 30 novembre au 6 décembre 2026", SiteWeek.of(Instant.parse("2026-12-02T10:00:00Z")).title())
        assertEquals("Semaine du 28 décembre 2026 au 3 janvier 2027", SiteWeek.of(Instant.parse("2026-12-31T10:00:00Z")).title())
        assertEquals("Semaine du 1er au 7 février 2027", SiteWeek.of(Instant.parse("2027-02-03T10:00:00Z")).title())
        assertEquals("1er mars 2027", frenchDate(LocalDate.of(2027, 3, 1)))
    }

    // --- les classements

    @Test
    @DisplayName("Classements : 404 en attente, 200 en ouvert avec la semaine en titre et les bornes lues sur l'horloge")
    fun rankingModes() {
        withRig(SiteMode.ATTENTE) { rig -> assertEquals(HttpStatusCode.NotFound, rig.page("/classements/").first.status) }
        withRig { rig ->
            rig.store.player("Kr", 120)
            val (response, html) = rig.page("/classements/")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(html.contains("Semaine du 2 au 8 novembre 2026"), html)
            assertTrue(html.contains("Le classement compte les Lingots gagnés cette semaine, pas la richesse totale."))
            assertEquals(Instant.parse("2026-11-02T00:00:00Z"), rig.store.lastFrom)
            assertEquals(Instant.parse("2026-11-09T00:00:00Z"), rig.store.lastUntil)
            assertEquals(HttpStatusCode.MovedPermanently, rig.client.get("/classements").status)
        }
    }

    @Test
    @DisplayName("Classements : l'ordre du dépôt, le rang, le gamertag, l'entreprise avec son lien vers la fiche, « Sans entreprise »")
    fun rankingRows() = withRig { rig ->
        rig.store.player("Moyenne", 50, 2L to "Les Silos")
        rig.store.player("Premier", 1_250, 1L to "Forge & Fils")
        rig.store.player("Seul", 10)
        val html = rig.page("/classements/").second
        assertTrue(html.indexOf("Premier") in 0 until html.indexOf("Moyenne"), "ordre")
        assertTrue(html.indexOf("Moyenne") < html.indexOf("Seul"), "ordre")
        assertTrue(html.contains("""<a href="/entreprise/1/">Forge &amp; Fils</a>"""), html)
        assertTrue(html.contains("""<a href="/entreprise/2/">Les Silos</a>"""))
        assertTrue(html.contains("Sans entreprise"))
        assertTrue(html.contains("sa-monnaie--lingots"))
        assertTrue(html.contains("1 250"), "les milliers sont séparés")
        assertTrue(html.contains("""data-label="Lingots""""), "chaque cellule porte son étiquette pour les fiches sous 600 px")
        assertWellFormed(html)
    }

    private fun assertWellFormed(html: String) {
        assertTrue(Regex("<html[^>]*\\blang=\"fr\"").containsMatchIn(html), "lang")
        assertEquals(1, Regex("<h1[ >]").findAll(html).count(), "un seul h1")
        assertTrue(Regex("<title>[^<]+</title>").containsMatchIn(html), "title")
        assertTrue(Regex("<meta name=\"description\" content=\"[^\"]+\"").containsMatchIn(html), "description")
        for (img in Regex("<img[^>]*>").findAll(html)) assertTrue(Regex("\\balt=\"[^\"]+\"").containsMatchIn(img.value), "alt : ${img.value}")
        assertFalse(html.contains("<script", ignoreCase = true))
    }

    @Test
    @DisplayName("Classements : sans ligne, la phrase « Personne n'a encore gagné de Lingots cette semaine. », pas de tableau")
    fun rankingEmpty() = withRig { rig ->
        val html = rig.page("/classements/").second
        assertTrue(html.contains("Personne n'a encore gagné de Lingots cette semaine."))
        assertFalse(html.contains("<table"))
    }

    @Test
    @DisplayName("Classements : vingt-cinq par page, les liens de page, le rang continue, une page au-delà du dernier joueur est un 404")
    fun rankingPages() = withRig { rig ->
        for (i in 1..30) rig.store.player("Joueur%02d".format(i), 1_000L - i)
        val first = rig.page("/classements/").second
        assertTrue(first.contains("Joueur25") && !first.contains("Joueur26"))
        assertTrue(first.contains("""href="/classements/?page=2""""))
        assertFalse(first.contains("Page précédente"))
        val second = rig.page("/classements/?page=2").second
        assertTrue(second.contains("Joueur26") && second.contains("Joueur30") && !second.contains("Joueur25"))
        assertTrue(second.contains("""<th scope="row" data-label="Rang">26</th>"""), second)
        assertTrue(second.contains("""href="/classements/""""))
        assertFalse(second.contains("Page suivante"))
        assertEquals(HttpStatusCode.NotFound, rig.page("/classements/?page=3").first.status)
        assertEquals(HttpStatusCode.NotFound, rig.page("/classements/?page=999").first.status)
        // Une page illisible ou négative retombe sur la première.
        assertTrue(rig.page("/classements/?page=abc").second.contains("Joueur01"))
        assertTrue(rig.page("/classements/?page=-4").second.contains("Joueur01"))
    }

    @Test
    @DisplayName("Classements : le cache d'une minute ne rappelle pas le store, puis relit quand l'horloge a avancé")
    fun rankingCache() = withRig { rig ->
        rig.store.player("Kr", 100)
        rig.page("/classements/")
        rig.page("/classements/")
        assertEquals(1, rig.store.rankingReads)
        rig.store.player("Nouveau", 5_000)
        rig.clock.advance(Duration.ofSeconds(59))
        assertFalse(rig.page("/classements/").second.contains("Nouveau"))
        assertEquals(1, rig.store.rankingReads)
        rig.clock.advance(Duration.ofSeconds(2))
        assertTrue(rig.page("/classements/").second.contains("Nouveau"))
        assertEquals(2, rig.store.rankingReads)
    }

    @Test
    @DisplayName("Classements : une nouvelle semaine ne retombe pas sur le cache de la précédente")
    fun rankingCacheAcrossWeeks() = withRig(now = Instant.parse("2026-11-08T23:59:30Z")) { rig ->
        rig.store.player("Kr", 100)
        assertTrue(rig.page("/classements/").second.contains("Semaine du 2 au 8 novembre 2026"))
        rig.clock.advance(Duration.ofSeconds(31))
        assertTrue(rig.page("/classements/").second.contains("Semaine du 9 au 15 novembre 2026"))
        assertEquals(2, rig.store.rankingReads)
    }

    @Test
    @DisplayName("Le cache est borné : au-delà du maximum, la plus ancienne entrée sort ; il garde aussi les valeurs nulles")
    fun cacheIsBounded() {
        val clock = MutableClock(Instant.parse("2026-11-05T12:00:00Z"))
        val cache = SiteCache<Int, String?>(clock, maxEntries = 3)
        runBlocking {
            for (key in 1..5) cache.get(key) { if (key == 5) null else "v$key" }
            assertEquals(3, cache.size())
            var loads = 0
            assertNull(cache.get(5) { loads++; "autre" })
            assertEquals("v1", cache.get(1) { loads++; "v1" })
            assertEquals(1, loads, "la clé 1 était sortie, la clé 5 (valeur nulle) est restée")
        }
    }

    // --- les fiches

    private fun card(id: Long = 7, name: String = "Forge & Fils") = CompanyCard(
        id, name, era = 2, stars = 3, createdAt = Instant.parse("2026-10-01T08:00:00Z"), prestiges = 2,
        members = listOf(CompanyMember("Chef", MemberRole.PDG), CompanyMember("Aide", MemberRole.ASSOCIE)),
    )

    @Test
    @DisplayName("Fiche : le nom, l'époque, les étoiles, les prestiges, la date en français et les membres, PDG d'abord ; rien d'autre")
    fun companyCard() = withRig { rig ->
        rig.store.companies[7] = card()
        val (response, html) = rig.page("/entreprise/7/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(html.contains("<h1 class=\"sa-plaque\">Forge &amp; Fils</h1>"), html)
        for (text in listOf("Époque 2", "3 étoiles", "2 prestiges", "Créée le 1er octobre 2026", "PDG", "Associé")) assertTrue(html.contains(text), text)
        assertTrue(html.indexOf("Chef") < html.indexOf("Aide"))
        assertWellFormed(html)
        for (absent in listOf("Donar", "Lingots", "slogan", "solde")) assertFalse(html.contains(absent, ignoreCase = true), absent)
        assertEquals(HttpStatusCode.MovedPermanently, rig.client.get("/entreprise/7").status)
        assertEquals("/entreprise/7/", rig.client.get("/entreprise/7").headers[HttpHeaders.Location])
        // La fiche n'est pas dans la navigation.
        assertFalse(Regex("""<nav[^>]*>.*?</nav>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value.contains("/entreprise/"))
    }

    @Test
    @DisplayName("Fiche : le pluriel (1 étoile, 1 prestige, aucun prestige) et un nom piégé est échappé")
    fun companyPlurals() = withRig { rig ->
        rig.store.companies[8] = card(8, "<script>x</script>").copy(stars = 1, prestiges = 1)
        rig.store.companies[9] = card(9).copy(stars = 0, prestiges = 0)
        val one = rig.page("/entreprise/8/").second
        assertTrue(one.contains("1 étoile<") && one.contains("1 prestige<"), one)
        assertFalse(one.contains("<script>x"))
        val none = rig.page("/entreprise/9/").second
        assertTrue(none.contains("0 étoiles"))
        assertTrue(none.contains("Aucun prestige"))
    }

    @Test
    @DisplayName("Fiche : un identifiant inconnu ou invalide est un 404 en page Silooo ; en attente, la fiche n'existe pas")
    fun companyNotFound() {
        withRig { rig ->
            for (path in listOf("/entreprise/999/", "/entreprise/abc/", "/entreprise/0/", "/entreprise/-1/", "/entreprise/99999999999999999999/", "/entreprise/1.5/")) {
                val (response, html) = rig.page(path)
                assertEquals(HttpStatusCode.NotFound, response.status, path)
                assertTrue(html.contains("Page introuvable"), path)
            }
            assertEquals(HttpStatusCode.NotFound, rig.client.get("/entreprise/abc").status)
        }
        withRig(SiteMode.ATTENTE) { rig ->
            rig.store.companies[7] = card()
            assertEquals(HttpStatusCode.NotFound, rig.page("/entreprise/7/").first.status)
        }
    }

    @Test
    @DisplayName("Fiche : le cache d'une minute par identifiant (l'inconnue aussi), relu quand l'horloge a avancé")
    fun companyCache() = withRig { rig ->
        rig.store.companies[7] = card()
        rig.page("/entreprise/7/")
        rig.page("/entreprise/7/")
        rig.page("/entreprise/8/")
        rig.page("/entreprise/8/")
        assertEquals(2, rig.store.companyReads)
        rig.store.companies[7] = card(name = "Renommée")
        rig.clock.advance(Duration.ofSeconds(61))
        assertTrue(rig.page("/entreprise/7/").second.contains("Renommée"))
        assertEquals(3, rig.store.companyReads)
    }
}
