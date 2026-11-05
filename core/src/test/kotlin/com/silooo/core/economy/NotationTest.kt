package com.silooo.core.economy

import com.silooo.core.GoldenFiles
import com.silooo.core.array
import com.silooo.core.double
import com.silooo.core.int
import com.silooo.core.objects
import com.silooo.core.string
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * L'affichage des grands nombres. Les exemples de la charte (« 84,3 Md · 1,2 aa · 512 k ») et les
 * bornes sont écrits ici ; la table des 103 suffixes et les textes du tableur viennent de notation.json,
 * simulation.json, courses.json et synthese.json.
 */
class NotationTest {
    @Test
    @DisplayName("Les exemples de la charte : 84,3 Md, 1,2 aa, 512 k")
    fun charterExamples() {
        assertEquals("84,3 Md", Notation.format(84.3e9))
        assertEquals("1,2 aa", Notation.format(1.2e12))
        assertEquals("512 k", Notation.format(512e3))
    }

    @Test
    @DisplayName("Trois chiffres au plus : une décimale sous 100, aucune à partir de 100")
    fun threeDigitsAtMost() {
        assertEquals("1,2 k", Notation.format(1200.0))
        assertEquals("12,4 k", Notation.format(12_400.0))
        assertEquals("99,9 k", Notation.format(99_900.0))
        assertEquals("100 k", Notation.format(100_000.0))
        assertEquals("195 M", Notation.format(195_577_200.0))
        assertEquals("999 k", Notation.format(999_999.0))
    }

    @Test
    @DisplayName("La décimale est tronquée, jamais arrondie, et une décimale nulle disparaît")
    fun truncatesAndDropsZero() {
        assertEquals("1,9 k", Notation.format(1999.0))
        assertEquals("1,9 k", Notation.format(1999.99))
        assertEquals("9,9 k", Notation.format(9999.0))
        assertEquals("1 k", Notation.format(1000.0))
        assertEquals("1 Md", Notation.format(1e9))
        assertEquals("1 k", Notation.format(1000.4))
    }

    @Test
    @DisplayName("Sous 1 000 : un entier arrondi, et 999,5 passe à 1 k au lieu d'écrire quatre chiffres")
    fun belowOneThousand() {
        assertEquals("0", Notation.format(0.0))
        assertEquals("0", Notation.format(-12.0))
        assertEquals("0", Notation.format(0.4))
        assertEquals("1", Notation.format(0.5))
        assertEquals("523", Notation.format(522.78))
        assertEquals("999", Notation.format(999.49))
        assertEquals("1 k", Notation.format(999.5))
        assertEquals("1 k", Notation.format(999.99))
    }

    @Test
    @DisplayName("Le résultat ne dépend pas de la division binaire : 1,2 k reste 1,2 k")
    fun decimalNotBinary() {
        for (tenths in 10..99) {
            val value = tenths * 100.0
            val expected = if (tenths % 10 == 0) "${tenths / 10} k" else "${tenths / 10},${tenths % 10} k"
            assertEquals(expected, Notation.format(value), "$value")
        }
    }

    @Test
    @DisplayName("Un reste de calcul d'un bit ne change pas l'affichage : 120 000 moins un bit s'affiche 120 k")
    fun floatNoiseIsInvisible() {
        for (value in listOf(1000.0, 1200.0, 120_000.0, 5e11, 1e17, 3e26)) {
            val expected = Notation.format(value)
            assertEquals(expected, Notation.format(Math.nextDown(value)), "un bit sous $value")
            assertEquals(expected, Notation.format(Math.nextUp(value)), "un bit sur $value")
        }
        assertEquals("120 k", Notation.format(119_999.99999999999))
    }

    @Test
    @DisplayName("Un nombre infini ou NaN est une erreur de l'appelant")
    fun nonFiniteIsRefused() {
        assertThrows<IllegalArgumentException> { Notation.format(Double.POSITIVE_INFINITY) }
        assertThrows<IllegalArgumentException> { Notation.format(Double.NaN) }
    }

