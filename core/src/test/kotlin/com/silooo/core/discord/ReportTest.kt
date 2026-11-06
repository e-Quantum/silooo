package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReportTest {
    private val zone = ZoneId.of("Europe/Paris")
    private class Store : ReportStore {
        var capture: Instant? = null
        val claims = mutableSetOf<LocalDate>()
        var sent: Boolean? = null
        val messages = mutableSetOf<String>()
        override suspend fun beginCapture(at: Instant): Instant = capture ?: at.also { capture = it }
        override suspend fun joined(userId: String, at: Instant, returnAfterDays: Int) {}
        override suspend fun left(userId: String, at: Instant) {}
        override suspend fun onboardingCompleted(userId: String, at: Instant) {}
        override suspend fun tutorialDmFailed(userId: String, at: Instant) {}
        override suspend fun message(messageId: String, userId: String, channelId: String, at: Instant) { messages += messageId }
        override suspend fun counts(activityFrom: Instant, activityUntil: Instant, returnAfterDays: Int, zone: ZoneId) =
            ReportCounts(2, 1, 0, 1, emptyMap())
        override suspend fun claim(weekStart: LocalDate, at: Instant): Boolean = claims.add(weekStart)
        override suspend fun finished(weekStart: LocalDate, sent: Boolean) { this.sent = sent }
    }

    @Test
    fun schedulesAtNineParisAndDoesNotRepeatAfterRestart() = runBlocking {
        val server = FakeDiscordServer()
        val category = server.createCategory(CategorySpec("Staff", null))
        val journal = server.createChannel(category, ChannelSpec("journal", ChannelKind.TEXTE, null, null, null, false))
        val timer = FakeTimer(Instant.parse("2026-11-09T07:59:59Z"))
        val store = Store()
        val context = BotContext(server, ServerIds(emptyMap(), mapOf(ServerPlan.JOURNAL_ID to journal, "discussion" to "chat")), timer, ServerPhase.ATTENTE)
        fun feature() = ReportFeature(context, ReportRules(9, 7, 7), zone, listOf("discussion"), store)
        val report = feature()
        report.start()
        assertTrue(server.messages.isEmpty())
        timer.advance(Duration.ofSeconds(1))
        assertEquals(1, server.messages.size)
        val (channel, text) = server.messages.single()
        assertEquals(journal, channel)
        assertTrue(text.contains("Arrivées : 2"))
        assertTrue(text.contains("date de liaison non mesurée"))
        assertTrue(text.contains("interruptions de capture"))
        feature().start()
        assertEquals(1, server.messages.size)
        val human = DiscordUser("7", "Humain")
        report.handle(DiscordEvent.MessagePosted("private", "1", human, "ignoré", timer.now()))
        report.handle(DiscordEvent.MessagePosted("chat", "2", human.copy(isBot = true), "ignoré", timer.now()))
        report.handle(DiscordEvent.MessagePosted("thread", "3", human, "sans conservation", timer.now(), "chat"))
        assertEquals(setOf("3"), store.messages)
    }

    @Test
    fun failedSendIsRecordedAndNotRetried() = runBlocking {
        val timer = FakeTimer(Instant.parse("2026-11-09T08:00:00Z"))
        val store = Store().also { it.capture = timer.now() - Duration.ofDays(1) }
        val context = BotContext(FakeDiscordServer(), ServerIds(emptyMap(), mapOf(ServerPlan.JOURNAL_ID to "missing")), timer, ServerPhase.ATTENTE)
        fun feature() = ReportFeature(context, ReportRules(9, 7, 7), zone, emptyList(), store)
        var failed = false
        try { feature().start() } catch (e: IllegalStateException) { failed = true }
        assertTrue(failed)
        assertEquals(false, store.sent)
        feature().start()
        assertEquals(1, store.claims.size)
    }

    @Test
    fun daylightSavingUsesLocalNine() {
        val context = BotContext(FakeDiscordServer(), ServerIds.NONE, FakeTimer(), ServerPhase.ATTENTE)
        val report = ReportFeature(context, ReportRules(9, 7, 7), zone, emptyList(), Store())
        assertEquals(LocalDate.parse("2026-03-23"), report.latestMonday(Instant.parse("2026-03-30T06:59:59Z")))
        assertEquals(LocalDate.parse("2026-03-30"), report.latestMonday(Instant.parse("2026-03-30T07:00:00Z")))
        assertEquals(LocalDate.parse("2026-10-19"), report.latestMonday(Instant.parse("2026-10-26T07:59:59Z")))
        assertEquals(LocalDate.parse("2026-10-26"), report.latestMonday(Instant.parse("2026-10-26T08:00:00Z")))
    }
}
