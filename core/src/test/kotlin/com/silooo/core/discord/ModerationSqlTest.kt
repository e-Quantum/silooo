package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ModerationSqlTest {
    private val store get() = JdbcModerationStore(MariaDbSupport.database, retentionDays = 30)
    private val now = Instant.parse("2026-11-05T12:00:00Z")
    @BeforeEach fun prepare() = MariaDbSupport.prepare()

    @Test
    @DisplayName("La réservation et la revendication SQL garantissent une seule tentative après rejeu")
    fun claimsAtMostOnceAcrossStoreInstances() = runBlocking {
        val item = ModerationCase("e85f27c7-55f8-4aa0-a54a-74df7db43a01", "76543210987654321", "12345678901234567", ModerationAction.TIMEOUT, "Perturbation", now, now.plusSeconds(600), ModerationStatus.RESERVED, 3600)
        assertTrue(store.reserve(item))
        assertFalse(store.reserve(item))
        assertNotNull(store.claim(item.id, item.actorId, now.plusSeconds(1)))
        assertNull(JdbcModerationStore(MariaDbSupport.database, 30).claim(item.id, item.actorId, now.plusSeconds(2)))
        store.finish(item.id, true, null, now.plusSeconds(3))
        assertEquals(ModerationStatus.SUCCEEDED, store.get(item.id)?.status)
        assertEquals(3600L, store.history(item.targetId, now.minusSeconds(1), 10).single().timeoutDurationSeconds)
    }

    @Test
    @DisplayName("La purge retire les dossiers avant la limite demandée")
    fun purgesExpiredCases() = runBlocking {
        val item = ModerationCase("c95f27c7-55f8-4aa0-a54a-74df7db43a02", "76543210987654321", "12345678901234567", ModerationAction.WARN, "Ancien motif", now, now.plusSeconds(600), ModerationStatus.RESERVED)
        assertTrue(store.reserve(item))
        assertEquals(1, store.purge(now.plusMillis(1)))
        assertNull(store.get(item.id))
    }

}
