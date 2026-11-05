package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.FakeDatabase
import com.silooo.core.site.pages.avecInsertions
import com.silooo.core.site.pages.frenchDate
import com.silooo.core.site.pages.PlayersOnline
import com.silooo.core.site.pages.joueursEnLigne
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * Les six pages publiques du mode ouvert (S2) : Accueil, Rejoindre, Ce qui se vend, Boutique, Vote, Mises à jour.
 * Chaque page répond 200 en `ouvert` et 404 en `attente`. Les valeurs attendues sont construites depuis
 * `TestDefinitions.loaded.shop` et depuis les réglages passés au module : aucun chiffre du jeu n'est écrit ici.
 */
class SitePublicPagesTest {
    private val shop = TestDefinitions.loaded.shop
    private val nbsp = ' '

    private fun withSite(
        mode: SiteMode = SiteMode.OUVERT,
        settings: SiteSettings = SiteSettings(),
        discordUrl: String? = null,
        articles: List<NewsArticle> = emptyList(),
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        application {
            siteModule(
                Dependencies(
                    FakeDatabase(), "cle", definitions = TestDefinitions.loaded, siteMode = mode, discordUrl = discordUrl, siteSettings = settings,
                ),
                articles = articles,
            )
        }
        block(createClient { followRedirects = false })
    }

