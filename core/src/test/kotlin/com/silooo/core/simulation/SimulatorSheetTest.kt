package com.silooo.core.simulation

import com.silooo.core.GoldenFiles
import com.silooo.core.double
import com.silooo.core.economy.assertGolden
import com.silooo.core.int
import com.silooo.core.long
import com.silooo.core.objects
import com.silooo.core.string
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le simulateur contre le tableur, achat par achat. Le simulateur CHOISIT ses achats (stratégie du joueur type) ;
 * ce test vérifie qu'il choisit les mêmes que la feuille Simulation, dans le même ordre, et qu'il retrouve les mêmes
 * prix, revenus, heures, courses et zones de ville, à la tolérance des tests dorés ([com.silooo.core.economy.GoldenTolerance]).
 *
 * C'est ce qui donne au verdict de [ThirteenTargetsTest] son sens : un simulateur qui ne rejouerait pas le joueur du
 * tableur mesurerait autre chose que les cibles du tableur. À l'inverse, si ce test échoue sans que les treize cibles
 * sortent de leur fourchette, c'est le simulateur (ou une formule du moteur) qui a changé, pas l'équilibrage.
 *
 * Le plan du tableur compte une ligne de plus par course que les achats faits : la première ligne que le joueur n'a
 * plus le temps de faire avant son prestige (colonne Q, « Avant le prestige ? » à 0). Elle n'est pas comparée ici.
 */
class SimulatorSheetTest {
    private val run = SimulationFixture.run
    private val engine = SimulationFixture.engine
    private val definitions = SimulationFixture.definitions
    private val idByName = definitions.factories.factories.associate { it.name to it.id }
    private val goldenCourses = GoldenFiles.read("courses").objects("courses")

    /** Les achats que le tableur fait avant chaque prestige (colonne Q à 1), dans l'ordre de la feuille. */
    private val made: List<JsonObject> = GoldenFiles.read("simulation").objects("purchases").filter { it.int("beforePrestige") == 1 }

    @Test
    @DisplayName("Le simulateur fait les mêmes achats que la feuille Simulation, dans le même ordre : usine, niveau avant, niveau visé")
    fun sameDecisions() {
        assertEquals(made.size, run.purchases.size, "nombre d'achats faits avant les prestiges")
        for ((expected, purchase) in made.zip(run.purchases)) {
            val where = "achat ${expected.int("n")} de la feuille (rang ${purchase.rank} du simulateur)"
            assertEquals(expected.int("course"), purchase.course, "course de l'$where")
            assertEquals(expected.string("factory"), definitions.factories.factories.first { it.id == purchase.factoryId }.name, "usine de l'$where")
            assertEquals(expected.int("levelBefore"), purchase.fromLevel, "niveau avant l'$where")
            assertEquals(expected.int("targetLevel"), purchase.toLevel, "niveau visé de l'$where")
        }
    }

    @Test
    @DisplayName("Chaque course fait autant d'achats que la feuille en compte avant son prestige")
    fun purchasesPerCourse() {
        for (expected in goldenCourses) {
            val n = expected.int("course")
            assertEquals(expected.int("purchasesBeforePrestige"), run.course(n).purchases.size, "achats faits dans la course $n")
        }
    }

    @Test
    @DisplayName("Prix, revenu avant et après, attente, Donar gagnés et heures de chaque achat : ceux de la feuille")
    fun figures() {
        for ((expected, purchase) in made.zip(run.purchases)) {
            val where = "achat ${expected.int("n")} (${expected.string("factory")} ${purchase.fromLevel} vers ${purchase.toLevel})"
            assertGolden(expected.double("price"), purchase.price, "prix de l'$where")
            assertGolden(expected.double("incomeBefore"), purchase.incomeBefore, "revenu avant l'$where")
            assertGolden(expected.double("incomeAfter"), purchase.incomeAfter, "revenu après l'$where")
            assertGolden(expected.double("waitHours"), purchase.waitHours, "attente avant l'$where")
            assertGolden(expected.double("donarEarnedInCourse"), purchase.donarEarned, "Donar gagnés à l'$where")
            assertGolden(expected.double("courseHour"), purchase.courseHour, "heure dans la course de l'$where")
            assertGolden(expected.double("cumulativeHour"), purchase.cumulativeHour, "heure cumulée de l'$where")
        }
    }

    @Test
    @DisplayName("Chaque course : seuil, époque, étoiles, Lingots, multiplicateur, durée, heures de départ et de fin")
    fun courses() {
        assertEquals(goldenCourses.size, run.courses.size)
        for (expected in goldenCourses) {
            val n = expected.int("course")
            val course = run.course(n)
            assertEquals(expected.double("prestigeThresholdDonar"), course.threshold, "seuil de la course $n")
            assertEquals(expected.int("mostAdvancedEra"), course.era, "époque de la course $n")
            assertEquals(expected.int("starsAlreadyEarned"), course.stars, "étoiles de la course $n")
            if (expected.double("startHour") == 0.0) {
                assertEquals(0.0, course.startHour, "la course 1 part de l'heure 0")
            } else {
                assertGolden(expected.double("startHour"), course.startHour, "heure de départ de la course $n")
            }
            assertGolden(expected.double("lingotsEarnedBeforeStart"), course.lingotsBefore, "Lingots gagnés avant la course $n")
            assertGolden(expected.double("lingotsKept"), course.lingotsKept, "Lingots gardés à la course $n")
            assertGolden(expected.double("globalMultiplier"), course.multiplier, "multiplicateur de la course $n")
            assertGolden(expected.double("durationHours"), course.duration, "durée de la course $n")
            assertGolden(expected.double("endHour"), course.endHour, "heure de fin de la course $n")
            assertEquals(Math.round(expected.double("lingotsEarnedAtPrestige")), course.lingotsAtPrestige, "Lingots du prestige de la course $n")
        }
    }

    @Test
    @DisplayName("Les zones de ville payées dans les courses jouées : prix, course et heure de la feuille Ville")
    fun cityZones() {
        val goldenZones = GoldenFiles.read("ville").objects("zones")
        val playedCourses = run.courses.size
        val reachable = goldenZones.filter { it.int("affordableInCourse") <= playedCourses }
        assertEquals(reachable.size, run.zones.size, "zones payées pendant les $playedCourses courses jouées")
        for ((expected, zone) in reachable.zip(run.zones)) {
            val n = expected.int("zone")
            assertEquals(n, zone.zone)
            assertEquals(expected.long("priceLingots"), zone.priceLingots, "prix de la zone $n")
            assertEquals(expected.int("affordableInCourse"), zone.course, "course où la zone $n devient abordable")
            assertGolden(expected.double("affordableAtHour"), zone.cumulativeHour, "heure où la zone $n devient abordable")
        }
    }

    @Test
    @DisplayName("Le revenu en fin de course est celui du moteur pour les niveaux atteints (aucun écart caché dans le simulateur)")
    fun incomeAtTheEnd() {
        val scale = { course: CourseResult -> course.multiplier * engine.dailyOfflineMultiplier(SimulationFixture.profile.playHoursPerDay) }
        for (course in run.courses) {
            assertGolden(engine.ratePerHour(course.levelsAtEnd, scale(course)), course.incomeAtEnd, "revenu en fin de la course ${course.course}")
        }
    }
}
