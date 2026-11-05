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
            Triple(
                SiteMode.OUVERT,
                null,
                listOf(
                    "/" to "ouvert/index.html",
                    "/mentions/" to "ouvert/mentions/index.html",
                    "/rejoindre/" to "ouvert/rejoindre/index.html",
                    "/ce-qui-se-vend/" to "ouvert/ce-qui-se-vend/index.html",
                    "/boutique/" to "ouvert/boutique/index.html",
                    "/vote/" to "ouvert/vote/index.html",
                    "/mises-a-jour/" to "ouvert/mises-a-jour/index.html",
                ),
            ),
            // Avec la boutique, deux sites de vote et le Discord posés : les liens et les boutons que le mode ouvert montre alors.
            Triple(
                SiteMode.OUVERT,
                "https://discord.gg/exemple",
                listOf(
                    "/" to "ouvert-complet/index.html",
                    "/boutique/" to "ouvert-complet/boutique/index.html",
                    "/vote/" to "ouvert-complet/vote/index.html",
                    "/mises-a-jour/" to "ouvert-complet/mises-a-jour/index.html",
                ),
            ),
        )
        val settings = SiteSettings(
            shopUrl = "https://boutique.exemple.test/",
            voteSites = listOf(VoteSite("Premier site", "https://un.exemple.test/"), VoteSite("Second site", "https://deux.exemple.test/")),
        )
        // Deux entrées d'exemple pour voir la page des mises à jour remplie ; elles ne sont écrites nulle part dans le dépôt.
        val sample = listOf(
            UpdateEntries.parse("2026-12-01-exemple.md", "---\ntitre: Une usine de plus\ndescription: Exemple.\ndate: 2026-12-01\n---\nTexte d'exemple.\n"),
            UpdateEntries.parse("2026-11-05-exemple.md", "---\ntitre: Le serveur ouvre\ndescription: Exemple.\ndate: 2026-11-05\n---\nTexte d'exemple.\n"),
        )
        for ((mode, discord, routes) in pages) {
            val complete = routes.first().second.startsWith("ouvert-complet")
            testApplication {
                application {
                    siteModule(
                        Dependencies(
                            FakeDatabase(), "cle", definitions = TestDefinitions.loaded, siteMode = mode, discordUrl = discord,
                            siteSettings = if (complete) settings else SiteSettings(),
                        ),
                        updates = if (complete) sample else emptyList(),
                    )
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
