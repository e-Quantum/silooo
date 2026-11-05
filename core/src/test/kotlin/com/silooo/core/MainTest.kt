package com.silooo.core

import com.silooo.core.definitions.DefinitionsLoader
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Les deux modes de démarrage, sans base : ce qui doit échouer échoue net, avec le bon code de sortie. */
class MainTest {
    // Un port où personne n'écoute : la connexion est refusée tout de suite.
    private val unreachable = mapOf(
        Config.DB_URL to "jdbc:mariadb://127.0.0.1:1/silooo",
        Config.DB_USER to "silooo",
        Config.DB_PASSWORD to "mot-de-passe-de-test",
        Config.API_KEY to "cle-de-test",
        Config.DEFINITIONS_DIR to TestDefinitions.directory.toString(),
    )

    @Test
    @DisplayName("Démarrage normal et migrate sortent avec le code de configuration quand il manque tout")
    fun bothModesRefuseWithoutConfiguration() {
        assertEquals(EXIT_CONFIG, runService(emptyMap()))
        assertEquals(EXIT_CONFIG, runMigrate(emptyMap()))
    }

    @Test
    @DisplayName("Le démarrage normal sans clé s'arrête avant de toucher à la base")
    fun serviceRefusesWithoutKey() {
        assertEquals(EXIT_CONFIG, runService(unreachable - Config.API_KEY))
    }

    @Test
    @DisplayName("Un dossier de définitions absent ou abîmé arrête le démarrage avant la base, avec le code de configuration")
    fun serviceRefusesBrokenDefinitions(@TempDir temporary: Path) {
        val missing = temporary.resolve("nulle-part").toString()
        assertEquals(EXIT_CONFIG, runService(unreachable + (Config.DEFINITIONS_DIR to missing)))

        // Un dossier qui existe mais dont economie.json est un JSON cassé : la base injoignable n'est jamais atteinte
        // (elle donnerait EXIT_FAILURE).
        Files.writeString(temporary.resolve(DefinitionsLoader.ECONOMY_FILE), "{ pas du json")
        Files.writeString(temporary.resolve(DefinitionsLoader.FACTORIES_FILE), TestDefinitions.text(DefinitionsLoader.FACTORIES_FILE))
        Files.writeString(temporary.resolve(DefinitionsLoader.SHOP_FILE), TestDefinitions.text(DefinitionsLoader.SHOP_FILE))
        assertEquals(EXIT_CONFIG, runService(unreachable + (Config.DEFINITIONS_DIR to temporary.toString())))
    }

    @Test
    @DisplayName("Avec une base injoignable, les deux modes échouent au lieu de démarrer à moitié")
    fun bothModesFailWhenDatabaseIsDown() {
        assertEquals(EXIT_FAILURE, runService(unreachable))
        assertEquals(EXIT_FAILURE, runMigrate(unreachable))
    }
}
