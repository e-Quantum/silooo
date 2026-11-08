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
        var failPointer = false
        override suspend fun create(reporterId: String, channelId: String, messageId: String, reason: String, at: Instant, cooldown: Duration): Signalement? {
            if (entries.any { it.reporterId == reporterId && it.messageId == messageId } || entries.lastOrNull { it.reporterId == reporterId }?.createdAt?.plus(cooldown)?.isAfter(at) == true) return null
            return Signalement((entries.size + 1).toLong(), reporterId, channelId, messageId, reason, "nouveau", null, at).also { entries += it }
        }
        override suspend fun setState(id: Long, state: String, staffId: String, at: Instant): Boolean {
            if (states[id] in setOf("resolu", "sans-suite") || entries.none { it.id == id }) return false
            if (state == "pris" && states[id] != null) return false
            states[id] = state; return true
        }
        override suspend fun posted(id: Long, channelId: String, messageId: String) {
            if (failPointer) error("simulated pointer persistence failure")
            entries.replaceAll { if (it.id == id) it.copy(postChannelId = channelId, postMessageId = messageId) else it }
        }
        override suspend fun published(id: Long) { entries.replaceAll { if (it.id == id) it.copy(deliveryState = "publie") else it } }
        override suspend fun publicationFailed(id: Long) { entries.replaceAll { if (it.id == id) it.copy(deliveryState = "echec") else it } }
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
        val card = server.edits.last().message
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
        server.names["staff-user"] = "Modérateur A"
        val post = server.post(reports, BotMessage("Signalement #1\nÉtat : nouveau", card = true))
        val denied = FakeReply()
        feature.handle(DiscordEvent.ButtonPressed("${SignalementFeature.ACTION_PREFIX}1:resolved", DiscordUser("staff-user", "Modérateur A"), reports, post, denied))
        assertTrue(store.states.isEmpty())
        allowed = true
        server.names["staff-user"] = "Modérateur A"
        server.messagesById[reports to post] = ChannelMessage("bot", "Oooscar", "Signalement #1\nÉtat : nouveau", Instant.EPOCH, bot = true)
        val accepted = FakeReply()
        feature.handle(DiscordEvent.ButtonPressed("${SignalementFeature.ACTION_PREFIX}1:claim", DiscordUser("staff-user", "Modérateur A"), reports, post, accepted))
        assertEquals("pris", store.states[1])
        val aClaimed = server.edits.last().message.text
        assertTrue(aClaimed.contains("Modérateur A"))
        server.messagesById[reports to post] = ChannelMessage("bot", "Oooscar", aClaimed, Instant.EPOCH, bot = true)
        val closedByB = FakeReply()
        server.names["staff-b"] = "Modérateur B"
        server.memberRoles["staff-b"] = mutableSetOf(mod)
        feature.handle(DiscordEvent.ButtonPressed("${SignalementFeature.ACTION_PREFIX}1:resolved", DiscordUser("staff-b", "Modérateur B"), reports, post, closedByB))
        val closedCard = server.edits.last().message.text
        assertTrue(closedCard.contains("Pris en charge par : Modérateur A"))
        assertTrue(closedCard.contains("Clôturé par : Modérateur B"))
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

    @Test
    fun pointerPersistenceFailureLeavesOnlyDeletedContentFreePlaceholder() = runBlocking {
        val server = FakeDiscordServer()
        val reports = server.createChannel(server.createCategory(CategorySpec("Staff", null)), ChannelSpec("signalements", ChannelKind.TEXTE, null, null, null, false))
        val store = Store().also { it.failPointer = true }
        val timer = FakeTimer(Instant.parse("2026-11-07T12:00:00Z"))
        server.messagesById["source" to "message"] = ChannelMessage("author-secret-id", "Nom secret", "corps confidentiel", timer.now())
        val feature = SignalementFeature(BotContext(server, ServerIds(emptyMap(), mapOf(SignalementFeature.SIGNALMENTS_CHANNEL to reports)), timer, ServerPhase.ATTENTE), store, SignalementRules()) { _, _ -> true }
        val reporter = DiscordUser("reporter", "Rapporteur")
        val opened = FakeReply()
        feature.handle(DiscordEvent.MessageReported(reporter, "source", "message", opened))
        val submit = FakeReply()
        feature.handle(DiscordEvent.FormSubmitted(opened.forms.single().id, mapOf("reason" to "motif confidentiel"), reporter, null, submit))
        assertTrue(server.posted.single().message.text.contains("préparation du dossier"))
        assertFalse(server.posted.single().message.text.contains("corps confidentiel"))
        assertTrue(server.deletedMessages.single().second == server.posted.single().messageId)
        assertEquals("echec", store.entries.single().deliveryState)
        assertTrue(submit.sent.single().first.text.contains("n'a pas pu être préparé"))
    }

    @Test
    fun editFailureKeepsPurgePointerAndDoesNotConfirmPublication() = runBlocking {
        val base = FakeDiscordServer()
        val reports = base.createChannel(base.createCategory(CategorySpec("Staff", null)), ChannelSpec("signalements", ChannelKind.TEXTE, null, null, null, false))
        val server = object : DiscordServer by base {
            override suspend fun edit(channelId: String, messageId: String, message: BotMessage) { throw DiscordRefusal("edit refused") }
        }
        val timer = FakeTimer(Instant.parse("2026-11-07T12:00:00Z"))
        val store = Store()
        base.messagesById["source" to "message"] = ChannelMessage("author", "Auteur", "corps confidentiel", timer.now())
        val feature = SignalementFeature(BotContext(server, ServerIds(emptyMap(), mapOf(SignalementFeature.SIGNALMENTS_CHANNEL to reports)), timer, ServerPhase.ATTENTE), store, SignalementRules()) { _, _ -> true }
        val reporter = DiscordUser("reporter", "Rapporteur")
        val opened = FakeReply()
        feature.handle(DiscordEvent.MessageReported(reporter, "source", "message", opened))
        val submit = FakeReply()
        feature.handle(DiscordEvent.FormSubmitted(opened.forms.single().id, mapOf("reason" to "motif"), reporter, null, submit))
        val report = store.entries.single()
        assertEquals(reports, report.postChannelId)
        assertEquals(serverPostedId(base), report.postMessageId)
        assertEquals("echec", report.deliveryState)
        assertTrue(base.deletedMessages.any { it.second == report.postMessageId })
        assertTrue(submit.sent.single().first.text.contains("publication n'est pas terminée"))
        assertFalse(base.posted.single().message.text.contains("corps confidentiel"))
    }

    private fun serverPostedId(server: FakeDiscordServer) = server.posted.single().messageId
}
