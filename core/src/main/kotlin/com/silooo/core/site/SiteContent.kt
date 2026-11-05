package com.silooo.core.site

import java.nio.file.Files
import java.nio.file.Path

/** Une page de contenu, lue et rendue une fois : son en-tête et son corps déjà en HTML. */
class ContentPage(
    /** Le nom du fichier sans `.md` : il choisit la fonction de rendu de la page. */
    val name: String,
    val title: String,
    val description: String,
    /** L'adresse de la page, qui finit par `/`. */
    val url: String,
    val modes: Set<SiteMode>,
    /** Le corps du Markdown, rendu en HTML. */
    val html: String,
    /** La clé facultative `ordre` de l'en-tête : la place d'une page de wiki dans l'index (voir [WikiContent]). */
    val order: Int? = null,
)

/** Un contenu est illisible : le message dit quel fichier et pourquoi, le démarrage du site échoue. */
class SiteContentException(message: String) : Exception(message)

/**
 * Les contenus du site. Ils sont lus et rendus UNE fois, au démarrage du module : une requête ne
 * touche ni au disque ni au Markdown, et un en-tête incomplet se voit au démarrage, pas devant un joueur.
 */
class SiteContent(val pages: List<ContentPage>) {

    /** Les pages que ce mode montre. Une même adresse ne mène qu'à une page par mode (contrôlé ici). */
    fun forMode(mode: SiteMode): List<ContentPage> {
        val shown = pages.filter { mode in it.modes }
        val twice = shown.groupBy { it.url }.filterValues { it.size > 1 }.keys
        check(twice.isEmpty()) { "Plusieurs contenus pour l'adresse ${twice.first()} en mode ${mode.word}" }
        return shown
    }

    companion object {
        private const val RESOURCE_DIR = "site/contenu"

        /**
         * Les contenus livrés dans le jar. Un jar ne se parcourt pas comme un dossier : la liste est écrite ici,
         * et un test la compare au dossier des ressources pour qu'un fichier ajouté sans elle se voie.
         */
        val BUNDLED = listOf("attente", "accueil", "rejoindre", "ce-qui-se-vend", "boutique", "vote", "actus", "discord", "pionniers", "classements", "mentions-legales", "cgv", "confidentialite", "parents")

        fun fromClasspath(): SiteContent = SiteContent(
            BUNDLED.map { name ->
                val path = "$RESOURCE_DIR/$name.md"
                val stream = SiteContent::class.java.classLoader.getResourceAsStream(path)
                    ?: throw SiteContentException("$path est introuvable")
                parse("$name.md", stream.use { String(it.readBytes(), Charsets.UTF_8) })
            },
        )

        /** Tous les `.md` d'un dossier (les tests y posent un contenu cassé). */
        fun fromDirectory(directory: Path): SiteContent = SiteContent(
            Files.list(directory).use { files ->
                files.filter { it.fileName.toString().endsWith(".md") }.sorted().toList()
            }.map { parse(it.fileName.toString(), Files.readString(it)) },
        )

        /** Lit un fichier : `titre`, `description`, `url` et `modes` sont obligatoires. */
        fun parse(fileName: String, text: String): ContentPage {
            val name = fileName.removeSuffix(".md")
            try {
                val (header, body) = Markdown.splitHeader(text)
                fun field(key: String): String =
                    header.fields[key]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("la clé « $key » manque ou est vide")
                val url = field("url")
                require(url.startsWith("/") && url.endsWith("/")) { "l'url doit commencer et finir par / : « $url »" }
                val modes = Markdown.parseList(field("modes")).map {
                    SiteMode.parse(it) ?: throw IllegalArgumentException("mode inconnu dans « modes » : « $it »")
                }
                require(modes.isNotEmpty()) { "« modes » ne peut pas être vide" }
                val order = header.fields["ordre"]?.let {
                    it.toIntOrNull()?.takeIf { value -> value > 0 } ?: throw IllegalArgumentException("« ordre » doit être un entier positif : « $it »")
                }
                return ContentPage(name, field("titre"), field("description"), url, modes.toSet(), Markdown.toHtml(body), order)
            } catch (e: IllegalArgumentException) {
                throw SiteContentException("Contenu $fileName : ${e.message}")
            }
        }
    }
}
