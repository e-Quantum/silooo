package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import com.silooo.core.discord.linking.GameDiscordLinks
import java.time.Instant
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
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

        val linkedAt = now.minusSeconds(60)
        val quietVote = ReminderDelivery("vote:1", "7", ReminderCategory.VOTE, "vote", now, "7", linkedAt)
        assertTrue(store.enqueue(quietVote))
        assertFalse(store.enqueue(quietVote))
        assertFalse(store.claim(quietVote.id, now, ZoneId.of("Europe/Paris"), ReminderRules(3, 22, 7)))

        repeat(3) { index ->
            val delivery = ReminderDelivery("gems:$index", "7", ReminderCategory.GEMS, "gemmes", now, "7", linkedAt)
            assertTrue(store.enqueue(delivery))
            assertTrue(store.claim(delivery.id, now, ZoneId.of("Europe/Paris"), ReminderRules(3, 22, 7)))
            store.finish(delivery.id, sent = index != 0, failure = if (index == 0) "discord-refused" else null, now = now)
        }
        val fourth = ReminderDelivery("gems:fourth", "7", ReminderCategory.GEMS, "gemmes", now, "7", linkedAt)
        store.enqueue(fourth)
        assertFalse(store.claim(fourth.id, now, ZoneId.of("Europe/Paris"), ReminderRules(3, 22, 7)))
        assertEquals("daily-limit", MariaDbSupport.string("select failure from discord_reminder_deliveries where delivery_id = 'gems:fourth'"))
    }

    @Test
    fun globalVotePartyFansOutOnlyToProvenOptedMemberAndClosedDmIsNotRetriedAfterRestart() = runBlocking {
        val now = Instant.parse("2026-11-06T10:00:00Z")
        MariaDbSupport.insertPlayer("7", "KrTest")
        val links = GameDiscordLinks(MariaDbSupport.database, Clock.fixed(now, ZoneId.of("UTC"))) { "ABC234" }
        val code = MariaDbSupport.database.transaction { links.issue(it, checkNotNull(com.silooo.core.db.Xuid.parse("7"))) }
        val proof = checkNotNull(links.redeem("11", code.code).link)
        val store = JdbcReminderStore(MariaDbSupport.database)
        MariaDbSupport.update("insert into discord_reminder_cursor (id, last_event_id) values (1, 0)")
        MariaDbSupport.update("insert into game_events (xuid, kind, payload, created_at) values (null, 'ANNONCE', '{\"action\":\"voteParty\"}', ?)", java.time.LocalDateTime.ofInstant(now, ZoneId.of("UTC")))
        store.set("11", ReminderCategory.VOTE, true, "Europe/Paris", now)
        val server = FakeDiscordServer().also { it.closedDirects += "11" }
        val timer = FakeTimer(now)
        val feature = ReminderFeature(BotContext(server, ServerIds.NONE, timer, ServerPhase.OUVERT), ReminderRules(3, 22, 7), ZoneId.of("Europe/Paris"), store, links)
        feature.start()
        timer.advance(java.time.Duration.ofSeconds(30))
        assertEquals(1L, MariaDbSupport.count("discord_reminder_deliveries"))
        assertEquals("failed", MariaDbSupport.string("select status from discord_reminder_deliveries"))
        assertEquals("discord-refused", MariaDbSupport.string("select failure from discord_reminder_deliveries"))
        assertEquals(proof.linkedAt, MariaDbSupport.raw { connection ->
            connection.prepareStatement("select proof_linked_at from discord_reminder_deliveries").use { statement ->
                statement.executeQuery().use { rows -> rows.next(); rows.getObject(1, java.time.LocalDateTime::class.java).toInstant(ZoneOffset.UTC) }
            }
        })
        val restartTimer = FakeTimer(now.plusSeconds(60))
        ReminderFeature(BotContext(server, ServerIds.NONE, restartTimer, ServerPhase.OUVERT), ReminderRules(3, 22, 7), ZoneId.of("Europe/Paris"), JdbcReminderStore(MariaDbSupport.database), links).start()
        restartTimer.advance(java.time.Duration.ofSeconds(30))
        assertEquals(1L, MariaDbSupport.count("discord_reminder_deliveries"))
    }

    @Test
    fun queuedDeliveryIsDiscardedWhenGameProofChangesAfterUnlinkAndRelink() = runBlocking {
        val now = Instant.parse("2026-11-06T10:00:00Z")
        MariaDbSupport.insertPlayer("7", "KrTest")
        var sequence = 0
        val links = GameDiscordLinks(MariaDbSupport.database, Clock.fixed(now, ZoneId.of("UTC"))) { listOf("ABC234", "DEF567")[sequence++] }
        suspend fun link() = MariaDbSupport.database.transaction { links.issue(it, checkNotNull(com.silooo.core.db.Xuid.parse("7"))) }.let { links.redeem("11", it.code).link!! }
        val oldProof = link()
        val store = JdbcReminderStore(MariaDbSupport.database)
        store.set("11", ReminderCategory.VOTE, true, null, now)
        store.enqueue(ReminderDelivery("persisted-before-unlink", "11", ReminderCategory.VOTE, "vote", now, oldProof.xuid, oldProof.linkedAt))
        links.unlink("11", oldProof.xuid, oldProof.linkedAt)
        val newProof = link()
        assertTrue(newProof.linkedAt.isAfter(oldProof.linkedAt))
        val server = FakeDiscordServer()
        val timer = FakeTimer(now)
        ReminderFeature(BotContext(server, ServerIds.NONE, timer, ServerPhase.OUVERT), ReminderRules(3, 22, 7), ZoneId.of("Europe/Paris"), store, links).start()
        timer.advance(java.time.Duration.ofSeconds(30))
        assertTrue(server.directs.isEmpty())
        assertEquals("game-link-changed", MariaDbSupport.string("select failure from discord_reminder_deliveries where delivery_id='persisted-before-unlink'"))
    }
}
