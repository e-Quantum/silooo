package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.FakeDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * Les actus (docs/site.md, « Actus ») : le format des fichiers de `site/contenu/actus/` et ses refus, le tri, le panneau
 * « Le chantier », puis la liste `/actus/` et les articles `/actus/<slug>/` tels que le visiteur les reçoit, dans les deux modes.
 */
class SiteActusTest {
    private val discord = "https://discord.gg/exemple"

    /** Une actu publiée au plan fixe, complète : une introduction, trois points, deux changements, deux corrections, une étape. */
    private val fullText = """
        ---
        titre: Une semaine de plus
        description: Trois nouveautés, deux changements et deux corrections.
        date: 2026-11-12
        etape: oui
        ---
        Une semaine de plus sur les îles.

        ## Nouveau

        - Les incidents à réparer arrivent sur les usines.
        - Oooscar te prévient quand le Silo est plein.
        - Le Panthéon ouvre la salle des villes.

        ## Changements

        - La Carrière produit plus pendant la première heure.
        - La Vote party demande moins de votes.

        ## Corrections

        - Le compteur ne saute plus après une reconnexion.
        - La fiche d'usine affiche le bon prix.
    """.trimIndent() + "\n"

    /** Une actu publiée sans introduction ni changements : un point nouveau, deux corrections, pas une étape. */
    private val shortText = """
        ---
        titre: Un correctif
        description: Un point nouveau et deux corrections.
        date: 2026-11-05
        ---
        ## Nouveau

        - Un nouveau point.

        ## Corrections

        - Un premier point corrigé.
        - Un second point corrigé.
    """.trimIndent() + "\n"

    private fun article(slug: String, text: String): NewsArticle = NewsArticles.parse("$slug.md", text)

    /** Une actu publiée d'un point, à la date donnée. */
    private fun published(slug: String, date: String, step: Boolean = false): NewsArticle = article(
        slug,
        "---\ntitre: Titre $slug\ndescription: Description.\ndate: $date\netape: ${if (step) "oui" else "non"}\n---\n## Nouveau\n\n- Un point.\n",
    )

    /** Une étape à venir : pas de date, un corps vide, un rang (ou aucun). */
    private fun upcoming(slug: String, order: Int?): NewsArticle = article(
        slug,
        "---\ntitre: Titre $slug\ndescription: Plus tard.\netape: oui\npublie: non\n" + (order?.let { "ordre: $it\n" } ?: "") + "---\n",
    )

    private val full = article("semaine-deux", fullText)
    private val short = article("un-correctif", shortText)
    private val soon = upcoming("moteur-du-jeu", 1)
    private val later = upcoming("premier-joueur", 2)
    private val sample = listOf(short, soon, full, later)