    private fun assertWellFormedPage(html: String, where: String) {
        assertTrue(Regex("""<html[^>]*\blang="fr"""").containsMatchIn(html), "$where : lang=\"fr\" manque")
        assertEquals(1, Regex("<h1[ >]").findAll(html).count(), "$where : il faut exactement un h1")
        assertTrue(Regex("<title>[^<]+</title>").containsMatchIn(html), "$where : title vide")
        assertTrue(Regex("""<meta name="description" content="[^"]+"""").containsMatchIn(html), "$where : meta description vide")
        for (img in Regex("<img[^>]*>").findAll(html)) {
            assertTrue(Regex("""\balt="[^"]*"""").containsMatchIn(img.value), "$where : image sans alt : ${img.value}")
        }
        assertOnlySiteScripts(html, where)
        assertFalse(html.contains("{{"), "$where : un marqueur de contenu est resté dans la page")
        assertTrue(Regex("""class="sa-bouton"""").findAll(html).count() <= 1, "$where : un seul bouton rouge par page")
    }

    /** Le texte visible du `main`, sans balises, les entités les plus courantes défaites. */
    private fun mainText(html: String): String =
        html.substringAfter("<main").substringBefore("</main>")
            .replace(Regex("<[^>]*>"), " ")
            .replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("[ \n]+"), " ")

    /** Les nombres du texte : « 3 500 » (espace insécable), « 4,99 », « 10 ». */
    private fun numbersIn(text: String): List<String> =
        Regex("""\d+(?:$nbsp\d{3})*(?:,\d+)?""").findAll(text).map { it.value }.toList().sorted()

    private fun grouped(value: Long): String = value.toString().reversed().chunked(3).joinToString(nbsp.toString()).reversed()

    private fun checkPage(path: String, h1: String, inNavigation: Boolean = true) {
        withSite(SiteMode.OUVERT) { client ->
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status, path)
            val html = response.bodyAsText()
            assertTrue(Regex("""<h1[^>]*>$h1</h1>""").containsMatchIn(html), "$path : h1 « $h1 » attendu")
            assertWellFormedPage(html, path)
            assertEquals(inNavigation, html.contains("""aria-current="page""""), "$path : la navigation la marque courante")
        }
        withSite(SiteMode.ATTENTE) { client ->
            assertEquals(HttpStatusCode.NotFound, client.get(path).status, "$path doit répondre 404 en mode attente")
        }
    }

    @Test
    @DisplayName("Accueil : 200 en ouvert avec le h1 Silooo ; en attente, / reste la page d'attente")
    fun home() {
        withSite(SiteMode.OUVERT) { client ->
            val html = client.get("/").bodyAsText()
            assertTrue(Regex("""<h1[^>]*>Silooo</h1>""").containsMatchIn(html))
            assertWellFormedPage(html, "/")
            assertTrue(html.contains("Un tycoon d&#39;usines dans le ciel, sur Minecraft Bedrock.") || html.contains("Un tycoon d'usines dans le ciel, sur Minecraft Bedrock."))
            assertFalse(html.contains("ouvre bientôt"))
        }
        withSite(SiteMode.ATTENTE) { client ->
            val response = client.get("/")
            assertEquals(HttpStatusCode.OK, response.status)
            val html = response.bodyAsText()
            assertTrue(html.contains("On construit. Reviens vite !"), "la bulle d'Oooscar dit l'attente")
            assertTrue(html.contains("Produis de jour comme de nuit"), "l'accroche de la maquette SiteAttente")
            assertTrue(html.contains("Me prévenir"), "le bouton mène à l'inscription des pionniers")
            assertFalse(html.contains("ouvre bientôt"), "l'attente ne promet plus d'ouverture")
            assertFalse(html.contains("Le serveur ouvre bientôt !"), "l'ancienne annonce n'existe plus")
            assertFalse(html.contains("/rejoindre/"))
            assertFalse(html.contains("play.silooo.com"))
        }
    }

    @Test
    @DisplayName("Accueil : l'adresse et le port de la configuration, le lien Rejoindre, pas de média visible, pas de joueurs en ligne")
    fun homeShowsAddressAndPort() {
        val settings = SiteSettings(gameAddress = "jeu.exemple.test", gamePort = 20123)
        withSite(settings = settings) { client ->
            val html = client.get("/").bodyAsText()
            val main = html.substringAfter("<main")
            assertTrue(main.contains("jeu.exemple.test") && main.contains("20123"))
            assertTrue(Regex("""<a href="/rejoindre/" class="sa-bouton sa-bouton--laiton">Rejoindre</a>""").containsMatchIn(html))
            assertTrue(main.contains("""<a href="/ce-qui-se-vend/">"""), "l'accueil mène à Ce qui se vend")
            assertFalse(main.contains("en ligne"), "sans donnée, aucun emplacement de joueurs ne s'affiche")
            assertFalse(main.contains("<img src=\"/public/media"), "aucune image de média absente")
            // Quatre paragraphes au plus.
            assertTrue(Regex("<p[ >]").findAll(main.substringBefore("""class="adresse"""")).count() <= 4)
        }
        withSite { client ->
            val main = client.get("/").bodyAsText().substringAfter("<main")
            assertTrue(main.contains("play.silooo.com") && main.contains(SiteSettings().gamePort.toString()))
        }
    }

    @Test
    @DisplayName("Accueil ouvert : le Discord est le seul bouton rouge, et seulement s'il est configuré")
    fun homeDiscord() {
        fun red(html: String) = Regex("""class="sa-bouton"""").findAll(html).count()
        withSite { client -> assertEquals(0, red(client.get("/").bodyAsText())) }
        withSite(discordUrl = "https://discord.gg/exemple") { client ->
            val html = client.get("/").bodyAsText()
            assertEquals(1, red(html))
            assertTrue(Regex("""<a href="https://discord.gg/exemple" class="sa-bouton">Rejoins le Discord</a>""").containsMatchIn(html))
        }
    }

    @Test
    @DisplayName("L'emplacement des joueurs en ligne ne rend rien sans donnée, et dit le nombre avec une donnée")
    fun onlinePlayersSlot() {
        assertEquals("<div></div>", createHTML(prettyPrint = false).div { joueursEnLigne(null) })
        val one = createHTML(prettyPrint = false).div { joueursEnLigne(PlayersOnline.Count(1)) }
        val many = createHTML(prettyPrint = false).div { joueursEnLigne(PlayersOnline.Count(12)) }
        assertTrue(one.contains("1 joueur en ligne"), one)
        assertTrue(many.contains("12 joueurs en ligne"), many)
        assertTrue(createHTML(prettyPrint = false).div { joueursEnLigne(PlayersOnline.Count(0)) }.contains("Personne en ligne pour l'instant."))
        assertTrue(createHTML(prettyPrint = false).div { joueursEnLigne(PlayersOnline.Closed) }.contains("Serveur fermé pour l'instant."))
    }

    @Test
    @DisplayName("Rejoindre : 200 en ouvert avec le h1, 404 en attente")
    fun joinPage() = checkPage("/rejoindre/", "Rejoindre")

    @Test
    @DisplayName("Rejoindre : l'adresse et le port de la configuration, la marche à suivre, le rappel des menus")
    fun joinContent() {
        withSite(settings = SiteSettings(gameAddress = "jeu.exemple.test", gamePort = 20123)) { client ->
            val text = mainText(client.get("/rejoindre/").bodyAsText())
            assertTrue(text.contains("jeu.exemple.test") && text.contains("20123"), text)
            for (step in listOf("Jouer", "Serveurs", "Ajouter un serveur")) assertTrue(text.contains(step), step)
            assertTrue(text.contains("Tout passe par des menus en jeu. Aucune commande à taper."))
            assertTrue(text.contains("La marche à suivre pour les consoles arrive."))
        }
        withSite { client ->
            val text = mainText(client.get("/rejoindre/").bodyAsText())
            assertTrue(text.contains("play.silooo.com") && text.contains(SiteSettings().gamePort.toString()))
        }
    }

    @Test
    @DisplayName("Ce qui se vend : 200 en ouvert avec le h1, 404 en attente")
    fun whatSellsPage() = checkPage("/ce-qui-se-vend/", "Ce qui se vend", inNavigation = false)

    @Test
    @DisplayName("Ce qui se vend : chaque pack, grade et abonnement du JSON est sur la page, et rien d'autre ne l'est")
    fun whatSellsMatchesDefinitions() = withSite { client ->
        val html = client.get("/ce-qui-se-vend/").bodyAsText()
        assertEquals(shop.gemPacks.size + shop.grades.size + shop.subscriptions.size, Regex("""<li class="offre">""").findAll(html).count())
        val text = mainText(html)
        assertTrue(text.contains("Tout ce qui s'achète se gagne aussi en jouant."))
        assertTrue(text.contains("Les grades se prennent dans l'ordre."))
        for (pack in shop.gemPacks) {
            assertTrue(html.contains("""<span class="sa-ticket">${pack.euros}$nbsp€</span>"""), "prix ${pack.euros}")
            assertTrue(text.contains(grouped(pack.gems)), "Gemmes ${pack.gems}")
        }
        for (grade in shop.grades) {
            assertTrue(html.contains("""<span class="offre__nom">${grade.name}</span>"""), grade.name)
            assertTrue(text.contains("Bonus de Donar ${grade.donarBonusPercent}$nbsp%"), grade.id)
        }
        for (subscription in shop.subscriptions) {
            assertTrue(text.contains("${subscription.days} jours"), subscription.id)
            subscription.donarBonusPercent?.let { assertTrue(text.contains("Bonus de Donar $it$nbsp%"), subscription.id) }
            subscription.gemsReturned?.let { assertTrue(text.contains("${grouped(it)} Gemmes rendues"), subscription.id) }
            subscription.reservedForGradeId?.let { id ->
                val name = shop.grades.first { it.id == id }.name
                assertTrue(text.contains("Réservé au grade $name"), subscription.id)
            }
        }
        // Tous les nombres de la page sont ceux du JSON, ni plus ni moins.
        val expected = buildList {
            for (pack in shop.gemPacks) {
                add(grouped(pack.gems)); add(pack.euros)
                if (pack.bonusPercent != 0) add(pack.bonusPercent.toString())
            }
            for (grade in shop.grades) { add(grouped(grade.gems)); add(grade.donarBonusPercent.toString()) }
            for (subscription in shop.subscriptions) {
                add(grouped(subscription.gems)); add(subscription.days.toString())
                subscription.donarBonusPercent?.let { add(it.toString()) }
                subscription.gemsReturned?.let { add(grouped(it)) }
            }
        }.sorted()
        assertEquals(expected, numbersIn(text))
        // Ce qui n'est jamais en vente.
        for (item in listOf("Lingots", "zones de ville", "places au classement", "usines", "salariés", "niveaux", "multiplicateurs personnels", "ressources")) {
            assertTrue(html.contains("<li>les $item</li>"), item)
        }
    }

    @Test
    @DisplayName("Boutique : 200 en ouvert avec le h1, 404 en attente")
    fun shopPage() = checkPage("/boutique/", "Boutique")

    @Test
    @DisplayName("Boutique : les grades et le Pilier du JSON, l'historique en jeu, un renvoi vers la liste des interdits")
    fun shopMatchesDefinitions() = withSite { client ->
        val html = client.get("/boutique/").bodyAsText()
        val text = mainText(html)
        assertEquals(shop.grades.size + shop.subscriptions.size, Regex("""<li class="offre">""").findAll(html).count())
        for (grade in shop.grades) {
            assertTrue(html.contains("""<span class="offre__nom">${grade.name}</span>"""), grade.name)
            assertTrue(text.contains(grouped(grade.gems)), grade.id)
            assertTrue(text.contains("Bonus de Donar ${grade.donarBonusPercent}$nbsp%"), grade.id)
        }
        for (subscription in shop.subscriptions) {
            assertTrue(text.contains(grouped(subscription.gems)), subscription.id)
            assertTrue(text.contains("${subscription.days} jours"), subscription.id)
            subscription.gemsReturned?.let { assertTrue(text.contains("${grouped(it)} Gemmes rendues"), subscription.id) }
        }
        val expected = buildList {
            for (grade in shop.grades) { add(grouped(grade.gems)); add(grade.donarBonusPercent.toString()) }
            for (subscription in shop.subscriptions) {
                add(grouped(subscription.gems)); add(subscription.days.toString())
                subscription.donarBonusPercent?.let { add(it.toString()) }
                subscription.gemsReturned?.let { add(grouped(it)) }
            }
        }.sorted()
        assertEquals(expected, numbersIn(text))
        assertTrue(text.contains("Ton historique d'achats se lit en jeu, dans la boutique."))
        assertTrue(html.contains("""href="/ce-qui-se-vend/""""))
    }

    @Test
    @DisplayName("Boutique : le bouton Acheter des Gemmes n'existe que si l'adresse est configurée")
    fun shopButton() {
        withSite { client ->
            val html = client.get("/boutique/").bodyAsText()
            assertFalse(html.contains("Acheter des Gemmes</a>"))
            assertTrue(mainText(html).contains("La boutique ouvre avec le serveur."))
        }
        withSite(settings = SiteSettings(shopUrl = "https://boutique.exemple.test/gemmes")) { client ->
            val html = client.get("/boutique/").bodyAsText()
            assertTrue(
                Regex("""<a href="https://boutique.exemple.test/gemmes" class="sa-bouton sa-bouton--laiton">Acheter des Gemmes</a>""").containsMatchIn(html),
                html.substringAfter("Acheter des Gemmes"),
            )
            assertFalse(mainText(html).contains("La boutique ouvre avec le serveur."))
            assertWellFormedPage(html, "/boutique/ avec adresse")
        }
    }

    @Test
    @DisplayName("Vote : 200 en ouvert avec le h1, 404 en attente")
    fun votePage() = checkPage("/vote/", "Vote")

    @Test
    @DisplayName("Vote : un bouton par site configuré, sinon une phrase ; la récompense arrive, aucun chiffre")
    fun voteContent() {
        withSite { client ->
            val html = client.get("/vote/").bodyAsText()
            val text = mainText(html)
            assertTrue(text.contains("Les sites de vote arrivent avec le serveur."))
            assertTrue(text.contains("La récompense par vote arrive."))
            assertTrue(text.contains("Quand le serveur atteint l'objectif de votes, tout le monde reçoit la même récompense."))
            assertEquals(emptyList<String>(), numbersIn(text), "la page Vote ne tape aucun chiffre")
            assertFalse(html.contains("sa-bouton--laiton"))
        }
        val sites = listOf(VoteSite("Premier site", "https://un.exemple.test/vote"), VoteSite("Second site", "https://deux.exemple.test/"))
        withSite(settings = SiteSettings(voteSites = sites)) { client ->
            val html = client.get("/vote/").bodyAsText()
            for (site in sites) {
                assertTrue(
                    html.contains("""<a href="${site.url}" class="sa-bouton sa-bouton--laiton">${site.name}</a>"""),
                    site.name,
                )
            }
            assertTrue(html.indexOf("Premier site") < html.indexOf("Second site"))
            assertFalse(mainText(html).contains("Les sites de vote arrivent avec le serveur."))
            assertWellFormedPage(html, "/vote/ avec sites")
        }
    }

    @Test
    @DisplayName("Une date s'écrit en français")
    fun frenchDates() {
        assertEquals("5 novembre 2026", frenchDate(LocalDate.of(2026, 11, 5)))
        assertEquals("1er décembre 2026", frenchDate(LocalDate.of(2026, 12, 1)))
        assertEquals("28 février 2027", frenchDate(LocalDate.of(2027, 2, 28)))
    }

    @Test
    @DisplayName("La navigation du mode ouvert montre sept entrées dans l'ordre (Mon compte en bouton), sans Accueil, Ce qui se vend ni Mentions")
    fun navigation() = withSite { client ->
        val html = client.get("/").bodyAsText()
        val labels = Regex("""<a href="[^"]*" class="[^"]*navigation__(?:lien|bouton)"[^>]*>([^<]+)</a>""")
            .findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(listOf("Rejoindre", "Boutique", "Classements", "Vote", "Guide", "Actus", "Mon compte"), labels)
        // Mon compte est le seul bouton de fonte de la navigation ; il mène à l'inscription des pionniers.
        assertTrue(html.contains("""<a href="/pionniers/" class="sa-bouton sa-bouton--fonte navigation__bouton">Mon compte</a>"""))
        assertEquals(1, Regex("""navigation__bouton""").findAll(html).count())
    }

    @Test
    @DisplayName("Un contenu qui appelle un bloc inconnu, ou une page qui prévoit un bloc non appelé, échoue")
    fun insertionsAreChecked() {
        val render = { html: String, blocks: Map<String, kotlinx.html.DIV.() -> Unit> ->
            createHTML().div { div { avecInsertions(html, blocks) } }
        }
        assertThrows<IllegalStateException> { render("<p>{{inconnu}}</p>\n", emptyMap()) }
        assertThrows<IllegalStateException> { render("<p>Texte</p>\n", mapOf("tableau" to {})) }
        assertThrows<IllegalStateException> { render("<p>avant {{tableau}} après</p>\n", mapOf("tableau" to {})) }
        assertTrue(render("<p>avant</p>\n<p>{{tableau}}</p>\n<p>après</p>\n", mapOf("tableau" to {})).contains("<p>avant</p>"))
    }

    @Test
    @DisplayName("Aucun chiffre dans les contenus écrits à la main des pages publiques, et aucun nom de grade dans le code")
    fun noTypedNumbers() {
        val content = SiteContent.fromClasspath()
        for (name in listOf("accueil", "rejoindre", "ce-qui-se-vend", "boutique", "vote")) {
            val page = content.pages.first { it.name == name }
            val text = page.html.replace(Regex("<[^>]*>"), " ")
            assertFalse(Regex("\\d").containsMatchIn(text), "$name.md contient un chiffre : le nombre vient des définitions ou de la configuration")
        }
        // Les noms de grade viennent des définitions : ni « Légende » ni « Ultime » dans le code ni dans un contenu du site.
        val roots = listOf(
            TestDefinitions.directory.resolveSibling("core/src/main/kotlin/com/silooo/core/site"),
            TestDefinitions.directory.resolveSibling("core/src/main/resources/site/contenu"),
        )
        for (root in roots) {
            Files.walk(root).use { paths ->
                for (file in paths.filter { Files.isRegularFile(it) && (it.toString().endsWith(".kt") || it.toString().endsWith(".md")) }) {
                    val text = Files.readString(file)
                    for (word in shop.grades.map { it.name }.filter { it.lowercase() in setOf("légende", "ultime") }) {
                        assertFalse(text.contains(word, ignoreCase = true), "${file.fileName} écrit le grade « $word » : il vient des définitions")
                    }
                }
            }
        }
    }
}
