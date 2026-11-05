package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.FakeDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La page d'attente (maquettes SiteAttente et SiteAttenteBureau, lignes R33 et R34 de docs/site-revision.md) : le logo et le nom,
 * l'accroche, le bouton « Me prévenir » (le seul bouton rouge), le chantier tiré des actus « étape », « Dans le jeu » et le
 * dessin d'île. Le gabarit pose Oooscar et sa bulle ; ce fichier ne vérifie que ce que la page y met. Les actus sont construites ici
 * (`NewsArticles.parse`), les textes attendus sont ceux de la maquette.
 */
class SiteAttenteTest {
    /** Une étape franchie, l'étape en cours (la première à venir par son rang) et une étape à venir. */
    private val steps = listOf(
        NewsArticles.parse(
            "plans-du-jeu.md",
            """
            ---
            titre: Les plans du jeu sont prêts
            description: Le plan de construction est écrit.
            date: 2026-10-30
            etape: oui
            ---
            ## Nouveau
            - Le plan en huit phases.
            """.trimIndent(),
        ),
        NewsArticles.parse(
            "moteur-du-jeu.md",
            """
            ---
            titre: Le moteur du jeu tourne
            description: Le service décide de tout.
            publie: non
            etape: oui
            ordre: 1
            ---
            """.trimIndent(),
        ),
        NewsArticles.parse(
            "premier-joueur.md",
            """
            ---
            titre: Premier joueur sur son île
            description: Un joueur arrive sur son île.
            publie: non
            etape: oui
            ordre: 2
            ---
            """.trimIndent(),
        ),
    )

    private fun withSite(
        articles: List<NewsArticle> = emptyList(),
        discordUrl: String? = null,
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        application {
            siteModule(
                Dependencies(FakeDatabase(), "cle", definitions = TestDefinitions.loaded, siteMode = SiteMode.ATTENTE, discordUrl = discordUrl),
                articles = articles,
            )
        }
        block(createClient { followRedirects = false })
    }

    /** Le `<main>` de la page, sans rien de ce que pose le gabarit autour. */
    private fun main(html: String): String = html.substringAfter("<main").substringBefore("</main>")

    private fun imgs(html: String): List<String> = Regex("<img[^>]*>").findAll(html).map { it.value }.toList()

