package com.silooo.core.economy

import com.silooo.core.TestDefinitions
import com.silooo.core.api.FactoryState
import com.silooo.core.definitions.DefinitionsLoader
import com.silooo.core.session.SlotOrigin
import com.silooo.core.session.StartingFactory
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Ce que la création d'une entreprise tire de `definitions/` : usines de départ, plafond hors ligne, grille des
 * îles de `monde.json`. Les valeurs de la grille viennent du fichier ; la grille d'un monde inventé ne sert qu'à
 * vérifier que le code suit le JSON.
 */
class DefinitionsBootstrapTest {
    private val definitions = TestDefinitions.loaded
    private val engine = EconomyEngine(definitions)
    private val bootstrap = DefinitionsBootstrap(engine, definitions)
    private val grid = definitions.world.archipelago

    /** Un bootstrap sur les vraies définitions, sauf `monde.json` remplacé par [worldJson]. */
    private fun bootstrapWithWorld(worldJson: String): DefinitionsBootstrap {
        val changed = DefinitionsLoader.parse(
            TestDefinitions.text(DefinitionsLoader.ECONOMY_FILE),
            TestDefinitions.text(DefinitionsLoader.FACTORIES_FILE),
            TestDefinitions.text(DefinitionsLoader.SHOP_FILE),
            worldJson,
            TestDefinitions.text(DefinitionsLoader.MISSIONS_FILE),
            TestDefinitions.text(DefinitionsLoader.PERSONNEL_FILE),
        )
        return DefinitionsBootstrap(engine, changed)
    }

    private fun world(spacing: Int, perRow: Int, x: Int, z: Int): String =
        """{"version": 1, "archipel": {"ecartEntreCases": $spacing, "casesParRangee": $perRow, "origine": {"x": $x, "z": $z}}}"""

    @Test
    @DisplayName("Les usines de départ sont celles de prestige.departDeCourse, ACTIVE, sans étape de remise en état")
    fun startingFactoriesAreTheCourseStart() {
        val start = definitions.economy.prestige.courseStart

        assertEquals(listOf(StartingFactory(start.factoryId, FactoryState.ACTIVE, start.level)), bootstrap.startingFactories())
        assertEquals(0, bootstrap.startingFactories().single().restoreStep)
    }

    @Test
    @DisplayName("Le plafond hors ligne de départ est horsLigne.plafondHeures")
    fun startingOfflineCapIsTheDefinitionsCap() {
        assertEquals(definitions.economy.offline.capHours, bootstrap.startingOfflineCapHours())
    }

    @Test
    @DisplayName("La case 0 est l'origine de monde.json ; l'écart se compte en colonnes puis en rangées")
    fun gridFollowsTheWorldFile() {
        val perRow = grid.slotsPerRow
        for (index in 0 until 3 * perRow + 2) {
            val expected = SlotOrigin(
                grid.origin.x + (index % perRow) * grid.slotSpacing,
                grid.origin.z + (index / perRow) * grid.slotSpacing,
            )
            assertEquals(expected, bootstrap.slotOrigin(index), "case $index")
        }
        assertEquals(SlotOrigin(grid.origin.x, grid.origin.z), bootstrap.slotOrigin(0))
        assertEquals(SlotOrigin(grid.origin.x, grid.origin.z + grid.slotSpacing), bootstrap.slotOrigin(perRow))
    }

    @Test
    @DisplayName("Deux cases n'ont jamais la même origine (uq_slots_origin) et sont au moins ecartEntreCases blocs l'une de l'autre")
    fun originsAreDistinctAndSpaced() {
        val origins = (0 until 4 * grid.slotsPerRow).map { bootstrap.slotOrigin(it) }

        assertEquals(origins.size, origins.toSet().size)
        for (a in origins.indices) for (b in a + 1 until origins.size) {
            val gap = maxOf(abs(origins[a].x - origins[b].x), abs(origins[a].z - origins[b].z))
            assertTrue(gap >= grid.slotSpacing, "cases $a et $b trop proches : $gap blocs")
        }
    }

    @Test
    @DisplayName("Un autre monde.json change la grille sans toucher au code")
    fun anotherWorldFileChangesTheGrid() {
        val other = bootstrapWithWorld(world(spacing = 10, perRow = 3, x = -5, z = 7))

        assertEquals(SlotOrigin(-5, 7), other.slotOrigin(0))
        assertEquals(SlotOrigin(5, 7), other.slotOrigin(1))
        assertEquals(SlotOrigin(15, 7), other.slotOrigin(2))
        assertEquals(SlotOrigin(-5, 17), other.slotOrigin(3))
        assertEquals(SlotOrigin(5, 27), other.slotOrigin(7))
    }

    @Test
    @DisplayName("Une case hors des entiers de 32 bits est refusée, jamais repliée sur une autre origine")
    fun gridOverflowIsRefused() {
        val huge = bootstrapWithWorld(world(spacing = 1_500_000_000, perRow = 4, x = 0, z = 0))

        assertEquals(SlotOrigin(1_500_000_000, 0), huge.slotOrigin(1))
        assertThrows<ArithmeticException> { huge.slotOrigin(2) }
    }

    @Test
    @DisplayName("Un numéro de case négatif est refusé")
    fun negativeSlotIsRefused() {
        assertThrows<IllegalArgumentException> { bootstrap.slotOrigin(-1) }
    }
}
