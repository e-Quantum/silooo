package com.silooo.core.definitions

import com.silooo.core.TestDefinitions
import com.silooo.core.keyPaths
import com.silooo.core.without
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * Le chargeur de definitions/ est strict : il lit les vrais fichiers du dépôt, et chaque défaut d'un
 * fichier (clé manquante, clé inconnue, mauvais type, fichier absent) l'arrête net avec un message qui
 * dit où. Aucun chiffre du jeu ici : les tests abîment les fichiers du dépôt, ils n'en recopient rien.
 */
class DefinitionsLoaderTest {
    private val economyText = TestDefinitions.text(DefinitionsLoader.ECONOMY_FILE)
    private val factoriesText = TestDefinitions.text(DefinitionsLoader.FACTORIES_FILE)
    private val shopText = TestDefinitions.text(DefinitionsLoader.SHOP_FILE)
    private val worldText = TestDefinitions.text(DefinitionsLoader.WORLD_FILE)

    private fun parseJson(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun load(
        economy: String = economyText,
        factories: String = factoriesText,
        shop: String = shopText,
        world: String = worldText,
    ): Definitions = DefinitionsLoader.parse(economy, factories, shop, world)

    @Test
    @DisplayName("Les quatre fichiers du dépôt se chargent, et le dossier donne le même résultat que les textes")
    fun loadsTheRepositoryFiles() {
        val fromDirectory = DefinitionsLoader.load(TestDefinitions.directory)
        val fromText = load()
        assertEquals(fromText.economy, fromDirectory.economy)
        assertEquals(fromText.factories, fromDirectory.factories)
        assertEquals(fromText.shop, fromDirectory.shop)
        assertEquals(fromText.world, fromDirectory.world)
        assertTrue(fromDirectory.factories.factories.isNotEmpty())
        assertTrue(fromDirectory.economy.prestige.thresholds.isNotEmpty())
    }

    @Test
    @DisplayName("Retirer n'importe quelle clé de economie.json fait échouer le chargement")
    fun everyEconomyKeyIsRequired() {
        val root = parseJson(economyText)
        val paths = root.keyPaths()
        assertTrue(paths.isNotEmpty())
        for (path in paths) {
            val damaged = root.without(path).toString()
            val error = assertThrows<DefinitionsException>("economie.json sans ${path.joinToString(".")}") { load(economy = damaged) }
            assertTrue(error.message!!.contains("economie.json"), error.message)
        }
    }

    @Test
    @DisplayName("Retirer n'importe quelle clé de usines.json fait échouer le chargement")
    fun everyFactoryKeyIsRequired() {
        val root = parseJson(factoriesText)
        for (path in root.keyPaths()) {
            val damaged = root.without(path).toString()
            val error = assertThrows<DefinitionsException>("usines.json sans ${path.joinToString(".")}") { load(factories = damaged) }
            assertTrue(error.message!!.contains("usines.json"), error.message)
        }
    }

    @Test
    @DisplayName("Retirer une clé obligatoire de boutique.json fait échouer le chargement")
    fun everyRequiredShopKeyIsRequired() {
        // Un abonnement porte soit un bonus de Donar, soit une réserve à un grade : ces trois clés sont facultatives.
        val optional = setOf("bonusDonarPourcent", "gemmesRendues", "reserveA")
        val root = parseJson(shopText)
        for (path in root.keyPaths().filter { it.last() !in optional }) {
            val damaged = root.without(path).toString()
            val error = assertThrows<DefinitionsException>("boutique.json sans ${path.joinToString(".")}") { load(shop = damaged) }
            assertTrue(error.message!!.contains("boutique.json"), error.message)
        }
    }

    @Test
    @DisplayName("Retirer n'importe quelle clé de monde.json fait échouer le chargement")
    fun everyWorldKeyIsRequired() {
        val root = parseJson(worldText)
        val paths = root.keyPaths()
        assertTrue(paths.isNotEmpty())
        for (path in paths) {
            val damaged = root.without(path).toString()
            val error = assertThrows<DefinitionsException>("monde.json sans ${path.joinToString(".")}") { load(world = damaged) }
            assertTrue(error.message!!.contains("monde.json"), error.message)
        }
    }

    @Test
    @DisplayName("Une grille de monde sans écart ou sans case par rangée est refusée")
    fun worldGridMustHaveRoom() {
        val root = parseJson(worldText)
        val archipelago = root.getValue("archipel").jsonObject
        for (key in listOf("ecartEntreCases", "casesParRangee")) {
            val damaged = JsonObject(root + ("archipel" to JsonObject(archipelago + (key to JsonPrimitive(0)))))
            val message = assertThrows<DefinitionsException>("monde.json avec $key à 0") { load(world = damaged.toString()) }.message!!
            assertTrue(message.contains("archipel.$key"), message)
        }
    }

    @Test
    @DisplayName("Un facteur de seuil qui ne dépasse pas 1 est refusé")
    fun nextThresholdFactorMustExceedOne() {
        val root = parseJson(economyText)
        val prestige = root.getValue("prestige").jsonObject
        for (factor in listOf(1, 0)) {
            val damaged = JsonObject(root + ("prestige" to JsonObject(prestige + ("facteurSeuilSuivant" to JsonPrimitive(factor)))))
            val message = assertThrows<DefinitionsException>("facteurSeuilSuivant à $factor") { load(economy = damaged.toString()) }.message!!
            assertTrue(message.contains("prestige.facteurSeuilSuivant"), message)
        }
    }

    @Test
    @DisplayName("Le message d'une clé manquante cite la clé et le chemin JSON")
    fun missingKeyMessageNamesTheKey() {
        val damaged = parseJson(economyText).without(listOf("niveaux", "croissancePrix")).toString()
        val message = assertThrows<DefinitionsException> { load(economy = damaged) }.message!!
        assertTrue(message.contains("croissancePrix"), message)
        assertTrue(message.contains("$.niveaux"), message)
    }

    @Test
    @DisplayName("Une clé inconnue est refusée : le fichier et le code disent la même chose")
    fun unknownKeyIsRefused() {
        val root = parseJson(economyText)
        val extra = JsonObject(root + ("cleEnTrop" to JsonPrimitive(1)))
        val message = assertThrows<DefinitionsException> { load(economy = extra.toString()) }.message!!
        assertTrue(message.contains("cleEnTrop"), message)
    }

    @Test
    @DisplayName("Un nombre à la place d'un texte, et du texte à la place d'un nombre, sont refusés")
    fun wrongTypeIsRefused() {
        val root = parseJson(economyText)
        val levels = root.getValue("niveaux").jsonObject
        val damaged = JsonObject(root + ("niveaux" to JsonObject(levels + ("croissancePrix" to JsonPrimitive("beaucoup")))))
        assertThrows<DefinitionsException> { load(economy = damaged.toString()) }
    }

    @Test
    @DisplayName("Un JSON cassé est refusé avec le nom du fichier")
    fun brokenJsonIsRefused() {
        val message = assertThrows<DefinitionsException> { load(factories = "{ pas du json") }.message!!
        assertTrue(message.contains("usines.json"), message)
    }

    @Test
    @DisplayName("Les problèmes de plusieurs fichiers sont rendus d'un coup")
    fun problemsOfSeveralFilesAreReportedTogether() {
        val message = assertThrows<DefinitionsException> { load(economy = "{}", factories = "{}") }.message!!
        assertTrue(message.contains("economie.json") && message.contains("usines.json"), message)
    }

    @Test
    @DisplayName("Une usine de départ qui n'existe pas dans usines.json est refusée")
    fun unknownStartingFactoryIsRefused() {
        val root = parseJson(economyText)
        val prestige = root.getValue("prestige").jsonObject
        val start = buildJsonObject {
            put("usine", "usine-qui-n-existe-pas")
            put("niveau", 1)
        }
        val damaged = JsonObject(root + ("prestige" to JsonObject(prestige + ("departDeCourse" to start))))
        val message = assertThrows<DefinitionsException> { load(economy = damaged.toString()) }.message!!
        assertTrue(message.contains("usine-qui-n-existe-pas"), message)
    }

    @Test
    @DisplayName("Une croissance de prix qui ne dépasse pas 1 est refusée")
    fun priceGrowthMustExceedOne() {
        val root = parseJson(economyText)
        val levels = root.getValue("niveaux").jsonObject
        val damaged = JsonObject(root + ("niveaux" to JsonObject(levels + ("croissancePrix" to JsonPrimitive(1)))))
        val message = assertThrows<DefinitionsException> { load(economy = damaged.toString()) }.message!!
        assertTrue(message.contains("croissancePrix"), message)
    }

    @Test
    @DisplayName("Des seuils de prestige qui ne se suivent pas depuis la course 1 sont refusés")
    fun thresholdsMustFollowCourseNumbers() {
        val root = parseJson(economyText)
        val prestige = root.getValue("prestige").jsonObject
        val reversed = JsonArray(prestige.getValue("seuils").jsonArray.reversed())
        val damaged = JsonObject(root + ("prestige" to JsonObject(prestige + ("seuils" to reversed))))
        val message = assertThrows<DefinitionsException> { load(economy = damaged.toString()) }.message!!
        assertTrue(message.contains("prestige.seuils"), message)
    }

    @Test
    @DisplayName("Un fichier absent ou un dossier absent sont refusés en disant lequel")
    fun missingFileOrDirectoryIsRefused(@TempDir temporary: Path) {
        val noDirectory = assertThrows<DefinitionsException> { DefinitionsLoader.load(temporary.resolve("nulle-part")) }
        assertTrue(noDirectory.message!!.contains("nulle-part"), noDirectory.message)

        Files.writeString(temporary.resolve(DefinitionsLoader.ECONOMY_FILE), economyText)
        Files.writeString(temporary.resolve(DefinitionsLoader.FACTORIES_FILE), factoriesText)
        val noShop = assertThrows<DefinitionsException> { DefinitionsLoader.load(temporary) }
        assertTrue(noShop.message!!.contains(DefinitionsLoader.SHOP_FILE), noShop.message)

        Files.writeString(temporary.resolve(DefinitionsLoader.SHOP_FILE), shopText)
        val noWorld = assertThrows<DefinitionsException> { DefinitionsLoader.load(temporary) }
        assertTrue(noWorld.message!!.contains(DefinitionsLoader.WORLD_FILE), noWorld.message)

        Files.writeString(temporary.resolve(DefinitionsLoader.WORLD_FILE), worldText)
        assertEquals(load().economy, DefinitionsLoader.load(temporary).economy)
    }
}
