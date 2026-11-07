package com.silooo.core.discord

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ModerationTest {
    private class MemoryStore : ModerationStore {
        val rows = linkedMapOf<String, ModerationCase>()
        val attempted = mutableSetOf<String>()
        override suspend fun reserve(case: ModerationCase) = (rows.putIfAbsent(case.id, case) == null)
        override suspend fun get(id: String) = rows[id]
        override suspend fun claim(id: String, actorId: String, now: Instant): ModerationCase? {
            val old = rows[id] ?: return null
            if (old.actorId != actorId || old.status != ModerationStatus.RESERVED || id in attempted || old.expiresAt <= now) return null
            attempted += id
            return old
        }
        override suspend fun finish(id: String, succeeded: Boolean, failure: String?, at: Instant) {
            val old = rows[id] ?: return
            if (old.status == ModerationStatus.RESERVED && id in attempted) rows[id] = old.copy(status = if (succeeded) ModerationStatus.SUCCEEDED else ModerationStatus.FAILED, failure = failure)
        }
        override suspend fun cancel(id: String, actorId: String, at: Instant): Boolean {
            val old = rows[id] ?: return false
            if (old.actorId != actorId || old.status != ModerationStatus.RESERVED || id in attempted) return false
            rows[id] = old.copy(status = ModerationStatus.FAILED, failure = "cancelled"); return true
        }
        override suspend fun history(targetId: String, since: Instant, limit: Int, offset: Int) = rows.values.filter { it.targetId == targetId && it.createdAt >= since }.drop(offset).take(limit)
    }
    private class Gateway : ModerationGateway {
        var target = ModerationTarget("12345678901234567", "Membre", false, false, true, true)
        var checks = 0
        val timeouts = mutableListOf<Instant?>()
        val warnings = mutableListOf<String>()
        var warnOk = true
        override suspend fun check(actorId: String, targetId: String): ModerationTarget? { checks++; return target.takeIf { it.id == targetId } }
        override suspend fun timeout(actorId: String, targetId: String, until: Instant?, reason: String) { timeouts += until }
        override suspend fun warn(actorId: String, targetId: String, reason: String): Boolean { warnings += reason; return warnOk }
    }
    private val staff = "staff-role"
    private val mod = "mod-role"
    private data class Setup(val feature: ModerationFeature, val gateway: Gateway, val store: MemoryStore, val server: FakeDiscordServer)
    private fun setup(): Setup {
        val server = FakeDiscordServer(); val timer = FakeTimer()
        val ids = ServerIds(mapOf("staff" to staff, "moderation" to mod), emptyMap())
        val ctx = BotContext(server, ids, timer, ServerPhase.ATTENTE)
        val store = MemoryStore(); val gateway = Gateway()
        server.memberRoles["76543210987654321"] = mutableSetOf(staff)
        return Setup(ModerationFeature(ctx, ModerationRules(600, 30, 10, 500), store, gateway, BotJournal(ctx)), gateway, store, server)
    }
    private fun command(feature: ModerationFeature, name: String, options: Map<String, String>, roles: Set<String> = setOf(staff), actor: String = "76543210987654321"): FakeReply {
        val reply = FakeReply(); runBlocking { feature.handle(DiscordEvent.CommandUsed(name, options, DiscordUser(actor, "Staff", roleIds = roles), null, reply)) }; return reply
    }
    private fun press(feature: ModerationFeature, id: String, actor: String = "76543210987654321", roles: Set<String> = setOf(staff)): FakeReply {
        val reply = FakeReply(); runBlocking { feature.handle(DiscordEvent.ButtonPressed(id, DiscordUser(actor, "Staff", roleIds = roles), null, "m", reply)) }; return reply
    }

    @Test
    @DisplayName("Les commandes sont staff-only et le dossier reste paginé selon les règles")
    fun commandsAndPrivateCaseListing() {
        val (feature, _, _, server) = setup()
        assertTrue(feature.commands.all { it.staffOnly })
        assertEquals(listOf("avertir", "timeout", "retablir", "dossier"), feature.commands.map { it.name })
        assertEquals(10, ModerationRules(600, 30, 10, 500).historyPageSize)
        server.memberRoles.remove("76543210987654321")
        val denied = command(feature, "dossier", mapOf("membre" to "12345678901234567"), roles = emptySet())
        assertTrue(denied.sent.single().second)
        assertEquals(ModerationTexts.NOT_STAFF, denied.sent.single().first.text)
    }

    @Test
    @DisplayName("Un timeout exige une confirmation par son auteur et ne s'exécute qu'une fois")
    fun timeoutNeedsOwnerConfirmationAndAtMostOnce() {
        val (feature, gateway, store, _) = setup()
        val preview = command(feature, "timeout", mapOf("membre" to "<@12345678901234567>", "duree" to "1h", "motif" to "Perturbation"))
        val (message, private) = preview.sent.single(); assertTrue(private); assertTrue(gateway.timeouts.isEmpty())
        val id = message.buttons.first().id
        val wrong = press(feature, id, actor = "11111111111111111")
        assertEquals(ModerationTexts.UNKNOWN, wrong.sent.single().first.text)
        val okay = press(feature, id); assertEquals(ModerationTexts.DONE, okay.sent.single().first.text)
        press(feature, id)
        assertEquals(1, gateway.timeouts.size)
        assertEquals(Instant.parse("2026-11-05T11:00:00Z"), gateway.timeouts.single())
        assertEquals(ModerationStatus.SUCCEEDED, store.rows.values.single().status)
    }

    @Test
    @DisplayName("La hiérarchie se revérifie au clic et une cible protégée ne reçoit aucune action")
    fun hierarchyRecheckedOnConfirmation() {
        val (feature, gateway, _, _) = setup()
        val preview = command(feature, "retablir", mapOf("membre" to "12345678901234567", "motif" to "Appel accepté"))
        val id = preview.sent.single().first.buttons.first().id
        gateway.target = gateway.target.copy(actorCanInteract = false)
        val rejected = press(feature, id)
        assertEquals(ModerationTexts.CANNOT_ACT, rejected.sent.single().first.text)
        assertTrue(gateway.timeouts.isEmpty())
        assertEquals(2, gateway.checks)
    }

    @Test
    @DisplayName("Un avertissement non remis est un échec journalisé, jamais un faux succès")
    fun failedWarningIsNotReportedAsSuccess() {
        val (feature, gateway, store, _) = setup()
        gateway.warnOk = false
        val preview = command(feature, "avertir", mapOf("membre" to "12345678901234567", "motif" to "Règle enfreinte"))
        val id = preview.sent.single().first.buttons.first().id
        val result = press(feature, id)
        assertTrue(result.sent.single().first.text.contains("non remis"))
        assertEquals(ModerationStatus.FAILED, store.rows.values.single().status)
        assertEquals(1, gateway.warnings.size)
        assertFalse(store.rows.values.single().status == ModerationStatus.SUCCEEDED)
    }
}
