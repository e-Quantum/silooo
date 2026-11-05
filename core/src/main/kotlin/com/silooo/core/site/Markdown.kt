package com.silooo.core.site

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/** L'en-tête d'un contenu : les clés que `SiteContent` exige, telles qu'écrites entre les deux lignes `---`. */
internal class ContentHeader(val fields: Map<String, String>)

/**
 * Le Markdown des contenus du site, rendu en HTML par commonmark-java.
 *
 * Deux choix de sécurité : le HTML brut écrit dans un contenu est escamoté (`escapeHtml`), et les adresses
 * dangereuses (`javascript:`) sont retirées des liens (`sanitizeUrls`). Un contenu est un texte de Kr ou d'une
 * session de travail, jamais une entrée du public, mais la page ne doit pas dépendre de cette confiance.
 */
internal object Markdown {
    private val parser = Parser.builder().build()
    private val renderer = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build()

    fun toHtml(markdown: String): String = renderer.render(parser.parse(markdown))

    /**
     * Sépare l'en-tête du corps. L'en-tête se lit à la main (« clé: valeur », une par ligne) plutôt qu'avec une
     * bibliothèque YAML : quatre clés ne justifient pas une dépendance de plus.
     *
     * @throws IllegalArgumentException si l'en-tête n'est pas fermé ou si une ligne n'a pas la forme « clé: valeur » ;
     *   l'appelant ajoute le nom du fichier.
     */
    fun splitHeader(text: String): Pair<ContentHeader, String> {
        val lines = text.removePrefix("﻿").replace("\r\n", "\n").split("\n")
        require(lines.firstOrNull()?.trim() == "---") { "le fichier doit commencer par une ligne ---" }
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        require(end >= 0) { "l'en-tête n'est pas fermé par une seconde ligne ---" }
        val fields = LinkedHashMap<String, String>()
        for (line in lines.subList(1, end + 1)) {
            if (line.isBlank()) continue
            val colon = line.indexOf(':')
            require(colon > 0) { "ligne d'en-tête illisible : « $line »" }
            fields[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }
        return ContentHeader(fields) to lines.drop(end + 2).joinToString("\n")
    }

    /** Une liste entre crochets, séparée par des virgules : `[attente, ouvert]`. */
    fun parseList(raw: String): List<String> {
        val value = raw.trim()
        require(value.startsWith("[") && value.endsWith("]")) { "une liste s'écrit entre crochets : « $raw »" }
        return value.substring(1, value.length - 1).split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
