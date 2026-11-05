package com.silooo.core.registration

import java.time.Duration
import java.time.Instant
import java.util.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Les codes de « Relier Discord » : l'alphabet, la longueur, la forme tapée, la durée. */
class LinkCodesTest {
    @Test
    @DisplayName("L'alphabet a 32 caractères distincts, majuscules et chiffres, sans O, 0, I ni 1")
    fun alphabet() {
        assertEquals(32, LinkCodes.ALPHABET.length)
        assertEquals(32, LinkCodes.ALPHABET.toSet().size, "aucun caractère en double")
        for (forbidden in "O0I1") assertFalse(forbidden in LinkCodes.ALPHABET, "« $forbidden » prête à confusion")
        assertTrue(LinkCodes.ALPHABET.all { it in 'A'..'Z' || it in '2'..'9' }, LinkCodes.ALPHABET)
    }

    @Test
    @DisplayName("Un code tiré a six caractères de l'alphabet, et la graine fixe le tirage")
    fun generate() {
        assertEquals(6, LinkCodes.LENGTH)
        repeat(500) {
            val code = LinkCodes.generate()
            assertEquals(LinkCodes.LENGTH, code.length, code)
            assertTrue(code.all { it in LinkCodes.ALPHABET }, code)
            assertEquals(code, LinkCodes.normalize(code), "un code tiré est déjà sous sa forme normale")
        }
        assertEquals(LinkCodes.generate(Random(42)), LinkCodes.generate(Random(42)))
        // Tous les caractères de l'alphabet sortent un jour : le tirage ne se limite pas à un sous-ensemble.
        val seen = buildSet { val random = Random(7); repeat(2000) { addAll(LinkCodes.generate(random).toList()) } }
        assertEquals(LinkCodes.ALPHABET.toSet(), seen)
    }

    @Test
    @DisplayName("Ce que le joueur tape est ramené à la forme du code : majuscules, sans espace ni tiret")
    fun normalizeAccepts() {
        assertEquals("K7M4PX", LinkCodes.normalize("k7m4px"))
        assertEquals("K7M4PX", LinkCodes.normalize("K7M4PX"))
        assertEquals("K7M4PX", LinkCodes.normalize("  k7m4px \n"))
        assertEquals("K7M4PX", LinkCodes.normalize("k7m-4px"))
        assertEquals("K7M4PX", LinkCodes.normalize("K7M 4PX"))
        assertEquals("K7M4PX", LinkCodes.normalize("k7 m4 - px"))
    }

    @Test
    @DisplayName("Trop court, trop long, vide, un caractère hors alphabet (O, 0, I, 1, accent, symbole) : ce n'est pas un code")
    fun normalizeRefuses() {
        for (typed in listOf("", "   ", "K7M4P", "K7M4PXX", "K7MOPX", "K7M0PX", "K7MIPX", "K7M1PX", "K7M4PÉ", "K7M4P!", "K7M_PX", "------")) {
            assertNull(LinkCodes.normalize(typed), "« $typed »")
        }
    }

    @Test
    @DisplayName("Un code vaut dix minutes, une session trente jours ; un code est expiré dès sa dernière seconde")
    fun lifetimes() {
        assertEquals(Duration.ofMinutes(10), LinkCodes.LIFETIME)
        assertEquals(Duration.ofDays(30), WebSessions.LIFETIME)
        val created = Instant.parse("2026-11-05T12:00:00Z")
        val code = LinkCode("K7M4PX", 1L, created, created.plus(LinkCodes.LIFETIME))
        assertFalse(code.expired(created))
        assertFalse(code.expired(created.plus(LinkCodes.LIFETIME).minusMillis(1)))
        assertTrue(code.expired(created.plus(LinkCodes.LIFETIME)), "à la minute dix, le code est expiré")
        assertTrue(code.expired(created.plus(LinkCodes.LIFETIME).plusSeconds(1)))
    }
}
