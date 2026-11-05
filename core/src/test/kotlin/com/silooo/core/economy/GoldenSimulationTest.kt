package com.silooo.core.economy

import com.silooo.core.GoldenFiles
import com.silooo.core.double
import com.silooo.core.int
import com.silooo.core.obj
import com.silooo.core.objects
import com.silooo.core.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Le rejeu de la feuille Simulation : le joueur type du tableur, rejoué de bout en bout avec le moteur et les
 * chiffres de definitions/, comparé aux 965 achats de simulation.json (README doré, « Rejouer le joueur type »).
 *
 * L'ordre des achats (usine, niveau visé) est une donnée du tableur : le test le lit, il ne le décide pas. Tout
 * le reste est recalculé : prix, revenu, attente, heures, Donar gagnés, multiplicateur, fin de course. La
 * tolérance, mesurée, est expliquée dans [GoldenTolerance].
 *
 * Interprétations retenues pour que le rejeu retrouve le tableur, toutes à valider par Kr (README doré) :
 *  - paliers à 25, 50 puis à chaque multiple de 100 (point 1) ;
 *  - la Carrière au niveau 1 au début de chaque course (point 2, clé `prestige.departDeCourse`) ;
 *  - multiplicateur figé pendant la course, Lingots fractionnaires dans le multiplicateur (point 4).
 */
class GoldenSimulationTest {
    private val golden: List<JsonObject> = GoldenFiles.read("simulation").objects("purchases")
    private val replay = TypicalPlayer.replay

    @Test
    @DisplayName("Le plan compte 965 achats sur 9 courses, et le rejeu en recalcule autant")
    fun sameNumberOfRows() {
        assertEquals(965, golden.size, "simulation.json a perdu des lignes")
        assertEquals(golden.size, replay.rows.size)
        assertEquals(GoldenFiles.read("courses").objects("courses").size, replay.courses.size)
        for ((row, expected) in replay.rows.zip(golden)) {
            assertEquals(expected.int("n"), row.n)
            assertEquals(expected.int("course"), row.course, "course de l'achat ${row.n}")
            assertEquals(expected.string("factory"), row.factoryName, "usine de l'achat ${row.n}")
            assertEquals(expected.int("targetLevel"), row.targetLevel, "niveau visé de l'achat ${row.n}")
        }
    }

    @Test
    @DisplayName("Le prix de chaque achat est la somme géométrique de levelBefore à targetLevel")
    fun prices() {
        for ((row, expected) in replay.rows.zip(golden)) {
            assertEquals(expected.int("levelBefore"), row.levelBefore, "niveau avant l'achat ${row.n}")
            assertGolden(expected.double("price"), row.price, "prix de l'achat ${row.n} (${row.factoryName} ${row.levelBefore} vers ${row.targetLevel})")
        }
    }

    @Test
    @DisplayName("Le revenu avant et après chaque achat : production des usines, paliers ×2, multiplicateur et hors ligne")
    fun incomes() {
        for ((row, expected) in replay.rows.zip(golden)) {
            assertGolden(expected.double("incomeBefore"), row.incomeBefore, "revenu avant l'achat ${row.n}")
            assertGolden(expected.double("incomeAfter"), row.incomeAfter, "revenu après l'achat ${row.n}")
            assertGolden(expected.double("globalMultiplier"), row.courseMultiplier, "multiplicateur de la course à l'achat ${row.n}")
        }
    }

    @Test
    @DisplayName("L'attente avant chaque achat, les Donar gagnés et les heures (dans la course et cumulées)")
    fun clocks() {
        for ((row, expected) in replay.rows.zip(golden)) {
            assertGolden(expected.double("waitHours"), row.waitHours, "attente avant l'achat ${row.n}")
            assertGolden(expected.double("donarEarnedInCourse"), row.donarEarned, "Donar gagnés à l'achat ${row.n}")
            assertGolden(expected.double("courseHour"), row.courseHour, "heure dans la course à l'achat ${row.n}")
            assertGolden(expected.double("cumulativeHour"), row.cumulativeHour, "heure cumulée à l'achat ${row.n}")
            if (expected.double("courseStartHour") == 0.0) {
                assertEquals(0.0, row.courseStartHour, "la course 1 part de l'heure 0 (achat ${row.n})")
            } else {
                assertGolden(expected.double("courseStartHour"), row.courseStartHour, "heure de départ de la course à l'achat ${row.n}")
            }
            assertGolden(expected.double("courseDurationIfPrestigeFollows"), row.durationIfPrestigeFollows, "durée de la course si le prestige suit l'achat ${row.n}")
        }
    }

    @Test
    @DisplayName("Le joueur type n'a jamais d'argent de côté : son solde est nul, au bruit de calcul près")
    fun balances() {
        for ((row, expected) in replay.rows.zip(golden)) {
            // Le tableur garde un résidu de -1 Donar sur un prix de 5,7e15 aux achats 550 et 551 : un arrondi, pas de l'argent.
            assertTrue(abs(expected.double("balanceBefore")) <= 1e-15 * row.price, "solde avant l'achat ${row.n} du tableur : ${expected.double("balanceBefore")}")
            assertTrue(abs(expected.double("balanceAfter")) <= 1e-15 * row.price, "solde après l'achat ${row.n} du tableur : ${expected.double("balanceAfter")}")
            assertEquals(0.0, row.balanceBefore, "solde avant l'achat ${row.n} du rejeu")
            assertEquals(0.0, row.balanceAfter, "solde après l'achat ${row.n} du rejeu")
        }
    }

    @Test
    @DisplayName("Le niveau de chaque usine après chaque achat")
    fun levelsAfterEachPurchase() {
        for ((row, expected) in replay.rows.zip(golden)) {
            val levels = expected.obj("levelsAfter").mapValues { it.value.jsonPrimitive.content.toInt() }
            assertEquals(levels, row.levelsAfter, "niveaux après l'achat ${row.n}")
        }
    }

    @Test
    @DisplayName("Les achats faits sont ceux qui laissent la course sous son seuil ; le dernier de chaque course n'est pas fait")
    fun decisionToStop() {
        for ((row, expected) in replay.rows.zip(golden)) {
            assertEquals(expected.int("beforePrestige") == 1, row.beforePrestige, "achat ${row.n} fait ou non")
            assertEquals(expected.int("newCourse") == 1, row.newCourse, "début de course à l'achat ${row.n}")
        }
        for ((course, rows) in replay.rows.groupBy { it.course }) {
            assertTrue(rows.dropLast(1).all { it.beforePrestige }, "course $course : tous les achats sauf le dernier sont faits")
            assertFalse(rows.last().beforePrestige, "course $course : le dernier achat du plan n'est pas fait")
            assertTrue(rows.first().newCourse && rows.drop(1).none { it.newCourse })
        }
        val madeAccordingToCourses = GoldenFiles.read("courses").objects("courses").sumOf { it.int("purchasesBeforePrestige") }
        assertEquals(madeAccordingToCourses, replay.rows.count { it.beforePrestige }, "achats faits sur toutes les courses")
    }

    @Test
    @DisplayName("Les prix et revenus affichés, calculés par le moteur, s'affichent comme dans le tableur")
    fun displays() {
        for ((row, expected) in replay.rows.zip(golden)) {
            assertEquals(sheetToCharter(expected.string("priceDisplay")), Notation.format(row.price), "prix affiché à l'achat ${row.n}")
            assertEquals(sheetToCharter(expected.string("incomeAfterDisplay")), Notation.format(row.incomeAfter), "revenu affiché à l'achat ${row.n}")
        }
    }
}
