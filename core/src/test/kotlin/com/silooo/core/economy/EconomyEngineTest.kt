package com.silooo.core.economy

import com.silooo.core.TestDefinitions
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Les propriétés du moteur, sans une seule valeur du jeu écrite ici : tout chiffre vient de
 * definitions/ (`TestDefinitions.loaded`). Si Kr change un prix, un palier ou un seuil dans le JSON,
 * ces tests suivent. Les valeurs du tableur sont rejouées à part, dans les tests Golden*.
 */
class EconomyEngineTest {
    private val definitions = TestDefinitions.loaded
    private val engine = EconomyEngine(definitions)
    private val factories = definitions.factories.factories
    private val levelRules = definitions.economy.levels
    private val prestigeRules = definitions.economy.prestige
    private val offlineRules = definitions.economy.offline

    private val step = levelRules.doublingMilestones.thenEvery
    private val fixed = levelRules.doublingMilestones.fixed.sorted()

    /** Des niveaux qui entourent chaque palier, tirés des paliers de definitions/. */
    private val sampleLevels: List<Int> = (listOf(0, 1) + fixed.flatMap { listOf(it - 1, it, it + 1) } +
        listOf(step - 1, step, step + 1, 2 * step - 1, 2 * step, 3 * step + 1)).filter { it >= 0 }.distinct().sorted()

    private fun assertRelative(expected: Double, actual: Double, tolerance: Double, message: String) {
        val scale = maxOf(abs(expected), abs(actual))
        assertTrue(abs(expected - actual) <= tolerance * scale, "$message : attendu $expected, obtenu $actual")
    }

    // ---------------------------------------------------------------- prix

    @Test
    @DisplayName("Le prix du niveau 1 est le prixNiveau1 de l'usine")
    fun firstLevelCostsLevel1Price() {
        for (f in factories) assertRelative(f.level1Price, engine.levelsCost(f.id, 0, 1), 1e-15, f.id)
    }

    @Test
    @DisplayName("Chaque niveau coûte croissancePrix fois le précédent")
    fun priceGrowsGeometrically() {
        for (f in factories) for (level in sampleLevels) {
            assertRelative(levelRules.priceGrowth, engine.nextLevelCost(f.id, level + 1) / engine.nextLevelCost(f.id, level), 1e-12, "${f.id} niveau $level")
        }
    }

    @Test
    @DisplayName("La somme géométrique égale la somme des prix niveau par niveau")
    fun geometricSumMatchesLevelByLevel() {
        for (f in factories) for (from in sampleLevels) for (count in 1..BuyAmount.TEN.fixedLevels!!) {
            var oneByOne = 0.0
            for (i in 0 until count) oneByOne += engine.nextLevelCost(f.id, from + i)
            assertRelative(oneByOne, engine.levelsCost(f.id, from, count), 1e-12, "${f.id} de $from sur $count niveaux")
        }
    }

    @Test
    @DisplayName("Acheter a puis b niveaux coûte comme acheter a + b d'un coup")
    fun purchasesAreAdditive() {
        for (f in factories) for (from in sampleLevels) for (a in 1..4) for (b in 1..4) {
            assertRelative(
                engine.levelsCost(f.id, from, a) + engine.levelsCost(f.id, from + a, b),
                engine.levelsCost(f.id, from, a + b),
                1e-12,
                "${f.id} de $from : $a puis $b",
            )
        }
    }

    @Test
    @DisplayName("Zéro niveau ne coûte rien ; un niveau négatif ou une usine inconnue sont refusés")
    fun priceInputsAreChecked() {
        assertEquals(0.0, engine.levelsCost(factories.first().id, 0, 0))
        assertThrows<IllegalArgumentException> { engine.levelsCost(factories.first().id, -1, 1) }
        assertThrows<IllegalArgumentException> { engine.levelsCost(factories.first().id, 0, -1) }
        assertThrows<IllegalArgumentException> { engine.levelsCost("usine-qui-n-existe-pas", 0, 1) }
    }

