package com.silooo.core.site

/**
 * Les pages du wiki, `site/contenu/wiki/<sujet>.md`. Ce sont des [ContentPage] comme les autres (même en-tête, plus la clé
 * `ordre`), mais elles ont leur propre liste, comme les mises à jour, parce qu'un jar ne se parcourt pas comme un dossier.
 * Le nom d'une page est `wiki/<sujet>` : il choisit la fonction de rendu (`SiteRoutes.kt`) et ne peut pas heurter celui
 * d'un contenu du dossier parent.
 *
 * `index` est la page `/wiki/` : elle n'a pas d'`ordre`, elle liste les autres. Toute autre page en a un, entier, et
 * deux pages ne partagent pas le même : l'index suit cet ordre, celui où le joueur rencontre les systèmes.
 */
object WikiContent {
    const val INDEX = "wiki/index"
    private const val RESOURCE_DIR = "site/contenu/wiki"

    /** Les sujets livrés dans le jar, sans `.md`. Un test les compare au dossier des ressources. */
    val BUNDLED = listOf(
        "index",
        "premiere-heure",
        "usines",
        "silo-et-hors-ligne",
        "personnel",
        "monnaies",
        "prestige",
        "incidents-et-electricite",
        "commande-du-jour-et-carnet",
        "entreprise-partagee",
        "ville",
        "mots-du-jeu",
        "regles",
    )

    fun fromClasspath(): List<ContentPage> = check(
        BUNDLED.map { subject ->
            val path = "$RESOURCE_DIR/$subject.md"
            val stream = WikiContent::class.java.classLoader.getResourceAsStream(path)
                ?: throw SiteContentException("$path est introuvable")
            parse(subject, stream.use { String(it.readBytes(), Charsets.UTF_8) })
        },
    )

    /** Lit une page : un contenu ordinaire dont le nom devient `wiki/<sujet>`. */
    fun parse(subject: String, text: String): ContentPage {
        val page = SiteContent.parse("$subject.md", text)
        return ContentPage(
            "wiki/$subject", page.title, page.description, page.url, page.modes, page.html, page.order,
        )
    }

    /** Refuse une page sans `ordre`, un `ordre` pris deux fois, une adresse hors de `/wiki/` ; rend les pages telles quelles. */
    fun check(pages: List<ContentPage>): List<ContentPage> {
        for (page in pages) {
            val subject = page.name.removePrefix("wiki/")
            val expected = if (page.name == INDEX) "/wiki/" else "/wiki/$subject/"
            if (page.url != expected) throw SiteContentException("Contenu wiki/$subject.md : l'url doit être $expected, pas ${page.url}")
            if (page.name == INDEX && page.order != null) throw SiteContentException("Contenu wiki/$subject.md : l'index n'a pas d'ordre")
            if (page.name != INDEX && page.order == null) throw SiteContentException("Contenu wiki/$subject.md : la clé « ordre » manque")
        }
        val twice = pages.mapNotNull { page -> page.order?.let { it to page.name } }
            .groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
        if (twice.isNotEmpty()) {
            val (order, names) = twice.entries.first()
            throw SiteContentException("Deux pages du wiki ont l'ordre $order : ${names.joinToString(", ")}")
        }
        return pages
    }

    /** Les pages de l'index, dans l'ordre de l'en-tête (sans l'index lui-même). */
    fun sorted(pages: List<ContentPage>): List<ContentPage> =
        pages.filter { it.name != INDEX }.sortedBy { it.order }
}