    @Test
    @DisplayName("Les suffixes : k, M, Md, puis deux lettres par puissance de mille")
    fun suffixScheme() {
        assertEquals("", Notation.suffix(0))
        assertEquals("k", Notation.suffix(1))
        assertEquals("M", Notation.suffix(2))
        assertEquals("Md", Notation.suffix(3))
        assertEquals("aa", Notation.suffix(4))
        assertEquals("ab", Notation.suffix(5))
        assertEquals("az", Notation.suffix(29))
        assertEquals("ba", Notation.suffix(30))
        assertEquals("zz", Notation.suffix(679))
        assertThrows<IllegalArgumentException> { Notation.suffix(-1) }
        assertThrows<IllegalArgumentException> { Notation.suffix(680) }
    }

    @Test
    @DisplayName("Le plus grand Double s'écrit avec le dernier suffixe de la table")
    fun largestDouble() {
        assertEquals("179 du", Notation.format(Double.MAX_VALUE))
        assertEquals("du", Notation.suffix(Notation.MAX_DOUBLE_POWER))
        assertEquals("1 du", Notation.format("1e${3 * Notation.MAX_DOUBLE_POWER}".toDouble()))
        assertTrue("1e${3 * (Notation.MAX_DOUBLE_POWER + 1)}".toDouble().isInfinite(), "la puissance suivante dépasse le Double")
    }

    // ---------------------------------------------------------------- tableur : notation.json

    @Test
    @DisplayName("La table des suffixes du tableur : 103 puissances de mille, de 0 à 102, mêmes suffixes, mêmes valeurs")
    fun suffixTableOfTheSheet() {
        val entries = GoldenFiles.read("notation").objects("suffixes")
        assertEquals(103, entries.size)
        assertEquals(Notation.MAX_DOUBLE_POWER + 1, entries.size, "la table s'arrête à la dernière puissance que le Double atteint")
        for ((index, entry) in entries.withIndex()) {
            val power = entry.int("power")
            assertEquals(index, power, "les puissances se suivent")
            assertEquals(entry.string("suffix"), Notation.suffix(power), "suffixe de la puissance $power")
            val value = entry.double("value")
            assertEquals("1e${3 * power}".toDouble(), value, "valeur de la puissance $power")
            val expected = if (power == 0) "1" else "1 ${entry.string("suffix")}"
            assertEquals(expected, Notation.format(value), "affichage de 1e${3 * power}")
        }
    }

    // ---------------------------------------------------------------- tableur : textes affichés

    @Test
    @DisplayName("Les 965 prix et 965 revenus que le tableur affiche s'affichent pareil, à la règle de la charte près")
    fun simulationDisplays() {
        val purchases = GoldenFiles.read("simulation").objects("purchases")
        assertEquals(965, purchases.size)
        for (row in purchases) {
            val n = row.int("n")
            assertEquals(sheetToCharter(row.string("priceDisplay")), Notation.format(row.double("price")), "prix de l'achat $n")
            assertEquals(sheetToCharter(row.string("incomeAfterDisplay")), Notation.format(row.double("incomeAfter")), "revenu après l'achat $n")
        }
    }

    @Test
    @DisplayName("Les seuils et les revenus de fin de course du tableur s'affichent pareil")
    fun courseDisplays() {
        val courses = GoldenFiles.read("courses").objects("courses")
        assertEquals(9, courses.size)
        for (course in courses) {
            val number = course.int("course")
            assertEquals(sheetToCharter(course.string("thresholdDisplay")), Notation.format(course.double("prestigeThresholdDonar")), "seuil de la course $number")
        }
        val seen = GoldenFiles.read("synthese").objects("playerSees")
        assertEquals(courses.size, seen.size)
        for ((course, see) in courses.zip(seen)) {
            assertEquals(course.string("thresholdDisplay"), see.string("thresholdDisplay"), "le tableur dit deux fois la même chose")
            assertEquals(course.string("incomeAtEndDisplay"), see.string("incomeAtEndDisplay"))
        }
    }

    @Test
    @DisplayName("Le tableur n'a aucune cellule d'affichage qu'on ne sache pas lire")
    fun everySheetDisplayIsReadable() {
        val synthese = GoldenFiles.read("synthese")
        val displays = synthese.objects("targets").map { it.string("displayedAmount") }.filter { it != "—" } +
            synthese.objects("playerSees").flatMap { listOf(it.string("thresholdDisplay"), it.string("incomeAtEndDisplay")) }
        assertTrue(displays.isNotEmpty())
        for (display in displays) sheetToCharter(display)
        assertEquals(13, synthese.array("targets").size)
    }
}