    // ---------------------------------------------------------------- paliers et production

    @Test
    @DisplayName("Les doublements comptent les paliers fixes atteints et les multiples du pas régulier")
    fun doublingsCountMilestones() {
        for (level in 0..(step * 4 + 1)) {
            val expected = fixed.count { level >= it } + (1..level).count { it % step == 0 }
            assertEquals(expected, engine.doublings(level), "niveau $level")
        }
    }

    @Test
    @DisplayName("Le prochain palier est le premier niveau au-dessus où la production double")
    fun nextMilestoneIsTheNextDoubling() {
        for (level in 0..(step * 4)) {
            val next = engine.nextMilestone(level)
            assertTrue(next > level, "niveau $level : le palier $next n'est pas au-dessus")
            assertEquals(engine.doublings(level) + 1, engine.doublings(next), "le palier $next fait doubler une fois de plus")
            for (between in level..<next) assertEquals(engine.doublings(level), engine.doublings(between), "pas de doublement entre $level et $next (niveau $between)")
        }
    }

    @Test
    @DisplayName("La production double au palier : le niveau du palier produit plus de deux fois le précédent")
    fun productionJumpsAtMilestones() {
        for (f in factories) {
            assertEquals(0.0, engine.factoryRate(f.id, 0), f.id)
            var level = 0
            repeat(fixed.size + 3) {
                val milestone = engine.nextMilestone(level)
                val before = engine.factoryRate(f.id, milestone - 1)
                val at = engine.factoryRate(f.id, milestone)
                // Un niveau de plus rapporte milestone / (milestone - 1) fois plus, et le palier double : ×2 en plus.
                assertRelative(2.0 * milestone / (milestone - 1), at / before, 1e-12, "${f.id} au palier $milestone")
                level = milestone
            }
        }
    }

    @Test
    @DisplayName("Entre deux paliers, la production est proportionnelle au niveau")
    fun productionIsLinearBetweenMilestones() {
        for (f in factories) for (level in 1..(step * 3)) {
            if (engine.doublings(level + 1) != engine.doublings(level)) continue
            assertRelative((level + 1.0) / level, engine.factoryRate(f.id, level + 1) / engine.factoryRate(f.id, level), 1e-12, "${f.id} niveau $level")
        }
    }

    @Test
    @DisplayName("La production totale est la somme des usines, dans un ordre qui ne change pas le résultat")
    fun baseRateSumsFactories() {
        val levels = factories.withIndex().associate { (i, f) -> f.id to (i + 1) * 7 }
        val expected = factories.sumOf { engine.factoryRate(it.id, levels.getValue(it.id)) }
        assertRelative(expected, engine.baseRate(levels), 1e-12, "somme des usines")

        val shuffled = LinkedHashMap<String, Int>()
        for (key in levels.keys.reversed()) shuffled[key] = levels.getValue(key)
        assertEquals(engine.baseRate(levels), engine.baseRate(shuffled), "l'ordre des clés ne compte pas, même au bit près")

        assertEquals(0.0, engine.baseRate(emptyMap()))
        assertEquals(0.0, engine.baseRate(mapOf(factories.first().id to 0)))
        assertThrows<IllegalArgumentException> { engine.baseRate(mapOf("usine-qui-n-existe-pas" to 1)) }
    }

    @Test
    @DisplayName("Le revenu est la production fois le multiplicateur")
    fun rateScalesWithMultiplier() {
        val levels = mapOf(factories.first().id to sampleLevels.last())
        val base = engine.baseRate(levels)
        assertEquals(base * 3.5, engine.ratePerHour(levels, 3.5))
        assertEquals(0.0, engine.ratePerHour(levels, 0.0))
    }

