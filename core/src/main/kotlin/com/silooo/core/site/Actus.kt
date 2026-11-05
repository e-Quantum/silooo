package com.silooo.core.site

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** L'adresse de la liste des actus ; un article est à `$NEWS_URL<slug>/`. */
const val NEWS_URL = "/actus/"

/**
 * Les trois parties du plan fixe d'une actu (docs/site.md, « Actus ») : le sommaire chiffré les compte, dans cet ordre.
 * [heading] est l'intertitre tel qu'il s'écrit dans le Markdown (`## Nouveau`), [anchor] l'ancre du sommaire.
 */
enum class NewsPart(val heading: String, val anchor: String) {
    NOUVEAU("Nouveau", "nouveau"),
    CHANGEMENTS("Changements", "changements"),
    CORRECTIONS("Corrections", "corrections"),
}

/** Une partie écrite d'une actu : combien de points elle liste (le chiffre du sommaire) et son HTML. */
class NewsSection(val part: NewsPart, val count: Int, val html: String)

/**
 * Une actu : aujourd'hui un fichier `site/contenu/actus/<slug>.md`, demain une ligne de `news_articles` écrite dans
 * l'admin (docs/site.md, « Ce que le site garde »). Les deux portent les mêmes champs.
 *
 * Marquée « étape », l'actu compte dans le panneau « Le chantier » de la page d'attente : publiée, c'est une étape
 * franchie qui mène à l'article ; pas encore publiée, c'est une étape à venir dont seul le titre se lit (voir [chantier]).
 */
class NewsArticle(
    val slug: String,
    val title: String,
    val description: String,
    /** La date de publication ; `null` tant que l'actu n'est pas publiée. */
    val date: LocalDate?,
    val step: Boolean,
    val published: Boolean,
    /** Le rang d'une étape à venir dans le chantier ; `null` pour une actu publiée. */
    val order: Int?,
    /** Le texte d'avant le premier intertitre, en HTML ; vide quand l'actu commence par une partie. */
    val intro: String,
    /** Les parties écrites, dans l'ordre de [NewsPart] ; une partie absente ne s'affiche pas. */
    val sections: List<NewsSection>,
    /** Le temps de lecture en minutes, une au moins, à deux cents mots la minute. */
    val readingMinutes: Int,
) {
    val url: String get() = "$NEWS_URL$slug/"
}

/** L'état d'une ligne du panneau « Le chantier » (maquette SiteAttente) : FAIT, EN COURS, ou à venir (cadenas). */
enum class ChantierState { FAITE, EN_COURS, A_VENIR }

/** Une ligne du chantier : le titre de l'étape, son état, et l'article quand l'étape est franchie. */
class ChantierStep(val title: String, val state: ChantierState, val url: String?)

/**
 * Les actus de `site/contenu/actus/`. Un fichier par actu, nommé par son slug (`premiere-heure.md`, en minuscules,
 * chiffres et tirets). Son en-tête :
 *
 * - `titre` et `description` : obligatoires ;
 * - `date` (`AAAA-MM-JJ`) : la date de publication, obligatoire dès que l'actu est publiée ;
 * - `publie` : `oui` (le défaut) ou `non` ; une actu non publiée n'a pas d'adresse et n'est pas listée ;
 * - `etape` : `oui` ou `non` (le défaut) ; voir [NewsArticle] ;
 * - `ordre` : un entier, seulement pour une étape à venir : son rang dans le chantier.
 *
 * Le corps suit le plan fixe : un texte d'introduction (facultatif), puis `## Nouveau`, `## Changements` et
 * `## Corrections`, dans cet ordre, chacune facultative, chacune une liste de points (`- `). Un autre intertitre de
 * niveau deux arrête le démarrage. Une actu publiée a au moins une partie ; une actu à venir peut être vide.
 */
object NewsArticles {
    private const val RESOURCE_DIR = "site/contenu/actus"

    /** Le slug, en minuscules, chiffres et tirets, comme une adresse. */
    private val SLUG = Regex("""[a-z0-9]+(?:-[a-z0-9]+)*""")
    private val HEADING = Regex("""^##\s+(.*?)\s*$""")
    private val LIST_ITEM = Regex("""^[-*]\s+\S""")

    /**
     * Les actus livrées dans le jar. Un jar ne se parcourt pas comme un dossier : la liste est écrite ici (les noms de
     * fichier, avec `.md`), et un test la compare au dossier des ressources.
     */
    val BUNDLED: List<String> = listOf("plans-du-jeu.md", "moteur-du-jeu.md", "premier-joueur.md", "premiere-heure.md", "beta-fermee.md")

    fun fromClasspath(): List<NewsArticle> = check(
        BUNDLED.map { fileName ->
            val path = "$RESOURCE_DIR/$fileName"
            val stream = NewsArticles::class.java.classLoader.getResourceAsStream(path)
                ?: throw SiteContentException("$path est introuvable")
            parse(fileName, stream.use { String(it.readBytes(), Charsets.UTF_8) })
        },
    )

