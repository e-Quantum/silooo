package com.silooo.core.economy

import com.silooo.core.GoldenFiles
import com.silooo.core.double
import com.silooo.core.int
import com.silooo.core.long
import com.silooo.core.obj
import com.silooo.core.objects
import com.silooo.core.string
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les 9 courses de courses.json et les 13 cibles de synthese.json, retrouvées par le moteur : seuils,
 * multiplicateurs, durées, Lingots du prestige, puis la valeur de chaque cible contre sa fourchette
 * (README doré, « Les 13 cibles »). Tolérance : [GoldenTolerance].
 */
class GoldenCoursesTest {
    private val goldenCourses = GoldenFiles.read("courses").objects("courses")
    private val replay = TypicalPlayer.replay
    private val engine = TypicalPlayer.engine
    private val settings = TypicalPlayer.settings

    @Test
    @DisplayName("Les 9 courses : seuil, époque, étoiles et nombre d'achats faits")
    fun thresholdsErasAndPurchases() {
        assertEquals(goldenCourses.size, replay.courses.size)
        for ((expected, course) in goldenCourses.zip(replay.courses)) {
            val n = expected.int("course")
            assertEquals(n, course.course)
            assertEquals(expected.double("prestigeThresholdDonar"), course.threshold, "seuil de la course $n")
            assertEquals(expected.int("mostAdvancedEra"), course.era, "époque de la course $n")
            assertEquals(expected.int("starsAlreadyEarned"), course.stars, "étoiles déjà gagnées à la course $n")
            assertEquals(expected.int("purchasesBeforePrestige"), course.purchasesMade, "achats faits à la course $n")
            assertEquals("Non", expected.string("exhaustedSteps"), "la course $n n'épuise pas les paliers du plan")
        }
    }

    @Test
    @DisplayName("Heure de départ, Lingots gagnés et gardés, multiplicateur, durée et heure de fin de chaque course")
    fun clocksAndMultipliers() {
        for ((expected, course) in goldenCourses.zip(replay.courses)) {
            val n = course.course
            if (expected.double("startHour") == 0.0) {
                assertEquals(0.0, course.startHour, "la course 1 part de l'heure 0")
            } else {
                assertGolden(expected.double("startHour"), course.startHour, "heure de départ de la course $n")
            }
            assertGolden(expected.double("lingotsEarnedBeforeStart"), course.lingotsEarnedBefore, "Lingots gagnés avant la course $n")
            assertGolden(expected.double("lingotsKept"), course.lingotsKept, "Lingots gardés à la course $n")
            assertGolden(expected.double("globalMultiplier"), course.courseMultiplier, "multiplicateur de la course $n")
            assertGolden(expected.double("durationHours"), course.duration, "durée de la course $n")
            assertGolden(expected.double("endHour"), course.endHour, "heure de fin de la course $n")
        }
    }

    @Test
    @DisplayName("Au seuil, chaque prestige donne 100 Lingots : référence de conversion, calcul exact et entier")
    fun lingotsAtPrestige() {
        for ((expected, course) in goldenCourses.zip(replay.courses)) {
            val n = course.course
            assertGolden(expected.double("conversionReferenceDonar"), course.conversionReference, "référence de conversion de la course $n")
            assertGolden(expected.double("lingotsEarnedAtPrestige"), course.lingotsAtPrestigeExact, "Lingots exacts de la course $n")
            assertEquals(Math.round(expected.double("lingotsEarnedAtPrestige")), course.lingotsAtPrestige, "Lingots entiers de la course $n")
            assertEquals(engine.prestigeLingots(n, expected.double("prestigeThresholdDonar")), course.lingotsAtPrestige)
        }
    }

    @Test
    @DisplayName("Le revenu en fin de course et les textes du seuil et du revenu")
    fun incomeAtTheEnd() {
        for ((expected, course) in goldenCourses.zip(replay.courses)) {
            val n = course.course
            assertEquals(sheetToCharter(expected.string("incomeAtEndDisplay")), Notation.format(course.incomeAtEnd), "revenu affiché à la fin de la course $n")
            assertEquals(sheetToCharter(expected.string("thresholdDisplay")), Notation.format(course.threshold), "seuil affiché de la course $n")
        }
    }

    @Test
    @DisplayName("Chaque course tient dans sa fourchette de durée, avec le verdict du tableur")
    fun verdictPerCourse() {
        for ((expected, course) in goldenCourses.zip(replay.courses)) {
            val n = course.course
            // Les trois premières courses se jugent à l'heure de fin, les suivantes à leur durée (Courses!R).
            val compared = if (n <= 3) course.endHour else course.duration
            assertGolden(expected.double("valueComparedToTarget"), compared, "valeur comparée à la cible de la course $n")
            assertEquals(expected.string("verdict"), verdict(compared, expected.double("targetMin"), expected.double("targetMax")), "verdict de la course $n")
        }
    }

