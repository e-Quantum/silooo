package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ReminderSqlTest {
    @BeforeEach fun prepare() = MariaDbSupport.prepare()

    @Test
    fun preferencesAreOffByDefaultAndDeliveriesAreIdempotentBudgetedAndQuiet() = runBlocking {
        val store = JdbcReminderStore(MariaDbSupport.database)
        val now = Instant.parse("2026-11-06T22:30:00Z") // 23:30 in Europe/Paris
        assertEquals(null, store.preferences("7"))
        store.set("7", ReminderCategory.VOTE, true, "Europe/Paris", now)
        store.set("7", ReminderCategory.GEMS, true, null, now)
        assertEquals(setOf(ReminderCategory.VOTE, ReminderCategory.GEMS), store.preferences("7")?.enabled)

        val quietVote = ReminderDelivery("vote:1", "7", ReminderCategory.VOTE, "vote", now)
        assertTrue(store.enqueue(quietVote))
        assertFalse(store.enqueue(quietVote))
        assertFalse(store.claim(quietVote.id, now, ZoneId.of("Europe/Paris"), ReminderRules(3, 22, 7)))

        repeat(3) { index ->
            val delivery = ReminderDelivery("gems:$index", "7", ReminderCategory.GEMS, "gemmes", now)
            assertTrue(store.enqueue(delivery))
            assertTrue(store.claim(delivery.id, now, ZoneId.of("Europe/Paris"), ReminderRules(3, 22, 7)))
            store.finish(delivery.id, sent = true, failure = null, now = now)
        }
        val fourth = ReminderDelivery("gems:fourth", "7", ReminderCategory.GEMS, "gemmes", now)
        store.enqueue(fourth)
        assertFalse(store.claim(fourth.id, now, ZoneId.of("Europe/Paris"), ReminderRules(3, 22, 7)))
        assertEquals("daily-limit", MariaDbSupport.string("select failure from discord_reminder_deliveries where delivery_id = 'gems:fourth'"))
    }
}
