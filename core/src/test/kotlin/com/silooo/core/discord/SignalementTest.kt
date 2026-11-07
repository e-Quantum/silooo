package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SignalementTest {
    private class Store : SignalementStore {
        val entries = mutableListOf<Signalement>()
        val states = mutableMapOf<Long, String>()
        val removed = mutableListOf<Long>()
        override suspend fun create(reporterId: String, channelId: String, messageId: String, reason: String, at: Instant, cooldown: Duration): Signalement? {
            if (entries.any { it.reporterId == reporterId && it.messageId == messageId } || entries.lastOrNull { it.reporterId == reporterId }?.createdAt?.plus(cooldown)?.isAfter(at) == true) return null
            return Signalement((entries.size + 1).toLong(), reporterId, channelId, messageId, reason, "nouveau", null, at).also { entries += it }
        }
        override suspend fun setState(id: Long, state: String, staffId: String, at: Instant): Boolean {
            if (states[id] in setOf("resolu", "sans-suite") || entries.none { it.id == id }) return false
            if (state == "pris" && states[id] != null) return false
            states[id] = state; return true
        }
        override suspend fun posted(id: Long, channelId: String, messageId: String) {}
        override suspend fun publicationFailed(id: Long) {}
        override suspend fun expired(before: Instant): List<Signalement> = entries.filter { it.createdAt.isBefore(before) }
        override suspend fun purge(id: Long, before: Instant): Int { removed += id; entries.removeIf { it.id == id }; return 1 }
        override suspend fun count(from: Instant, until: Instant): Long = entries.count { !it.createdAt.isBefore(from) && it.createdAt.isBefore(until) }.toLong()
    }

    @Test
    fun modalIsBoundToReporterExpiresAndCanBeUsedOnlyOnce() = runBlocking {
        val server = FakeDiscordServer()
        val modRole = server.createRole(RoleSpec("Modération", "#e0614f", true, false))
        val category = server.createCategory(CategorySpec("Staff", setOf(modRole)))
        val reports = server.createChannel(category, ChannelSpec("signalements", ChannelKind.TEXTE, null, setOf(modRole), setOf(modRole), false))
        val ids = ServerIds(mapOf("moderation" to modRole), mapOf(SignalementFeature.SIGNALMENTS_CHANNEL to reports))
        val timer = FakeTimer(Instant.parse("2026-11-07T12:00:00Z"))
        val store = Store()
        val feature = SignalementFeature(BotContext(server, ids, timer, ServerPhase.ATTENTE), store, SignalementRules()) { _, _ -> true }
        server.messagesById["channel" to "message"] = ChannelMessage("author", "Auteur", "contenu explicite", timer.now())
        val reporter = DiscordUser("reporter", "Rapporteur")
        val formReply = FakeReply()
        feature.handle(DiscordEvent.MessageReported(reporter, "channel", "message", formReply))
        val form = formReply.forms.single()
        assertTrue(form.id.startsWith(SignalementFeature.FORM_PREFIX))
        assertEquals(emptyList<Signalement>(), store.entries)

        val invalid = FakeReply()
        feature.handle(DiscordEvent.FormSubmitted(form.id, mapOf("reason" to "harcèlement"), DiscordUser("other", "Autre"), null, invalid))
        assertTrue(store.entries.isEmpty())

        val submitted = FakeReply()
        feature.handle(DiscordEvent.FormSubmitted(form.id, mapOf("reason" to "harcèlement"), reporter, null, submitted))
        assertEquals(1, store.entries.size)
        assertFalse(server.posted.isEmpty())
        val card = server.posted.last().message
        assertTrue(card.text.contains("contenu explicite"))
        assertTrue(card.text.contains("Motif : harcèlement"))
        assertTrue(card.buttons.any { it.label == "Prendre en charge" })
        assertTrue(submitted.sent.single().first.text.contains("enregistré"))

        val replay = FakeReply()
        feature.handle(DiscordEvent.FormSubmitted(form.id, mapOf("reason" to "rejeu"), reporter, null, replay))
        assertEquals(1, store.entries.size)
    }

    @Test
    fun everyModerationActionRevalidatesRoleAndChannelAccess() = runBlocking {
        val server = FakeDiscordServer()
        val staff = server.createRole(RoleSpec("Staff", "#e0614f", true, false))
        val mod = server.createRole(RoleSpec("Modération", "#e0614f", true, false))
        val reports = server.createChannel(server.createCategory(CategorySpec("Staff", setOf(mod))), ChannelSpec("signalements", ChannelKind.TEXTE, null, setOf(mod), setOf(mod), false))
        val ids = ServerIds(mapOf("staff" to staff, "moderation" to mod), mapOf(SignalementFeature.SIGNALMENTS_CHANNEL to reports))
        val store = Store().also { it.entries += Signalement(1, "r", "c", "m", "motif privé", "nouveau", null, Instant.EPOCH) }
        var allowed = false
        val feature = SignalementFeature(BotContext(server, ids, FakeTimer(), ServerPhase.ATTENTE), store, SignalementRules()) { _, _ -> allowed }
        server.memberRoles["staff-user"] = mutableSetOf(staff)
        val denied = FakeReply()
        feature.handle(DiscordEvent.ButtonPressed("${SignalementFeature.ACTION_PREFIX}1:resolved", DiscordUser("staff-user", "Staff"), reports, "post", denied))
        assertTrue(store.states.isEmpty())
        allowed = true
        val accepted = FakeReply()
        feature.handle(DiscordEvent.ButtonPressed("${SignalementFeature.ACTION_PREFIX}1:resolved", DiscordUser("staff-user", "Staff"), reports, "post", accepted))
        assertEquals("resolu", store.states[1])
    }

    @Test
    fun retentionDeletesThePrivateDiscordCardAndItsStoredContext() = runBlocking {
        val server = FakeDiscordServer()
        val modRole = server.createRole(RoleSpec("Modération", "#e0614f", true, false))
        val reports = server.createChannel(server.createCategory(CategorySpec("Staff", setOf(modRole))), ChannelSpec("signalements", ChannelKind.TEXTE, null, setOf(modRole), setOf(modRole), false))
        val now = Instant.parse("2026-12-08T12:00:00Z")
        val timer = FakeTimer(now)
        val store = Store().also {
            it.entries += Signalement(8, "reporter", "target-channel", "target-message", "motif", "resolu", "mod", now.minus(Duration.ofDays(31)), reports, "card-8", "publie", "mod", "mod")
        }
        server.messagesById[reports to "card-8"] = ChannelMessage("bot", "Oooscar", "Signalement #8\nContenu signalé : contexte privé", now.minus(Duration.ofDays(31)), bot = true)
        val context = BotContext(server, ServerIds(mapOf("moderation" to modRole), mapOf(SignalementFeature.SIGNALMENTS_CHANNEL to reports)), timer, ServerPhase.ATTENTE)
        SignalementFeature(context, store, SignalementRules()) { _, _ -> true }.start()
        assertEquals(listOf(8L), store.removed)
        assertFalse(server.messagesById.containsKey(reports to "card-8"))
    }
}
