package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SignalementSqlTest {
    private val store get() = JdbcSignalementStore(MariaDbSupport.database)
    private val start = Instant.parse("2026-11-07T12:00:00Z")

    @BeforeEach fun prepare() = MariaDbSupport.prepare()

    @Test
    @DisplayName("Un message ne peut être signalé deux fois par la même personne et le cooldown résiste aux relances")
    fun duplicateAndCooldownAreDurable() = runBlocking {
        val first = store.create("10", "20", "30", "motif", start, Duration.ofHours(1))!!
        assertEquals("nouveau", first.state)
        assertNull(store.create("10", "20", "30", "doublon", start.plusSeconds(1), Duration.ofHours(1)))
        assertNull(store.create("10", "20", "31", "autre", start.plusSeconds(30), Duration.ofHours(1)))
        assertTrue(store.setState(first.id, "pris", "99", start.plusSeconds(5)))
        assertTrue(store.setState(first.id, "resolu", "99", start.plusSeconds(6)))
        assertFalse(store.setState(first.id, "sans-suite", "99", start.plusSeconds(7)))
        assertEquals(1, store.expired(start.plus(Duration.ofDays(30))).size)
        assertEquals(1, store.purge(first.id))
        val afterPurge = store.create("10", "20", "30", "nouveau dossier", start.plus(Duration.ofDays(31)), Duration.ofHours(1))
        assertTrue(afterPurge != null)
        assertEquals(1L, store.count(start, start.plus(Duration.ofDays(32))))
    }
}
