package com.silooo.core.economy

import com.silooo.core.api.Role
import com.silooo.core.db.Xuid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Le partage des Lingots d'un prestige entre les membres : des nombres entiers, rien de créé, rien de perdu. */
class PrestigeSharesTest {
    private val ceo = Xuid.parse("2535428000000001")!!
    private val second = Xuid.parse("2535428000000002")!!
    private val third = Xuid.parse("2535428000000003")!!

    private fun member(xuid: Xuid, percent: Int, role: Role = Role.ASSOCIE) = MemberShare(xuid, role, percent)

    private fun lingots(result: List<LingotShare>) = result.map { it.lingots }

    @Test
    @DisplayName("Un PDG seul reçoit tout")
    fun aloneTakesEverything() {
        assertEquals(listOf(37L), lingots(splitLingots(37, listOf(member(ceo, 100, Role.PDG)))))
    }

    @Test
    @DisplayName("Chacun reçoit sa part arrondie à l'inférieur, et le reste va au PDG")
    fun remainderGoesToTheCeo() {
        val members = listOf(member(ceo, 34, Role.PDG), member(second, 33), member(third, 33))
        // 10 Lingots : 3, 3 et 3 par membre (arrondi inférieur), le dixième va au PDG.
        assertEquals(listOf(4L, 3L, 3L), lingots(splitLingots(10, members)))
        // 100 Lingots : le partage tombe juste, il n'y a pas de reste.
        assertEquals(listOf(34L, 33L, 33L), lingots(splitLingots(100, members)))
    }

    @Test
    @DisplayName("Le total est toujours distribué en entier, pour tous les totaux et toutes les répartitions de deux ou trois membres")
    fun neverCreatesNorLosesLingots() {
        for (total in 0L..250L) {
            for (a in 0..100 step 7) {
                val two = listOf(member(ceo, a, Role.PDG), member(second, 100 - a))
                assertEquals(total, splitLingots(total, two).sumOf { it.lingots }, "$total Lingots, parts $a/${100 - a}")
                val b = (100 - a) / 2
                val three = listOf(member(ceo, a, Role.PDG), member(second, b), member(third, 100 - a - b))
                assertEquals(total, splitLingots(total, three).sumOf { it.lingots }, "$total Lingots, parts $a/$b/${100 - a - b}")
            }
        }
    }

    @Test
    @DisplayName("Un associé sans part ne reçoit rien ; le PDG garde le reste")
    fun partnerWithoutShare() {
        val members = listOf(member(ceo, 100, Role.PDG), member(second, 0))
        assertEquals(listOf(9L, 0L), lingots(splitLingots(9, members)))
    }

    @Test
    @DisplayName("Un total énorme ne déborde pas d'un Long")
    fun hugeTotalDoesNotOverflow() {
        val members = listOf(member(ceo, 50, Role.PDG), member(second, 50))
        val result = splitLingots(Long.MAX_VALUE, members)
        assertEquals(Long.MAX_VALUE, result.sumOf { it.lingots })
        assertEquals(Long.MAX_VALUE / 2, result[1].lingots)
    }

    @Test
    @DisplayName("Le résultat suit l'ordre des membres et garde leurs XUID")
    fun keepsTheMemberOrder() {
        val result = splitLingots(50, listOf(member(second, 50), member(ceo, 50, Role.PDG)))
        assertEquals(listOf(second, ceo), result.map { it.xuid })
    }

    @Test
    @DisplayName("Des parts qui dépassent 100, ou une entreprise sans PDG, sont une erreur de données : aucun Lingot n'est fabriqué")
    fun brokenDataIsRefused() {
        assertThrows<IllegalStateException> { splitLingots(10, listOf(member(ceo, 80, Role.PDG), member(second, 40))) }
        assertThrows<IllegalStateException> { splitLingots(10, listOf(member(second, 100))) }
        assertThrows<IllegalArgumentException> { splitLingots(-1, listOf(member(ceo, 100, Role.PDG))) }
    }
}
