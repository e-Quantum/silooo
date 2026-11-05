package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.FakeDatabase
import com.silooo.core.definitions.StaffRole
import com.silooo.core.site.pages.inputName
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Le wiki (S3) : treize pages sous `/wiki/` en modes `beta` et `ouvert`, 404 en `attente`. Les valeurs attendues sont construites
 * depuis `TestDefinitions.loaded` : aucun chiffre du jeu n'est écrit ici.
 */
class SiteWikiTest {
    private val definitions = TestDefinitions.loaded
    private val nbsp = ' '
    private val wikiPages: List<ContentPage> = WikiContent.fromClasspath()

    private fun withSite(mode: SiteMode = SiteMode.OUVERT, wiki: List<ContentPage> = wikiPages, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) =
        testApplication {
            application {
                siteModule(Dependencies(FakeDatabase(), "cle", definitions = definitions, siteMode = mode), articles = emptyList(), wiki = wiki)
            }
            block(createClient { followRedirects = false })
        }

    private fun escapeHtml(text: String) = text.replace("&", "&amp;").replace("'", "&#39;").replace("\"", "&quot;")

    private fun decode(html: String): String = html.replace(Regex("<[^>]*>"), " ")
        .replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("[ \n]+"), " ")

    private fun mainText(html: String): String = decode(html.substringAfter("<main").substringBefore("</main>"))

    /** Les lignes du premier `tbody` : le texte de chaque cellule. */
    private fun rows(html: String): List<List<String>> =
        Regex("<tr>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).findAll(html.substringAfter("<tbody>").substringBefore("</tbody>")).map { row ->
            Regex("<t[hd][^>]*>(.*?)</t[hd]>", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1]).map { decode(it.groupValues[1]).trim() }.toList()
        }.toList()

    private val pagesOnly = wikiPages.filter { it.name != WikiContent.INDEX }

    @Test
    @DisplayName("Le wiki a treize contenus : l'index et douze pages, rendues en bêta et en ouvert seulement")
    fun thirteenContents() {
        assertEquals(13, wikiPages.size)
        assertEquals(12, pagesOnly.size)
        assertEquals(listOf("/wiki/"), wikiPages.filter { it.name == WikiContent.INDEX }.map { it.url })
        assertTrue(wikiPages.all { it.modes == setOf(SiteMode.BETA, SiteMode.OUVERT) }, "le wiki ne se rend qu'en bêta et en ouvert")
    }

    @Test
    @DisplayName("Chaque page du wiki : 200 en ouvert et en bêta, 404 en attente, un seul h1, lang, title, description, seulement les scripts du site, un bouton rouge au plus")
    fun everyPage() {
        for (page in wikiPages) {
            withSite(SiteMode.OUVERT) { client ->
                val response = client.get(page.url)
                assertEquals(HttpStatusCode.OK, response.status, page.url)
                val html = response.bodyAsText()
                assertTrue(Regex("""<h1[^>]*>${Regex.escape(escapeHtml(page.title))}</h1>""").containsMatchIn(html), "${page.url} : h1 « ${page.title} » attendu")
                assertEquals(1, Regex("<h1[ >]").findAll(html).count(), "${page.url} : un seul h1")
                assertTrue(Regex("""<html[^>]*\blang="fr"""").containsMatchIn(html), "${page.url} : lang")
                assertTrue(Regex("<title>[^<]+</title>").containsMatchIn(html), "${page.url} : title")
                assertTrue(Regex("""<meta name="description" content="[^"]+"""").containsMatchIn(html), "${page.url} : meta description")
                assertOnlySiteScripts(html, page.url)
                assertFalse(html.contains("{{"), "${page.url} : un marqueur est resté")
                assertTrue(Regex("""class="sa-bouton"""").findAll(html).count() <= 1, "${page.url} : un seul bouton rouge")
                if (page.name == WikiContent.INDEX) assertTrue(html.contains("""aria-current="page""""), "l'index est la page courante de la navigation")
            }
            withSite(SiteMode.BETA) { client ->
                assertEquals(HttpStatusCode.OK, client.get(page.url).status, "${page.url} doit répondre 200 en bêta")
            }
            withSite(SiteMode.ATTENTE) { client ->
                assertEquals(HttpStatusCode.NotFound, client.get(page.url).status, "${page.url} doit répondre 404 en attente")
            }
        }
    }

    @Test
    @DisplayName("/wiki sans barre finale redirige vers /wiki/ ; la navigation de l'ouvert porte Guide")
    fun redirectAndNavigation() = withSite { client ->
        val response = client.get("/wiki")
        assertEquals(HttpStatusCode.MovedPermanently, response.status)
        assertEquals("/wiki/", response.headers["Location"])
        assertTrue(Regex("""<a href="/wiki/"[^>]*>Guide</a>""").containsMatchIn(client.get("/").bodyAsText()))
    }

    @Test
    @DisplayName("L'index liste toutes les pages dans l'ordre de leur en-tête, une description chacune, et chaque lien mène à une page")
    fun indexListsPagesInOrder() = withSite { client ->
        val html = client.get("/wiki/").bodyAsText()
        val links = Regex("""<a href="(/wiki/[^"]+/)" class="sommaire__lien">""").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(pagesOnly.sortedBy { it.order }.map { it.url }, links)
        assertEquals(pagesOnly.size, links.toSet().size, "aucun doublon")
        val text = mainText(html)
        for (page in pagesOnly) assertTrue(text.contains(page.description), "${page.url} : sa description est dans l'index")
        for (link in links) assertEquals(HttpStatusCode.OK, client.get(link).status, link)
        // Les pages sont dans l'ordre où le joueur rencontre les systèmes.
        assertEquals(
            listOf(
                "premiere-heure", "usines", "silo-et-hors-ligne", "personnel", "monnaies", "prestige", "incidents-et-electricite",
                "commande-du-jour-et-carnet", "entreprise-partagee", "ville", "mots-du-jeu", "regles",
            ),
            links.map { it.removePrefix("/wiki/").removeSuffix("/") },
        )
    }

    @Test
    @DisplayName("Deux pages de même ordre, une page sans ordre ou une adresse hors de /wiki/ arrêtent le démarrage, avec le nom du fichier")
    fun orderIsChecked() {
        fun page(subject: String, order: String?) = WikiContent.parse(
            subject,
            "---\ntitre: T\ndescription: D.\nurl: /wiki/$subject/\nmodes: [ouvert]\n${order?.let { "ordre: $it\n" } ?: ""}---\nTexte.\n",
        )
        assertEquals(2, WikiContent.check(listOf(page("a", "1"), page("b", "2"))).size)
        val twice = assertThrows<SiteContentException> { WikiContent.check(listOf(page("a", "1"), page("b", "1"))) }
        assertTrue(twice.message!!.contains("wiki/a") && twice.message!!.contains("wiki/b"), twice.message)
        assertTrue(assertThrows<SiteContentException> { WikiContent.check(listOf(page("a", null))) }.message!!.contains("wiki/a.md"))
        assertTrue(assertThrows<SiteContentException> { page("a", "zéro") }.message!!.contains("ordre"))
        val elsewhere = WikiContent.parse("a", "---\ntitre: T\ndescription: D.\nurl: /autre/\nmodes: [ouvert]\nordre: 1\n---\nTexte.\n")
        assertTrue(assertThrows<SiteContentException> { WikiContent.check(listOf(elsewhere)) }.message!!.contains("/wiki/a/"))
        // Et sans index ni ordre répété dans le dépôt.
        val orders = pagesOnly.map { it.order }
        assertEquals(orders.size, orders.toSet().size)
    }

    @Test
    @DisplayName("La liste des contenus du wiki est celle du dossier des ressources")
    fun bundledListMatchesFolder() {
        val folder = Path.of(checkNotNull(SiteContent::class.java.classLoader.getResource("site/contenu/wiki")).toURI())
        val files = Files.list(folder).use { stream -> stream.map { it.fileName.toString() }.filter { it.endsWith(".md") }.sorted().toList() }
        assertEquals(WikiContent.BUNDLED.map { "$it.md" }.sorted(), files)
    }

    @Test
    @DisplayName("Usines : une ligne par usine des définitions, avec son nom et son époque, triées par époque puis dans l'ordre du fichier")
    fun factoriesTable() = withSite { client ->
        val html = client.get("/wiki/usines/").bodyAsText()
        val table = rows(html)
        val factories = definitions.factories.factories
        assertEquals(factories.size, table.size, "aucune ligne de plus ni de moins")
        val expected = factories.sortedBy { it.era }
        for ((row, factory) in table.zip(expected)) {
            assertEquals(factory.name, row[0])
            assertTrue(row[1].contains(factory.era.toString()), "${factory.name} : époque ${factory.era}")
            assertEquals(factory.products.joinToString(", "), row[2])
            assertEquals(if (factory.needsElectricity) "Oui" else "Non", row[4])
        }
        // Les entrées se disent par le nom de l'usine qui les produit, sinon par l'identifiant.
        val fonderie = table.first { it[0] == definitions.factories.factories.first { f -> f.id == "fonderie" }.name }
        assertTrue(fonderie[3].contains(inputName(definitions, "minerai-fer")) && !fonderie[3].contains("minerai-fer"), fonderie[3])
        assertEquals("Rien", table.first { it[0] == factories.first { f -> f.inputs.isEmpty() }.name }[3])
        assertEquals("beau-produit", inputName(definitions, "beau-produit"))
        // Aucun prix, aucune production chiffrée : la seule colonne de nombres est l'époque.
        for (row in table) assertTrue(row.drop(2).none { Regex("\\d").containsMatchIn(it) }, row.toString())
    }

    @Test
    @DisplayName("Personnel : chaque rareté des définitions est dans le tableau, avec ses valeurs ; l'agence et les postes viennent des définitions")
    fun staffPage() = withSite { client ->
        val html = client.get("/wiki/personnel/").bodyAsText()
        val text = mainText(html)
        val table = rows(html)
        assertEquals(definitions.personnel.rarities.size, table.size)
        for ((rarity, definition) in definitions.personnel.rarities) {
            val row = table.first { it[0] == rarity.name.lowercase() }
            assertTrue(row[1].startsWith(plain(definition.sharePercent)), "${rarity.name} : part")
            assertTrue(row[2].startsWith(plain(definition.productionBonusPercent)), "${rarity.name} : bonus")
        }
        assertTrue(text.contains("${definitions.personnel.agency.candidatesPerDay} candidats"))
        val leaders = definitions.personnel.agency.rolesPerSlot.count { it == StaffRole.CHEF }
        assertTrue(text.contains("$leaders chef d'équipe"))
        assertTrue(text.contains("${definitions.personnel.posts.levelsPerPost} niveaux"))
        assertTrue(text.contains("${plain(definitions.personnel.teamLeader.offlineCapBonusHours)} heures"))
    }

    @Test
    @DisplayName("Incidents et électricité : chaque sorte des définitions est sur la page, avec la Centrale et l'époque du courant")
    fun incidentsPage() = withSite { client ->
        val html = client.get("/wiki/incidents-et-electricite/").bodyAsText()
        val table = rows(html)
        assertEquals(definitions.incidents.kinds.size, table.size)
        for (kind in definitions.incidents.kinds) {
            val row = table.first { it[0] == kind.id.lowercase() }
            assertTrue(row[1].startsWith(plain(kind.slowdown * 100)), "${kind.id} : ralentissement")
        }
        val text = mainText(html)
        val electricity = definitions.incidents.electricity
        val plant = definitions.factories.factories.first { it.id == electricity.powerPlantId }
        assertTrue(text.contains(plant.name))
        assertTrue(text.contains("époque ${electricity.fromEra}"))
        for (factory in definitions.factories.factories.filter { it.needsElectricity }) assertTrue(text.contains(factory.name), factory.name)
    }

    @Test
    @DisplayName("Entreprise partagée : le nombre de membres et les heures d'invitation des définitions sont sur la page")
    fun sharedCompanyPage() = withSite { client ->
        val text = mainText(client.get("/wiki/entreprise-partagee/").bodyAsText())
        val company = definitions.world.company
        assertTrue(text.contains("${company.maxMembers} membres au plus"), text)
        assertTrue(text.contains("${company.maxMembers - 1} associés"), text)
        assertTrue(text.contains("${company.invitationHours} heures"), text)
    }

    @Test
    @DisplayName("Prestige et silo : les époques viennent des usines, le plafond et l'efficacité hors ligne des définitions")
    fun prestigeAndSiloPages() = withSite { client ->
        val prestige = mainText(client.get("/wiki/prestige/").bodyAsText())
        val last = definitions.factories.factories.maxOf { it.era }
        for (number in 1..last) assertTrue(prestige.contains("Époque $number :"), "époque $number")
        assertFalse(prestige.contains("Époque ${last + 1}"))
        val silo = mainText(client.get("/wiki/silo-et-hors-ligne/").bodyAsText())
        assertTrue(silo.contains("${plain(definitions.economy.offline.capHours)} heures"))
        assertTrue(silo.contains(plain(definitions.economy.offline.efficiency * 100) + nbsp + "%"))
    }

    @Test
    @DisplayName("Règles : un h1 et une seule phrase, à écrire par Kr")
    fun rulesPage() = withSite { client ->
        val html = client.get("/wiki/regles/").bodyAsText()
        assertEquals(1, Regex("<p>").findAll(html.substringAfter("<main").substringBefore("</main>")).count())
        assertTrue(mainText(html).contains("Les règles du serveur s'écrivent ici avant l'ouverture."))
    }

    @Test
    @DisplayName("La page des mots du jeu définit chaque mot de la liste, sans nom de colonne ni de code")
    fun glossaryPage() = withSite { client ->
        val text = mainText(client.get("/wiki/mots-du-jeu/").bodyAsText())
        val words = listOf(
            "Donar", "Lingots", "Gemmes", "Silo", "PDG", "associé", "part", "prestige", "époque", "étoile", "palier", "usine en ruine",
            "remise en état", "agence", "chef d'équipe", "incident", "Centrale", "commande du jour", "Carnet", "mairie", "zone",
        )
        for (word in words) assertTrue(text.contains("$word :"), "mot manquant : $word")
        for (code in listOf("company_", "player_", "EN_RUINE", "REMISE_EN_ETAT", "share_percent", "_id")) assertFalse(text.contains(code), code)
    }

    /** Un nombre comme le site l'écrit : virgule décimale, sans zéro inutile. */
    private fun plain(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().replace('.', ',')

    /**
     * Les nombres que le Markdown du wiki ne doit pas porter : un nombre suivi d'une unité du jeu. Un chiffre se lit dans les
     * définitions. Les dates et « époque 1 » restent permises.
     */
    private val forbidden = Regex("""\d[\d$nbsp ,.]*\s*(?:Donar|Lingots?|Gemmes?|%|heures?|h|€|joueurs?|associés?)(?![\p{L}\d])""", RegexOption.IGNORE_CASE)

    @Test
    @DisplayName("Aucun chiffre du jeu tapé dans le Markdown du wiki : pas de nombre devant Donar, Lingots, Gemmes, %, heures, h, €, joueurs, associés")
    fun noTypedNumbers() {
        val folder = Path.of(checkNotNull(SiteContent::class.java.classLoader.getResource("site/contenu/wiki")).toURI())
        Files.list(folder).use { stream -> stream.filter { it.toString().endsWith(".md") }.toList() }.forEach { file ->
            val body = Files.readString(file).substringAfter("\n---\n")
            assertEquals(null, forbidden.find(body)?.value, "${file.fileName} tape un chiffre du jeu : il vient des définitions")
        }
        // Le motif attrape ce qu'il doit attraper, et laisse passer les dates et les époques.
        for (bad in listOf("1 Donar", "10 Lingots", "3 500 Gemmes", "5 %", "5%", "8 heures", "8 h", "4,99 €", "4 joueurs", "4 associés", "1 heure")) {
            assertTrue(forbidden.containsMatchIn("texte $bad texte"), bad)
        }
        for (good in listOf("époque 1", "le 5 novembre 2026", "Époque 3 : Centrale", "1. Parler", "2026-11-05")) {
            assertFalse(forbidden.containsMatchIn("texte $good texte"), good)
        }
    }
}
