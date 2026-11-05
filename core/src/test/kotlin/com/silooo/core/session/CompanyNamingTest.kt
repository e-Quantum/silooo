package com.silooo.core.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CompanyNamingTest {
    @Test
    @DisplayName("Le nom provisoire dérive du gamertag")
    fun provisionalFromGamertag() {
        assertEquals("Entreprise de Kr", CompanyNaming.provisional("Kr"))
        assertEquals("Entreprise de Kr", CompanyNaming.provisional("  Kr  "))
    }

    @Test
    @DisplayName("Un gamertag de 32 caractères donne un nom coupé à 32 caractères, sans espace final")
    fun provisionalIsCut() {
        val name = CompanyNaming.provisional("A".repeat(32))
        assertEquals(CompanyNaming.MAX_LENGTH, name.length)
        assertTrue(name.startsWith("Entreprise de A"))
        assertEquals(name.trimEnd(), name)
    }

    @Test
    @DisplayName("Le premier essai est le nom lui-même, les suivants prennent un numéro")
    fun candidates() {
        assertEquals("Entreprise de Kr", CompanyNaming.candidate("Entreprise de Kr", 1))
        assertEquals("Entreprise de Kr 2", CompanyNaming.candidate("Entreprise de Kr", 2))
        assertEquals("Entreprise de Kr 10", CompanyNaming.candidate("Entreprise de Kr", 10))
    }

    @Test
    @DisplayName("Avec un numéro, le nom reste dans 32 caractères : c'est la base qui est coupée")
    fun candidatesStayShort() {
        val base = "B".repeat(32)
        for (attempt in listOf(2, 9, 10, 99, 100)) {
            val name = CompanyNaming.candidate(base, attempt)
            assertTrue(name.length <= CompanyNaming.MAX_LENGTH, name)
            assertTrue(name.endsWith(" $attempt"), name)
        }
    }

    @Test
    @DisplayName("La coupure ne casse pas une paire de substitution")
    fun cutIsCodePointSafe() {
        val gamertag = "😀".repeat(30) // 30 caractères Unicode, 60 unités UTF-16
        val name = CompanyNaming.provisional(gamertag)
        assertEquals(name, String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8), "aucune moitié de paire")
        assertTrue(name.codePointCount(0, name.length) <= CompanyNaming.MAX_LENGTH)
    }

    @Test
    @DisplayName("Un essai inférieur à 1 est une erreur de programmation")
    fun attemptStartsAtOne() {
        assertThrows<IllegalArgumentException> { CompanyNaming.candidate("x", 0) }
    }
}
