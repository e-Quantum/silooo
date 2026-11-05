package com.silooo.core.simulation

import com.silooo.core.economy.closeTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Les propriétés du simulateur, indépendantes du tableur : une partie rejouée est cohérente avec elle-même et avec
 * le moteur (le temps avance, les Donar gagnés restent sous le seuil jusqu'au prestige, chaque usine est révélée puis
 * rachetée avant d'être montée, les Lingots se reportent d'une course à l'autre). SimulatorSheetTest compare la même
 * partie au tableur ; ThirteenTargetsTest en tire les treize temps.
 */
class GameSimulatorTest {
    private val definitions = SimulationFixture.definitions
    private val engine = SimulationFixture.engine
    private val profile = SimulationFixture.profile
    private val run = SimulationFixture.run

    @Test
    @DisplayName("Une même entrée donne toujours la même partie")
    fun deterministic() {
        val again = GameSimulator(definitions, profile, engine).run(run.courses.size)
        assertEquals(run.courses, again.courses)
        assertEquals(run.zones, again.zones)
    }

    @Test
    @DisplayName("Jouer moins de courses donne le début de la même partie")
    fun prefixOfALongerRun() {
        val short = GameSimulator(definitions, profile, engine).run(3)
        assertEquals(run.courses.take(3), short.courses)
    }

    @Test
    @DisplayName("Une partie compte au moins une course")
    fun atLeastOneCourse() {
        assertThrows<IllegalArgumentException> { GameSimulator(definitions, profile, engine).run(0) }
    }

    @Test
    @DisplayName("Le temps avance : les attentes sont positives, les heures croissent d'un achat à l'autre et d'une course à l'autre")
    fun timeMovesForward() {
        for (purchase in run.purchases) {
            assertTrue(purchase.waitHours > 0.0, "attente nulle à l'achat ${purchase.rank}")
            assertTrue(purchase.incomeAfter > purchase.incomeBefore, "l'achat ${purchase.rank} ne rapporte rien")
            assertEquals(purchase.course, run.course(purchase.course).course)
        }
        for ((before, after) in run.purchases.zipWithNext()) {
            assertEquals(before.rank + 1, after.rank, "les rangs se suivent")
            // Tard dans une course, une attente de 1e-14 h passe sous la précision d'un double autour de 150 h : l'heure cumulée peut stagner, jamais reculer.
            assertTrue(after.cumulativeHour >= before.cumulativeHour, "l'heure recule entre les achats ${before.rank} et ${after.rank}")
            if (after.course == before.course) assertTrue(after.courseHour > before.courseHour)
        }
        for ((before, after) in run.courses.zipWithNext()) {
            assertEquals(before.endHour, after.startHour, "la course ${after.course} part où la ${before.course} finit")
        }
        for (course in run.courses) {
            assertEquals(course.startHour + course.duration, course.endHour)
            assertTrue(course.purchases.all { it.courseHour <= course.duration }, "un achat de la course ${course.course} après son prestige")
        }
    }

    @Test
    @DisplayName("Les Donar gagnés restent sous le seuil jusqu'au prestige, et le prestige a lieu au seuil exact")
    fun prestigeAtTheThreshold() {
        for (course in run.courses) {
            for (purchase in course.purchases) {
                assertFalse(engine.canPrestige(course.course, purchase.donarEarned), "course ${course.course} : le prestige était possible avant l'achat ${purchase.rank}")
            }
            val last = course.purchases.last()
            val missing = engine.onlineEarnings(course.incomeAtEnd, course.duration - last.courseHour)
            assertTrue(
                closeTo(course.threshold, last.donarEarned + missing),
                "course ${course.course} : le dernier achat gagne ${last.donarEarned}, il manque $missing pour le seuil ${course.threshold}",
            )
        }
    }

    @Test
    @DisplayName("Époque, étoiles, seuil et Lingots du prestige sont ceux du moteur")
    fun courseFiguresComeFromTheEngine() {
        for (course in run.courses) {
            assertEquals(engine.eraAt(course.course), course.era)
            assertEquals(engine.starsAt(course.course), course.stars)
            assertEquals(engine.prestigeThreshold(course.course), course.threshold)
            assertEquals(engine.prestigeLingots(course.course, course.threshold), course.lingotsAtPrestige)
        }
    }

    @Test
    @DisplayName("Les Lingots se reportent : bienvenue, une par heure de jeu, prestiges passés ; le joueur en garde sa part pour le multiplicateur")
    fun lingotsCarryOver() {
        val rules = definitions.economy.lingotsOutsidePrestige
        var fromPrestiges = 0.0
        for (course in run.courses) {
            val expected = rules.welcomeMission + rules.averagePerPlayHour * course.startHour + fromPrestiges
            assertEquals(expected, course.lingotsBefore, "Lingots cumulés au départ de la course ${course.course}")
            assertEquals(profile.keptLingotsShare * expected, course.lingotsKept)
            assertEquals(engine.globalMultiplier(course.lingotsKept, course.stars), course.multiplier)
            fromPrestiges += course.lingotsAtPrestige.toDouble()
        }
        assertTrue(run.courses.zipWithNext().all { (a, b) -> b.multiplier > a.multiplier }, "le multiplicateur ne baisse pas d'une course à l'autre")
    }

    @Test
    @DisplayName("Une usine en ruine se rachète d'abord, au prix du niveau de la remise en état, puis se monte de palier en palier")
    fun buyoutThenLadder() {
        val starting = engine.startingLevels()
        val rungs = profile.ladder
        for (course in run.courses) {
            val owned = HashMap(starting)
            for (purchase in course.purchases) {
                val level = owned[purchase.factoryId]
                if (level == null) {
                    assertTrue(purchase.buyout, "course ${course.course} : ${purchase.factoryId} monte sans rachat")
                    assertEquals(0, purchase.fromLevel)
                    assertEquals(engine.restoredLevel(0), purchase.toLevel)
                    assertEquals(engine.levelsCost(purchase.factoryId, 0, purchase.toLevel), purchase.price)
                } else {
                    assertFalse(purchase.buyout, "course ${course.course} : ${purchase.factoryId} est déjà rachetée")
                    assertEquals(level, purchase.fromLevel, "le niveau de départ d'un achat est le niveau où l'usine est restée")
                    assertEquals(rungs.first { it > level }, purchase.toLevel, "un achat vise le palier suivant de l'échelle")
                    assertEquals(engine.levelsCost(purchase.factoryId, level, purchase.toLevel - level), purchase.price)
                }
                owned[purchase.factoryId] = purchase.toLevel
            }
            assertEquals(owned, course.levelsAtEnd, "niveaux en fin de course ${course.course}")
        }
    }

    @Test
    @DisplayName("Chaque course repart des usines de départ : les autres sont à nouveau en ruine")
    fun everyCourseRestartsFromTheStartingFactories() {
        val starting = engine.startingLevels()
        for (course in run.courses) {
            val firstBuyouts = course.purchases.filter { it.buyout }.map { it.factoryId }
            assertTrue(firstBuyouts.none { it in starting }, "course ${course.course} : une usine de départ est rachetée")
            assertEquals(firstBuyouts.size, firstBuyouts.toSet().size, "course ${course.course} : une usine est rachetée deux fois")
            assertEquals(starting.keys + firstBuyouts, course.levelsAtEnd.keys)
        }
    }

    @Test
    @DisplayName("Une usine n'est achetée qu'une fois révélée, et chaque usine n'est révélée qu'une fois dans la partie")
    fun discoveryComesBeforePurchase() {
        val revealed = run.courses.flatMap { it.discoveries }
        assertEquals(revealed.size, revealed.map { it.factoryId }.toSet().size, "une usine est révélée deux fois")
        val firstRevealedIn = revealed.associate { it.factoryId to it }
        for (course in run.courses) {
            val owned = HashMap(engine.startingLevels())
            for (purchase in course.purchases) {
                val discovery = firstRevealedIn[purchase.factoryId]
                if (discovery == null || discovery.course == course.course) {
                    // Révélée dans cette course (ou de départ) : la règle de découverte du moteur le dit avec les niveaux d'avant l'achat.
                    assertTrue(
                        engine.isDiscovered(purchase.factoryId, engine.companyLevel(owned), course.era, owned.keys),
                        "course ${course.course} : ${purchase.factoryId} achetée avant d'être révélée",
                    )
                }
                if (discovery != null) {
                    assertTrue(discovery.cumulativeHour <= purchase.cumulativeHour, "${purchase.factoryId} révélée après son achat")
                    assertTrue(discovery.course <= course.course)
                }
                owned[purchase.factoryId] = purchase.toLevel
            }
        }
    }

    @Test
    @DisplayName("Les zones de ville se paient dans l'ordre, avec les prix de definitions/, jamais avant la mission de bienvenue")
    fun cityZonesInOrder() {
        val prices = definitions.economy.city.zonePricesLingots
        assertTrue(run.zones.isNotEmpty())
        for ((index, zone) in run.zones.withIndex()) {
            assertEquals(index + 1, zone.zone)
            assertEquals(prices[index], zone.priceLingots)
            assertTrue(zone.cumulativeHour >= profile.welcomeMissionHour, "la zone ${zone.zone} avant la mission de bienvenue")
            assertTrue(zone.course in 1..run.courses.size)
            val course = run.course(zone.course)
            assertTrue(zone.cumulativeHour <= maxOf(course.endHour, profile.welcomeMissionHour), "la zone ${zone.zone} après la fin de sa course")
        }
        for ((before, after) in run.zones.zipWithNext()) {
            assertTrue(after.cumulativeHour >= before.cumulativeHour, "la zone ${after.zone} s'ouvre avant la zone ${before.zone}")
            assertTrue(after.course >= before.course)
        }
    }

    // --- Les hypothèses du joueur type comptent vraiment ---

    @Test
    @DisplayName("Un joueur qui ne garde aucun Lingot a un multiplicateur plus bas, donc des courses plus longues")
    fun keepingLingotsMatters() {
        val spender = GameSimulator(definitions, profile.copy(keptLingotsShare = 0.0), engine).run(2)
        assertEquals(engine.globalMultiplier(0.0, 0), spender.course(2).multiplier)
        assertTrue(spender.course(2).multiplier < run.course(2).multiplier)
        assertTrue(spender.course(2).duration > run.course(2).duration)
        // La première course ne change que par les Lingots de bienvenue.
        assertNotEquals(run.course(1).multiplier, spender.course(1).multiplier)
    }

    @Test
    @DisplayName("Une échelle de paliers trop courte épuise les achats : le simulateur le dit au lieu d'inventer une fin de course")
    fun exhaustedLadderFailsLoudly() {
        val tiny = profile.copy(ladder = listOf(engine.restoredLevel(0)))
        val error = assertThrows<IllegalStateException> { GameSimulator(definitions, tiny, engine).run(1) }
        assertTrue(error.message!!.contains("échelle"), error.message)
        assertTrue(error.message!!.contains("Course 1"), error.message)
    }

    @Test
    @DisplayName("Le joueur type du tableur est lu dans golden/ : heures de jeu, part gardée, mission de bienvenue, échelle de paliers")
    fun profileComesFromTheSheet() {
        assertTrue(profile.playHoursPerDay > 0.0)
        assertTrue(profile.keptLingotsShare in 0.0..1.0)
        assertTrue(profile.ladder.size > 1)
        assertEquals(profile.ladder.sorted(), profile.ladder)
        // Chaque palier de ×2 du jeu est une marche de l'échelle : le joueur type s'arrête exactement là où la production double.
        val milestones = definitions.economy.levels.doublingMilestones.fixed
        assertTrue(profile.ladder.containsAll(milestones), "l'échelle ${profile.ladder} ne contient pas les paliers ×2 $milestones")
    }

    @Test
    @DisplayName("Un joueur type incohérent est refusé à la construction")
    fun invalidProfilesAreRefused() {
        assertThrows<IllegalArgumentException> { profile.copy(playHoursPerDay = 0.0) }
        assertThrows<IllegalArgumentException> { profile.copy(keptLingotsShare = 1.5) }
        assertThrows<IllegalArgumentException> { profile.copy(welcomeMissionHour = -1.0) }
        assertThrows<IllegalArgumentException> { profile.copy(ladder = emptyList()) }
        assertThrows<IllegalArgumentException> { profile.copy(ladder = listOf(5, 5)) }
        assertThrows<IllegalArgumentException> { profile.copy(ladder = listOf(10, 5)) }
        assertThrows<IllegalArgumentException> { profile.copy(ladder = listOf(0, 5)) }
    }
}
