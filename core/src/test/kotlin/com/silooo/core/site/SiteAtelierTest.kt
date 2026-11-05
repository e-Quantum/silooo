package com.silooo.core.site

import com.silooo.core.TestDefinitions
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Silooo Atelier sur le web : ce que Gradle copie de `design/` l'est à l'octet près, `composants.css` est `bundle.css` aux
 * adresses des textures près, le CSS n'emploie rien de ce que la charte bannit, et chaque fichier qu'il cite existe dans les
 * ressources du site. Le dossier `design/` reste la source ; les copies ne sont pas dans le dépôt, `core/build.gradle.kts` les pose.
 */
class SiteAtelierTest {
    private val design: Path = TestDefinitions.directory.resolveSibling("design")

    /** Le dossier `site/public/` des ressources, tel que Gradle l'a composé pour les tests. */
    private val publicDir: Path = Path.of(checkNotNull(SiteAtelierTest::class.java.classLoader.getResource("site/public")).toURI())

    private fun filesIn(directory: Path): List<Path> = Files.list(directory).use { files -> files.filter { Files.isRegularFile(it) }.sorted().toList() }

    private fun stylesheets(): List<Path> =
        listOf(publicDir.resolve("site.css"), publicDir.resolve("atelier.css"), publicDir.resolve("atelier/composants.css"), publicDir.resolve("atelier/tokens.css"))

    private fun assertCopy(source: Path, copy: Path) {
        assertTrue(Files.exists(copy), "${publicDir.relativize(copy)} manque : Gradle le copie de design/ (processResources)")
        assertArrayEquals(
            Files.readAllBytes(source),
            Files.readAllBytes(copy),
            "${publicDir.relativize(copy)} n'est pas la copie de design/${design.relativize(source)} : la copie vient de Gradle, relance ./gradlew processResources",
        )
    }

    @Test
    @DisplayName("Les jetons, textures, icônes, Oooscar et logos du site sont les copies exactes de design/, en entier")
    fun copiesAreExact() {
        assertCopy(design.resolve("tokens.css"), publicDir.resolve("atelier/tokens.css"))
        val folders = mapOf("atelier/textures" to "textures", "atelier/icones" to "icones", "atelier/oooscar" to "mascotte", "logo" to "logo")
        for ((served, source) in folders) {
            val copies = filesIn(publicDir.resolve(served))
            // Tout le dossier source est là : les 14 icônes, les cinq visages d'Oooscar (PNG et SVG), chaque texture, chaque logo.
            assertEquals(filesIn(design.resolve(source)).map { it.fileName }, copies.map { it.fileName }, "$served n'a pas les fichiers de design/$source")
            for (copy in copies) assertCopy(design.resolve(source).resolve(copy.fileName.toString()), copy)
        }
    }

    @Test
    @DisplayName("composants.css est bundle.css où chaque adresse /_blob/ est devenue une texture de atelier/textures/, et rien d'autre")
    fun componentsAreDerivedFromTheBundle() {
        val bundle = Files.readString(design.resolve("composants/bundle.css"))
        val components = Files.readString(publicDir.resolve("atelier/composants.css"))
        assertFalse(components.contains("/_blob/"), "composants.css garde une adresse /_blob/ : la table atelierBlobs de core/build.gradle.kts est incomplète")
        // composants.css vit dans atelier/ : ses textures s'écrivent « textures/<fichier> », relatives à lui.
        val blobs = Regex("""/_blob/[0-9a-f]+""").findAll(bundle).map { it.value }.toSet()
        val textures = Regex("""textures/[a-z0-9-]+\.png""").findAll(components).map { it.value }.toSet()
        assertEquals(blobs.size, textures.size, "autant de textures distinctes que d'adresses /_blob/ distinctes")
        assertEquals(
            bundle.replace(Regex("""/_blob/[0-9a-f]+"""), "§"),
            components.replace(Regex("""textures/[a-z0-9-]+\.png"""), "§"),
            "composants.css et bundle.css ne diffèrent que par les adresses des textures",
        )
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
    @DisplayName("site.css est le point d'entrée : il importe les jetons, puis les composants, puis atelier.css")
    fun entryPoint() {
        assertEquals(
            listOf("atelier/tokens.css", "atelier/composants.css", "atelier.css"),
            urlsOf(Files.readString(publicDir.resolve("site.css"))),
            "site.css importe d'abord les jetons, puis composants.css, puis atelier.css",
        )
    }

    @Test
    @DisplayName("atelier.css ne réécrit aucun composant de la charte : il ne garde que ses réglages et la mise en page")
    fun atelierDoesNotRewriteComponents() {
        // Une règle qui pose un border-image entier (texture, découpe, largeur) est un composant : elle vit dans bundle.css.
        val atelier = Files.readString(publicDir.resolve("atelier.css"))
        assertFalse(Regex("""(?<!-)border-image\s*:""").containsMatchIn(atelier), "atelier.css redéfinit un composant (border-image) : la charte vit dans design/composants/bundle.css")
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

    @Test
    @DisplayName("L'image de partage (logo et Oooscar) est un PNG de 1200 sur 630, assez léger pour les aperçus")
    fun shareImage() {
        val image = publicDir.resolve("partage.png")
        assertTrue(Files.isRegularFile(image), "partage.png manque : node tools/verifier_site.mjs --partage la régénère")
        val bytes = Files.readAllBytes(image)
        assertEquals("\u0089PNG", String(bytes, 0, 4, Charsets.ISO_8859_1), "partage.png n'est pas un PNG")
        val header = ByteBuffer.wrap(bytes, 16, 8)
        assertEquals(1200 to 630, header.int to header.int, "partage.png fait 1200 sur 630 (la taille des aperçus Open Graph)")
        assertTrue(bytes.size < 300_000, "partage.png pèse ${bytes.size} octets : trop lourd pour un aperçu")
    }
}