    @Test
    @DisplayName("Chaque course démarre avec l'usine et le niveau de prestige.departDeCourse")
    fun startingLevelsComeFromDefinitions() {
        val start = prestigeRules.courseStart
        assertEquals(mapOf(start.factoryId to start.level), engine.startingLevels())
        assertTrue(start.factoryId in engine.factoryIds())
    }

    // ---------------------------------------------------------------- achats

    private val someFactory = factories.first().id

    @Test
    @DisplayName("Un niveau et dix niveaux : le lot fixe, au prix de la somme")
    fun fixedLots() {
        val rich = Double.MAX_VALUE
        for (level in sampleLevels) {
            val one = engine.quote(someFactory, level, BuyAmount.ONE, rich)
            assertEquals(1, one.levels)
            assertEquals(level + 1, one.newLevel)
            assertEquals(engine.nextLevelCost(someFactory, level), one.cost)
            val ten = engine.quote(someFactory, level, BuyAmount.TEN, rich)
            assertEquals(BuyAmount.TEN.fixedLevels, ten.levels)
            assertEquals(engine.levelsCost(someFactory, level, ten.levels), ten.cost)
            assertTrue(one.affordable && ten.affordable)
        }
    }

    @Test
    @DisplayName("Un lot fixe est refusé quand le solde est un centime trop court, accepté quand il suffit")
    fun fixedLotAffordability() {
        val cost = engine.nextLevelCost(someFactory, 0)
        assertTrue(engine.quote(someFactory, 0, BuyAmount.ONE, cost).affordable)
        assertFalse(engine.quote(someFactory, 0, BuyAmount.ONE, Math.nextDown(cost)).affordable)
        assertFalse(engine.quote(someFactory, 0, BuyAmount.ONE, 0.0).affordable)
    }

    @Test
    @DisplayName("Palier : on monte jusqu'au prochain doublement, ni plus ni moins")
    fun milestoneLot() {
        for (level in 0..(step * 3)) {
            val quote = engine.quote(someFactory, level, BuyAmount.MILESTONE, 0.0)
            assertEquals(engine.nextMilestone(level), quote.newLevel)
            assertEquals(quote.newLevel - level, quote.levels)
            assertEquals(engine.levelsCost(someFactory, level, quote.levels), quote.cost)
            assertEquals(engine.doublings(level) + 1, engine.doublings(quote.newLevel))
            assertFalse(quote.affordable, "un solde nul ne paie rien")
        }
    }

    @Test
    @DisplayName("Max : le plus grand nombre de niveaux que le solde paie, au centime près")
    fun maxLot() {
        for (f in factories) for (level in sampleLevels) {
            for (k in 1..BuyAmount.TEN.fixedLevels!! * 4) {
                val exact = engine.levelsCost(f.id, level, k)
                val atCost = engine.quote(f.id, level, BuyAmount.MAX, exact)
                assertEquals(k, atCost.levels, "${f.id} niveau $level : le solde du prix exact de $k niveaux")
                assertEquals(exact, atCost.cost)
                assertTrue(atCost.affordable)

                val justUnder = engine.quote(f.id, level, BuyAmount.MAX, Math.nextDown(exact))
                assertEquals(k - 1, justUnder.levels, "${f.id} niveau $level : un cheveu sous le prix de $k niveaux")

                val between = engine.quote(f.id, level, BuyAmount.MAX, exact + (engine.nextLevelCost(f.id, level + k) / 2.0))
                assertEquals(k, between.levels, "${f.id} niveau $level : entre $k et ${k + 1} niveaux")
            }
        }
    }

