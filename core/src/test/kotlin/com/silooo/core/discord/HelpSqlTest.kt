package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `discord_help` et `discord_help_marks` sur la vraie MariaDB : marquer un sujet une fois, compter, classer. Ignoré sans Docker : la CI le joue. */
class HelpSqlTest {
    private val store get() = JdbcHelpStore(MariaDbSupport.database)
    private val start = Instant.parse("2026-11-05T12:00:00Z")
    private val november = LocalDate.of(2026, 11, 1)

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    @Test
    @DisplayName("Un sujet ne se marque qu'une fois : le second marquage ne change ni la marque ni le compte")
    fun marksAThreadOnce() = runBlocking {
        assertFalse(store.isResolved("t1"))

        assertTrue(store.mark("t1", "7", "5", start, november))
        assertTrue(store.isResolved("t1"))
        assertFalse(store.mark("t1", "8", "5", start, november))

        assertEquals(listOf(HelpCount("7", 1)), store.top(november, 10))
        assertEquals("7", MariaDbSupport.string("select resolver_id from discord_help_marks where thread_id = ?", "t1"))
        assertEquals("5", MariaDbSupport.string("select marked_by from discord_help_marks where thread_id = ?", "t1"))
        assertEquals(1L, MariaDbSupport.count("discord_help_marks"))
    }

    @Test
    @DisplayName("Le classement du mois : réponses décroissantes, égalité par identifiant, limite respectée, un autre mois à part")
    fun ranksTheMonth() = runBlocking {
        store.mark("t1", "a1", "5", start, november)
        store.mark("t2", "a2", "5", start, november)
        store.mark("t3", "a2", "5", start, november)
        store.mark("t4", "a3", "5", start, november)
        store.mark("t5", "a4", "5", start, november.plusMonths(1))

        assertEquals(listOf(HelpCount("a2", 2), HelpCount("a1", 1), HelpCount("a3", 1)), store.top(november, 10))
        assertEquals(listOf("a2", "a1"), store.top(november, 2).map { it.userId })
        assertEquals(listOf(HelpCount("a4", 1)), store.top(november.plusMonths(1), 10))
        assertEquals(listOf<HelpCount>(), store.top(november.minusMonths(1), 10))
    }
}
