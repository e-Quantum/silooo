package com.silooo.core.site

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class SiteContentTest {
    private val validHeader = "---\ntitre: Essai\ndescription: Un essai.\nurl: /essai/\nmodes: [attente, ouvert]\n---\n"

    @Test
    @DisplayName("Les contenus du dépôt se chargent, avec une url qui finit par / et des modes")
    fun repositoryContent() {
        val content = SiteContent.fromClasspath()
        assertEquals(SiteContent.BUNDLED.toSet(), content.pages.map { it.name }.toSet())
        for (page in content.pages) {
            assertTrue(page.url.startsWith("/") && page.url.endsWith("/"), "${page.name} : url ${page.url}")
            assertTrue(page.modes.isNotEmpty(), "${page.name} : modes vide")
            assertTrue(page.title.isNotBlank() && page.description.isNotBlank() && page.html.isNotBlank(), page.name)
        }
    }

    @Test
    @DisplayName("La liste des contenus livrés est celle du dossier des ressources")
    fun bundledListMatchesFolder() {
        val folder = Path.of(checkNotNull(SiteContent::class.java.classLoader.getResource("site/contenu")).toURI())
        val files = Files.list(folder).use { stream ->
            // Les sous-dossiers (mises-a-jour/) ont leur propre liste et leur propre test (SitePublicPagesTest).
            stream.filter { Files.isRegularFile(it) }.map { it.fileName.toString().removeSuffix(".md") }.sorted().toList()
        }
        assertEquals(SiteContent.BUNDLED.sorted(), files)
    }

    @Test
    @DisplayName("Chaque mode montre une seule page à l'adresse /")
    fun oneHomePerMode() {
        val content = SiteContent.fromClasspath()
        assertEquals(listOf("attente"), content.forMode(SiteMode.ATTENTE).filter { it.url == "/" }.map { it.name })
        // La bêta montre tout sauf la vitrine : `/` reste la page d'attente.
        assertEquals(listOf("attente"), content.forMode(SiteMode.BETA).filter { it.url == "/" }.map { it.name })
        assertEquals(listOf("accueil"), content.forMode(SiteMode.OUVERT).filter { it.url == "/" }.map { it.name })
        for (mode in SiteMode.entries) assertTrue(content.forMode(mode).any { it.url == "/mentions-legales/" }, "mentions légales : absentes du mode ${mode.word}")
    }

    @Test
    @DisplayName("La bêta montre tout sauf la vitrine : les pages de l'ouverture, moins l'accueil, plus l'attente")
    fun betaShowsEverythingButTheShopWindow() {
        val content = SiteContent.fromClasspath()
        val beta = content.forMode(SiteMode.BETA).map { it.name }.toSet()
        // La page des pionniers a ses propres modes (elle appartient aux comptes) : elle ne se compare pas ici.
        val open = content.forMode(SiteMode.OUVERT).map { it.name }.toSet() - setOf("accueil", "pionniers")
        assertTrue(beta.containsAll(open), "absentes de la bêta : ${open - beta}")
        assertTrue("attente" in beta && "accueil" !in beta)
        // L'attente ne montre rien de l'ouverture que ses pages à elle.
        val waiting = content.forMode(SiteMode.ATTENTE).map { it.name }.toSet()
        for (name in listOf("rejoindre", "boutique", "classements", "vote", "ce-qui-se-vend")) {
            assertTrue(name !in waiting && name in beta, name)
        }
    }

    @Test
    @DisplayName("Les pages du wiki se rendent en bêta et en ouvert, jamais en attente")
    fun wikiIsBetaAndOpen() {
        val wiki = WikiContent.fromClasspath()
        assertTrue(wiki.isNotEmpty())
        for (page in wiki) assertEquals(setOf(SiteMode.BETA, SiteMode.OUVERT), page.modes, page.name)
    }

    @Test
    @DisplayName("Les quatre pages légales sont dans les trois modes, à leur adresse, avec des sections et une description de 160 caractères au plus")
    fun legalPages() {
        val expected = mapOf(
            "mentions-legales" to ("/mentions-legales/" to "Mentions légales"),
            "cgv" to ("/cgv/" to "Conditions de vente"),
            "confidentialite" to ("/confidentialite/" to "Confidentialité"),
            "parents" to ("/parents/" to "Note aux parents"),
        )
        val content = SiteContent.fromClasspath()
        for ((name, shown) in expected) {
            val page = content.pages.single { it.name == name }
            assertEquals(shown.first, page.url, name)
            assertEquals(shown.second, page.title, name)
            assertEquals(setOf(SiteMode.ATTENTE, SiteMode.BETA, SiteMode.OUVERT), page.modes, name)
            assertTrue(page.description.length <= 160, "$name : description de ${page.description.length} caractères")
            assertTrue(page.html.contains("<h2>"), "$name : des sections en ##")
            for (mode in SiteMode.entries) assertTrue(content.forMode(mode).any { it.name == name }, "$name : absente du mode ${mode.word}")
        }
        assertTrue(content.pages.none { it.name == "mentions" }, "l'ancienne page « mentions » n'existe plus")
    }

    @Test
    @DisplayName("Un en-tête sans titre fait échouer le chargement, avec le nom du fichier")
    fun missingTitle(@TempDir directory: Path) {
        Files.writeString(directory.resolve("sans-titre.md"), "---\ndescription: Un essai.\nurl: /essai/\nmodes: [attente]\n---\nCorps.\n")
        val error = assertThrows<SiteContentException> { SiteContent.fromDirectory(directory) }
        assertTrue(error.message!!.contains("sans-titre.md"), error.message)
        assertTrue(error.message!!.contains("titre"), error.message)
    }

    @Test
    @DisplayName("Un en-tête cassé est refusé : url sans barre finale, mode inconnu, liste vide, en-tête non fermé")
    fun brokenHeaders() {
        val broken = mapOf(
            "url.md" to validHeader.replace("/essai/", "/essai"),
            "mode.md" to validHeader.replace("[attente, ouvert]", "[bientot]"),
            "vide.md" to validHeader.replace("[attente, ouvert]", "[]"),
            "liste.md" to validHeader.replace("[attente, ouvert]", "attente"),
            "ouvert.md" to "---\ntitre: Essai\n",
            "debut.md" to "titre: Essai\n",
        )
        for ((name, text) in broken) {
            val error = assertThrows<SiteContentException>(name) { SiteContent.parse(name, text) }
            assertTrue(error.message!!.contains(name), error.message)
        }
    }

    @Test
    @DisplayName("Le Markdown est rendu en HTML, le HTML brut est escamoté, les liens relatifs restent")
    fun markdownRendering() {
        val page = SiteContent.parse(
            "essai.md",
            validHeader + "## Titre\n\nUn [lien](/mentions-legales/) et <script>alert(1)</script> et [piège](javascript:alert(1)).\n",
        )
        assertEquals(setOf(SiteMode.ATTENTE, SiteMode.OUVERT), page.modes)
        assertTrue(page.html.contains("<h2>Titre</h2>"))
        assertTrue(page.html.contains("""href="/mentions-legales/">lien</a>"""), page.html)
        assertFalse(page.html.contains("<script>"))
        assertFalse(page.html.contains("javascript:"))
        assertNotNull(page.html)
    }
}