    @Test
    @DisplayName("Max sans le sou, ou avec un solde énorme, ne se trompe pas et ne boucle pas")
    fun maxLotExtremes() {
        val empty = engine.quote(someFactory, 0, BuyAmount.MAX, 0.0)
        assertEquals(0, empty.levels)
        assertEquals(0.0, empty.cost)
        assertEquals(0, empty.newLevel)
        assertFalse(empty.affordable)

        val huge = engine.quote(someFactory, 0, BuyAmount.MAX, Double.MAX_VALUE)
        assertTrue(huge.levels > 0 && huge.affordable)
        assertTrue(huge.cost <= Double.MAX_VALUE)
        assertTrue(engine.levelsCost(someFactory, 0, huge.levels + 1) > Double.MAX_VALUE, "un niveau de plus dépasserait le solde")

        assertThrows<IllegalArgumentException> { engine.quote(someFactory, 0, BuyAmount.MAX, -1.0) }
        assertThrows<IllegalArgumentException> { engine.quote(someFactory, -1, BuyAmount.ONE, 0.0) }
    }

    @Test
    @DisplayName("Les valeurs du contrat se lisent dans les deux sens")
    fun wireValues() {
        for (amount in BuyAmount.entries) assertEquals(amount, BuyAmount.fromWire(amount.wire))
        assertEquals(null, BuyAmount.fromWire("beaucoup"))
    }

    // ---------------------------------------------------------------- multiplicateurs

    @Test
    @DisplayName("Chaque Lingot détenu ajoute bonusParLingotDetenu au multiplicateur")
    fun lingotMultiplierIsLinear() {
        assertEquals(1.0, engine.lingotMultiplier(0.0))
        for (held in listOf(1.0, 10.0, 123.5, 1000.0)) {
            assertRelative(1.0 + prestigeRules.bonusPerHeldLingot * held, engine.lingotMultiplier(held), 1e-15, "$held Lingots")
        }
        assertRelative(prestigeRules.bonusPerHeldLingot, engine.lingotMultiplier(51.0) - engine.lingotMultiplier(50.0), 1e-12, "un Lingot de plus")
    }

    @Test
    @DisplayName("Le bonus se calcule sur la moyenne des membres : une équipe n'est pas la somme de ses membres")
    fun teamUsesTheAverage() {
        assertEquals(0.0, engine.averageHeldLingots(emptyList()))
        assertEquals(40.0, engine.averageHeldLingots(listOf(40L)))
        val team = listOf(100L, 0L, 20L, 40L)
        val average = engine.averageHeldLingots(team)
        assertEquals(team.sum().toDouble() / team.size, average)
        val soloOfTheRichest = engine.lingotMultiplier(team.max().toDouble())
        val shared = engine.lingotMultiplier(average)
        assertTrue(shared < soloOfTheRichest, "l'équipe produit moins vite que son membre le plus riche seul")
        assertTrue(shared < engine.lingotMultiplier(team.sum().toDouble()), "et bien moins que si l'on additionnait les Lingots")
    }

    @Test
    @DisplayName("Chaque étoile multiplie la production par productionParEtoile")
    fun starMultiplierIsGeometric() {
        assertEquals(1.0, engine.starMultiplier(0))
        for (stars in 0..5) {
            assertRelative(prestigeRules.productionPerStar, engine.starMultiplier(stars + 1) / engine.starMultiplier(stars), 1e-12, "étoile ${stars + 1}")
        }
        assertThrows<IllegalArgumentException> { engine.starMultiplier(-1) }
    }

    @Test
    @DisplayName("Le multiplicateur global est celui des Lingots fois celui des étoiles")
    fun globalMultiplierIsTheProduct() {
        for (stars in 0..3) for (held in listOf(0.0, 12.5, 400.0)) {
            assertEquals(engine.lingotMultiplier(held) * engine.starMultiplier(stars), engine.globalMultiplier(held, stars))
        }
    }

    // ---------------------------------------------------------------- hors ligne

    @Test
    @DisplayName("Le temps hors ligne compté s'arrête au plafond")
    fun offlineCap() {
        val cap = offlineRules.capHours
        assertEquals(0.0, engine.offlineHours(0.0))
        assertEquals(cap / 2, engine.offlineHours(cap / 2))
        assertEquals(cap, engine.offlineHours(cap))
        assertEquals(cap, engine.offlineHours(cap + 1.0))
        assertEquals(cap, engine.offlineHours(cap * 1000))
        assertThrows<IllegalArgumentException> { engine.offlineHours(-0.5) }
    }

