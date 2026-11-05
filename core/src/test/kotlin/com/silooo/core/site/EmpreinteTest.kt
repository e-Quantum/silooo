package com.silooo.core.site

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les vecteurs viennent de `provisionalXuid` (`addon/src/identity.ts`), calculés avec Node sur le même code : un gamertag
 * avec espace, majuscules et caractère accentué, un autre hors du plan multilingue de base (deux unités UTF-16), et la
 * chaîne vide. Si l'add-on change de calcul, ce test casse avant la boutique.
 */
class EmpreinteTest {
    @Test
    @DisplayName("L'empreinte est celle de l'add-on, gamertag par gamertag")
    fun matchesTheAddon() {
        assertEquals("14695981039346656037", Empreinte.duGamertag(""))
        assertEquals("620337896427418084", Empreinte.duGamertag("a"))
        assertEquals("8314889119629673698", Empreinte.duGamertag("Kr Test"))
        assertEquals("9601854309877372291", Empreinte.duGamertag("Élodie Dupont"))
        assertEquals("5141716415491074777", Empreinte.duGamertag("Oooscar"))
        assertEquals("11080061904825434512", Empreinte.duGamertag("Joueur 😀"))
    }

    @Test
    @DisplayName("Les espaces autour et la casse ne comptent pas, comme dans l'add-on")
    fun normalizesLikeTheAddon() {
        val reference = Empreinte.duGamertag("Kr Test")
        for (variant in listOf("  Kr Test  ", "KR TEST", "kr test", " Kr Test﻿", "\tKr Test\n")) {
            assertEquals(reference, Empreinte.duGamertag(variant), "« $variant »")
        }
    }

    @Test
    @DisplayName("L'empreinte passe le schéma Xuid du contrat : de 1 à 20 chiffres")
    fun fitsTheContract() {
        for (gamertag in listOf("", "Kr Test", "Un gamertag assez long pour la forme", "😀😀😀")) {
            assertTrue(Regex("^[0-9]{1,20}$").matches(Empreinte.duGamertag(gamertag)), gamertag)
        }
    }
}
