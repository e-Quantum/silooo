package com.silooo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConfigTest {
    private val complete = mapOf(
        Config.DB_URL to "jdbc:mariadb://mariadb:3306/silooo",
        Config.DB_USER to "silooo",
        Config.DB_PASSWORD to "mot-de-passe-de-test",
        Config.API_KEY to "cle-de-test",
    )

    @Test
    @DisplayName("Le port vaut 8080 quand SILOOO_PORT n'est pas posé")
    fun defaultPort() {
        val config = Config.service(complete)
        assertEquals(Config.DEFAULT_PORT, config.port)
        assertEquals("cle-de-test", config.apiKey)
        assertEquals("silooo", config.database.user)
    }

    @Test
    @DisplayName("SILOOO_PORT change le port")
    fun customPort() {
        assertEquals(9090, Config.service(complete + (Config.PORT to "9090")).port)
    }

    @Test
    @DisplayName("Sans clé ni base, le service refuse de démarrer et dit tout ce qui manque")
    fun missingEverything() {
        val error = assertThrows<ConfigException> { Config.service(emptyMap()) }
        for (name in listOf(Config.DB_URL, Config.DB_USER, Config.DB_PASSWORD, Config.API_KEY)) {
            assertTrue(error.message!!.contains(name), "Le message devrait citer $name : ${error.message}")
        }
    }

    @Test
    @DisplayName("Une clé vide compte pour une clé absente")
    fun blankKey() {
        val error = assertThrows<ConfigException> { Config.service(complete + (Config.API_KEY to "   ")) }
        assertTrue(error.message!!.contains(Config.API_KEY))
    }

    @Test
    @DisplayName("Un port qui n'est pas un port est refusé")
    fun invalidPort() {
        for (bad in listOf("abc", "0", "70000", "-1")) {
            assertThrows<ConfigException>("SILOOO_PORT=$bad") { Config.service(complete + (Config.PORT to bad)) }
        }
    }

    @Test
    @DisplayName("L'URL de la base doit être une URL JDBC")
    fun invalidUrl() {
        assertThrows<ConfigException> { Config.service(complete + (Config.DB_URL to "mariadb://mariadb/silooo")) }
    }

    @Test
    @DisplayName("Le mode migrate n'a besoin que de la base, pas de la clé")
    fun migrateNeedsOnlyDatabase() {
        val database = Config.database(complete - Config.API_KEY)
        assertEquals("jdbc:mariadb://mariadb:3306/silooo", database.url)
        assertThrows<ConfigException> { Config.database(complete - Config.DB_URL) }
    }

    @Test
    @DisplayName("Ni le mot de passe ni la clé ne s'affichent")
    fun secretsStayHidden() {
        val text = Config.service(complete).toString()
        assertFalse(text.contains("mot-de-passe-de-test"), text)
        assertFalse(text.contains("cle-de-test"), text)
    }
}
