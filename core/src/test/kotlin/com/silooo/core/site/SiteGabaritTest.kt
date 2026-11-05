package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.db.FakeDatabase
import com.silooo.core.server.ServerPresence
import com.silooo.core.site.pages.Reactions
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

private val SITE_SCRIPT = Regex("""<script src="/public/(menu|oooscar|direct)\.js"( defer="defer")?></script>""")

/**
 * Le gabarit ne charge que ses trois scripts de `/public/` (`menu.js`, `oooscar.js`, `direct.js`), et jamais du code écrit
 * dans la page : la politique de sécurité l'interdit. Chaque `<script` de la page doit donc être l'une des trois balises
 * exactes, sans `<script>` nu. Les tests de pages l'appellent à la place de l'ancienne règle « aucun script ».
 */
internal fun assertOnlySiteScripts(html: String, where: String) {
    assertFalse(html.contains("<script>", ignoreCase = true), "$where : un <script> nu, sans src")
    val rest = html.replace(SITE_SCRIPT, "")
    assertFalse(rest.contains("<script", ignoreCase = true), "$where : un script qui n'est ni menu.js, ni oooscar.js, ni direct.js")
}

/**
 * Ce que chaque page reçoit du gabarit : la scène d'Oooscar sous le contenu, le bouton Menu et la navigation, les trois
 * scripts, le pied, le bandeau direct hors de l'attente. Les valeurs attendues viennent du mode et de la configuration passés
 * au module ; les phrases d'Oooscar sont à valider par Kr, seules celles de l'attente et des erreurs sont écrites ici.
 */
class SiteGabaritTest {
    private val now = Instant.parse("2026-11-05T12:00:00Z")
    private val moods = setOf("joie", "fier", "oups", "panique", "dodo")
    private val reactionKeys = setOf("touche", "toit", "neuf", "repare", "attrape", "chute", "salut", "reveil", "nuit", "voteParty")
    private val discord = "https://discord.gg/exemple"

    /** [online] : `null` = aucun signal de vie (serveur fermé), une liste = un serveur qui bat avec ces joueurs. */
    private fun withSite(
        mode: SiteMode,
        discordUrl: String? = null,
        settings: SiteSettings = SiteSettings(),
        online: List<String>? = null,
        content: SiteContent = SiteContent.fromClasspath(),
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        val clock = MutableClock(now)
        val presence = ServerPresence(clock).apply { if (online != null) record("bds-1", online) }
        application {
            siteModule(
                Dependencies(
                    FakeDatabase(), "cle", serverPresence = presence, definitions = TestDefinitions.loaded, clock = clock,
                    siteMode = mode, discordUrl = discordUrl, siteSettings = settings,
                ),
                content = content,
            )
        }
        block(createClient { followRedirects = false })
    }

