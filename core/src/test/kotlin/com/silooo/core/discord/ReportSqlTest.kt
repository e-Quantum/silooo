package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ReportSqlTest {
    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    @Test
    fun persistsCaptureDeduplicatesEventsAndBoundsCounts() = runBlocking {
        val store = JdbcReportStore(MariaDbSupport.database)
        val from = Instant.parse("2026-11-02T08:00:00Z")
        val until = from + Duration.ofDays(7)
        assertEquals(from, store.beginCapture(from))
        assertEquals(from, JdbcReportStore(MariaDbSupport.database).beginCapture(until))
        store.left("7", from - Duration.ofDays(7))
        repeat(2) {
            store.joined("7", from, 7)
            store.onboardingCompleted("7", from)
            store.message("10", "7", "chat", from)
            store.tutorialDmFailed("7", from)
        }
        store.message("11", "7", "chat", until)
        store.joined("8", until, 7)
        val counts = store.counts(from, until, 7, ZoneId.of("Europe/Paris"))
        assertEquals(1L, counts.arrivals)
        assertEquals(1L, counts.onboardingCompleted)
        assertEquals(1L, counts.returnsAfterThreshold)
        assertEquals(1L, counts.tutorialDmFailures)
        assertEquals(mapOf(LocalDate.parse("2026-11-02") to 1L), counts.messagesByDay)
        assertTrue(store.claim(LocalDate.parse("2026-11-02"), until))
        store.finished(LocalDate.parse("2026-11-02"), sent = false)
        assertEquals("failed", MariaDbSupport.string("select status from discord_report_claims"))
        assertFalse(JdbcReportStore(MariaDbSupport.database).claim(LocalDate.parse("2026-11-02"), until))
    }
}
