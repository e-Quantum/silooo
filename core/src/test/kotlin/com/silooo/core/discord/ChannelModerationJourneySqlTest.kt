package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport as Db
import com.silooo.core.discord.jda.MODERATION_COMMANDS
import com.silooo.core.discord.jda.isModerationButton
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ChannelModerationJourneySqlTest {
    private val actor = DiscordUser("111", "Staff")
    private val rules = ChannelModerationRules(listOf("discussion"), 100, 100, 13, listOf(0, 10, 30), 300)
    private val mod = ModerationRules(600, 30, 10, 500)
    @BeforeEach fun prepare() = Db.prepare()
    private inner class Setup {
        val fake = FakeDiscordServer().apply { memberRoles["111"] = mutableSetOf("staff"); memberRoles["222"] = mutableSetOf("staff") }
        val timer = FakeTimer(Instant.parse("2026-11-08T18:00:00Z"))
        val context = BotContext(fake, ServerIds(mapOf("staff" to "staff"), mapOf("discussion" to "77")), timer, ServerPhase.ATTENTE)
        val realStore = JdbcChannelModerationStore(Db.database)
        var state = ChannelModerationSnapshot(ChannelPermissionValue(false), 0, true)
        var mutations = 0
        var checks = 0
        var fail = false
        var cancelled = false
        var auditFail = false
        val gateway = object : ChannelModerationGateway {
            override suspend fun snapshot(actorId: String, channelId: String, action: ChannelAction): ChannelModerationSnapshot { checks++; return state }
            override suspend fun candidates(actorId: String, channelId: String, count: Int, now: Instant) = listOf("101", "100").take(count)
            override suspend fun execute(case: ChannelModerationCase, now: Instant): Int {
                mutations++
                state = case.plan.after
                if (cancelled) throw CancellationException("Processus arrêté après envoi")
                if (fail) throw DiscordRefusal("Réseau incertain")
                return if (case.plan.action == ChannelAction.PURGE) case.plan.messageIds.size else 1
            }
        }
        val store = object : ChannelModerationStore by realStore {
            override suspend fun finish(id: String, status: ChannelCaseStatus, affected: Int?, now: Instant) {
                if (auditFail) error("Audit unavailable")
                realStore.finish(id, status, affected, now)
            }
        }
        fun feature() = ChannelModerationFeature(context, rules, mod, store, gateway, BotJournal(context))
        val feature = feature()
        suspend fun command(name: String, options: Map<String, String> = emptyMap(), user: DiscordUser = actor, channel: String? = "77", handler: Feature = feature): FakeReply {
            val reply = FakeReply(); handler.handle(DiscordEvent.CommandUsed(name, mapOf("motif" to "Motif") + options, user, channel, reply)); return reply
        }
        suspend fun press(id: String, user: DiscordUser = actor, channel: String? = "77", handler: Feature = feature): FakeReply {
            val reply = FakeReply(); handler.handle(DiscordEvent.ButtonPressed(id, user, channel, "preview", reply)); return reply
        }
        suspend fun preview(name: String, options: Map<String, String> = emptyMap()) = command(name, options).sent.single().also { assertTrue(it.second) }.first
    }

    @Test fun everyCommandUsesPrivateConfirmationAndDurableAtMostOnceClaims() = runBlocking {
        val s = Setup()
        for ((name, options) in listOf("purger" to mapOf("nombre" to "2"), "mode-lent" to mapOf("secondes" to "10"), "verrouiller" to emptyMap(), "deverrouiller" to emptyMap())) {
            val before = s.mutations; val preview = s.preview(name, options)
            assertEquals(before, s.mutations)
            val confirm = preview.buttons.first().id
            val response = s.press(confirm)
            assertTrue(response.sent.single().second)
            assertTrue(response.sent.single().first.text.contains("enregistr"))
            s.press(confirm)
            assertEquals(before + 1, s.mutations)
        }
        assertNull(s.realStore.lock("77"))
        assertFalse(s.state.everyone.exists)
        assertEquals(10, s.state.slowSeconds)
        assertEquals(4L, Db.count("discord_channel_cases"))
    }

    @Test fun wrongActorWrongChannelRoleLossAndCancellationPreventExecution() = runBlocking {
        val s = Setup(); val preview = s.preview("purger", mapOf("nombre" to "2"))
        s.press(preview.buttons.first().id, DiscordUser("222", "Autre staff"))
        s.press(preview.buttons.first().id, channel = "88")
        assertEquals(0, s.mutations)
        s.fake.memberRoles.clear()
        assertTrue(s.press(preview.buttons.first().id).sent.single().first.text.contains("réservée"))
        s.fake.memberRoles["111"] = mutableSetOf("staff")
        s.press(preview.buttons.last().id); s.press(preview.buttons.first().id)
        assertEquals(0, s.mutations)
    }

    @Test fun invalidOptionsPrivateContextsAndExpiredConfirmationsDoNotMutate() = runBlocking {
        val s = Setup()
        for ((name, option) in listOf("purger" to mapOf("nombre" to "101"), "mode-lent" to mapOf("secondes" to "17"))) {
            assertTrue(s.command(name, option).sent.single().first.buttons.isEmpty())
        }
        assertTrue(s.command("verrouiller", channel = null).sent.single().first.buttons.isEmpty())
        assertTrue(s.command("verrouiller", channel = "private").sent.single().first.buttons.isEmpty())
        assertEquals(0L, Db.count("discord_channel_cases"))
        val preview = s.preview("purger", mapOf("nombre" to "2"))
        s.timer.advance(Duration.ofSeconds(600)); s.press(preview.buttons.first().id)
        assertEquals(0, s.mutations)
    }

    @Test fun uncertainLockSurvivesRestartAndCanOnlyBeRestoredByNewExplicitConfirmation() = runBlocking {
        val s = Setup(); val preview = s.preview("verrouiller")
        s.fail = true; s.press(preview.buttons.first().id)
        assertEquals(ChannelCaseStatus.UNCERTAIN, s.realStore.get(preview.buttons.first().id.substringAfter(':'))!!.status)
        assertNotNull(s.realStore.lock("77")); assertEquals(1, s.mutations)
        val reloaded = s.feature()
        reloaded.start()
        assertEquals(1, s.mutations)
        s.fail = false
        s.press(preview.buttons.first().id, handler = reloaded)
        assertEquals(1, s.mutations)
        assertTrue(s.command("deverrouiller", handler = reloaded).sent.single().first.buttons.isEmpty())
        s.timer.advance(Duration.ofSeconds(301))
        val restore = s.command("deverrouiller", handler = reloaded).sent.single().first
        assertEquals(1, s.mutations)
        s.press(restore.buttons.first().id, handler = reloaded)
        assertEquals(2, s.mutations); assertNull(s.realStore.lock("77")); assertFalse(s.state.everyone.exists)
    }

    @Test fun auditFailureOrCoroutineCancellationAfterMutationNeverReplaysIt() = runBlocking {
        for (cancellation in listOf(false, true)) {
            val s = Setup(); val preview = s.preview("purger", mapOf("nombre" to "2"))
            s.auditFail = !cancellation; s.cancelled = cancellation
            try {
                val reply = s.press(preview.buttons.first().id)
                assertTrue(reply.sent.single().first.text.contains("Ne relance pas"))
            } catch (e: CancellationException) { assertTrue(cancellation) }
            s.auditFail = false; s.cancelled = false
            s.press(preview.buttons.first().id)
            assertEquals(1, s.mutations)
        }
    }

    @Test fun existingManualLocksAndBypassExceptionsAreNeverAdopted() = runBlocking {
        val s = Setup()
        s.state = s.state.copy(everyone = ChannelPermissionValue(true, 0, CHANNEL_WRITING_MASK))
        assertTrue(s.command("verrouiller").sent.single().first.buttons.isEmpty())
        s.state = s.state.copy(everyone = ChannelPermissionValue(false), lockable = false)
        assertTrue(s.command("verrouiller").sent.single().first.buttons.isEmpty())
        assertNull(s.realStore.lock("77")); assertEquals(0L, Db.count("discord_channel_cases")); assertEquals(0, s.mutations)
    }

    @Test fun restorationPreservesManualUnrelatedBitsAndRejectsChangedWritingBits() {
        val original = ChannelPermissionValue(false)
        val locked = ChannelPermissionValue(true, 0, CHANNEL_WRITING_MASK)
        val manual = ChannelPermissionValue(true, 1L shl 60, CHANNEL_WRITING_MASK)
        assertEquals(ChannelPermissionValue(true, 1L shl 60, 0), restoreChannelWriting(manual, original, locked, CHANNEL_WRITING_MASK))
        assertThrows(ChannelActionRefused::class.java) { restoreChannelWriting(ChannelPermissionValue(true, 1L shl 11, 0), original, locked, CHANNEL_WRITING_MASK) }
        assertEquals(original, restoreChannelWriting(original, original, locked, CHANNEL_WRITING_MASK))
        assertEquals(original, restoreChannelWriting(original, ChannelPermissionValue(true), locked, CHANNEL_WRITING_MASK))
    }

    @Test fun moderationInteractionsAreDeferredBeforeEnteringTheQueue() {
        for (name in listOf("expulser", "bannir", "debannir", "purger", "mode-lent", "verrouiller", "deverrouiller")) assertTrue(name in MODERATION_COMMANDS)
        assertTrue(isModerationButton(ChannelModerationFeature.CONFIRM + "id"))
        assertTrue(isModerationButton("moderation-history:123:0:111"))
        assertFalse(isModerationButton("ticket-fermer:123"))
    }

    @Test fun closedServerRejectsChannelActionsEvenThroughDirectCalls() = runBlocking {
        val closed = ClosedServer(FakeDiscordServer(), setOf("staff"))
        try { closed.channelModerationSnapshot("111", "77", ChannelAction.LOCK, setOf("staff")); fail<Unit>("QG fermé") } catch (_: ChannelActionRefused) { }
        assertEquals(0, closed.held)
    }
}
