package com.silooo.core.simulation

import com.silooo.core.definitions.Definitions
import com.silooo.core.economy.EconomyEngine
import com.silooo.core.economy.assertGolden
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La barrière de l'équilibrage : le simulateur fait jouer le joueur type, et chacune des treize étapes du jeu doit
 * arriver dans sa fourchette de temps. Quand un réglage de definitions/ ou une formule d'EconomyEngine fait sortir
 * une étape de sa fourchette, ce test échoue, et la CI avec lui.
 *
 * Les treize cibles, leurs fourchettes et leur provenance viennent de golden/synthese.json (feuille Synthèse du
 * tableur, colonnes A à D) : aucune n'est écrite ici. Si un temps sort de sa fourchette, ce n'est pas la fourchette
 * qu'on change pour faire passer le test (docs/economie.md, « Le simulateur », dit comment lire l'échec).
 */
class ThirteenTargetsTest {
    private val targets = SimulationFixture.targets
    private val run = SimulationFixture.run
    private val results = targets.measure(run)

    @Test
    @DisplayName("Les treize cibles du tableur sont lues dans synthese.json, avec leur provenance")
    fun targetsComeFromTheSheet() {
        assertEquals(13, targets.targets.size, "la feuille Synthèse a treize étapes")
        assertEquals(targets.targets.size, targets.targets.map { it.id }.toSet().size, "chaque cible a un identifiant")
        assertTrue(targets.provenance.contains("silooo-equilibrage-v2.xlsx"), targets.provenance)
        assertTrue(targets.provenance.contains("sha256"), targets.provenance)
        for (target in targets.targets) {
            assertTrue(target.min <= target.max, "fourchette à l'envers pour ${target.id}")
            assertTrue(target.sheetCells.startsWith("Synthèse!C"), "la fourchette de ${target.id} vient des colonnes C et D de la Synthèse")
        }
        assertEquals(9, targets.coursesNeeded, "la dernière cible porte sur la course 9")
    }

    @Test
    @DisplayName("Chacune des treize étapes arrive dans sa fourchette de temps de jeu")
    fun everyTargetIsInItsRange() {
        val text = report(targets.provenance, results, run)
        println(text)
        assertTrue(results.all { it.inTarget }, "\n$text")
    }

    @Test
    @DisplayName("Le simulateur retrouve la valeur que le tableur calcule pour chaque cible")
    fun measuresMatchTheSheet() {
        for (result in results) {
            val measured = checkNotNull(result.measured) { "${result.target.id} n'est jamais atteinte" }
            assertGolden(result.target.sheetValue, measured, "cible ${result.target.id} (${result.target.stage})")
        }
    }

    // --- Le test voit bien un réglage qui casse l'équilibrage ---

    private fun withPriceGrowthTimes(factor: Double): Definitions {
        val base = SimulationFixture.definitions
        val levels = base.economy.levels.copy(priceGrowth = base.economy.levels.priceGrowth * factor)
        return Definitions(base.economy.copy(levels = levels), base.factories, base.shop, base.world, base.missions, base.personnel, base.incidents, base.city, base.vote, base.pass)
    }

    /** Les cibles mesurées sur les `courses` premières courses ; les étapes des courses non jouées sont « jamais atteintes ». */
    private fun resultsFor(definitions: Definitions, courses: Int): List<TargetResult> =
        TargetSet.fromGolden(definitions).measure(GameSimulator(definitions, SimulationFixture.profile, EconomyEngine(definitions)).run(courses))

    @Test
    @DisplayName("Un prix qui monte plus vite (croissancePrix +1 %) fait sortir le premier prestige de sa fourchette, par le haut")
    fun slowerPricesAreCaught() {
        val perturbed = resultsFor(withPriceGrowthTimes(1.01), targets.coursesNeeded)
        val first = perturbed.first { it.target.id == "prestige-1" }
        assertEquals(Verdict.TOO_LONG, first.verdict, "le premier prestige devrait devenir trop long : ${first.line()}")
        assertTrue(first.gap > 0.0)
        assertTrue(perturbed.any { !it.inTarget })
    }

    @Test
    @DisplayName("Un prix qui monte moins vite (croissancePrix -5 %) fait sortir le premier prestige de sa fourchette, par le bas")
    fun fasterPricesAreCaught() {
        // Des prix plus bas épuisent l'échelle de paliers avant la fin de la partie : on ne joue que la première course.
        val perturbed = resultsFor(withPriceGrowthTimes(0.95), courses = 1)
        val first = perturbed.first { it.target.id == "prestige-1" }
        assertEquals(Verdict.TOO_SHORT, first.verdict, "le premier prestige devrait devenir trop court : ${first.line()}")
        assertTrue(first.gap < 0.0)
        assertEquals(Verdict.NOT_REACHED, perturbed.first { it.target.id == "prestige-2" }.verdict, "la course 2 n'est pas jouée")
    }

    // --- Le verdict est celui du tableur ---

    private val sample = Target("x", "étape", Measure.CourseEnd(1), min = 10.0, max = 15.0, sheetValue = 12.0, sheetCells = "Synthèse!C9:D9")

    @Test
    @DisplayName("Le verdict : sous le minimum, dedans (bornes comprises), au-dessus du maximum, ou jamais atteint")
    fun verdicts() {
        assertEquals(Verdict.TOO_SHORT, TargetResult(sample, 9.99).verdict)
        assertEquals(Verdict.IN_TARGET, TargetResult(sample, 10.0).verdict)
        assertEquals(Verdict.IN_TARGET, TargetResult(sample, 12.5).verdict)
        assertEquals(Verdict.IN_TARGET, TargetResult(sample, 15.0).verdict)
        assertEquals(Verdict.TOO_LONG, TargetResult(sample, 15.01).verdict)
        assertEquals(Verdict.NOT_REACHED, TargetResult(sample, null).verdict)
        assertEquals(Verdict.NOT_REACHED, TargetResult(sample, Double.NaN).verdict)
        assertFalse(TargetResult(sample, null).inTarget)
    }

    @Test
    @DisplayName("L'écart est signé : négatif sous le minimum, positif au-dessus du maximum, nul dedans")
    fun gaps() {
        assertEquals(-1.0, TargetResult(sample, 9.0).gap, 1e-12)
        assertEquals(2.0, TargetResult(sample, 17.0).gap, 1e-12)
        assertEquals(0.0, TargetResult(sample, 12.0).gap)
    }

    @Test
    @DisplayName("Le compte rendu d'un échec donne la cible, le temps mesuré, la fourchette, l'écart et les cellules du tableur")
    fun failureReportIsReadable() {
        val text = report("tableur.xlsx", listOf(TargetResult(sample, 16.5), TargetResult(sample.copy(id = "y"), 12.0)), run)
        assertTrue(text.startsWith("1 cible(s) sur 2 hors de leur fourchette"), text)
        assertTrue(text.contains("Trop long"), text)
        assertTrue(text.contains("16,500 h"), text)
        assertTrue(text.contains("+1,500"), text)
        assertTrue(text.contains("[10 ; 15]"), text)
        assertTrue(text.contains("Synthèse!C9:D9"), text)
        assertTrue(text.contains("docs/economie.md"), text)
    }
}
