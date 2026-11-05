package com.silooo.core.economy

import com.silooo.core.TestDefinitions
import com.silooo.core.definitions.Definitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * La découverte des usines et le niveau d'entreprise : la lecture de la clé `decouverte` de usines.json par le moteur.
 * Aucune valeur du jeu n'est écrite ici : les niveaux et les époques à atteindre sont lus dans la clé elle-même.
 */
class EngineDiscoveryTest {
    private val definitions = TestDefinitions.loaded
    private val engine = EconomyEngine(definitions)
    private val factories = definitions.factories.factories

    private fun number(key: String, prefix: String): Int? = if (key.startsWith(prefix)) key.removePrefix(prefix).toIntOrNull() else null

    @Test
    @DisplayName("Le niveau d'entreprise est la somme des niveaux ; une usine absente compte pour zéro")
    fun companyLevelIsTheSumOfLevels() {
        assertEquals(0, engine.companyLevel(emptyMap()))
        val levels = factories.withIndex().associate { (index, factory) -> factory.id to index + 1 }
        assertEquals(levels.values.sum(), engine.companyLevel(levels))
        val first = factories.first().id
        assertEquals(7, engine.companyLevel(mapOf(first to 7)))
    }

    @Test
    @DisplayName("Le niveau d'entreprise refuse une usine inconnue et un niveau négatif")
    fun companyLevelChecksItsInput() {
        assertThrows<IllegalArgumentException> { engine.companyLevel(mapOf("usine-qui-n-existe-pas" to 1)) }
        assertThrows<IllegalArgumentException> { engine.companyLevel(mapOf(factories.first().id to -1)) }
    }

    @Test
    @DisplayName("depart : l'usine est révélée tout de suite, à n'importe quel niveau d'entreprise et dans n'importe quelle époque")
    fun atStartIsAlwaysDiscovered() {
        val atStart = factories.filter { it.discovery == "depart" }
        assertTrue(atStart.isNotEmpty(), "usines.json a au moins une usine de départ")
        for (factory in atStart) assertTrue(engine.isDiscovered(factory.id, 0, 1, emptySet()), factory.id)
    }

    @Test
    @DisplayName("niveau-entreprise-N : révélée au niveau d'entreprise N, pas à N - 1, quelle que soit l'époque")
    fun companyLevelRuleOpensAtItsLevel() {
        val gated = factories.mapNotNull { factory -> number(factory.discovery, "niveau-entreprise-")?.let { factory to it } }
        assertTrue(gated.isNotEmpty(), "usines.json a des usines révélées par le niveau d'entreprise")
        for ((factory, needed) in gated) {
            assertFalse(engine.isDiscovered(factory.id, needed - 1, 99, setOf(factory.id)), "${factory.id} à ${needed - 1}")
            assertTrue(engine.isDiscovered(factory.id, needed, 1, emptySet()), "${factory.id} à $needed")
            assertTrue(engine.isDiscovered(factory.id, needed + 1, 1, emptySet()), "${factory.id} à ${needed + 1}")
        }
    }

    @Test
    @DisplayName("epoque-N : révélée à l'époque N, pas à N - 1, quel que soit le niveau d'entreprise")
    fun eraRuleOpensAtItsEra() {
        val gated = factories.mapNotNull { factory -> number(factory.discovery, "epoque-")?.let { factory to it } }
        assertTrue(gated.isNotEmpty(), "usines.json a des usines révélées par l'époque")
        for ((factory, needed) in gated) {
            assertFalse(engine.isDiscovered(factory.id, Int.MAX_VALUE, needed - 1, setOf(factory.id)), "${factory.id} à l'époque ${needed - 1}")
            assertTrue(engine.isDiscovered(factory.id, 0, needed, emptySet()), "${factory.id} à l'époque $needed")
            assertTrue(engine.isDiscovered(factory.id, 0, needed + 1, emptySet()), "${factory.id} à l'époque ${needed + 1}")
        }
    }

    @Test
    @DisplayName("apres-<usine> : révélée quand l'usine nommée est remise en état, pas avant")
    fun afterRuleWaitsForTheNamedFactory() {
        val gated = factories.filter { it.discovery.startsWith("apres-") }
        assertTrue(gated.isNotEmpty(), "usines.json a des usines révélées après une autre")
        for (factory in gated) {
            val required = factory.discovery.removePrefix("apres-")
            assertTrue(required in engine.factoryIds(), "${factory.id} : $required est une usine de usines.json")
            assertFalse(engine.isDiscovered(factory.id, Int.MAX_VALUE, 99, engine.factoryIds().toSet() - required), "${factory.id} sans $required")
            assertTrue(engine.isDiscovered(factory.id, 0, 1, setOf(required)), "${factory.id} avec $required")
        }
    }

    @Test
    @DisplayName("Toutes les clés decouverte de usines.json sont comprises, et une époque ou un niveau d'entreprise hors limite est refusé")
    fun everyKeyIsUnderstoodAndInputsAreChecked() {
        for (factory in factories) engine.isDiscovered(factory.id, 0, 1, emptySet())
        assertThrows<IllegalArgumentException> { engine.isDiscovered("usine-qui-n-existe-pas", 0, 1, emptySet()) }
        assertThrows<IllegalArgumentException> { engine.isDiscovered(factories.first().id, -1, 1, emptySet()) }
        assertThrows<IllegalArgumentException> { engine.isDiscovered(factories.first().id, 0, 0, emptySet()) }
    }

    private fun engineWithDiscovery(key: String): EconomyEngine {
        val first = definitions.factories.factories.first()
        val changed = definitions.factories.copy(factories = listOf(first.copy(discovery = key)) + definitions.factories.factories.drop(1))
        return EconomyEngine(Definitions(definitions.economy, changed, definitions.shop, definitions.world, definitions.missions))
    }

    @Test
    @DisplayName("Une clé decouverte qui n'a aucune forme connue, ou qui nomme une usine inconnue ou elle-même, échoue en disant laquelle")
    fun unknownKeysFailLoudly() {
        val first = factories.first().id
        for (key in listOf("plus-tard", "niveau-entreprise-", "niveau-entreprise-0", "epoque-x", "apres-usine-qui-n-existe-pas", "apres-$first")) {
            val error = assertThrows<IllegalArgumentException>(key) { engineWithDiscovery(key).isDiscovered(first, 0, 1, emptySet()) }
            assertTrue(error.message!!.contains(first) && error.message!!.contains(key), "le message nomme l'usine et la clé : ${error.message}")
        }
    }

    @Test
    @DisplayName("Un moteur dont une clé decouverte est fausse reste utilisable pour tout le reste")
    fun badDiscoveryKeyDoesNotBreakThePrices() {
        val broken = engineWithDiscovery("plus-tard")
        assertEquals(engine.levelsCost(factories.first().id, 0, 3), broken.levelsCost(factories.first().id, 0, 3))
    }
}
