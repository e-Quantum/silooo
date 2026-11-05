package com.silooo.core.staff

import com.silooo.core.TestDefinitions
import com.silooo.core.definitions.StaffRarity
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La liste du jour de l'agence d'emploi : déterminée par l'entreprise et le jour seulement, aux parts de `personnel.json`,
 * sans autre entrée donc sans moyen de payer pour une autre liste. Les parts attendues sortent des définitions, jamais d'un
 * littéral.
 */
class AgencyDrawTest {
    private val personnel = TestDefinitions.loaded.personnel
    private val draw = AgencyDraw(personnel)
    private val day = LocalDate.of(2026, 11, 5)

    @Test
    @DisplayName("SplitMix64 rend la suite publiée du générateur pour la graine 0 : la liste ne change pas avec la JVM ou Kotlin")
    fun splitMixVector() {
        val random = SplitMix64(0L)
        assertEquals(0xE220A8397B1DCDAFuL.toLong(), random.nextLong())
        assertEquals(0x6E789E6AA1B965F4uL.toLong(), random.nextLong())
        assertEquals(0x06C45D188009454FuL.toLong(), random.nextLong())
    }

    @Test
    @DisplayName("nextUnit reste dans [0, 1[ et nextInt dans [0, borne[")
    fun rangesAreRespected() {
        val random = SplitMix64(42L)
        repeat(10_000) {
            val unit = random.nextUnit()
            assertTrue(unit >= 0.0 && unit < 1.0, "nextUnit : $unit")
            val int = random.nextInt(7)
            assertTrue(int in 0 until 7, "nextInt : $int")
        }
    }

    @Test
    @DisplayName("La liste du jour est la même à chaque appel pour une entreprise et un jour, et change avec l'entreprise ou le jour")
    fun deterministicPerCompanyAndDay() {
        assertEquals(draw.candidates(7L, day), draw.candidates(7L, day))
        assertEquals(draw.candidates(7L, day), AgencyDraw(personnel).candidates(7L, day), "une autre instance, la même liste")

        // Sur quelques jours et entreprises, il y a toujours une liste différente : on ne compare pas une seule paire,
        // qui pourrait tomber sur deux listes pareilles.
        val lists = (1L..20L).map { draw.candidates(it, day) }
        assertTrue(lists.toSet().size > 1, "les entreprises ont des listes différentes")
        val days = (0L..20L).map { draw.candidates(7L, day.plusDays(it)) }
        assertTrue(days.toSet().size > 1, "les jours ont des listes différentes")
    }

    @Test
    @DisplayName("Chaque place a le rôle de personnel.json, une apparence de ce rôle, et les noms de la liste sont tous différents")
    fun slotsFollowTheDefinitions() {
        repeat(200) { companyId ->
            val list = draw.candidates(companyId.toLong() + 1, day.plusDays(companyId.toLong()))
            assertEquals(personnel.agency.candidatesPerDay, list.size)
            assertEquals((1..personnel.agency.candidatesPerDay).toList(), list.map { it.slot })
            assertEquals(personnel.agency.rolesPerSlot, list.map { it.role })
            for (candidate in list) {
                assertTrue(candidate.skinId in personnel.agency.appearances.getValue(candidate.role), "apparence ${candidate.skinId} du rôle ${candidate.role}")
                assertTrue(candidate.name in personnel.agency.names)
            }
            assertEquals(list.size, list.map { it.name }.toSet().size, "deux candidats du jour ne portent pas le même nom : $list")
        }
    }

    @Test
    @DisplayName("Sur beaucoup de listes, les raretés suivent la répartition de personnel.json à un point près")
    fun raritiesFollowTheSharesOfTheDefinitions() {
        val companies = 4_000L
        val days = 10L
        val counts = StaffRarity.entries.associateWith { 0 }.toMutableMap()
        var total = 0
        for (company in 1..companies) {
            for (offset in 0 until days) {
                for (candidate in draw.candidates(company, day.plusDays(offset))) {
                    counts[candidate.rarity] = counts.getValue(candidate.rarity) + 1
                    total++
                }
            }
        }
        for (rarity in StaffRarity.entries) {
            val observed = 100.0 * counts.getValue(rarity) / total
            val expected = personnel.rarity(rarity).sharePercent
            assertEquals(expected, observed, 1.0, "part des $rarity : $observed % observé, $expected % attendu sur $total candidats")
        }
        assertEquals(100.0, personnel.rarities.values.sumOf { it.sharePercent }, 1e-9, "les parts font 100")
    }

    @Test
    @DisplayName("rarityAt coupe aux bornes cumulées : la plus courante en tête, la plus rare en queue")
    fun rarityAtBoundaries() {
        var upTo = 0.0
        for (rarity in StaffRarity.entries) {
            val start = upTo
            upTo += personnel.rarity(rarity).sharePercent
            assertEquals(rarity, draw.rarityAt(start), "début de $rarity")
            assertEquals(rarity, draw.rarityAt(Math.nextDown(upTo)), "fin de $rarity, juste avant la borne")
        }
        assertEquals(StaffRarity.entries.last(), draw.rarityAt(100.0), "le bruit du dernier bit va à la plus rare")
    }
}