    @Test
    @DisplayName("Le plafond passé par l'appelant (celui de l'entreprise) remplace celui des définitions, la formule reste la même")
    fun offlineCapOfTheCaller() {
        val cap = offlineRules.capHours
        for (companyCap in listOf(cap / 2, cap, cap * 3)) {
            assertEquals(companyCap, engine.offlineHours(companyCap * 10, companyCap))
            assertEquals(companyCap / 2, engine.offlineHours(companyCap / 2, companyCap))
            val rate = 4321.5
            assertEquals(rate * companyCap * offlineRules.efficiency, engine.offlineEarnings(rate, companyCap * 10, companyCap))
        }
        assertEquals(0.0, engine.offlineHours(3.0, 0.0))
        assertThrows<IllegalArgumentException> { engine.offlineHours(1.0, -1.0) }
    }

    @Test
    @DisplayName("Le gain hors ligne est le revenu fois les heures comptées fois l'efficacité")
    fun offlineEarnings() {
        val rate = 12_345.678
        assertEquals(rate * offlineRules.capHours * offlineRules.efficiency, engine.offlineEarnings(rate, offlineRules.capHours * 3))
        assertEquals(rate * 1.5 * offlineRules.efficiency, engine.offlineEarnings(rate, 1.5))
        assertEquals(0.0, engine.offlineEarnings(rate, 0.0))
    }

    @Test
    @DisplayName("Le gain en ligne est le revenu fois les heures : ni plafond ni efficacité")
    fun onlineEarnings() {
        val rate = 12_345.678
        assertEquals(rate * 1.5, engine.onlineEarnings(rate, 1.5))
        assertEquals(rate * offlineRules.capHours * 3, engine.onlineEarnings(rate, offlineRules.capHours * 3), "au-delà du plafond hors ligne, tout est compté")
        assertEquals(0.0, engine.onlineEarnings(rate, 0.0))
        assertEquals(0.0, engine.onlineEarnings(0.0, 10.0))
        assertThrows<IllegalArgumentException> { engine.onlineEarnings(rate, -0.5) }
    }

    @Test
    @DisplayName("Le multiplicateur hors ligne moyen vaut 1 sans heures libres et baisse quand on joue plus")
    fun dailyOfflineMultiplier() {
        assertEquals(1.0, engine.dailyOfflineMultiplier(EconomyEngine.HOURS_PER_DAY))
        var previous = Double.MAX_VALUE
        for (hours in listOf(0.5, 1.0, 1.5, 3.0, 8.0, 16.0, 20.0)) {
            val multiplier = engine.dailyOfflineMultiplier(hours)
            assertTrue(multiplier >= 1.0 && multiplier < previous, "$hours h par jour : $multiplier après $previous")
            previous = multiplier
        }
        assertThrows<IllegalArgumentException> { engine.dailyOfflineMultiplier(0.0) }
        assertThrows<IllegalArgumentException> { engine.dailyOfflineMultiplier(EconomyEngine.HOURS_PER_DAY + 1) }
    }

    // ---------------------------------------------------------------- prestige

    @Test
    @DisplayName("Les seuils listés sont ceux de prestige.seuils ; une course qui n'est pas numérotée depuis 1 est refusée")
    fun thresholds() {
        for (entry in prestigeRules.thresholds) {
            assertTrue(engine.hasListedThreshold(entry.course))
            assertEquals(entry.donar, engine.prestigeThreshold(entry.course))
        }
        assertFalse(engine.hasListedThreshold(prestigeRules.thresholds.size + 1))
        assertFalse(engine.hasListedThreshold(0))
        assertThrows<IllegalArgumentException> { engine.prestigeThreshold(0) }
        assertThrows<IllegalArgumentException> { engine.prestigeThreshold(-3) }
    }

