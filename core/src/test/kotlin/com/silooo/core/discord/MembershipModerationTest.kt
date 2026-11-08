package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MembershipModerationTest {
    private val actor = "76543210987654321"
    private val targetId = "12345678901234567"
    private val actions = mapOf("expulser" to ModerationAction.KICK, "bannir" to ModerationAction.BAN, "debannir" to ModerationAction.UNBAN)
    private class Gateway : ModerationGateway {
        var target: ModerationTarget? = ModerationTarget("12345678901234567", "Membre", false, false, true, true)
        var calls = 0
        var fail = false
        var cancelled = false
        var departed = false
        val checked = mutableListOf<ModerationAction>()
        override suspend fun check(actorId: String, targetId: String) = if (departed) null else target
        override suspend fun checkAction(actorId: String, targetId: String, action: ModerationAction): ModerationTarget? { checked += action; return target }
        override suspend fun historyTarget(actorId: String, targetId: String) = target
        override suspend fun membership(actorId: String, targetId: String, action: ModerationAction, reason: String) {
            calls++
            if (cancelled) throw CancellationException("Résultat inconnu")
            if (fail) throw DiscordRefusal("Résultat inconnu")
        }
        override suspend fun timeout(actorId: String, targetId: String, until: Instant?, reason: String) = error("Pas de timeout")
        override suspend fun warn(actorId: String, targetId: String, reason: String): Boolean = error("Pas d'avertissement")
    }
    private inner class Setup {
        val server = FakeDiscordServer().apply { memberRoles[actor] = mutableSetOf("staff") }
        val timer = FakeTimer()
        val store = ModerationTest.MemoryStore()
        val gateway = Gateway()
        val context = BotContext(server, ServerIds(mapOf("staff" to "staff"), emptyMap()), timer, ServerPhase.ATTENTE)
        val feature = ModerationFeature(context, ModerationRules(600, 30, 10, 500), store, gateway, BotJournal(context), {})
        suspend fun command(name: String, id: String = targetId): FakeReply = FakeReply().also {
            feature.handle(DiscordEvent.CommandUsed(name, mapOf("membre" to id, "motif" to "Règle enfreinte"), DiscordUser(actor, "Staff"), null, it))
        }
        suspend fun press(id: String, by: String = actor): FakeReply = FakeReply().also {
            feature.handle(DiscordEvent.ButtonPressed(id, DiscordUser(by, "Staff"), null, "m", it))
        }
        suspend fun preview(name: String) = command(name).sent.single().also { assertTrue(it.second) }.first
    }

    @Test fun everyActionRequiresPrivateOwnerConfirmationAndRunsAtMostOnce() = runBlocking {
        for ((command, action) in actions) {
            val s = Setup()
            val preview = s.preview(command)
            assertEquals(0, s.gateway.calls)
            val id = preview.buttons.first().id
            s.server.memberRoles["11111111111111111"] = mutableSetOf("staff")
            assertEquals(ModerationTexts.UNKNOWN, s.press(id, "11111111111111111").sent.single().first.text)
            assertEquals(ModerationTexts.DONE, s.press(id).sent.single().first.text)
            s.press(id)
            assertEquals(1, s.gateway.calls)
            assertTrue(s.gateway.checked.all { it == action })
            assertEquals(action, s.store.rows.values.single().action)
            assertEquals(ModerationStatus.SUCCEEDED, s.store.rows.values.single().status)
        }
    }

    @Test fun roleAndHierarchyLossBeforeConfirmationPreventEveryMembershipAction() = runBlocking {
        for (command in actions.keys) {
            val s = Setup(); val id = s.preview(command).buttons.first().id
            s.gateway.target = s.gateway.target!!.copy(actorCanInteract = false)
            assertEquals(ModerationTexts.CANNOT_ACT, s.press(id).sent.single().first.text)
            assertEquals(0, s.gateway.calls)
            s.gateway.target = s.gateway.target!!.copy(actorCanInteract = true)
            s.server.memberRoles.clear()
            assertEquals(ModerationTexts.NOT_STAFF, s.press(id).sent.single().first.text)
            assertEquals(0, s.gateway.calls)
        }
    }

    @Test fun protectedTargetsCannotCreateAReservation() = runBlocking {
        for (command in actions.keys) for (target in listOf(
            ModerationTarget(targetId, "Membre", true, false, true, true),
            ModerationTarget(targetId, "Membre", false, true, true, true),
            ModerationTarget(targetId, "Membre", false, false, true, false),
            ModerationTarget(actor, "Staff", false, false, true, true),
        )) {
            val s = Setup(); s.gateway.target = target
            assertEquals(ModerationTexts.CANNOT_ACT, s.command(command).sent.single().first.text)
            assertTrue(s.store.rows.isEmpty())
        }
    }

    @Test fun missingBanOrDepartedTargetDoesNotReserveAnAction() = runBlocking {
        for (command in actions.keys) {
            val s = Setup(); s.gateway.target = null
            assertEquals(ModerationTexts.BAD_TARGET, s.command(command).sent.single().first.text)
            assertTrue(s.store.rows.isEmpty())
        }
    }

    @Test fun expiredAndCancelledMembershipActionsDoNotRun() = runBlocking {
        for (command in actions.keys) {
            val s = Setup(); val message = s.preview(command)
            s.press(message.buttons.last().id)
            s.press(message.buttons.first().id)
            assertEquals(0, s.gateway.calls)
            val expired = s.preview(command).buttons.first().id
            s.timer.advance(Duration.ofSeconds(600))
            assertEquals(ModerationTexts.EXPIRED, s.press(expired).sent.single().first.text)
            assertEquals(0, s.gateway.calls)
        }
    }

    @Test fun unknownExternalResultAndAuditFailureNeverReplayMembershipActions() = runBlocking {
        for (command in actions.keys) for (auditFailure in listOf(false, true)) {
            val s = Setup(); val id = s.preview(command).buttons.first().id
            s.gateway.fail = !auditFailure; s.store.failFinish = auditFailure
            val result = s.press(id).sent.single().first.text
            assertTrue(if (auditFailure) result.contains("audit durable") else result.contains("pas confirmé"))
            s.store.failFinish = false; s.gateway.fail = false
            s.press(id)
            assertEquals(1, s.gateway.calls)
        }
    }

    @Test fun coroutineCancellationPreservesClaimAndPropagatesWithoutRetry() = runBlocking {
        val s = Setup(); val id = s.preview("bannir").buttons.first().id
        s.gateway.cancelled = true
        try { s.press(id); fail<Unit>("L'annulation doit remonter") } catch (_: CancellationException) { }
        s.gateway.cancelled = false; s.press(id)
        assertEquals(1, s.gateway.calls)
    }

    @Test fun dossierRemainsPrivateAfterDepartureAndRechecksFreshRoles() = runBlocking {
        val s = Setup(); val id = s.preview("bannir").buttons.first().id; s.press(id)
        s.gateway.departed = true
        val dossier = s.command("dossier").sent.single()
        assertTrue(dossier.second)
        assertTrue(dossier.first.text.contains("Bannissement"))
        s.server.memberRoles.clear()
        assertEquals(ModerationTexts.NOT_STAFF, s.command("dossier").sent.single().first.text)
    }

    @Test fun previewsExplainConsequencesAndBanPreservesMessages() = runBlocking {
        assertTrue(Setup().preview("expulser").text.contains("invité"))
        assertTrue(Setup().preview("bannir").text.contains("messages seront conservés"))
        assertTrue(Setup().preview("debannir").text.contains("pas réintégré automatiquement"))
    }

    @Test fun legacyGatewaysFailClosedForNewActions() = runBlocking {
        val gateway = object : ModerationGateway {
            override suspend fun check(actorId: String, targetId: String) = ModerationTarget(targetId, "Membre", false, false, true, true)
            override suspend fun timeout(actorId: String, targetId: String, until: Instant?, reason: String) = Unit
            override suspend fun warn(actorId: String, targetId: String, reason: String) = true
        }
        for (action in actions.values) assertNull(gateway.checkAction(actor, targetId, action))
    }
}
