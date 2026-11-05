package com.silooo.core.discord

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Les identifiants que le plan appliqué donne au bot : les catégories y sont, créées ou déjà là, par leur identifiant du plan. */
class PlanCategoryIdsTest {
    private val plan = ServerPlan.fromClasspath()

    private fun apply(server: DiscordServer, phase: ServerPhase = ServerPhase.ATTENTE, dryRun: Boolean = false): PlanReport =
        runBlocking { PlanApplier(plan, server, phase, dryRun).run() }

    @Test
    @DisplayName("Les catégories créées par le plan sont dans les identifiants ; celles d'une phase à venir n'y sont pas")
    fun createdCategoriesAreKnown() {
        val fake = FakeDiscordServer()
        val ids = apply(fake).ids

        assertEquals(fake.categories.first { it.name == "Aide" }.id, ids.category("aide"))
        assertEquals(fake.categories.first { it.name == "Vocaux" }.id, ids.category("vocaux"))
        assertNull(ids.category("jeu"), "la catégorie de la bêta n'existe pas encore")
        assertNull(ids.category("inconnue"))
    }

    @Test
    @DisplayName("Un second passage retrouve les mêmes catégories ; à blanc, une catégorie qui manque n'a pas d'identifiant")
    fun existingCategoriesAreKnown() {
        val fake = FakeDiscordServer()
        val first = apply(fake).ids
        val second = apply(fake).ids
        assertEquals(first.categories, second.categories)
        assertEquals(fake.categories.size, second.categories.size)

        val empty = apply(FakeDiscordServer(), dryRun = true).ids
        assertNull(empty.category("aide"))
    }
}
