package com.silooo.core.simulation

import com.silooo.core.TestDefinitions
import com.silooo.core.economy.EconomyEngine
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le joueur à une seule usine (plan d'action de la vidéo du 8 novembre 2026, P2) : tant que l'usine de départ est seule,
 * aucune deuxième usine n'est découverte, et le joueur ne peut que la monter. Chaque niveau de cette montée doit se
 * rembourser en `niveaux.retourMaxSeuleUsineHeures` au plus, sans multiplicateur : sinon le joueur attend sans rien à
 * faire. Les niveaux, les prix et la découverte viennent de definitions/ ; aucun chiffre du jeu n'est écrit ici.
 */
class SingleFactoryPaybackTest {
    private val definitions = TestDefinitions.loaded
    private val engine = EconomyEngine(definitions)
    private val start = definitions.economy.prestige.courseStart
    private val limit = definitions.economy.levels.maxPaybackSingleFactoryHours

    /** Le temps de retour du niveau suivant de l'usine de départ, au niveau [level], sans multiplicateur ni équipe. */
    private fun payback(level: Int): Double =
        engine.nextLevelCost(start.factoryId, level) /
            (engine.factoryRate(start.factoryId, level + 1) - engine.factoryRate(start.factoryId, level))

    @Test
    @DisplayName("Seule, l'usine de départ révèle une autre usine, et chacun de ses niveaux jusque-là se rembourse dans la limite")
    fun everyLevelBeforeTheSecondFactoryPaysBack() {
        val active = setOf(start.factoryId)
        var level = start.level
        while (engine.discoveredFactories(mapOf(start.factoryId to level), 1, active).size < 2) {
            val hours = payback(level)
            assertTrue(hours <= limit, "niveau $level → ${level + 1} : ${"%.2f".format(hours)} h pour se rembourser, plus de $limit h")
            level += 1
            assertTrue(level < MAX_LEVELS, "aucune deuxième usine n'est découverte avant le niveau $MAX_LEVELS")
        }
    }

    private companion object {
        /** Une borne de sécurité pour la boucle, pas une valeur du jeu. */
        const val MAX_LEVELS = 10_000
    }
}
