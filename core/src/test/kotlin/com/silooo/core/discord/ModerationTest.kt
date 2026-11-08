package com.silooo.core.discord

import java.time.Instant
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ModerationTest {
    internal class MemoryStore : ModerationStore {
        val rows = linkedMapOf<String, ModerationCase>()
        val attempted = mutableSetOf<String>()
        var failFinish = false
        var purgeCalls = 0
        override suspend fun reserve(case: ModerationCase) = (rows.putIfAbsent(case.id, case) == null)
        override suspend fun get(id: String) = rows[id]
        override suspend fun claim(id: String, actorId: String, now: Instant): ModerationCase? {
            val old = rows[id] ?: return null
            if (old.actorId != actorId || old.status != ModerationStatus.RESERVED || id in attempted || old.expiresAt <= now) return null
            attempted += id
            return old
        }
        override suspend fun finish(id: String, succeeded: Boolean, failure: String?, at: Instant) {
            if (failFinish) error("test audit failure")
            val old = rows[id] ?: return
            if (old.status == ModerationStatus.RESERVED && id in attempted) rows[id] = old.copy(status = if (succeeded) ModerationStatus.SUCCEEDED else ModerationStatus.FAILED, failure = failure)
        }
        override suspend fun cancel(id: String, actorId: String, at: Instant): Boolean {
            val old = rows[id] ?: return false
            if (old.actorId != actorId || old.status != ModerationStatus.RESERVED || id in attempted) return false
            rows[id] = old.copy(status = ModerationStatus.FAILED, failure = "cancelled"); return true
        }
        override suspend fun purge(before: Instant): Int {
            purgeCalls++
            val expired = rows.values.filter { it.createdAt < before }.map { it.id }
            expired.forEach(rows::remove)
            return expired.size
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
    private data class Setup(val feature: ModerationFeature, val gateway: Gateway, val store: MemoryStore, val server: FakeDiscordServer, val timer: FakeTimer)
    private fun setup(writeJournal: suspend (String) -> Unit = {}): Setup {
        val server = FakeDiscordServer(); val timer = FakeTimer()
        val ids = ServerIds(mapOf("staff" to staff, "moderation" to mod), emptyMap())
        val ctx = BotContext(server, ids, timer, ServerPhase.ATTENTE)
        val store = MemoryStore(); val gateway = Gateway()
        server.memberRoles["76543210987654321"] = mutableSetOf(staff)
        return Setup(ModerationFeature(ctx, ModerationRules(600, 30, 10, 500), store, gateway, BotJournal(ctx), writeJournal), gateway, store, server, timer)
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
        val (feature, _, _, server, _) = setup()
        assertTrue(feature.commands.all { it.staffOnly })
        assertEquals(listOf("avertir", "timeout", "retablir", "expulser", "bannir", "debannir", "dossier"), feature.commands.map { it.name })
        assertEquals(10, ModerationRules(600, 30, 10, 500).historyPageSize)
        server.memberRoles.remove("76543210987654321")
        val denied = command(feature, "dossier", mapOf("membre" to "12345678901234567"), roles = emptySet())
        assertTrue(denied.sent.single().second)
        assertEquals(ModerationTexts.NOT_STAFF, denied.sent.single().first.text)
    }

    @Test
    @DisplayName("Un timeout exige une confirmation par son auteur et ne s'exécute qu'une fois")
    fun timeoutNeedsOwnerConfirmationAndAtMostOnce() {
        val (feature, gateway, store, server, _) = setup()
        server.memberRoles["11111111111111111"] = mutableSetOf(staff)
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
        val (feature, gateway, _, _, _) = setup()
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
        val (feature, gateway, store, _, _) = setup()
        gateway.warnOk = false
        val preview = command(feature, "avertir", mapOf("membre" to "12345678901234567", "motif" to "Règle enfreinte"))
        val id = preview.sent.single().first.buttons.first().id
        val result = press(feature, id)
        assertTrue(result.sent.single().first.text.contains("non remis"))
        assertEquals(ModerationStatus.FAILED, store.rows.values.single().status)
        assertEquals(1, gateway.warnings.size)
        assertFalse(store.rows.values.single().status == ModerationStatus.SUCCEEDED)
    }

    @Test
    @DisplayName("Dossier de dix motifs longs reste sous 2 000 caractères et se parcourt en privé")
    fun longHistoryFitsAndPaginatesPrivately() {
        val (feature, _, store, _, _) = setup()
        val now = Instant.parse("2026-11-05T10:00:00Z")
        repeat(11) { index ->
            val id = java.util.UUID.randomUUID().toString()
            store.rows[id] = ModerationCase(id, "76543210987654321", "12345678901234567", ModerationAction.WARN, "motif-$index " + "x".repeat(490), now, now.plusSeconds(600), ModerationStatus.SUCCEEDED)
        }
        val first = command(feature, "dossier", mapOf("membre" to "12345678901234567"))
        val (message, private) = first.sent.single()
        assertTrue(private)
        assertTrue(message.text.length <= 2000)
        assertEquals(10, message.text.lineSequence().drop(1).count { it.endsWith(" (abrégé)") })
        assertTrue(message.text.contains("Avertissement"))
        assertTrue(message.text.contains("Effectué"))
        val next = message.buttons.single { it.label == "Suivant" }
        val second = press(feature, next.id)
        assertTrue(second.sent.single().second)
        assertTrue(second.sent.single().first.text.startsWith("Dossier de Membre · page 2"))
        assertTrue(second.acknowledged == 1)
    }

    @Test
    @DisplayName("Une panne d'écriture d'audit après l'action ne provoque ni faux refus ni seconde action")
    fun auditFailureDoesNotRetryExternalAction() {
        val (feature, gateway, store, _, _) = setup()
        val preview = command(feature, "timeout", mapOf("membre" to "12345678901234567", "duree" to "1h", "motif" to "Test audit"))
        val id = preview.sent.single().first.buttons.first().id
        store.failFinish = true
        val first = press(feature, id)
        assertTrue(first.sent.single().first.text.contains("l'audit durable n'a pas été confirmé"))
        assertEquals(1, gateway.timeouts.size)
        store.failFinish = false
        val repeated = press(feature, id)
        assertEquals(ModerationTexts.EXPIRED, repeated.sent.single().first.text)
        assertEquals(1, gateway.timeouts.size)
    }

    @Test
    @DisplayName("Une panne du journal ne transforme pas une action enregistrée en refus")
    fun journalFailureDoesNotMisreportAction() {
        val (feature, gateway, store, _, _) = setup { error("test journal failure") }
        val preview = command(feature, "retablir", mapOf("membre" to "12345678901234567", "motif" to "Test journal"))
        val id = preview.sent.single().first.buttons.first().id
        val result = press(feature, id)
        assertEquals(ModerationTexts.DONE, result.sent.single().first.text)
        assertEquals(1, gateway.timeouts.size)
        assertEquals(ModerationStatus.SUCCEEDED, store.rows.values.single().status)
    }


    @Test
    @DisplayName("La purge démarre tout de suite puis suit l'intervalle défini")
    fun purgeStartsImmediatelyAndRepeats() = runBlocking {
        val setup = setup()
        setup.feature.start()
        assertEquals(1, setup.store.purgeCalls)
        setup.timer.advance(Duration.ofSeconds(21_600))
        assertEquals(2, setup.store.purgeCalls)
    }

}
