package com.silooo.core

import java.nio.file.Files
import com.silooo.core.site.SiteMode
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ConfigTest {
    private val complete = mapOf(
        Config.DB_URL to "jdbc:mariadb://mariadb:3306/silooo",
        Config.DB_USER to "silooo",
        Config.DB_PASSWORD to "mot-de-passe-de-test",
        Config.API_KEY to "cle-de-test",
        Config.DEFINITIONS_DIR to TestDefinitions.directory.toString(),
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

    @Test
    @DisplayName("SILOOO_DEFINITIONS_DIR désigne le dossier des définitions")
    fun definitionsDirFromEnvironment() {
        assertEquals(TestDefinitions.directory, Config.service(complete).definitionsDir)
    }

    @Test
    @DisplayName("SILOOO_DEFINITIONS_DIR qui ne désigne pas un dossier est signalée avec les autres problèmes")
    fun definitionsDirMustBeADirectory(@TempDir temporary: Path) {
        val missing = temporary.resolve("nulle-part").toString()
        val error = assertThrows<ConfigException> { Config.service((complete - Config.API_KEY) + (Config.DEFINITIONS_DIR to missing)) }
        assertTrue(error.message!!.contains(Config.DEFINITIONS_DIR), error.message)
        assertTrue(error.message!!.contains(Config.API_KEY), "Les problèmes sont rendus d'un coup : ${error.message}")
    }

    @Test
    @DisplayName("En développement, sans variable, definitions/ du dépôt se trouve depuis core/ ou depuis la racine")
    fun definitionsDirFoundFromTheRepository(@TempDir temporary: Path) {
        val withoutVariable = complete - Config.DEFINITIONS_DIR
        val definitions = Files.createDirectories(temporary.resolve("definitions"))
        Files.writeString(definitions.resolve("economie.json"), "{}")
        val core = Files.createDirectories(temporary.resolve("core"))

        assertEquals(definitions.normalize(), Config.service(withoutVariable, workingDir = core).definitionsDir)
        assertEquals(definitions.normalize(), Config.service(withoutVariable, workingDir = temporary).definitionsDir)
    }

    @Test
    @DisplayName("Sans variable et hors du dépôt, le service refuse de démarrer et nomme la variable")
    fun definitionsDirMissingEverywhere(@TempDir temporary: Path) {
        val error = assertThrows<ConfigException> { Config.service(complete - Config.DEFINITIONS_DIR, workingDir = temporary) }
        assertTrue(error.message!!.contains(Config.DEFINITIONS_DIR), error.message)
    }

    @Test
    @DisplayName("Le mode migrate n'a pas besoin des définitions")
    fun migrateDoesNotNeedDefinitions() {
        Config.database(complete - Config.DEFINITIONS_DIR)
    }

    @Test
    @DisplayName("Le site écoute sur 8081 en mode attente quand rien n'est posé")
    fun siteDefaults() {
        val config = Config.service(complete)
        assertEquals(Config.DEFAULT_SITE_PORT, config.sitePort)
        assertEquals(8081, config.sitePort)
        assertEquals(SiteMode.ATTENTE, config.siteMode)
        assertEquals(Config.DEFAULT_SITE_URL, config.siteUrl)
        assertEquals(null, config.discordUrl)
    }

    @Test
    @DisplayName("SILOOO_SITE_MODE=ouvert et SILOOO_SITE_PORT sont lus")
    fun siteVariablesAreRead() {
        val config = Config.service(
            complete + mapOf(
                Config.SITE_MODE to "ouvert",
                Config.SITE_PORT to "9091",
                Config.SITE_URL to "https://exemple.test/",
                Config.SITE_URL_DISCORD to "https://discord.gg/exemple",
            ),
        )
        assertEquals(SiteMode.OUVERT, config.siteMode)
        assertEquals(9091, config.sitePort)
        assertEquals("https://exemple.test", config.siteUrl)
        assertEquals("https://discord.gg/exemple", config.discordUrl)
    }

    @Test
    @DisplayName("Un mode du site inconnu est refusé et le message cite la variable")
    fun unknownSiteMode() {
        for (bad in listOf("ouvert!", "closed", "1")) {
            val error = assertThrows<ConfigException>(bad) { Config.service(complete + (Config.SITE_MODE to bad)) }
            assertTrue(error.message!!.contains(Config.SITE_MODE), error.message)
        }
    }

    @Test
    @DisplayName("Le port du site ne peut pas être celui de l'API, et le message le dit")
    fun sitePortEqualToApiPort() {
        val error = assertThrows<ConfigException> { Config.service(complete + mapOf(Config.PORT to "9090", Config.SITE_PORT to "9090")) }
        assertTrue(error.message!!.contains(Config.SITE_PORT) && error.message!!.contains(Config.PORT), error.message)
        // Le défaut du site (8081) ne doit pas non plus tomber sur le port de l'API.
        assertThrows<ConfigException> { Config.service(complete + (Config.PORT to "8081")) }
    }

    @Test
    @DisplayName("Un port du site invalide est refusé")
    fun invalidSitePort() {
        for (bad in listOf("abc", "0", "70000", "-1")) {
            assertThrows<ConfigException>("SILOOO_SITE_PORT=$bad") { Config.service(complete + (Config.SITE_PORT to bad)) }
        }
    }

    @Test
    @DisplayName("Les problèmes du site s'ajoutent à ceux du reste, dans un seul message")
    fun siteProblemsAreListedWithTheOthers() {
        val error = assertThrows<ConfigException> { Config.service(mapOf(Config.SITE_MODE to "nimporte")) }
        assertTrue(error.message!!.contains(Config.SITE_MODE) && error.message!!.contains(Config.API_KEY), error.message)
    }

    @Test
    @DisplayName("Une adresse du site qui ne commence pas par http est refusée")
    fun invalidSiteUrl() {
        assertThrows<ConfigException> { Config.service(complete + (Config.SITE_URL to "silooo.com")) }
        assertThrows<ConfigException> { Config.service(complete + (Config.SITE_URL_DISCORD to "discord.gg/x")) }
    }

    @Test
    @DisplayName("Le toString montre le port et le mode du site, jamais la clé")
    fun toStringShowsSite() {
        val text = Config.service(complete).toString()
        assertTrue(text.contains("sitePort=8081") && text.contains("siteMode=attente"), text)
        assertFalse(text.contains("cle-de-test"), text)
    }
    // --- la réception des votes (Votifier)

    private val votifierToken = "jeton-factice-pour-les-tests-seulement"

    @Test
    @DisplayName("Sans SILOOO_VOTIFIER_TOKEN, la réception des votes est éteinte : pas de réglage, pas d'erreur")
    fun votifierIsOffWithoutAToken() {
        assertNull(Config.service(complete).votifier)
        assertNull(Config.service(complete + (Config.VOTIFIER_TOKEN to "   ")).votifier, "un jeton vide compte pour un jeton absent")
        assertNull(Config.service(complete + (Config.VOTIFIER_PORT to "pas-un-port")).votifier, "le port n'est lu que si le jeton est posé")
    }

    @Test
    @DisplayName("Avec un jeton, le port vaut 8192 par défaut ; SILOOO_VOTIFIER_PORT le change")
    fun votifierPort() {
        val byDefault = Config.service(complete + (Config.VOTIFIER_TOKEN to votifierToken)).votifier
        assertEquals(Config.DEFAULT_VOTIFIER_PORT, byDefault?.port)
        assertEquals(8192, Config.DEFAULT_VOTIFIER_PORT)
        assertEquals(votifierToken, byDefault?.token)

        val custom = Config.service(complete + (Config.VOTIFIER_TOKEN to votifierToken) + (Config.VOTIFIER_PORT to " 9192 ")).votifier
        assertEquals(9192, custom?.port)
    }

    @Test
    @DisplayName("Un port Votifier qui n'est pas un port, ou qui est celui de l'API ou du site, est refusé et nommé")
    fun invalidVotifierPort() {
        val withToken = complete + (Config.VOTIFIER_TOKEN to votifierToken)
        for (bad in listOf("abc", "0", "70000", "-1")) {
            val error = assertThrows<ConfigException>("SILOOO_VOTIFIER_PORT=$bad") { Config.service(withToken + (Config.VOTIFIER_PORT to bad)) }
            assertTrue(error.message!!.contains(Config.VOTIFIER_PORT), error.message)
        }
        assertThrows<ConfigException> { Config.service(withToken + (Config.VOTIFIER_PORT to Config.DEFAULT_PORT.toString())) }
        assertThrows<ConfigException> { Config.service(withToken + (Config.VOTIFIER_PORT to Config.DEFAULT_SITE_PORT.toString())) }
        assertThrows<ConfigException> { Config.service(withToken + (Config.PORT to "8192")) }
    }

    @Test
    @DisplayName("Le jeton Votifier ne s'affiche jamais, ni dans la configuration ni dans les réglages ; le port, si")
    fun votifierTokenStaysHidden() {
        val config = Config.service(complete + (Config.VOTIFIER_TOKEN to votifierToken))
        val text = config.toString()

        assertFalse(text.contains(votifierToken), text)
        assertFalse(config.votifier.toString().contains(votifierToken))
        assertTrue(text.contains("port=8192"), text)
    }

    @Test
    @DisplayName("Un port Votifier invalide est listé avec les autres problèmes, dans un seul message")
    fun votifierProblemIsListedWithTheOthers() {
        val error = assertThrows<ConfigException> {
            Config.service(
                (complete - Config.API_KEY) + (Config.VOTIFIER_TOKEN to votifierToken) + (Config.VOTIFIER_PORT to "abc"),
            )
        }

        assertTrue(error.message!!.contains(Config.API_KEY), error.message)
        assertTrue(error.message!!.contains(Config.VOTIFIER_PORT), error.message)
        assertFalse(error.message!!.contains(votifierToken), error.message)
    }
}