    private fun unescape(text: String): String =
        text.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** La valeur (défaite de ses entités) de l'attribut [name] dans une balise ouvrante ou une suite d'attributs. */
    private fun attribute(tag: String, name: String): String? =
        Regex("""\b${Regex.escape(name)}="([^"]*)"""").find(tag)?.groupValues?.get(1)?.let(::unescape)

    private fun isHidden(tag: String): Boolean = Regex("""\bhidden\b""").containsMatchIn(tag)

    private fun words(text: String): Int = text.trim().split(Regex("\\s+")).size

    private fun footer(html: String): String = html.substringAfter("<footer").substringBefore("</footer>")

    /** Le texte du pied, sans balises, les blancs réduits à un espace. */
    private fun footerText(html: String): String = footer(html).replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Vérifie la scène d'une page et rend (humeur, bulle) : sous le contenu et avant le pied, l'image d'Oooscar au visage
     * de l'humeur avec un alt, la bulle en zone de statut qui répète `data-bulle`, `data-textes` en JSON valide.
     */
    private fun checkScene(html: String, where: String): Pair<String, String> {
        assertEquals(1, Regex("""class="scene"""").findAll(html).count(), "$where : une seule scène")
        val sceneAt = html.indexOf("""<div class="scene">""")
        assertTrue(sceneAt > html.indexOf("</main>") && html.indexOf("</main>") > 0, "$where : la scène vient après le contenu")
        assertTrue(sceneAt < html.indexOf("<footer"), "$where : la scène vient avant le pied")
        val scene = html.substring(sceneAt, html.indexOf("<footer"))
        val tag = Regex("""<div class="oooscar"[^>]*>""").find(scene)?.value ?: error("$where : pas de div.oooscar dans la scène")

        val humeur = attribute(tag, "data-humeur") ?: error("$where : data-humeur manque")
        assertTrue(humeur in moods, "$where : l'humeur « $humeur » n'est pas l'un des cinq visages")
        val bulle = attribute(tag, "data-bulle") ?: error("$where : data-bulle manque")
        assertTrue(words(bulle) in 1..6, "$where : la bulle a plus de six mots : $bulle")

        val image = Regex("""<img[^>]*class="sa-oooscar"[^>]*>""").find(scene)?.value ?: error("$where : pas d'img.sa-oooscar")
        assertEquals("/public/atelier/oooscar/oooscar-$humeur.png", attribute(image, "src"), "$where : l'image suit l'humeur")
        assertTrue(!attribute(image, "alt").isNullOrBlank(), "$where : l'image d'Oooscar n'a pas de alt")

        val paragraph = Regex("""<p class="sa-bulle"([^>]*)>([^<]+)</p>""").find(scene) ?: error("$where : pas de p.sa-bulle")
        assertEquals("status", attribute(paragraph.groupValues[1], "role"), "$where : la bulle est une zone de statut")
        assertEquals(bulle, unescape(paragraph.groupValues[2]).trim(), "$where : data-bulle répète le texte de la bulle")
        assertTrue(scene.indexOf(image) < scene.indexOf(paragraph.value), "$where : la bulle suit l'image dans le même bloc")

        val texts = Json.parseToJsonElement(attribute(tag, "data-textes") ?: error("$where : data-textes manque")).jsonObject
        assertEquals(reactionKeys, texts.keys, "$where : les clés de data-textes")
        assertEquals(6, texts.getValue("touche").jsonArray.size, "$where : six phrases quand on touche Oooscar")
        for ((key, value) in texts) {
            val strings = if (key == "touche") value.jsonArray.map { it.jsonPrimitive } else listOf(value.jsonPrimitive)
            for (text in strings) assertTrue(text.isString && text.content.isNotBlank(), "$where : data-textes.$key doit être du texte non vide")
        }
        assertEquals(Json.parseToJsonElement(Reactions.json), texts, "$where : data-textes est celui de Reactions, sans perte à l'échappement")
        return humeur to bulle
    }

    @Test
    @DisplayName("Chaque page a sa scène : Oooscar au visage de son humeur, une bulle de six mots au plus, ses textes en JSON")
    fun everyPageHasItsScene() {
        for ((mode, path) in listOf(
            SiteMode.ATTENTE to "/", SiteMode.ATTENTE to "/mentions-legales/", SiteMode.BETA to "/", SiteMode.BETA to "/rejoindre/", SiteMode.OUVERT to "/rejoindre/",
        )) {
            withSite(mode) { client ->
                val response = client.get(path)
                assertEquals(HttpStatusCode.OK, response.status, path)
                checkScene(response.bodyAsText(), "$path (${mode.word})")
            }
        }
    }

    @Test
    @DisplayName("L'attente : la bulle dit « On construit. Reviens vite ! », et l'ancienne annonce n'existe plus")
    fun waitingBubble() = withSite(SiteMode.ATTENTE) { client ->
        for (path in listOf("/", "/mentions-legales/")) {
            val html = client.get(path).bodyAsText()
            val (_, bubble) = checkScene(html, path)
            if (path == "/") assertEquals("On construit. Reviens vite !", bubble)
            assertFalse(html.contains("Le serveur ouvre bientôt !"), "$path : cette bulle n'existe plus")
        }
    }

    @Test
    @DisplayName("La bêta garde la page d'attente à / avec sa bulle, et le guide garde la sienne")
    fun betaBubbles() = withSite(SiteMode.BETA) { client ->
        val (_, bubble) = checkScene(client.get("/").bodyAsText(), "/ (bêta)")
        assertEquals("On construit. Reviens vite !", bubble)
        val milestones = TestDefinitions.loaded.economy.levels.doublingMilestones
        val first = milestones.fixed.firstOrNull() ?: milestones.thenEvery
        assertEquals("Niveau $first : production ×2 !", checkScene(client.get("/wiki/").bodyAsText(), "/wiki/ (bêta)").second)
    }

    @Test
    @DisplayName("Le guide : la bulle donne le premier palier ×2 des définitions")
    fun guideBubble() = withSite(SiteMode.OUVERT) { client ->
        val milestones = TestDefinitions.loaded.economy.levels.doublingMilestones
        val first = milestones.fixed.firstOrNull() ?: milestones.thenEvery
        for (path in listOf("/wiki/", "/wiki/usines/")) {
            val (_, bubble) = checkScene(client.get(path).bodyAsText(), path)
            assertEquals("Niveau $first : production ×2 !", bubble, path)
        }
    }

    @Test
    @DisplayName("Le 404 est une page Silooo avec sa scène : Oooscar surpris (oups) dit « Cette page s'est envolée. », dans les deux modes")
    fun notFoundScene() {
        for (mode in SiteMode.entries) {
            withSite(mode) { client ->
                val response = client.get("/nulle-part/")
                assertEquals(HttpStatusCode.NotFound, response.status, mode.word)
                val html = response.bodyAsText()
                val (humeur, bubble) = checkScene(html, "404 (${mode.word})")
                assertEquals("oups", humeur, mode.word)
                assertEquals("Cette page s'est envolée.", bubble, mode.word)
                assertFalse(html.contains("aria-current"), "${mode.word} : une page d'erreur n'a pas de page courante")
            }
        }
    }

    @Test
    @DisplayName("La 500 garde la scène : Oooscar surpris dit « Réessaie dans un moment. », sans rien de technique")
    fun internalErrorScene() = withSite(SiteMode.OUVERT) { client ->
        // /classements/ lit la base ; FakeDatabase refuse toute transaction, le module répond par sa page d'erreur.
        val response = client.get("/classements/")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val html = response.bodyAsText()
        val (humeur, bubble) = checkScene(html, "500")
        assertEquals("oups", humeur)
        assertEquals("Réessaie dans un moment.", bubble)
        assertFalse(html.contains("UnsupportedOperationException") || html.contains("FakeDatabase"), "la page ne dit rien du détail")
        assertFalse(html.contains("aria-current"), "une page d'erreur n'a pas de page courante")
    }

    @Test
    @DisplayName("Le bouton Menu (caché, replié, lié à nav#menu) précède la navigation, dans les trois modes")
    fun menuButtonAndNavigation() {
        for ((mode, path) in listOf(SiteMode.ATTENTE to "/", SiteMode.ATTENTE to "/mentions-legales/", SiteMode.BETA to "/rejoindre/", SiteMode.OUVERT to "/rejoindre/")) {
            withSite(mode) { client ->
                val html = client.get(path).bodyAsText()
                val button = Regex("""<button[^>]*menu__bouton[^>]*>""").find(html)?.value ?: error("$path : pas de bouton Menu")
                assertTrue(isHidden(button), "$path : le bouton Menu naît caché, menu.js le montre")
                assertEquals("menu", attribute(button, "aria-controls"), path)
                assertEquals("false", attribute(button, "aria-expanded"), path)
                assertEquals("button", attribute(button, "type"), path)
                val nav = Regex("""<nav[^>]*>""").find(html)?.value ?: error("$path : pas de navigation")
                assertEquals("menu", attribute(nav, "id"), path)
                assertEquals("navigation", attribute(nav, "class"), path)
                assertFalse(attribute(nav, "aria-label").isNullOrBlank(), "$path : la navigation a un nom")
                assertTrue(Regex("""</button>\s*<nav""").containsMatchIn(html), "$path : le bouton précède immédiatement la navigation")
                assertEquals(1, Regex("<nav[ >]").findAll(html).count(), path)
            }
        }
    }

    @Test
    @DisplayName("Sans entrée de navigation, ni bouton Menu ni nav : une seule page de la navigation suffit à les ramener")
    fun noMenuWithoutNavigation() {
        // Sans les contenus des actus, du Discord et des pionniers, l'attente ne rend aucune page de sa navigation.
        val navigationPages = setOf("actus", "discord", PIONEERS_PAGE)
        val bare = SiteContent(SiteContent.fromClasspath().pages.filter { it.name !in navigationPages })
        withSite(SiteMode.ATTENTE, discordUrl = discord, content = bare) { client ->
            val html = client.get("/").bodyAsText()
            assertFalse(html.contains("<nav"), "pas de navigation vide, même avec un Discord configuré : l'invitation vit sur la page du Discord")
            assertFalse(html.contains("menu__bouton"), "pas de bouton Menu pour rien")
            assertTrue(html.contains("""class="marque""""), "le logo mène toujours à l'accueil")
        }
        val withDiscord = SiteContent(SiteContent.fromClasspath().pages.filter { it.name !in navigationPages || it.name == "discord" })
        withSite(SiteMode.ATTENTE, content = withDiscord) { client ->
            val html = client.get("/").bodyAsText()
            assertTrue(html.contains("menu__bouton") && html.contains("""id="menu""""))
            assertTrue(html.contains("""<a href="/discord/" class="navigation__lien">Discord</a>"""))
        }
    }

    @Test
    @DisplayName("Trois scripts au plus, tous dans le head : menu.js sans defer, oooscar.js différé, direct.js différé hors de l'attente")
    fun scripts() {
        val menu = """<script src="/public/menu.js"></script>"""
        val oooscar = """<script src="/public/oooscar.js" defer="defer"></script>"""
        val direct = """<script src="/public/direct.js" defer="defer"></script>"""
        for ((mode, expected) in listOf(SiteMode.ATTENTE to listOf(menu, oooscar), SiteMode.BETA to listOf(menu, oooscar, direct), SiteMode.OUVERT to listOf(menu, oooscar, direct))) {
            withSite(mode) { client ->
                for (path in listOf("/", "/mentions-legales/", "/nulle-part/")) {
                    val html = client.get(path).bodyAsText()
                    val inHead = Regex("""<script[^>]*></script>""").findAll(html.substringBefore("</head>")).map { it.value }.toList()
                    assertEquals(expected, inHead, "$path (${mode.word}) : les scripts du head, dans l'ordre")
                    assertEquals(expected.size, Regex("<script").findAll(html).count(), "$path (${mode.word}) : aucun script hors du head")
                    assertOnlySiteScripts(html, "$path (${mode.word})")
                }
            }
        }
    }

    @Test
    @DisplayName("Le pied : « Discord Silooo » mène à la page du Discord, puis les quatre pages légales dans l'ordre, dans les deux modes")
    fun footerLinks() {
        val legalLinks = listOf(
            """<a href="/mentions-legales/">Mentions légales</a>""",
            """<a href="/cgv/">CGV</a>""",
            """<a href="/confidentialite/">Confidentialité</a>""",
            """<a href="/parents/">Parents</a>""",
        )
        // Le lien mène à la page /discord/ même sans invitation configurée : c'est elle qui porte l'invitation, ou son absence.
        for (mode in SiteMode.entries) for (discordUrl in listOf(null, discord)) {
            withSite(mode, discordUrl = discordUrl) { client ->
                val footer = footer(client.get("/mentions-legales/").bodyAsText())
                assertTrue(footer.contains("""<a href="/discord/">Discord Silooo</a>"""), "${mode.word} : le pied mène à la page du Discord")
                assertFalse(footer.contains(discord), "${mode.word} : l'invitation n'est pas dans le pied")
                val positions = legalLinks.map { footer.indexOf(it) }
                assertTrue(positions.all { it >= 0 }, "${mode.word} : les quatre liens légaux sont dans le pied : $positions")
                assertEquals(positions.sorted(), positions, "${mode.word} : ordre des liens légaux")
                assertTrue(footer.indexOf("Discord Silooo") < positions.first(), "${mode.word} : le Discord vient avant les pages légales")
            }
        }
    }

    @Test
    @DisplayName("« Voir aussi » n'apparaît que pour les sites frères configurés : aucun, les deux, ou un seul")
    fun seeAlso() {
        val both = SiteSettings(sileneMcUrl = "https://silenemc.example", hubyyUrl = "https://hubyy.example")
        withSite(SiteMode.ATTENTE) { client ->
            val html = client.get("/").bodyAsText()
            assertFalse(html.contains("Voir aussi"), "sans site frère, pas de ligne")
            assertFalse(html.contains("pied__voir"))
        }
        for (mode in SiteMode.entries) {
            withSite(mode, settings = both) { client ->
                val html = client.get("/mentions-legales/").bodyAsText()
                assertTrue(Regex("""<p class="pied__voir"[^>]*>""").containsMatchIn(footer(html)), "${mode.word} : p.pied__voir dans le pied")
                assertTrue(footerText(html).contains("Voir aussi : SileneMC · Hubyy"), "${mode.word} : ${footerText(html)}")
                assertTrue(Regex("""<a href="https://silenemc.example">SileneMC</a>""").containsMatchIn(footer(html)), mode.word)
                assertTrue(Regex("""<a href="https://hubyy.example">Hubyy</a>""").containsMatchIn(footer(html)), mode.word)
            }
        }
        withSite(SiteMode.ATTENTE, settings = SiteSettings(hubyyUrl = "https://hubyy.example")) { client ->
            val text = footerText(client.get("/").bodyAsText())
            assertTrue(text.contains("Voir aussi : Hubyy"), text)
            assertFalse(text.contains("SileneMC"), text)
        }
    }

    @Test
    @DisplayName("Le bandeau direct n'existe qu'en bêta et en ouvert : div.direct en zone de statut, qui relit /site/direct.json, sous l'en-tête et avant le contenu")
    fun directBandOnlyWhenOpen() {
        for (mode in listOf(SiteMode.BETA, SiteMode.OUVERT)) withSite(mode) { client ->
            for (path in listOf("/", "/rejoindre/", "/nulle-part/")) {
                val html = client.get(path).bodyAsText()
                val tag = Regex("""<div class="direct"[^>]*>""").find(html)?.value ?: error("$path (${mode.word}) : pas de bandeau")
                assertEquals("status", attribute(tag, "role"), path)
                assertEquals("/site/direct.json", attribute(tag, "data-direct"), path)
                assertEquals(1, Regex("""data-direct=""").findAll(html).count(), "$path (${mode.word}) : un seul bandeau")
                assertTrue(html.indexOf("</header>") < html.indexOf(tag) && html.indexOf(tag) < html.indexOf("<main"), "$path : le bandeau est entre l'en-tête et le contenu")
                assertTrue(html.contains("""<script src="/public/direct.js" defer="defer"></script>"""), "$path (${mode.word}) : direct.js")
            }
        }
        withSite(SiteMode.ATTENTE) { client ->
            for (path in listOf("/", "/mentions-legales/", "/nulle-part/")) {
                val html = client.get(path).bodyAsText()
                assertFalse(html.contains("""class="direct""""), "$path : pas de bandeau en attente")
                assertFalse(html.contains("data-direct"), path)
                assertFalse(html.contains("direct.js"), "$path : pas de direct.js en attente")
            }
        }
    }

    /** Pour chaque entrée du bandeau (`data-champ`, et `data-etat` pour l'état) : visible au premier rendu ou non. */
    private fun directItems(html: String): Map<String, Boolean> =
        Regex("""<span class="direct__item"([^>]*)>""").findAll(html).associate { match ->
            val attributes = match.groupValues[1]
            listOfNotNull(attribute(attributes, "data-champ"), attribute(attributes, "data-etat")).joinToString("/") to !isHidden(attributes)
        }

    @Test
    @DisplayName("Bandeau, premier rendu : serveur fermé, « Fermé » seul se montre ; serveur qui bat, les joueurs et « Ouvert » ; version et Vote party attendent le script")
    fun directBandFirstRender() {
        withSite(SiteMode.OUVERT) { client ->
            val html = client.get("/").bodyAsText()
            assertEquals(
                mapOf("joueurs" to false, "etat/ouvert" to false, "etat/ferme" to true, "version" to false, "voteParty" to false),
                directItems(html),
            )
            assertTrue(Regex("""data-valeur="joueurs">\s*<""").containsMatchIn(html), "aucun nombre de joueurs tant que le serveur ne bat pas")
        }
        withSite(SiteMode.OUVERT, online = listOf("1", "2", "3")) { client ->
            val html = client.get("/").bodyAsText()
            assertEquals(
                mapOf("joueurs" to true, "etat/ouvert" to true, "etat/ferme" to false, "version" to false, "voteParty" to false),
                directItems(html),
            )
            assertTrue(Regex("""data-valeur="joueurs">\s*3\s*</span>""").containsMatchIn(html), "trois joueurs en ligne au premier rendu")
        }
        // Un serveur qui bat sans personne dedans est ouvert, avec zéro joueur.
        withSite(SiteMode.OUVERT, online = emptyList()) { client ->
            val items = directItems(client.get("/").bodyAsText())
            assertEquals(true, items["etat/ouvert"])
            assertEquals(false, items["etat/ferme"])
        }
    }
}
