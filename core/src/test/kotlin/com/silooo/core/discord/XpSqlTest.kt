package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `discord_chat_xp` et `discord_xp_messages` sur la vraie MariaDB : compter, reprendre, classer, remettre la semaine à zéro. Ignoré sans Docker : la CI le joue. */
class XpSqlTest {
    private val store get() = JdbcXpStore(MariaDbSupport.database)
    private val start = Instant.parse("2026-11-05T12:00:00Z")
    private val monday = LocalDate.of(2026, 11, 2)

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    @Test
    @DisplayName("Un message crée la ligne, le suivant s'y ajoute ; la semaine posée à la création ne bouge pas, le niveau s'écrit")
    fun countsMessages() = runBlocking {
        assertNull(store.record("7"))

        val first = store.count("7", "m1", 20, start, monday)
        assertEquals(XpRecord("7", 20, 0, 20, monday, start), first)
        val second = store.count("7", "m2", 17, start + Duration.ofSeconds(90), monday.plusWeeks(1))
        assertEquals(XpRecord("7", 37, 0, 37, monday, start + Duration.ofSeconds(90)), second)
        assertEquals(second, store.record("7"))
        assertEquals(1L, MariaDbSupport.count("discord_chat_xp"))
        assertEquals(2L, MariaDbSupport.count("discord_xp_messages"))

        store.levelReached("7", 1)
        assertEquals(1, store.record("7")?.level)
        assertEquals(37, store.record("7")?.points, "le niveau ne change pas les points")
    }

    @Test
    @DisplayName("Un message supprimé rend ses points au total et à la semaine, une seule fois ; un message inconnu ne rend rien")
    fun uncountsAMessage() = runBlocking {
        store.count("7", "m1", 20, start, monday)
        store.count("7", "m2", 17, start + Duration.ofMinutes(2), monday)

        assertEquals(20, store.uncount("m1"))
        val after = checkNotNull(store.record("7"))
        assertEquals(17, after.points)
        assertEquals(17, after.weekPoints)
        assertNull(store.uncount("m1"))
        assertNull(store.uncount("inconnu"))
        assertEquals(17, store.record("7")?.points)
        assertEquals(1L, MariaDbSupport.count("discord_xp_messages"))
    }

    @Test
    @DisplayName("Les points repris ne passent jamais sous zéro, au total comme à la semaine")
    fun neverBelowZero() = runBlocking {
        store.count("7", "m1", 20, start, monday)
        store.resetWeek(monday)
        assertEquals(20, store.uncount("m1"))
        val weekFloor = checkNotNull(store.record("7"))
        assertEquals(0, weekFloor.points)
        assertEquals(0, weekFloor.weekPoints, "la semaine remise à zéro ne devient pas négative")

        store.count("8", "m2", 20, start, monday)
        MariaDbSupport.update("update discord_chat_xp set points = 5 where discord_user_id = ?", "8")
        assertEquals(20, store.uncount("m2"))
        assertEquals(0, store.record("8")?.points)
        assertEquals(0, store.record("8")?.weekPoints)
    }

    @Test
    @DisplayName("Le classement de la semaine : points décroissants, égalité par identifiant, limite respectée, zéro exclu")
    fun ranksTheWeek() = runBlocking {
        store.count("a1", "m1", 30, start, monday)
        store.count("a2", "m2", 50, start, monday)
        store.count("a3", "m3", 50, start, monday)
        store.count("a4", "m4", 10, start, monday)
        store.count("a5", "m5", 10, start, monday)
        store.uncount("m5")

        assertEquals(listOf("a2", "a3", "a1", "a4"), store.weekTop(10).map { it.userId })
        assertEquals(listOf("a2", "a3"), store.weekTop(2).map { it.userId })
        assertEquals(listOf(50, 50), store.weekTop(2).map { it.weekPoints })

        store.resetWeek(monday.plusWeeks(1))
        assertEquals(listOf<XpRecord>(), store.weekTop(10))
    }

    @Test
    @DisplayName("La remise à zéro vide la semaine de tous, garde les totaux, pose le lundi et purge les messages d'avant")
    fun resetsTheWeek() = runBlocking {
        assertNull(store.oldestWeekStart())

        store.count("a1", "old", 25, Instant.parse("2026-10-28T12:00:00Z"), monday.minusWeeks(1))
        store.count("a2", "kept", 40, start, monday)
        assertEquals(monday.minusWeeks(1), store.oldestWeekStart())

        store.resetWeek(monday)
        assertEquals(monday, store.oldestWeekStart())
        val one = checkNotNull(store.record("a1"))
        assertEquals(25, one.points, "le total reste")
        assertEquals(0, one.weekPoints)
        assertEquals(monday, one.weekStart)
        assertEquals(0, store.record("a2")?.weekPoints)
        assertEquals(40, store.record("a2")?.points)

        assertEquals(1L, MariaDbSupport.count("discord_xp_messages"), "le message d'avant le lundi est purgé")
        assertNull(store.uncount("old"))
        assertEquals(25, store.record("a1")?.points, "un message purgé ne reprend plus rien")
        assertEquals(40, store.uncount("kept"))
    }
}
