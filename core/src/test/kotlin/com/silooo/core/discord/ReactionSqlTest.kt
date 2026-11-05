package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `discord_reactions` et `discord_reaction_marks` sur la vraie MariaDB : compter une fois, classer, purger. Ignoré sans Docker : la CI le joue. */
class ReactionSqlTest {
    private val store get() = JdbcReactionStore(MariaDbSupport.database)
    private val start = Instant.parse("2026-11-05T12:00:00Z")
    private val monday = LocalDate.of(2026, 11, 2)

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    @Test
    @DisplayName("Une réaction compte une fois par message et par membre ; un autre message, ou un autre membre, compte")
    fun countsOncePerMessageAndMember() = runBlocking {
        assertTrue(store.add("m1", "7", start, monday))
        assertFalse(store.add("m1", "7", start, monday), "la même réaction deux fois")
        assertTrue(store.add("m2", "7", start, monday))
        assertTrue(store.add("m1", "8", start, monday))

        assertEquals(listOf(ReactionCount("7", 2), ReactionCount("8", 1)), store.top(monday, 10))
        assertEquals(3L, MariaDbSupport.count("discord_reaction_marks"))
        assertEquals(2L, MariaDbSupport.count("discord_reactions"))
    }

    @Test
    @DisplayName("Le classement : réactions décroissantes, égalité par identifiant, limite respectée, une autre semaine à part")
    fun ranksTheWeek() = runBlocking {
        store.add("m1", "a1", start, monday)
        store.add("m1", "a2", start, monday)
        store.add("m2", "a2", start, monday)
        store.add("m1", "a3", start, monday)
        store.add("m2", "a3", start, monday)
        store.add("m3", "a4", start, monday.plusWeeks(1))

        assertEquals(listOf("a2", "a3", "a1"), store.top(monday, 10).map { it.userId })
        assertEquals(listOf("a2", "a3"), store.top(monday, 2).map { it.userId })
        assertEquals(listOf(2, 2), store.top(monday, 2).map { it.given })
        assertEquals(listOf("a4"), store.top(monday.plusWeeks(1), 10).map { it.userId })
        assertEquals(listOf<ReactionCount>(), store.top(monday.minusWeeks(1), 10))
    }

    @Test
    @DisplayName("La plus ancienne semaine comptée est connue, null sans réaction")
    fun knowsTheOldestWeek() = runBlocking {
        assertNull(store.oldestWeekStart())

        store.add("m1", "7", start, monday.plusWeeks(1))
        store.add("m2", "7", start, monday)
        assertEquals(monday, store.oldestWeekStart())
    }

    @Test
    @DisplayName("La purge efface les semaines d'avant le lundi donné et les marques d'avant l'instant donné, pas le reste")
    fun purgesOldWeeksAndMarks() = runBlocking {
        store.add("old", "7", Instant.parse("2026-11-01T12:00:00Z"), monday.minusWeeks(1))
        store.add("kept", "8", start, monday)

        store.purge(monday, Instant.parse("2026-11-01T23:00:00Z"))

        assertEquals(monday, store.oldestWeekStart())
        assertEquals(1L, MariaDbSupport.count("discord_reactions"))
        assertEquals(1L, MariaDbSupport.count("discord_reaction_marks"))
        assertTrue(store.add("old", "7", start, monday), "la marque purgée laisse le message compter de nouveau")
        assertFalse(store.add("kept", "8", start, monday), "la marque gardée reste")
    }
}
