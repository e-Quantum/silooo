package com.silooo.core.site

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Une entrée de la page « Mises à jour » : un fichier `AAAA-MM-JJ-<sujet>.md`, rendu une fois. */
class UpdateEntry(
    val fileName: String,
    val date: LocalDate,
    val title: String,
    val description: String,
    /** Le corps du Markdown, rendu en HTML. */
    val html: String,
)

/**
 * Les entrées de `site/contenu/mises-a-jour/`. Elles ne sont pas des [ContentPage] : elles n'ont pas d'adresse, la page
 * « Mises à jour » les rassemble. Leur en-tête est `titre`, `description` et `date` (`AAAA-MM-JJ`, la même que celle du nom
 * du fichier). Une entrée mal nommée ou sans date arrête le démarrage, avec le nom du fichier.
 *
 * Une entrée par version déployée, écrite pour les joueurs. Ses intertitres sont des `###` : le `h2` est le titre.
 */
object UpdateEntries {
    private const val RESOURCE_DIR = "site/contenu/mises-a-jour"

    /** `AAAA-MM-JJ-<sujet>.md`, le sujet en minuscules, chiffres et tirets. */
    private val FILE_NAME = Regex("""(\d{4}-\d{2}-\d{2})-[a-z0-9]+(?:-[a-z0-9]+)*\.md""")

    /**
     * Les entrées livrées dans le jar. Un jar ne se parcourt pas comme un dossier : la liste est écrite ici (les noms de
     * fichier, avec `.md`), et un test la compare au dossier des ressources. Vide tant que rien n'est déployé.
     */
    val BUNDLED: List<String> = emptyList()

    fun fromClasspath(): List<UpdateEntry> = sort(
        BUNDLED.map { fileName ->
            val path = "$RESOURCE_DIR/$fileName"
            val stream = UpdateEntries::class.java.classLoader.getResourceAsStream(path)
                ?: throw SiteContentException("$path est introuvable")
            parse(fileName, stream.use { String(it.readBytes(), Charsets.UTF_8) })
        },
    )

    /** Tous les `.md` d'un dossier (les tests y posent des entrées). */
    fun fromDirectory(directory: Path): List<UpdateEntry> = sort(
        Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".md") }.sorted().toList()
        }.map { parse(it.fileName.toString(), Files.readString(it)) },
    )

    /** La plus récente en tête ; à date égale, l'ordre inverse des noms de fichier. */
    private fun sort(entries: List<UpdateEntry>): List<UpdateEntry> =
        entries.sortedWith(compareByDescending<UpdateEntry> { it.date }.thenByDescending { it.fileName })

    fun parse(fileName: String, text: String): UpdateEntry {
        try {
            val match = FILE_NAME.matchEntire(fileName)
                ?: throw IllegalArgumentException("le nom doit s'écrire année-mois-jour-sujet, par exemple 2026-11-05-ouverture.md (sujet en minuscules, chiffres et tirets)")
            val (header, body) = Markdown.splitHeader(text)
            fun field(key: String): String =
                header.fields[key]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("la clé « $key » manque ou est vide")
            val date = try {
                LocalDate.parse(field("date"))
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("« date » doit s'écrire année-mois-jour, par exemple 2026-11-05 : « ${field("date")} »")
            }
            require(date.toString() == match.groupValues[1]) { "la date « $date » n'est pas celle du nom du fichier" }
            require(body.isNotBlank()) { "l'entrée n'a pas de texte" }
            return UpdateEntry(fileName, date, field("titre"), field("description"), Markdown.toHtml(body))
        } catch (e: IllegalArgumentException) {
            throw SiteContentException("Mise à jour $fileName : ${e.message}")
        }
    }
}
