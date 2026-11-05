package com.silooo.core.site.pages

import kotlinx.html.DIV

/**
 * Un contenu qui mêle du texte écrit à la main et des blocs rendus par le code (un tableau tiré des définitions, une
 * adresse tirée de la configuration). Dans le Markdown, un bloc se place par un paragraphe seul de la forme `{{nom}}` ;
 * la page donne, pour chaque nom, la fonction qui le rend. Le Markdown reste du Markdown, le code ne rend que ce qui
 * vient des définitions ou de la configuration.
 *
 * Un nom que la page ne connaît pas, ou une fonction que le contenu n'appelle pas, fait échouer le rendu : le site
 * ne montre jamais un `{{nom}}` brut et ne cache jamais un tableau qui devait y être.
 */
internal fun DIV.avecInsertions(html: String, blocks: Map<String, DIV.() -> Unit>) {
    val marker = Regex("""<p>\{\{([a-z-]+)\}\}</p>\n?""")
    val used = mutableSetOf<String>()
    var cursor = 0
    for (match in marker.findAll(html)) {
        val name = match.groupValues[1]
        val block = blocks[name] ?: throw IllegalStateException("Le contenu appelle un bloc « $name » que la page ne connaît pas")
        rendered(html.substring(cursor, match.range.first))
        block()
        used += name
        cursor = match.range.last + 1
    }
    rendered(html.substring(cursor))
    check(used == blocks.keys) { "La page prévoit des blocs que le contenu n'appelle pas : ${blocks.keys - used}" }
    check(!marker.replace(html, "").contains("{{")) { "Un marqueur du contenu est mal écrit : il doit former un paragraphe seul, comme {{nom}}" }
}