    @Test
    @DisplayName("Au-delà des courses listées, chaque seuil vaut le précédent fois facteurSeuilSuivant")
    fun thresholdsBeyondTheListedCourses() {
        val listed = prestigeRules.thresholds
        val factor = prestigeRules.nextThresholdFactor
        val first = listed.size + 1
        assertEquals(listed.last().donar * factor, engine.prestigeThreshold(first))
        var previous = engine.prestigeThreshold(first)
        for (course in first + 1..first + 5) {
            val threshold = engine.prestigeThreshold(course)
            assertRelative(previous * factor, threshold, 1e-12, "course $course")
            assertTrue(threshold > previous, "les seuils croissent : course $course")
            previous = threshold
        }
        for (course in first..first + 5) {
            assertFalse(engine.hasListedThreshold(course))
            assertTrue(engine.canPrestige(course, engine.prestigeThreshold(course)))
            assertFalse(engine.canPrestige(course, Math.nextDown(engine.prestigeThreshold(course))))
        }
    }

    @Test
    @DisplayName("Un seuil qui sortirait des doubles reste fini : la course est sans fin, jamais une erreur")
    fun thresholdsStayFinite() {
        val farAway = prestigeRules.thresholds.size + 10_000
        assertEquals(Double.MAX_VALUE, engine.prestigeThreshold(farAway))
        assertEquals(Double.MAX_VALUE, engine.prestigeThreshold(Int.MAX_VALUE))
        assertTrue(engine.prestigeLingotsExact(farAway, 1.0).isFinite())
    }

    @Test
    @DisplayName("Le prestige est permis dès que les Donar gagnés atteignent le seuil, pas avant")
    fun canPrestigeAtTheThreshold() {
        for (entry in prestigeRules.thresholds) {
            assertTrue(engine.canPrestige(entry.course, entry.donar))
            assertTrue(engine.canPrestige(entry.course, entry.donar * 2))
            assertFalse(engine.canPrestige(entry.course, Math.nextDown(entry.donar)))
            assertFalse(engine.canPrestige(entry.course, 0.0))
        }
    }

    @Test
    @DisplayName("Au seuil de chaque course, le prestige donne les mêmes Lingots : la référence suit le seuil")
    fun lingotsAtTheThresholdAreTheSameEveryCourse() {
        val conversion = prestigeRules.lingotsConversion
        val expected = conversion.coefficient * Math.cbrt(conversion.referenceDivisor)
        for (entry in prestigeRules.thresholds) {
            assertRelative(expected, engine.prestigeLingotsExact(entry.course, entry.donar), 1e-12, "course ${entry.course}")
            assertEquals(Math.round(expected), engine.prestigeLingots(entry.course, entry.donar), "course ${entry.course}")
        }
    }

    @Test
    @DisplayName("Rendement décroissant : huit fois le seuil ne donne que le double de Lingots")
    fun cubeRootDiminishingReturns() {
        val course = prestigeRules.thresholds.first().course
        val threshold = engine.prestigeThreshold(course)
        val atThreshold = engine.prestigeLingotsExact(course, threshold)
        assertRelative(2 * atThreshold, engine.prestigeLingotsExact(course, 8 * threshold), 1e-12, "8 fois le seuil")
        assertRelative(atThreshold / 2, engine.prestigeLingotsExact(course, threshold / 8), 1e-12, "un huitième du seuil")
        var previous = -1L
        for (factor in listOf(0.01, 0.1, 0.5, 1.0, 1.5, 10.0, 1000.0)) {
            val lingots = engine.prestigeLingots(course, threshold * factor)
            assertTrue(lingots >= previous, "monotone : $factor fois le seuil donne $lingots après $previous")
            previous = lingots
        }
    }

