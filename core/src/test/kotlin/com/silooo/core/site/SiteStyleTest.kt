package com.silooo.core.site

import com.silooo.core.Config
import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.FakeDatabase
import com.silooo.core.site.pages.NavKind
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
        Regex("""<a href="[^"]*" class="[^"]*navigation__(?:lien|bouton)"[^>]*>([^<]+)</a>""")
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
    @DisplayName("Navigation : en attente Actus, Discord (s'il est configuré) puis Me prévenir, selon les pages que le mode rend ; en ouvert les sept entrées")
    fun navigationByMode() {
        // Ce que l'attente rend vient des contenus : une entrée n'apparaît que si sa page est rendue dans le mode (« Me prévenir » mène à /pionniers/, « Actus » à /mises-a-jour/).
        val waiting = SiteContent.fromClasspath().forMode(SiteMode.ATTENTE).map { it.url }
        fun expectedWaiting(withDiscord: Boolean): List<String> = buildList {
            if ("/mises-a-jour/" in waiting) add("Actus")
            if (withDiscord) add("Discord")
            if ("/pionniers/" in waiting) add("Me prévenir")
        }
        assertTrue("/pionniers/" in waiting, "l'attente rend /pionniers/ : c'est son bouton « Me prévenir »")
        withSite(SiteMode.ATTENTE) { client ->
            for (path in listOf("/", "/mentions/", "/wiki/")) assertEquals(expectedWaiting(false), navLabels(client.get(path).bodyAsText()), path)
        }
        withSite(SiteMode.ATTENTE, discordUrl = "https://discord.gg/exemple") { client ->
            for (path in listOf("/", "/mentions/", "/wiki/")) {
                val html = client.get(path).bodyAsText()
                assertEquals(expectedWaiting(true), navLabels(html), path)
                assertTrue(html.contains("""<a href="https://discord.gg/exemple" class="navigation__lien">Discord</a>"""), "$path : le lien Discord mène à l'adresse configurée")
            }
        }
        withSite(SiteMode.OUVERT) { client ->
            val html = client.get("/rejoindre/").bodyAsText()
            assertEquals(listOf("Rejoindre", "Boutique", "Classements", "Vote", "Guide", "Actus", "Mon compte"), navLabels(html))
            assertEquals(1, Regex("""aria-current="page"""").findAll(html).count(), "la page courante est marquée une fois")
            assertTrue(html.contains("""<a href="/rejoindre/" class="navigation__lien" aria-current="page">Rejoindre</a>"""), "et c'est Rejoindre")
            // Les mentions sont au pied et le logo mène à l'accueil : aucune des deux n'est dans la navigation, donc aucune n'est « courante ».
            assertEquals(0, Regex("""aria-current="page"""").findAll(client.get("/mentions/").bodyAsText()).count())
            assertEquals(0, Regex("""aria-current="page"""").findAll(client.get("/").bodyAsText()).count())
        }
    }

    @Test
    @DisplayName("Navigation : l'ordre est celui de la liste du mode, Discord vient de la configuration, et une page absente n'a pas de lien")
    fun navigationOrder() {
        val discord = "https://discord.gg/exemple"
        fun page(url: String) = ContentPage(url.trim('/'), "t", "d", url, SiteMode.entries.toSet(), "")
        val every = listOf("/mentions/", "/vote/", "/", "/wiki/", "/mises-a-jour/", "/boutique/", "/classements/", "/rejoindre/", "/pionniers/").map(::page)
        val open = listOf("/rejoindre/", "/boutique/", "/classements/", "/vote/", "/wiki/", "/mises-a-jour/", "/pionniers/")
        assertEquals(open, Navigation.items(SiteMode.OUVERT, every).map { it.url })
        assertEquals(listOf("Rejoindre", "Boutique", "Classements", "Vote", "Guide", "Actus", "Mon compte"), Navigation.items(SiteMode.OUVERT, every).map { it.label })
        assertEquals(listOf("/mises-a-jour/", "/pionniers/"), Navigation.items(SiteMode.ATTENTE, every).map { it.url })
        assertEquals(listOf("Actus", "Me prévenir"), Navigation.items(SiteMode.ATTENTE, every).map { it.label })
        // Discord n'est pas une page : son adresse est celle de la configuration, entre Actus et Me prévenir, et l'ouvert ne le montre pas.
        assertEquals(listOf("/mises-a-jour/", discord, "/pionniers/"), Navigation.items(SiteMode.ATTENTE, every, discord).map { it.url })
        assertEquals(listOf("Actus", "Discord", "Me prévenir"), Navigation.items(SiteMode.ATTENTE, every, discord).map { it.label })
        assertEquals(listOf(discord), Navigation.items(SiteMode.ATTENTE, emptyList(), discord).map { it.url })
        assertEquals(open, Navigation.items(SiteMode.OUVERT, every, discord).map { it.url })
        // Deux boutons de fonte seulement : le compte et « Me prévenir ». Le reste est un lien simple.
        assertEquals(listOf("Mon compte"), Navigation.items(SiteMode.OUVERT, every).filter { it.kind == NavKind.BOUTON }.map { it.label })
        assertEquals(listOf("Me prévenir"), Navigation.items(SiteMode.ATTENTE, every, discord).filter { it.kind == NavKind.BOUTON }.map { it.label })
        // Les mentions sont au pied, le logo mène à l'accueil : ni l'un ni l'autre n'est dans la navigation, même rendus.
        assertEquals(emptyList<String>(), Navigation.items(SiteMode.OUVERT, listOf(page("/mentions/"), page("/"))).map { it.url })
        assertEquals(listOf("/vote/"), Navigation.items(SiteMode.OUVERT, listOf(page("/vote/"), page("/mentions/"))).map { it.url })
        assertEquals(emptyList<String>(), Navigation.items(SiteMode.ATTENTE, emptyList()).map { it.url })
    }

    @Test
    @DisplayName("La page d'attente montre Oooscar et une bulle de six mots au plus, et le gabarit a ses quatre zones")
    fun waitingPage() = withSite(SiteMode.ATTENTE) { client ->
        val html = client.get("/").bodyAsText()
        assertTrue(html.contains("""src="/public/atelier/oooscar/oooscar-joie.png""""))
        val bubble = Regex("""<p class="sa-bulle"[^>]*>([^<]+)</p>""").find(html)?.groupValues?.get(1) ?: error("pas de bulle")
        assertEquals("On construit. Reviens vite !", bubble)
        assertTrue(bubble.trim().split(Regex("\\s+")).size <= 6, "la bulle a plus de six mots : $bubble")
        for (tag in listOf("<header", "<nav", "<main", "<footer")) assertTrue(html.contains(tag), tag)
        assertFalse(html.contains("""class="sa-bouton">"""), "sans Discord configuré, la page n'a pas de bouton rouge")
    }

    @Test
    @DisplayName("Un seul bouton rouge par page : le Discord sur l'attente, le retour à l'accueil sur l'erreur, aucun sur les mentions ; « Me prévenir » est un bouton de fonte")
    fun singleRedButton() = withSite(SiteMode.ATTENTE, discordUrl = "https://discord.gg/exemple") { client ->
        fun red(html: String) = Regex("""class="sa-bouton"""").findAll(html).count()
        // Les boutons de la navigation (« Me prévenir », le bouton Menu) sont des boutons de fonte : ils ne comptent pas parmi les rouges.
        assertTrue(client.get("/").bodyAsText().contains("""class="sa-bouton sa-bouton--fonte navigation__bouton""""))
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