    /** Tous les `.md` d'un dossier (les tests y posent des actus). */
    fun fromDirectory(directory: Path): List<NewsArticle> = check(
        Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".md") }.sorted().toList()
        }.map { parse(it.fileName.toString(), Files.readString(it)) },
    )

    /** Deux actus ne portent pas le même slug ; deux étapes à venir n'ont pas le même rang. */
    fun check(articles: List<NewsArticle>): List<NewsArticle> {
        val twice = articles.groupBy { it.slug }.filterValues { it.size > 1 }.keys
        if (twice.isNotEmpty()) throw SiteContentException("Actus : le slug « ${twice.first()} » est pris deux fois")
        val ranks = articles.filter { it.step && !it.published && it.order != null }.groupBy { it.order }.filterValues { it.size > 1 }.keys
        if (ranks.isNotEmpty()) throw SiteContentException("Actus : deux étapes à venir ont le rang ${ranks.first()}")
        return articles
    }

    /** Les actus publiées, la plus récente en tête ; à date égale, l'ordre inverse des slugs. */
    fun published(articles: List<NewsArticle>): List<NewsArticle> =
        articles.filter { it.published }.sortedWith(compareByDescending<NewsArticle> { it.date }.thenByDescending { it.slug })

    /**
     * Le panneau « Le chantier » : les étapes franchies dans l'ordre où elles l'ont été (la plus ancienne en tête),
     * puis les étapes à venir dans l'ordre de leur rang. La première étape à venir est celle en cours.
     */
    fun chantier(articles: List<NewsArticle>): List<ChantierStep> {
        val done = articles.filter { it.step && it.published }
            .sortedWith(compareBy<NewsArticle> { it.date }.thenBy { it.slug })
            .map { ChantierStep(it.title, ChantierState.FAITE, it.url) }
        val next = articles.filter { it.step && !it.published }
            .sortedWith(compareBy<NewsArticle> { it.order ?: Int.MAX_VALUE }.thenBy { it.slug })
            .mapIndexed { index, article -> ChantierStep(article.title, if (index == 0) ChantierState.EN_COURS else ChantierState.A_VENIR, null) }
        return done + next
    }

    fun parse(fileName: String, text: String): NewsArticle {
        try {
            val slug = fileName.removeSuffix(".md")
            require(fileName.endsWith(".md") && SLUG.matches(slug)) { "le nom du fichier est le slug de l'actu, en minuscules, chiffres et tirets, suivi de .md (par exemple premiere-heure.md)" }
            val (header, body) = Markdown.splitHeader(text)
            fun field(key: String): String =
                header.fields[key]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("la clé « $key » manque ou est vide")
            fun flag(key: String, default: Boolean): Boolean = when (val raw = header.fields[key]?.trim()?.lowercase()) {
                null, "" -> default
                "oui" -> true
                "non" -> false
                else -> throw IllegalArgumentException("« $key » s'écrit oui ou non : « $raw »")
            }
            val published = flag("publie", default = true)
            val step = flag("etape", default = false)
            val date = header.fields["date"]?.takeIf { it.isNotBlank() }?.let {
                try {
                    LocalDate.parse(it.trim())
                } catch (e: DateTimeParseException) {
                    throw IllegalArgumentException("« date » doit s'écrire année-mois-jour, par exemple 2026-11-05 : « $it »")
                }
            }
            require(!published || date != null) { "une actu publiée a une « date » (année-mois-jour)" }
            val order = header.fields["ordre"]?.takeIf { it.isNotBlank() }?.let {
                it.trim().toIntOrNull() ?: throw IllegalArgumentException("« ordre » est un entier : « $it »")
            }
            require(order == null || (step && !published)) { "« ordre » ne sert qu'à une étape à venir (etape: oui, publie: non)" }
            val (intro, sections) = plan(body)
            require(!published || sections.isNotEmpty()) { "une actu publiée a au moins une partie : ## Nouveau, ## Changements ou ## Corrections" }
            return NewsArticle(slug, field("titre"), field("description"), date, step, published, order, intro, sections, readingMinutes(body))
        } catch (e: IllegalArgumentException) {
            throw SiteContentException("Actu $fileName : ${e.message}")
        }
    }

    /** Découpe le corps au plan fixe : l'introduction, puis les parties dans l'ordre de [NewsPart]. */
    private fun plan(body: String): Pair<String, List<NewsSection>> {
        val lines = body.replace("\r\n", "\n").split("\n")
        val intro = StringBuilder()
        val parts = LinkedHashMap<NewsPart, StringBuilder>()
        var current: StringBuilder = intro
        for (line in lines) {
            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                val name = heading.groupValues[1]
                val part = NewsPart.entries.firstOrNull { it.heading.equals(name, ignoreCase = true) }
                    ?: throw IllegalArgumentException("l'intertitre « $name » n'est pas du plan fixe : ## Nouveau, ## Changements, ## Corrections")
                require(part !in parts) { "la partie « ${part.heading} » est écrite deux fois" }
                require(parts.keys.all { it.ordinal < part.ordinal }) { "les parties vont dans l'ordre Nouveau, Changements, Corrections" }
                current = StringBuilder().also { parts[part] = it }
            } else {
                current.append(line).append('\n')
            }
        }
        val sections = parts.map { (part, text) ->
            val count = text.lines().count { LIST_ITEM.containsMatchIn(it) }
            require(count > 0) { "la partie « ${part.heading} » liste au moins un point (une ligne qui commence par « - »)" }
            NewsSection(part, count, Markdown.toHtml(text.toString()))
        }
        val introText = intro.toString()
        return (if (introText.isBlank()) "" else Markdown.toHtml(introText)) to sections
    }

    /** Deux cents mots la minute, une minute au moins. */
    private fun readingMinutes(body: String): Int {
        val words = body.split(Regex("""\s+""")).count { it.any(Char::isLetterOrDigit) }
        return maxOf(1, (words + 199) / 200)
    }
}