    // ---------------------------------------------------------------- synthese.json

    private val synthese = GoldenFiles.read("synthese")
    private val targets = synthese.objects("targets")

    private fun verdict(value: Double, min: Double, max: Double): String = when {
        value < min -> "Trop court"
        value > max -> "Trop long"
        else -> "Dans la cible"
    }

    /** La valeur que le moteur donne à la cible, d'après le lien que synthese.json indique. */
    private fun replayedTarget(target: kotlinx.serialization.json.JsonObject): Double {
        val link = target.obj("link")
        return when {
            "simulationPurchase" in link -> replay.rows[link.int("simulationPurchase") - 1].cumulativeHour
            "cityZone" in link -> TypicalPlayer.cityZones(replay.courses)[link.int("cityZone") - 1].affordableAtHour
            "course" in link -> {
                val course = replay.courses[link.int("course") - 1]
                when (val measure = target.string("measure")) {
                    "cumulativeHour" -> course.endHour
                    "courseDuration" -> course.duration
                    else -> error("Mesure inconnue : $measure")
                }
            }
            else -> error("Lien inconnu : $link")
        }
    }

    @Test
    @DisplayName("Les 13 cibles : la valeur recalculée par le moteur égale celle du tableur et tombe dans la fourchette")
    fun thirteenTargets() {
        assertEquals(13, targets.size)
        for (target in targets) {
            val id = target.string("id")
            val value = replayedTarget(target)
            assertGolden(target.double("computed"), value, "cible $id")
            assertTrue(value >= target.double("targetMin") && value <= target.double("targetMax"), "cible $id : $value hors de [${target.double("targetMin")} ; ${target.double("targetMax")}]")
            assertEquals(target.string("verdict"), verdict(value, target.double("targetMin"), target.double("targetMax")), "verdict de la cible $id")
            assertGolden(target.double("computedMinutes"), value * MINUTES_PER_HOUR, "cible $id en minutes")
        }
    }

    @Test
    @DisplayName("Le verdict global du tableur : toutes les cibles dans la fourchette")
    fun overallVerdict() {
        val inTarget = targets.count { verdict(replayedTarget(it), it.double("targetMin"), it.double("targetMax")) == "Dans la cible" }
        assertEquals(synthese.string("overallVerdict"), "$inTarget étapes sur ${targets.size} dans la cible")
        assertEquals(targets.size, inTarget)
    }

    @Test
    @DisplayName("Ce que la cible montre au joueur : le revenu après l'achat, ou le seuil de prestige, ou rien")
    fun displayedAmounts() {
        for (target in targets) {
            val id = target.string("id")
            val shown = target.string("displayedAmount")
            when (val meaning = target.string("displayedAmountMeaning")) {
                "incomeAfterPerHour" -> {
                    val row = replay.rows[target.obj("link").int("simulationPurchase") - 1]
                    assertEquals(sheetToCharter(shown), Notation.format(row.incomeAfter), "revenu affiché de la cible $id")
                }
                "prestigeThreshold" -> {
                    val course = replay.courses[target.obj("link").int("course") - 1]
                    assertEquals(sheetToCharter(shown), Notation.format(course.threshold), "seuil affiché de la cible $id")
                }
                "none" -> assertEquals("—", shown, "cible $id")
                else -> error("Sens inconnu : $meaning")
            }
        }
    }

    @Test
    @DisplayName("Ce que voit le joueur à chaque course : seuil, revenu, multiplicateur, Lingots, heure de fin, jours réels")
    fun whatThePlayerSees() {
        val seen = synthese.objects("playerSees")
        assertEquals(replay.courses.size, seen.size)
        for ((expected, course) in seen.zip(replay.courses)) {
            val n = expected.int("course")
            assertEquals(course.course, n)
            assertEquals(sheetToCharter(expected.string("thresholdDisplay")), Notation.format(course.threshold), "seuil vu à la course $n")
            assertEquals(sheetToCharter(expected.string("incomeAtEndDisplay")), Notation.format(course.incomeAtEnd), "revenu vu à la course $n")
            assertGolden(expected.double("globalMultiplier"), course.courseMultiplier, "multiplicateur vu à la course $n")
            assertEquals(expected.long("lingotsEarned"), course.lingotsAtPrestige, "Lingots vus à la course $n")
            assertGolden(expected.double("endHour"), course.endHour, "heure de fin vue à la course $n")
            assertGolden(expected.double("realDays"), course.endHour / settings.playHoursPerDay, "jours réels à la course $n")
        }
    }

    private companion object {
        /** Une heure : constante de la nature. */
        const val MINUTES_PER_HOUR = 60.0
    }
}
