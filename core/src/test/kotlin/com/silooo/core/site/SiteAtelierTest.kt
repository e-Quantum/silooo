package com.silooo.core.site

import com.silooo.core.TestDefinitions
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Silooo Atelier sur le web : ce qui est copié de `design/` l'est à l'octet près, le CSS n'emploie rien de ce que la
 * charte bannit, et chaque fichier qu'il cite existe dans les ressources du site. Le dossier `design/` reste la source.
 */
class SiteAtelierTest {
    private val design: Path = TestDefinitions.directory.resolveSibling("design")

    /** Le dossier `site/public/` des ressources, tel que Gradle l'a posé pour les tests. */
    private val publicDir: Path = Path.of(checkNotNull(SiteAtelierTest::class.java.classLoader.getResource("site/public")).toURI())

    private fun filesIn(directory: Path): List<Path> = Files.list(directory).use { files -> files.filter { Files.isRegularFile(it) }.sorted().toList() }

    private fun stylesheets(): List<Path> = listOf(publicDir.resolve("site.css"), publicDir.resolve("atelier.css"), publicDir.resolve("atelier/tokens.css"))

    private fun assertCopy(source: Path, copy: Path) {
        assertTrue(Files.exists(copy), "${publicDir.relativize(copy)} manque")
        assertArrayEquals(
            Files.readAllBytes(source),
            Files.readAllBytes(copy),
            "${publicDir.relativize(copy)} n'est pas la copie de design/${design.relativize(source)} : recopie la source, ne retouche pas la copie",
        )
    }

    @Test
    @DisplayName("Les jetons, textures, icônes, Oooscar et logos du site sont les copies exactes de design/")
    fun copiesAreExact() {
        assertCopy(design.resolve("tokens.css"), publicDir.resolve("atelier/tokens.css"))
        val folders = mapOf("atelier/textures" to "textures", "atelier/icones" to "icones", "atelier/oooscar" to "mascotte", "logo" to "logo")
        for ((served, source) in folders) {
            val copies = filesIn(publicDir.resolve(served))
            assertTrue(copies.isNotEmpty(), "$served est vide")
            for (copy in copies) assertCopy(design.resolve(source).resolve(copy.fileName.toString()), copy)
        }
        // Les 14 icônes de la charte sont toutes là, pour les pages à venir.
        assertEquals(filesIn(design.resolve("icones")).map { it.fileName }, filesIn(publicDir.resolve("atelier/icones")).map { it.fileName })
    }

    private fun urlsOf(css: String): List<String> =
        Regex("""url\(\s*["']?([^"')]+)["']?\s*\)""").findAll(css).map { it.groupValues[1] }.toList()

    @Test
    @DisplayName("Chaque fichier cité par le CSS existe dans les ressources, et rien n'est chargé d'un autre hôte")
    fun citedFilesExist() {
        for (sheet in stylesheets()) {
            val urls = urlsOf(Files.readString(sheet))
            for (url in urls) {
                assertFalse(Regex("""^(https?:)?//""").containsMatchIn(url), "${sheet.fileName} charge un autre hôte : $url")
                if (url.startsWith("data:")) continue
                val target = sheet.parent.toUri().resolve(URI(url.replace(" ", "%20"))).let { Path.of(it) }
                assertTrue(target.startsWith(publicDir), "${sheet.fileName} sort de public/ : $url")
                assertTrue(Files.isRegularFile(target), "${sheet.fileName} cite $url, absent des ressources")
            }
        }
    }

    @Test
    @DisplayName("Le CSS n'a ni arrondi, ni dégradé, ni flou, ni largeur fixe au-dessus de 360 px")
    fun bannedCss() {
        val banned = listOf("border-radius", "linear-gradient", "radial-gradient", "conic-gradient", "blur(", "backdrop-filter")
        for (sheet in stylesheets()) {
            val css = Files.readString(sheet)
            for (word in banned) assertFalse(css.contains(word), "${sheet.fileName} contient « $word », que la charte bannit")
            // `width:` seule : ni min-width, ni max-width, ni border-image-width.
            for (match in Regex("""(?<![-\w])width\s*:\s*(\d+(?:\.\d+)?)px""").findAll(css)) {
                val pixels = match.groupValues[1].toDouble()
                assertTrue(pixels <= 360, "${sheet.fileName} : « ${match.value} » dépasse 360 px, la page ne tiendrait plus sur un téléphone")
            }
        }
    }

    @Test
    @DisplayName("site.css est le point d'entrée : il importe les jetons, puis atelier.css")
    fun entryPoint() {
        assertEquals(
            listOf("atelier/tokens.css", "atelier.css"),
            urlsOf(Files.readString(publicDir.resolve("site.css"))),
            "site.css importe d'abord les jetons, puis atelier.css",
        )
    }

    @Test
    @DisplayName("Chaque police a son fichier woff2, sa licence et son LISEZ-MOI, et le CSS cite chaque woff2")
    fun fonts() {
        val css = Files.readString(publicDir.resolve("atelier.css"))
        val cited = urlsOf(css).filter { it.endsWith(".woff2") }.map { publicDir.resolve(it).normalize() }.toSet()
        val directories = Files.list(publicDir.resolve("polices")).use { it.sorted().toList() }
        assertEquals(listOf("jersey-10", "pixelify-sans", "silkscreen"), directories.map { it.fileName.toString() })
        val present = mutableSetOf<Path>()
        for (directory in directories) {
            val files = filesIn(directory)
            val names = files.map { it.fileName.toString() }
            assertTrue("LICENSE" in names, "${directory.fileName} : le fichier de licence manque")
            assertTrue("LISEZ-MOI.md" in names, "${directory.fileName} : LISEZ-MOI.md manque")
            val fonts = files.filter { it.fileName.toString().endsWith(".woff2") }
            assertTrue(fonts.isNotEmpty(), "${directory.fileName} : aucun woff2")
            present += fonts
            val readme = Files.readString(directory.resolve("LISEZ-MOI.md"))
            assertTrue(Regex("""@fontsource/${directory.fileName}""").containsMatchIn(readme), "${directory.fileName} : le paquet n'est pas nommé")
            assertTrue(readme.contains("OFL"), "${directory.fileName} : la licence n'est pas nommée")
            assertTrue(Regex("""\d+\.\d+\.\d+""").containsMatchIn(readme), "${directory.fileName} : la version n'est pas notée")
        }
        assertEquals(present.map { it.normalize() }.toSet(), cited, "chaque woff2 est cité par atelier.css, et inversement")
        // Un commentaire mal fermé avale la règle qui le suit : on compte les @font-face une fois les commentaires retirés.
        val code = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        assertFalse(code.contains("*/"), "un commentaire se ferme avant la fin")
        assertEquals(4, Regex("@font-face").findAll(code).count())
        assertEquals(4, Regex("font-display:\\s*swap").findAll(code).count())
    }
}
