package com.silooo.core.economy

import com.silooo.core.GoldenFiles
import com.silooo.core.double
import com.silooo.core.int
import com.silooo.core.long
import com.silooo.core.objects
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La feuille Ville de golden/ville.json : les 16 zones, l'heure où le joueur type peut payer chacune, et le
 * budget ville après chaque course. Les prix des zones viennent de definitions/economie.json ; le calcul du
 * budget (la part des Lingots que le joueur type ne garde pas pour le multiplicateur) est dans TypicalPlayer,
 * qui rejoue la formule de la feuille avec la durée des courses que le moteur a trouvée.
 *
 * Le moteur n'a pas de règle « zone de ville » en phase 1 : ces tests prouvent que les chiffres de definitions/
 * et les Lingots du prestige recomposent la feuille, pas qu'une mécanique de ville existe.
 */
class GoldenCityTest {
    private val replay = TypicalPlayer.replay
    private val goldenZones = GoldenFiles.read("ville").objects("zones")
    private val goldenBudgets = GoldenFiles.read("ville").objects("courseBudgets")

    @Test
    @DisplayName("Le budget ville après chaque course : heure de fin, Lingots de prestige cumulés, part non gardée")
    fun courseBudgets() {
        assertEquals(replay.courses.size, goldenBudgets.size)
        val budgets = TypicalPlayer.cityBudgetsJustAfter(replay.courses)
        var cumulativePrestige = 0L
        for ((index, expected) in goldenBudgets.withIndex()) {
            val course = replay.courses[index]
            val n = expected.int("course")
            assertEquals(course.course, n)
            cumulativePrestige += course.lingotsAtPrestige
            assertGolden(expected.double("endHour"), course.endHour, "heure de fin de la course $n")
            assertEquals(expected.long("cumulativePrestigeLingots"), cumulativePrestige, "Lingots de prestige cumulés à la course $n")
            assertGolden(expected.double("cityBudgetJustAfter"), budgets[index], "budget ville juste après la course $n")
        }
    }

    @Test
    @DisplayName("Les 16 zones : prix, prix cumulé et course où le budget ville les paie")
    fun zonePricesAndCourses() {
        val zones = TypicalPlayer.cityZones(replay.courses)
        assertEquals(16, goldenZones.size)
        assertEquals(goldenZones.size, zones.size)
        for ((expected, zone) in goldenZones.zip(zones)) {
            val n = expected.int("zone")
            assertEquals(n, zone.zone)
            assertEquals(expected.long("priceLingots"), zone.priceLingots, "prix de la zone $n")
            assertEquals(expected.long("cumulativePriceLingots"), zone.cumulativePriceLingots, "prix cumulé de la zone $n")
            assertEquals(expected.int("affordableInCourse"), zone.affordableInCourse, "course où la zone $n devient abordable")
        }
    }

    @Test
    @DisplayName("Les 16 zones : heure de jeu cumulée et jours réels du joueur type")
    fun zoneHours() {
        val zones = TypicalPlayer.cityZones(replay.courses)
        for ((expected, zone) in goldenZones.zip(zones)) {
            val n = expected.int("zone")
            assertGolden(expected.double("affordableAtHour"), zone.affordableAtHour, "heure où la zone $n devient abordable")
            assertGolden(expected.double("realDays"), zone.realDays, "jours réels de la zone $n")
        }
    }

    @Test
    @DisplayName("Les zones se paient dans l'ordre : l'heure ne recule jamais, la course non plus")
    fun zonesAreOrdered() {
        val zones = TypicalPlayer.cityZones(replay.courses)
        for ((before, after) in zones.zipWithNext()) {
            assertTrue(after.affordableAtHour >= before.affordableAtHour, "la zone ${after.zone} s'ouvre avant la zone ${before.zone}")
            assertTrue(after.affordableInCourse >= before.affordableInCourse)
            assertTrue(after.cumulativePriceLingots > before.cumulativePriceLingots)
        }
    }
}