    @Test
    @DisplayName("L'arrondi des Lingots : partie entière, avec le bruit de calcul rendu à l'entier")
    fun lingotRounding() {
        val whole = 42.0
        assertEquals(42L, engine.roundLingots(whole))
        assertEquals(42L, engine.roundLingots(Math.nextDown(whole)))
        assertEquals(42L, engine.roundLingots(Math.nextUp(whole)))
        assertEquals(42L, engine.roundLingots(whole * (1 - EconomyEngine.FLOAT_NOISE / 2)))
        assertEquals(41L, engine.roundLingots(whole * (1 - EconomyEngine.FLOAT_NOISE * 10)))
        assertEquals(41L, engine.roundLingots(whole - 0.001))
        assertEquals(42L, engine.roundLingots(whole + 0.999))
        assertEquals(0L, engine.roundLingots(0.4))
        assertEquals(0L, engine.roundLingots(0.0))
        for (exact in listOf(1.2, 17.5, 83.9999, 2400.01)) {
            assertTrue(engine.roundLingots(exact) <= exact, "$exact : jamais plus que le calcul")
            assertTrue(exact - engine.roundLingots(exact) < 1.0, "$exact : à moins d'un Lingot")
        }
    }

    @Test
    @DisplayName("Sans Donar gagné, pas de Lingots")
    fun noDonarNoLingots() {
        val course = prestigeRules.thresholds.first().course
        assertEquals(0L, engine.prestigeLingots(course, 0.0))
        assertEquals(0L, engine.prestigeLingots(course, -5.0))
    }

    // ---------------------------------------------------------------- époques et étoiles

    @Test
    @DisplayName("Une époque de plus à chaque course jusqu'à la dernière d'usines.json, puis une étoile par course")
    fun erasAndStars() {
        val lastEra = factories.maxOf { it.era }
        assertEquals(1, engine.eraAt(1))
        assertEquals(0, engine.starsAt(1))
        for (course in 1..(prestigeRules.thresholds.size + 3)) {
            assertEquals(minOf(course, lastEra), engine.eraAt(course), "époque de la course $course")
            assertEquals(maxOf(0, course - lastEra), engine.starsAt(course), "étoiles de la course $course")
            assertTrue(engine.eraAt(course) <= lastEra)
            assertTrue(engine.eraAt(course) in factories.map { it.era }, "chaque époque atteinte existe dans usines.json")
        }
        assertThrows<IllegalArgumentException> { engine.eraAt(0) }
        assertThrows<IllegalArgumentException> { engine.starsAt(0) }
    }

    @Test
    @DisplayName("Le prestige donne une époque jusqu'à la dernière d'usines.json, puis une étoile ; cela colle à eraAt et starsAt")
    fun prestigeGivesAStarOnlyAfterTheLastEra() {
        val lastEra = factories.maxOf { it.era }
        for (course in 1..(lastEra + 3)) {
            assertEquals(course >= lastEra, engine.prestigeGivesStar(course), "prestige de la course $course")
            assertEquals(engine.prestigeGivesStar(course), engine.starsAt(course + 1) > engine.starsAt(course))
            assertEquals(!engine.prestigeGivesStar(course), engine.eraAt(course + 1) > engine.eraAt(course))
        }
    }

    // ---------------------------------------------------------------- déterminisme

    @Test
    @DisplayName("Deux moteurs sur les mêmes définitions donnent les mêmes résultats au bit près")
    fun deterministic() {
        val other = EconomyEngine(definitions)
        val levels = factories.associate { it.id to sampleLevels.last() }
        assertEquals(engine.baseRate(levels), other.baseRate(levels))
        for (f in factories) {
            assertEquals(engine.levelsCost(f.id, sampleLevels.last(), 7), other.levelsCost(f.id, sampleLevels.last(), 7))
            assertEquals(engine.quote(f.id, 3, BuyAmount.MAX, 1e30), other.quote(f.id, 3, BuyAmount.MAX, 1e30))
        }
        val course = prestigeRules.thresholds.last().course
        assertEquals(engine.prestigeLingotsExact(course, 1.234e30), other.prestigeLingotsExact(course, 1.234e30))
    }
}
