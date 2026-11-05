package com.silooo.core.site

import com.silooo.core.Config
import com.silooo.core.ConfigException
import com.silooo.core.TestDefinitions
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Les variables S2 du site : l'adresse et le port du jeu, la boutique, les sites de vote. */
class SiteSettingsConfigTest {
    private val complete = mapOf(
        Config.DB_URL to "jdbc:mariadb://mariadb:3306/silooo",
        Config.DB_USER to "silooo",
        Config.DB_PASSWORD to "mot-de-passe-de-test",
        Config.API_KEY to "cle-de-test",
        Config.DEFINITIONS_DIR to TestDefinitions.directory.toString(),
    )

    private fun settings(vararg extra: Pair<String, String>): SiteSettings = Config.service(complete + extra).siteSettings

    @Test
    @DisplayName("Sans variable : play.silooo.com, le port du compose, pas de boutique, pas de site de vote")
    fun defaults() {
        val settings = settings()
        assertEquals("play.silooo.com", settings.gameAddress)
        assertEquals(Config.DEFAULT_GAME_PORT_SHOWN, settings.gamePort)
        assertNull(settings.shopUrl)
        assertTrue(settings.voteSites.isEmpty())
        assertEquals(SiteSettings(), settings)
    }

    @Test
    @DisplayName("Les variables sont lues : adresse, port, boutique, sites de vote dans l'ordre donné")
    fun variablesAreRead() {
        val settings = settings(
            Config.SITE_GAME_ADDRESS to " jeu.exemple.test ",
            Config.SITE_GAME_PORT to "20000",
            Config.SITE_SHOP_URL to "https://boutique.exemple.test/gemmes/",
            Config.SITE_VOTE_SITES to "Premier|https://un.exemple.test/vote ; Second|https://deux.exemple.test/ ;",
        )
        assertEquals("jeu.exemple.test", settings.gameAddress)
        assertEquals(20000, settings.gamePort)
        assertEquals("https://boutique.exemple.test/gemmes", settings.shopUrl)
        assertEquals(
            listOf(VoteSite("Premier", "https://un.exemple.test/vote"), VoteSite("Second", "https://deux.exemple.test/")),
            settings.voteSites,
        )
    }

    @Test
    @DisplayName("Une liste de sites de vote mal formée est refusée, le message cite la variable")
    fun malformedVoteSites() {
        for (bad in listOf("sans-barre", "|https://x.test", "Nom|", "Nom|ftp://x.test", "Bon|https://x.test;Mauvais")) {
            val error = assertThrows<ConfigException>(bad) { settings(Config.SITE_VOTE_SITES to bad) }
            assertTrue(error.message!!.contains(Config.SITE_VOTE_SITES), "${error.message}")
        }
    }

    @Test
    @DisplayName("Un port, une adresse ou une boutique invalide est refusé avec le nom de la variable")
    fun invalidValues() {
        for (bad in listOf("abc", "0", "70000", "-5")) {
            val error = assertThrows<ConfigException>(bad) { settings(Config.SITE_GAME_PORT to bad) }
            assertTrue(error.message!!.contains(Config.SITE_GAME_PORT))
        }
        for (bad in listOf("play.silooo.com:19142", "play silooo", "https://play.silooo.com/")) {
            val error = assertThrows<ConfigException>(bad) { settings(Config.SITE_GAME_ADDRESS to bad) }
            assertTrue(error.message!!.contains(Config.SITE_GAME_ADDRESS))
        }
        assertTrue(assertThrows<ConfigException> { settings(Config.SITE_SHOP_URL to "boutique.test") }.message!!.contains(Config.SITE_SHOP_URL))
    }

    @Test
    @DisplayName("Le port affiché par défaut est le défaut de BDS_PORT dans deploy/compose.yml")
    fun defaultPortMatchesCompose() {
        val compose = Files.readString(TestDefinitions.directory.resolveSibling("deploy").resolve("compose.yml"))
        val defaults = Regex("""BDS_PORT:\s*\$\{BDS_PORT:-(\d+)}""").findAll(compose).map { it.groupValues[1].toInt() }.toList()
        assertTrue(defaults.isNotEmpty(), "deploy/compose.yml ne pose plus BDS_PORT:  \${BDS_PORT:-nombre}")
        assertEquals(
            setOf(Config.DEFAULT_GAME_PORT_SHOWN),
            defaults.toSet(),
            "Le port du jeu a changé dans deploy/compose.yml : mets Config.DEFAULT_GAME_PORT_SHOWN à jour (ADR 0004)",
        )
        // Le port publié par le compose est le même que celui de BDS.
        val published = Regex("""\$\{BDS_PORT:-(\d+)}:\$\{BDS_PORT:-(\d+)}/tcp""").find(compose)
        if (published != null) {
            assertEquals(Config.DEFAULT_GAME_PORT_SHOWN, published.groupValues[1].toInt())
        }
    }
}
