package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.FakeDatabase
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Outil de relecture, pas un test : écrit les pages rendues et le dossier `public/` dans le dossier que désigne
 * `SILOOO_SITE_RENDER_DIR`, pour les ouvrir dans un navigateur (captures à 360 et 1280 px). Ignoré sans la variable.
 * Il ne vérifie rien : les pages se contrôlent dans [SiteStyleTest] et [SiteModuleTest].
 */
@EnabledIfEnvironmentVariable(named = "SILOOO_SITE_RENDER_DIR", matches = ".+")
class SiteRenderToDisk {
    @Test
    @DisplayName("Écrit les pages du site sur le disque pour les relire dans un navigateur")
    fun render() {
        val out = Path.of(System.getenv("SILOOO_SITE_RENDER_DIR"))
        val publicSource = Path.of(checkNotNull(SiteRenderToDisk::class.java.classLoader.getResource("site/public")).toURI())
        Files.walk(publicSource).use { paths ->
            for (source in paths) {
                val target = out.resolve("public").resolve(publicSource.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val pages = listOf(
            Triple(SiteMode.ATTENTE, null as String?, listOf("/" to "index.html", "/mentions/" to "mentions/index.html", "/wiki/" to "introuvable/index.html")),
            Triple(SiteMode.ATTENTE, "https://discord.gg/exemple", listOf("/" to "discord/index.html")),
            Triple(SiteMode.OUVERT, null, listOf("/" to "ouvert/index.html", "/mentions/" to "ouvert/mentions/index.html")),
        )
        for ((mode, discord, routes) in pages) {
            testApplication {
                application {
                    siteModule(Dependencies(FakeDatabase(), "cle", definitions = TestDefinitions.loaded, siteMode = mode, discordUrl = discord))
                }
                val client = createClient { followRedirects = false }
                for ((route, file) in routes) {
                    val target = out.resolve(file)
                    Files.createDirectories(target.parent)
                    Files.writeString(target, client.get(route).bodyAsText())
                }
            }
        }
    }
}