    private fun attribute(tag: String, name: String): String? = Regex("""\b$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

    /** Les rangs d'une section, un texte par rang : un lien ou un bloc, jamais imbriqués dans la page. */
    private fun rows(section: String): List<String> =
        Regex("""<(a|div)\b[^>]*\bclass="rang[ "][^>]*>.*?</\1>""", RegexOption.DOT_MATCHES_ALL).findAll(section).map { it.value }.toList()

    private fun chantierSection(html: String): String =
        main(html).substringAfter("""<section class="sa-panneau chantier"""").substringBefore("</section>")

    private fun gameSection(html: String): String =
        main(html).substringAfter("""<section class="sa-panneau dans-le-jeu"""").substringBefore("</section>")

    @Test
    @DisplayName("Attente : le contenu se pose sur le ciel, dans main.ciel.attente et sans le panneau de fonte")
    fun mainIsOnTheSky() = withSite { client ->
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val html = response.bodyAsText()
        assertTrue(Regex("""<main class="ciel attente"[^>]*>""").containsMatchIn(html), "main.ciel.attente attendu")
        assertFalse(Regex("""<main class="[^"]*sa-panneau""").containsMatchIn(html), "la page n'est pas dans le panneau de fonte")
        assertFalse(html.contains("ouvre bientôt"), "l'ancienne annonce n'existe plus")
        assertFalse(html.contains("Le serveur ouvre bientôt !"))
    }

    @Test
    @DisplayName("Attente : le h1 Silooo et le logo dans .attente__marque, puis l'accroche et le paragraphe du contenu")
    fun brandAndTagline() = withSite { client ->
        val html = client.get("/").bodyAsText()
        val main = main(html)
        val brand = Regex("""<div class="attente__marque">\s*(<img[^>]*>)\s*<h1>Silooo</h1>\s*</div>""").find(main)
        assertTrue(brand != null, "le logo et le h1 « Silooo » forment la marque : $main")
        val logo = brand!!.groupValues[1]
        assertEquals("/public/logo/silooo-logo.png", attribute(logo, "src"))
        assertEquals("66", attribute(logo, "width"))
        assertEquals("80", attribute(logo, "height"))
        assertTrue(!attribute(logo, "alt").isNullOrBlank(), "le logo a son alt : $logo")
        assertEquals(1, Regex("<h1[ >]").findAll(html).count(), "un seul h1 par page")
        assertTrue(main.contains("""<p class="attente__accroche">Produis de jour comme de nuit</p>"""))
        assertTrue(
            Regex(
                """<div class="attente__texte">\s*<p>Serveur Minecraft Bedrock : tu fais tourner des usines sur une île dans le ciel """ +
                    """et tu y bâtis ta ville\. Sur console, téléphone et PC\. Pas encore ouvert\.</p>""",
            ).containsMatchIn(main),
            "le paragraphe du contenu est dans .attente__texte",
        )
    }

    @Test
    @DisplayName("Attente : « Me prévenir » mène à /pionniers/ et c'est le seul bouton rouge, même avec un Discord configuré")
    fun theOnlyRedButton() {
        for (discord in listOf(null, "https://discord.gg/exemple")) {
            withSite(discordUrl = discord) { client ->
                val html = client.get("/").bodyAsText()
                val main = main(html)
                assertTrue(main.contains("""<a href="/pionniers/" class="sa-bouton attente__bouton">Me prévenir</a>"""), main)
                assertEquals(1, main.split("sa-bouton").size - 1, "un seul bouton dans le contenu : $main")
                // La navigation a ses boutons de fonte (sa-bouton sa-bouton--fonte) ; tout autre sa-bouton est un bouton rouge.
                assertEquals(1, Regex("""class="sa-bouton(?! sa-bouton--)""").findAll(html).count(), "un seul bouton rouge par page")
                assertFalse(html.contains("Rejoins le Discord"), "le bouton Discord de l'ancienne page n'existe plus")
                assertTrue(main.contains("""<p class="attente__petit">Par Xbox ou Discord. Les inscrits entrent en bêta et reçoivent le titre Pionnier.</p>"""))
            }
        }
    }

    @Test
    @DisplayName("Attente : sans étape, pas de section Le chantier ; « Dans le jeu » reste")
    fun noChantierWithoutSteps() = withSite { client ->
        val main = main(client.get("/").bodyAsText())
        assertFalse(main.contains("""aria-label="Le chantier""""))
        assertFalse(main.contains("chantier"), "aucune classe du chantier sans étape")
        assertTrue(main.contains("""<section class="sa-panneau dans-le-jeu" aria-label="Ce que tu vas faire">"""))
    }

    @Test
    @DisplayName("Attente : le chantier montre l'étape franchie (lien, Fait), l'étape en cours (En cours) et l'étape à venir (cadenas), dans l'ordre")
    fun chantierRows() = withSite(articles = steps) { client ->
        val html = client.get("/").bodyAsText()
        val main = main(html)
        assertTrue(main.contains("""<section class="sa-panneau chantier" aria-label="Le chantier">"""))
        val section = chantierSection(html)
        assertTrue(section.contains("""<h2 class="sa-plaque sa-plaque--petite">Le chantier</h2>"""))
        val rows = rows(section)
        assertEquals(3, rows.size, section)

        val done = rows[0]
        assertTrue(done.startsWith("""<a href="/actus/plans-du-jeu/" class="rang">"""), "l'étape franchie mène à son actu : $done")
        assertTrue(done.contains("""<span class="rang__etat rang__etat--faite">"""))
        assertTrue(done.contains("<svg") && done.contains("""viewBox="0 0 8 8"""") && done.contains("""aria-hidden="true""""), "la coche est un SVG décoratif")
        assertTrue(done.contains("Les plans du jeu sont prêts"))
        assertTrue(done.contains("""<span class="rang__fin">Fait</span>"""))

        val ongoing = rows[1]
        assertTrue(ongoing.startsWith("""<div class="rang">"""), "l'étape en cours n'est pas un lien : $ongoing")
        assertTrue(ongoing.contains("""<span class="rang__etat rang__etat--en-cours">"""))
        val gear = imgs(ongoing).single()
        assertEquals("/public/atelier/icones/engrenage.png", attribute(gear, "src"))
        assertEquals("", attribute(gear, "alt"), "l'icône est décorative")
        assertEquals("20", attribute(gear, "width"))
        assertEquals("20", attribute(gear, "height"))
        assertTrue(ongoing.contains("Le moteur du jeu tourne"))
        assertTrue(ongoing.contains("""<span class="rang__fin">En cours</span>"""))

        val coming = rows[2]
        assertTrue(coming.startsWith("""<div class="rang">"""), "l'étape à venir n'est pas un lien : $coming")
        assertTrue(coming.contains("""<span class="rang__etat rang__etat--a-venir">"""))
        val lock = imgs(coming).single()
        assertEquals("/public/atelier/icones/cadenas.png", attribute(lock, "src"))
        assertEquals("20", attribute(lock, "width"))
        assertTrue(coming.contains("Premier joueur sur son île"))
        assertFalse(coming.contains("rang__fin"), "rien à droite d'une étape à venir")

        // Les mots s'écrivent en minuscules dans le HTML : c'est le CSS qui les met en capitales.
        assertFalse(main.contains("FAIT") || main.contains("EN COURS"))
        assertTrue(section.indexOf("Les plans du jeu") < section.indexOf("Le moteur du jeu") && section.indexOf("Le moteur du jeu") < section.indexOf("Premier joueur"))
    }

    @Test
    @DisplayName("Attente : « Dans le jeu » a trois rangs, Usines, Ville et Prestige, chacun avec son icône")
    fun gamePanel() = withSite { client ->
        val html = client.get("/").bodyAsText()
        val section = gameSection(html)
        assertTrue(section.contains("""<h2 class="sa-plaque sa-plaque--petite">Dans le jeu</h2>"""))
        val rows = rows(section)
        assertEquals(3, rows.size, section)
        val expected = listOf(
            Triple("usine", "Usines", "Pose des usines et monte leurs niveaux. Chaque palier double leur production."),
            Triple("ville", "Ville", "Bâtis ta ville sur ton île, zone par zone."),
            Triple("etoile", "Prestige", "Repars de zéro et gagne des Lingots, qui accélèrent la course suivante."),
        )
        for ((index, row) in rows.withIndex()) {
            val (icon, title, detail) = expected[index]
            assertTrue(row.startsWith("""<div class="rang rang--haut">"""), row)
            val image = imgs(row).single()
            assertEquals("/public/atelier/icones/$icon.png", attribute(image, "src"))
            assertEquals("", attribute(image, "alt"), "l'icône est décorative")
            assertEquals("32", attribute(image, "width"))
            assertEquals("32", attribute(image, "height"))
            assertTrue(row.contains("""<span class="rang__bloc">"""))
            assertTrue(row.contains("""<span class="rang__titre">$title</span>"""), row)
            assertTrue(row.contains("""<span class="rang__detail">$detail</span>"""), row)
        }
    }

    @Test
    @DisplayName("Attente : le dessin d'île est décoratif (aria-hidden), sans image, et Oooscar reste seul sur le sol avec sa bulle")
    fun islandAndMascot() = withSite(articles = steps) { client ->
        val html = client.get("/").bodyAsText()
        assertTrue(html.contains("""<div class="ile" aria-hidden="true">"""), "le dessin d'île est caché aux lecteurs d'écran")
        val island = main(html).substringAfter("""class="ile"""").substringBefore("</section>")
        assertEquals(2, Regex("""class="nuage ile__nuage-\d"""").findAll(island).count(), "deux nuages")
        for (n in 1..3) assertTrue(island.contains("""class="ile__bloc ile__bloc-$n""""), "le bloc de terre $n")
        assertTrue(Regex("""<div class="ile__silo">\s*<b></b>\s*<i></i>\s*</div>""").containsMatchIn(island), "le silo : un chapeau et un corps")
        assertTrue(island.contains("""class="ile__planches""""), "les planches")
        assertFalse(island.contains("<img"), "pas d'Oooscar dans l'île : une seule mascotte par page")
        assertEquals(1, Regex("""class="sa-oooscar"""").findAll(html).count(), "Oooscar n'est qu'une fois sur la page")
        val bubble = Regex("""<p class="sa-bulle"[^>]*>([^<]+)</p>""").find(html)?.groupValues?.get(1)
        assertEquals("On construit. Reviens vite !", bubble, "la bulle du gabarit")
    }

    @Test
    @DisplayName("Attente : aucun style en ligne dans le contenu, un seul h1, des scripts du site seulement, un alt sur chaque image")
    fun wellFormed() = withSite(articles = steps) { client ->
        val html = client.get("/").bodyAsText()
        assertFalse(main(html).contains("style="), "aucun style en ligne dans main")
        assertTrue(Regex("""<html[^>]*\blang="fr"""").containsMatchIn(html), "lang=\"fr\" manque")
        assertEquals(1, Regex("<h1[ >]").findAll(html).count(), "il faut exactement un h1")
        assertTrue(Regex("<title>[^<]+</title>").containsMatchIn(html), "title vide")
        assertTrue(Regex("""<meta name="description" content="[^"]+"""").containsMatchIn(html), "meta description vide")
        assertOnlySiteScripts(html, "/ (attente)")
        for (image in imgs(html)) assertTrue(attribute(image, "alt") != null, "image sans alt : $image")
        assertFalse(html.contains("{{"), "un marqueur de contenu est resté dans la page")
    }
}