    private fun withSite(
        mode: SiteMode,
        discordUrl: String? = null,
        articles: List<NewsArticle> = sample,
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        application {
            siteModule(
                Dependencies(FakeDatabase(), "cle", definitions = TestDefinitions.loaded, siteMode = mode, discordUrl = discordUrl),
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
            assertTrue(Regex("""\balt="[^"]+"""").containsMatchIn(img.value), "$where : image sans alt : ${img.value}")
        }
        assertOnlySiteScripts(html, where)
        assertFalse(html.contains("{{"), "$where : un marqueur de contenu est resté dans la page")
        assertFalse(Regex("""\sstyle=""").containsMatchIn(html), "$where : un style en ligne, que la politique de sécurité interdit")
        assertTrue(Regex("""class="sa-bouton"""").findAll(html).count() <= 1, "$where : un seul bouton rouge par page")
    }

    /** Le texte visible du `main`, sans balises, les entités les plus courantes défaites. */
    private fun mainText(html: String): String =
        html.substringAfter("<main").substringBefore("</main>")
            .replace(Regex("<[^>]*>"), " ")
            .replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("[ \n]+"), " ")

    private fun expectRefusal(file: String, text: String, vararg words: String) {
        val error = assertThrows<SiteContentException>("$file : $text") { NewsArticles.parse(file, text) }
        val message = error.message!!
        assertTrue(message.contains(file), "le nom du fichier manque dans : $message")
        for (word in words) assertTrue(message.contains(word), "« $word » manque dans : $message")
    }

    // ---------------------------------------------------------------- Le format des fichiers

    @Test
    @DisplayName("parse : une actu complète donne le slug, l'adresse, la date, l'étape, l'introduction, les trois parties et leurs comptes")
    fun parsesFullArticle() {
        assertEquals("semaine-deux", full.slug)
        assertEquals("/actus/semaine-deux/", full.url)
        assertEquals("Une semaine de plus", full.title)
        assertEquals("Trois nouveautés, deux changements et deux corrections.", full.description)
        assertEquals(LocalDate.of(2026, 11, 12), full.date)
        assertTrue(full.step)
        assertTrue(full.published)
        assertNull(full.order)
        assertTrue(full.intro.contains("Une semaine de plus sur les îles."), full.intro)
        assertEquals(listOf(NewsPart.NOUVEAU, NewsPart.CHANGEMENTS, NewsPart.CORRECTIONS), full.sections.map { it.part })
        assertEquals(listOf(3, 2, 2), full.sections.map { it.count })
        assertTrue(full.sections[0].html.contains("<li>Les incidents à réparer arrivent sur les usines.</li>"), full.sections[0].html)
        assertEquals(1, full.readingMinutes)
        assertEquals(listOf(NewsPart.NOUVEAU, NewsPart.CORRECTIONS), short.sections.map { it.part })
        assertEquals("", short.intro)
    }

    @Test
    @DisplayName("parse : sans clé publie ni etape, l'actu est publiée et n'est pas une étape ; une étape à venir peut être vide, sans date")
    fun defaultsAndEmptyUpcoming() {
        assertTrue(short.published)
        assertFalse(short.step)
        assertNull(short.order)
        val coming = article("plus-tard", "---\ntitre: Plus tard\ndescription: Un jour.\netape: oui\npublie: non\nordre: 3\n---\n")
        assertFalse(coming.published)
        assertTrue(coming.step)
        assertNull(coming.date)
        assertEquals(3, coming.order)
        assertTrue(coming.sections.isEmpty())
        assertEquals("", coming.intro)
        assertEquals(1, coming.readingMinutes)
        // Une actu à venir qui n'est pas une étape ne porte pas de rang.
        assertNull(article("brouillon", "---\ntitre: B\ndescription: D\npublie: non\n---\n").order)
    }

    @Test
    @DisplayName("parse : le temps de lecture est de deux cents mots la minute, une minute au moins")
    fun readingTime() {
        // Le corps compte « Nouveau », « Un » et « point » en plus de l'introduction : 197 mots d'introduction font 200 mots.
        fun minutes(words: Int) = article(
            "lecture",
            "---\ntitre: T\ndescription: D\ndate: 2026-11-05\n---\n" + "mot ".repeat(words) + "\n\n## Nouveau\n\n- Un point.\n",
        ).readingMinutes
        assertEquals(1, minutes(0))
        assertEquals(1, minutes(197))
        assertEquals(2, minutes(198))
        assertEquals(2, minutes(397))
        assertEquals(3, minutes(398))
    }

    @Test
    @DisplayName("parse refuse un nom de fichier qui n'est pas un slug")
    fun refusesFileNamesThatAreNotSlugs() {
        for (name in listOf("Premiere-heure.md", "première-heure.md", "premiere heure.md", "premiere_heure.md", "-premiere.md", "premiere-.md", "premiere--heure.md", "premiere-heure.txt", "premiere-heure", ".md")) {
            expectRefusal(name, fullText)
        }
        // Les chiffres et les tirets sont permis.
        assertEquals("beta-2", article("beta-2", fullText).slug)
    }

    @Test
    @DisplayName("parse refuse une actu publiée sans date, avec une date mal écrite, sans titre ou sans description")
    fun refusesMissingFields() {
        expectRefusal("sans-date.md", fullText.replace("date: 2026-11-12\n", ""), "date")
        expectRefusal("date-en-lettres.md", fullText.replace("2026-11-12", "12 novembre 2026"), "date")
        expectRefusal("date-impossible.md", fullText.replace("2026-11-12", "2026-13-45"), "date")
        expectRefusal("sans-titre.md", fullText.replace("titre: Une semaine de plus\n", ""), "titre")
        expectRefusal("sans-description.md", fullText.replace("description: Trois nouveautés, deux changements et deux corrections.\n", "description:\n"), "description")
        expectRefusal("non-ferme.md", "---\ntitre: T\n", "en-tête")
    }

    @Test
    @DisplayName("parse refuse une actu publiée sans partie, un intertitre hors plan, une partie en double ou dans le désordre, une partie sans point")
    fun refusesBrokenPlans() {
        val header = "---\ntitre: T\ndescription: D\ndate: 2026-11-05\n---\n"
        expectRefusal("sans-partie.md", header + "Seulement une introduction.\n", "au moins une partie")
        expectRefusal("hors-plan.md", fullText.replace("## Changements", "## Divers"), "Divers")
        expectRefusal("en-double.md", fullText + "\n## Corrections\n\n- Encore une.\n", "deux fois")
        expectRefusal("desordre.md", header + "## Corrections\n\n- Un.\n\n## Nouveau\n\n- Deux.\n", "ordre")
        expectRefusal("sans-point.md", header + "## Nouveau\n\nDu texte, mais aucun point.\n", "au moins un point")
        expectRefusal("sans-point-au-milieu.md", fullText.replace(Regex("- La Carrière.*\n- La Vote party.*\n"), ""), "Changements")
    }

    @Test
    @DisplayName("parse refuse un rang sur une actu publiée ou qui n'est pas une étape, un rang qui n'est pas un entier, et un indicateur qui n'est ni oui ni non")
    fun refusesBadFlags() {
        expectRefusal("rang-publie.md", fullText.replace("etape: oui\n", "etape: oui\nordre: 2\n"), "ordre")
        expectRefusal("rang-sans-etape.md", "---\ntitre: T\ndescription: D\npublie: non\nordre: 1\n---\n", "ordre")
        expectRefusal("rang-en-lettres.md", "---\ntitre: T\ndescription: D\netape: oui\npublie: non\nordre: premier\n---\n", "entier")
        expectRefusal("peut-etre.md", fullText.replace("etape: oui\n", "etape: oui\npublie: peut-être\n"), "publie", "oui ou non", "peut-être")
        expectRefusal("etape-peut-etre.md", fullText.replace("etape: oui", "etape: bientôt"), "etape", "oui ou non")
    }

    @Test
    @DisplayName("check refuse deux slugs égaux et deux étapes à venir de même rang, et laisse passer le reste")
    fun checkRefusesDuplicates() {
        val twin = published("meme-slug", "2026-11-05")
        val error = assertThrows<SiteContentException> { NewsArticles.check(listOf(twin, published("meme-slug", "2026-11-06"))) }
        assertTrue(error.message!!.contains("meme-slug"), error.message)
        val sameRank = assertThrows<SiteContentException> { NewsArticles.check(listOf(upcoming("a-venir", 1), upcoming("aussi-a-venir", 1))) }
        assertTrue(sameRank.message!!.contains("rang 1"), sameRank.message)
        // Deux rangs différents, ou deux étapes franchies (sans rang) : rien à refuser.
        assertEquals(2, NewsArticles.check(listOf(upcoming("a-venir", 1), upcoming("suivante", 2))).size)
        assertEquals(3, NewsArticles.check(listOf(twin, published("etape-un", "2026-11-05", step = true), published("etape-deux", "2026-11-05", step = true))).size)
    }

    @Test
    @DisplayName("fromDirectory lit les .md d'un dossier dans l'ordre des noms, ignore le reste et refuse un nom mal formé")
    fun readsADirectory(@TempDir directory: Path) {
        Files.writeString(directory.resolve("bonjour.md"), shortText)
        Files.writeString(directory.resolve("autre.md"), fullText)
        Files.writeString(directory.resolve("notes.txt"), "pas une actu")
        assertEquals(listOf("autre", "bonjour"), NewsArticles.fromDirectory(directory).map { it.slug })
        Files.writeString(directory.resolve("Mauvais Nom.md"), shortText)
        val error = assertThrows<SiteContentException> { NewsArticles.fromDirectory(directory) }
        assertTrue(error.message!!.contains("Mauvais Nom.md"), error.message)
    }

    // ---------------------------------------------------------------- Le tri et le chantier

    @Test
    @DisplayName("published ne garde que les actus publiées, la plus récente en tête, à date égale l'ordre inverse des slugs")
    fun publishedAreNewestFirst() {
        val articles = listOf(
            published("ancienne", "2026-11-05"),
            published("jour-a", "2026-11-20"),
            upcoming("brouillon", 1),
            published("recente", "2026-12-01"),
            published("jour-b", "2026-11-20"),
        )
        assertEquals(listOf("recente", "jour-b", "jour-a", "ancienne"), NewsArticles.published(articles).map { it.slug })
        assertTrue(NewsArticles.published(emptyList()).isEmpty())
    }

    @Test
    @DisplayName("chantier : les étapes franchies dans l'ordre des dates, puis la première à venir en cours, puis les autres à venir")
    fun chantierOrdersTheSteps() {
        val articles = listOf(
            upcoming("quatrieme", 4),
            published("deuxieme-faite", "2026-11-20", step = true),
            upcoming("sans-rang", null),
            upcoming("troisieme", 3),
            published("pas-une-etape", "2026-11-25"),
            published("premiere-faite", "2026-11-05", step = true),
            article("brouillon", "---\ntitre: B\ndescription: D\npublie: non\n---\n"),
        )
        val steps = NewsArticles.chantier(articles)
        assertEquals(
            listOf("Titre premiere-faite", "Titre deuxieme-faite", "Titre troisieme", "Titre quatrieme", "Titre sans-rang"),
            steps.map { it.title },
        )
        assertEquals(
            listOf(ChantierState.FAITE, ChantierState.FAITE, ChantierState.EN_COURS, ChantierState.A_VENIR, ChantierState.A_VENIR),
            steps.map { it.state },
        )
        // Seule une étape franchie mène à son article ; une étape à venir n'a pas d'adresse.
        assertEquals(listOf("/actus/premiere-faite/", "/actus/deuxieme-faite/", null, null, null), steps.map { it.url })
        assertTrue(NewsArticles.chantier(emptyList()).isEmpty())
        // Toutes franchies : aucune n'est en cours.
        assertEquals(listOf(ChantierState.FAITE), NewsArticles.chantier(listOf(published("seule", "2026-11-05", step = true))).map { it.state })
    }

    // ---------------------------------------------------------------- La liste /actus/

    @Test
    @DisplayName("/actus/ répond 200 dans les deux modes : les actus publiées seulement, la plus récente en tête, la marque Étape et les dates")
    fun listPage() {
        for (mode in SiteMode.entries) {
            withSite(mode) { client ->
                val response = client.get("/actus/")
                assertEquals(HttpStatusCode.OK, response.status, mode.word)
                val html = response.bodyAsText()
                assertTrue(Regex("""<h1 class="sa-plaque">Actus</h1>""").containsMatchIn(html), "${mode.word} : le h1")
                assertWellFormedPage(html, "/actus/ (${mode.word})")
                assertTrue(Regex("""<main class="ciel actus"""").containsMatchIn(html), "la page se pose sur le ciel")
                // Le texte de actus.md, rendu.
                val intro = SiteContent.fromClasspath().pages.first { it.name == "actus" }.html
                assertTrue(html.contains(intro), "le texte de actus.md manque")
                // Les actus publiées, la plus récente (semaine-deux, 12 novembre) avant l'autre (un-correctif, 5 novembre).
                val links = Regex("""<a href="(/actus/[^"]+)" class="rang">""").findAll(html).map { it.groupValues[1] }.toList()
                assertEquals(listOf("/actus/semaine-deux/", "/actus/un-correctif/"), links, mode.word)
                assertTrue(html.contains("""<span class="rang__titre">Une semaine de plus</span>"""))
                assertTrue(html.contains("""<time datetime="2026-11-12">12 novembre 2026</time>"""))
                assertTrue(html.contains("""<time datetime="2026-11-05">5 novembre 2026</time>"""))
                // Les actus à venir n'ont ni ligne ni adresse.
                assertFalse(html.contains("moteur-du-jeu") || html.contains("Titre moteur-du-jeu") || html.contains("premier-joueur"))
                // La marque Étape : sur la ligne de l'étape, pas sur l'autre.
                assertEquals(1, Regex("""<span class="rang__etape">Étape</span>""").findAll(html).count())
                val firstRow = html.substringAfter("/actus/semaine-deux/").substringBefore("/actus/un-correctif/")
                assertTrue(firstRow.contains("rang__etape"), "la marque Étape est sur l'étape")
                assertFalse(html.substringAfter("/actus/un-correctif/").substringBefore("</main>").contains("rang__etape"))
                // La navigation marque Actus comme page courante.
                assertTrue(html.contains("""<a href="/actus/" class="navigation__lien" aria-current="page">Actus</a>"""), "la navigation marque Actus")
            }
        }
    }

    @Test
    @DisplayName("/actus/ sans actu publiée dit qu'il n'y a rien à lire encore, sans ligne ni lien")
    fun emptyListPage() {
        for (mode in SiteMode.entries) {
            for (articles in listOf(emptyList<NewsArticle>(), listOf(soon, later))) {
                withSite(mode, articles = articles) { client ->
                    val response = client.get("/actus/")
                    assertEquals(HttpStatusCode.OK, response.status)
                    val html = response.bodyAsText()
                    assertTrue(html.contains("actus__vide"), "le message d'une liste vide manque")
                    assertTrue(mainText(html).contains("Rien à lire pour l'instant."), mainText(html))
                    assertFalse(html.contains("""class="rang""""))
                    assertWellFormedPage(html, "/actus/ vide (${mode.word})")
                }
            }
        }
    }

    @Test
    @DisplayName("/actus mène à /actus/ par une redirection 301, dans les deux modes")
    fun listRedirects() {
        for (mode in SiteMode.entries) {
            withSite(mode) { client ->
                val response = client.get("/actus")
                assertEquals(HttpStatusCode.MovedPermanently, response.status, mode.word)
                assertEquals("/actus/", response.headers[HttpHeaders.Location], mode.word)
            }
        }
    }

    // ---------------------------------------------------------------- Un article /actus/<slug>/

    @Test
    @DisplayName("Un article publié répond 200 dans les deux modes : fil d'Ariane, titre, date, temps de lecture, sommaire chiffré, parties dans l'ordre")
    fun articlePage() {
        for (mode in SiteMode.entries) {
            withSite(mode) { client ->
                val response = client.get("/actus/semaine-deux/")
                assertEquals(HttpStatusCode.OK, response.status, mode.word)
                val html = response.bodyAsText()
                val where = "/actus/semaine-deux/ (${mode.word})"
                assertWellFormedPage(html, where)
                assertTrue(html.contains("<title>Une semaine de plus · Silooo</title>"), where)
                assertTrue(html.contains("""<meta name="description" content="Trois nouveautés, deux changements et deux corrections.">"""), where)
                assertTrue(Regex("""<h1 class="sa-plaque">Une semaine de plus</h1>""").containsMatchIn(html), "$where : le h1")
                // La page se pose sur le ciel : <main> n'a pas le panneau de fonte.
                assertTrue(Regex("""<main class="ciel actu"""").containsMatchIn(html), where)
                assertFalse(Regex("""<main class="[^"]*sa-panneau""").containsMatchIn(html), where)
                assertTrue(html.contains("""src="/public/atelier/oooscar/oooscar-fier.png""""), "$where : Oooscar est fier")

                // Le fil d'Ariane : Actus mène à la liste, puis le titre.
                assertTrue(Regex("""<nav [^>]*class="fil"[^>]*aria-label="Fil d(?:'|&#39;)Ariane"""").containsMatchIn(html), "$where : le fil d'Ariane")
                assertTrue(html.contains("""<a href="/actus/">Actus</a>"""), where)
                val text = mainText(html)
                // Actus (le lien), le séparateur, le titre du fil, puis le h1, puis la ligne de date.
                assertTrue(text.contains("Actus › Une semaine de plus Une semaine de plus 12 novembre 2026 · 1 min de lecture"), "$where : $text")

                // La date et le temps de lecture.
                assertTrue(html.contains("""<time datetime="2026-11-12">12 novembre 2026</time>"""), where)

                // Le sommaire : une ligne par partie présente, le bon chiffre, le bon ordre.
                assertTrue(Regex("""<section [^>]*class="sa-panneau sommaire-actu"""").containsMatchIn(html), "$where : le sommaire")
                val summary = html.substringAfter("""aria-label="Sommaire"""").substringBefore("</section>")
                assertEquals(
                    listOf("#nouveau", "#changements", "#corrections"),
                    Regex("""<a href="(#[a-z]+)" class="rang">""").findAll(summary).map { it.groupValues[1] }.toList(),
                    where,
                )
                assertTrue(summary.contains("""<span class="rang__numero rang__numero--nouveau">3</span>"""), summary)
                assertTrue(summary.contains("""<span class="rang__numero rang__numero--changements">2</span>"""), summary)
                assertTrue(summary.contains("""<span class="rang__numero rang__numero--corrections">2</span>"""), summary)
                assertTrue(text.contains("3 Nouveau 2 Changements 2 Corrections"), "$where : $text")

                // L'introduction, puis les trois parties, chacune avec son ancre, son intertitre et ses points.
                assertTrue(html.contains("""<div class="actu__intro">"""), where)
                val ids = listOf("nouveau", "changements", "corrections").map { html.indexOf("""id="$it"""") }
                assertTrue(ids.all { it >= 0 } && ids == ids.sorted(), "$where : les parties manquent ou sont dans le désordre : $ids")
                assertTrue(html.indexOf("actu__intro") < ids[0] && html.indexOf("sommaire-actu") < html.indexOf("actu__intro"), "$where : sommaire, introduction, parties")
                for (heading in listOf("Nouveau", "Changements", "Corrections")) assertTrue(html.contains("<h2>$heading</h2>"), "$where : $heading")
                assertTrue(html.contains("<li>Oooscar te prévient quand le Silo est plein.</li>"), where)
                assertTrue(html.contains("<li>La Vote party demande moins de votes.</li>"), where)
                assertTrue(text.contains("La fiche d'usine affiche le bon prix."), where)
                for ((part, count) in listOf("nouveau" to 3, "changements" to 2, "corrections" to 2)) {
                    val section = html.substringAfter("""id="$part"""").substringBefore("</section>")
                    assertEquals(count, Regex("<li>").findAll(section).count(), "$where : les points de $part")
                    assertTrue(html.contains("actu__partie--$part"), "$where : la classe de la partie $part")
                }

                // Pas de Discord configuré : pas de ligne « Un bug ? ».
                assertFalse(html.contains("actu__bug"), where)
            }
        }
    }

    @Test
    @DisplayName("Un article sans introduction ni changements n'a pas de bloc d'introduction, et son sommaire ne compte que ses deux parties")
    fun articleWithTwoParts() {
        for (mode in SiteMode.entries) {
            withSite(mode) { client ->
                val html = client.get("/actus/un-correctif/").bodyAsText()
                assertWellFormedPage(html, "/actus/un-correctif/ (${mode.word})")
                assertFalse(html.contains("actu__intro"))
                val summary = html.substringAfter("""aria-label="Sommaire"""").substringBefore("</section>")
                assertEquals(
                    listOf("#nouveau", "#corrections"),
                    Regex("""<a href="(#[a-z]+)" class="rang">""").findAll(summary).map { it.groupValues[1] }.toList(),
                )
                assertTrue(summary.contains("""rang__numero--nouveau">1</span>"""), summary)
                assertTrue(summary.contains("""rang__numero--corrections">2</span>"""), summary)
                val content = html.substringAfter("<main").substringBefore("</main>")
                assertFalse(content.contains("changements", ignoreCase = true), "aucune trace d'une partie absente")
                assertTrue(mainText(html).contains("5 novembre 2026 · 1 min de lecture"))
            }
        }
    }

    @Test
    @DisplayName("Le temps de lecture s'écrit « 1 min » au singulier comme au pluriel, et passe à 2 min au-delà de deux cents mots")
    fun articleReadingTime() {
        val long = article(
            "longue",
            "---\ntitre: Longue\ndescription: D.\ndate: 2026-11-05\n---\n" + "mot ".repeat(250) + "\n\n## Nouveau\n\n- Un point.\n",
        )
        withSite(SiteMode.ATTENTE, articles = listOf(long, short)) { client ->
            assertTrue(mainText(client.get("/actus/longue/").bodyAsText()).contains("5 novembre 2026 · 2 min de lecture"))
            assertTrue(mainText(client.get("/actus/un-correctif/").bodyAsText()).contains("5 novembre 2026 · 1 min de lecture"))
        }
    }

    @Test
    @DisplayName("Le lien « Un bug ? » vers le Discord n'apparaît sur un article que si le Discord est configuré")
    fun articleBugLink() {
        for (mode in SiteMode.entries) {
            withSite(mode, discordUrl = discord) { client ->
                val html = client.get("/actus/semaine-deux/").bodyAsText()
                assertTrue(
                    Regex("""<p class="actu__bug">\s*<a href="https://discord\.gg/exemple">Un bug \? Signale-le sur le Discord\.</a>\s*</p>""").containsMatchIn(html),
                    "${mode.word} : le lien vers le Discord",
                )
                assertWellFormedPage(html, "/actus/semaine-deux/ avec Discord (${mode.word})")
                // La liste n'a pas cette ligne.
                assertFalse(client.get("/actus/").bodyAsText().contains("actu__bug"))
            }
            withSite(mode) { client ->
                assertFalse(client.get("/actus/semaine-deux/").bodyAsText().contains("actu__bug"), mode.word)
            }
        }
    }

    @Test
    @DisplayName("Une actu non publiée ou inconnue répond 404, avec ou sans barre finale ; une actu publiée sans barre finale redirige en 301")
    fun unpublishedAndUnknownAreNotFound() {
        for (mode in SiteMode.entries) {
            withSite(mode) { client ->
                for (path in listOf("/actus/moteur-du-jeu/", "/actus/moteur-du-jeu", "/actus/premier-joueur/", "/actus/inconnue/", "/actus/inconnue")) {
                    assertEquals(HttpStatusCode.NotFound, client.get(path).status, "$path (${mode.word})")
                }
                val redirect = client.get("/actus/semaine-deux")
                assertEquals(HttpStatusCode.MovedPermanently, redirect.status, mode.word)
                assertEquals("/actus/semaine-deux/", redirect.headers[HttpHeaders.Location], mode.word)
            }
        }
    }

    @Test
    @DisplayName("Le plan du site liste /actus/ et les actus publiées, pas les actus à venir")
    fun sitemapListsPublishedArticles() {
        for (mode in SiteMode.entries) {
            withSite(mode) { client ->
                val xml = client.get("/sitemap.xml").bodyAsText()
                for (path in listOf("/actus/", "/actus/semaine-deux/", "/actus/un-correctif/")) {
                    assertTrue(xml.contains("$path</loc>"), "$path manque au plan du site (${mode.word})")
                }
                assertFalse(xml.contains("moteur-du-jeu") || xml.contains("premier-joueur"), "une actu à venir est listée")
            }
        }
    }

    // ---------------------------------------------------------------- Les actus livrées

    @Test
    @DisplayName("La liste des actus livrées est celle du dossier des ressources, et fromClasspath les lit toutes")
    fun bundledListMatchesFolder() {
        val folder = Path.of(checkNotNull(NewsArticles::class.java.classLoader.getResource("site/contenu/actus")).toURI())
        val files = Files.list(folder).use { stream ->
            stream.map { it.fileName.toString() }.filter { it.endsWith(".md") }.sorted().toList()
        }
        assertEquals(NewsArticles.BUNDLED.sorted(), files)
        val articles = NewsArticles.fromClasspath()
        assertEquals(NewsArticles.BUNDLED.size, articles.size)
        assertEquals(NewsArticles.BUNDLED.map { it.removeSuffix(".md") }.toSet(), articles.map { it.slug }.toSet())
    }

    @Test
    @DisplayName("Les actus livrées sont les cinq étapes du chantier : une franchie, qui mène à son article, puis une en cours et trois à venir")
    fun bundledArticlesAreTheChantier() {
        val articles = NewsArticles.fromClasspath()
        assertTrue(articles.all { it.step }, "chaque actu livrée est une étape du chantier")
        val steps = NewsArticles.chantier(articles)
        assertEquals(
            listOf("Les plans du jeu sont prêts", "Le moteur du jeu tourne", "Premier joueur sur son île", "La première heure se joue", "Bêta fermée pour les inscrits"),
            steps.map { it.title },
        )
        assertEquals(
            listOf(ChantierState.FAITE, ChantierState.EN_COURS, ChantierState.A_VENIR, ChantierState.A_VENIR, ChantierState.A_VENIR),
            steps.map { it.state },
        )
        assertEquals("/actus/plans-du-jeu/", steps.first().url)
        assertTrue(steps.drop(1).all { it.url == null })
        // Une seule publiée : les quatre autres n'ont ni date ni article.
        assertEquals(listOf("plans-du-jeu"), NewsArticles.published(articles).map { it.slug })
        assertEquals(3, NewsArticles.published(articles).single().sections.single().count)
    }

    @Test
    @DisplayName("Avec les actus livrées, la liste montre l'article des plans du jeu en étape, et cet article répond 200 dans les deux modes")
    fun bundledPagesRender() {
        for (mode in SiteMode.entries) {
            withSite(mode, discordUrl = discord, articles = NewsArticles.fromClasspath()) { client ->
                val list = client.get("/actus/").bodyAsText()
                assertTrue(list.contains("""<a href="/actus/plans-du-jeu/" class="rang">"""), mode.word)
                assertTrue(list.contains("""<span class="rang__etape">Étape</span>"""), mode.word)
                assertWellFormedPage(list, "/actus/ livrées (${mode.word})")
                val response = client.get("/actus/plans-du-jeu/")
                assertEquals(HttpStatusCode.OK, response.status, mode.word)
                val html = response.bodyAsText()
                assertTrue(html.contains("""<time datetime="2026-11-05">5 novembre 2026</time>"""), mode.word)
                assertTrue(html.contains("""rang__numero rang__numero--nouveau">3</span>"""), mode.word)
                assertWellFormedPage(html, "/actus/plans-du-jeu/ (${mode.word})")
                assertEquals(HttpStatusCode.NotFound, client.get("/actus/moteur-du-jeu/").status, mode.word)
            }
        }
    }
}
