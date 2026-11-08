package com.silooo.core.discord

import com.silooo.core.TestDefinitions
import com.silooo.core.db.MariaDbSupport as Db
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Assemblage de production et vraie base ; aucun message ni sanction sur le serveur réel. */
class ManagementJourneySqlTest {
    @BeforeEach fun prepare() = Db.prepare()

    @Test fun moderatorConfirmsOnlyOwnActionAndHandlesPrivateReportedMessage() = runBlocking {
        val actor = "76543210987654321"
        val target = "12345678901234567"
        val reporter = "11111111111111111"
        val fake = FakeDiscordServer()
        val plan = PlanApplier(ServerPlan.fromClasspath(), fake, ServerPhase.ATTENTE, false).run()
        assertTrue(plan.failures.isEmpty())
        val mod = plan.ids.role("moderation")!!
        fake.memberRoles[actor] = mutableSetOf(mod)
        var calls = 0
        val server = object : DiscordServer by fake {
            override suspend fun moderationTarget(actorId: String, targetId: String, allowedRoleIds: Set<String>): ModerationTarget? =
                ModerationTarget(targetId, "Membre", false, false, fake.rolesOf(actorId).any { it in allowedRoleIds }, true)
            override suspend fun timeoutMember(actorId: String, targetId: String, until: Instant?, reason: String, allowedRoleIds: Set<String>) {
                if (fake.rolesOf(actorId).none { it in allowedRoleIds }) throw DiscordRefusal("Droit retiré")
                calls++
            }
            override suspend fun canReadChannel(channelId: String, userId: String) =
                (userId == reporter && channelId == plan.ids.channel("discussion")) ||
                    (userId == actor && fake.rolesOf(userId).contains(mod))
        }
        val timer = FakeTimer(Instant.parse("2026-11-07T10:00:00Z"))
        val features = BotFeatures.build(TestDefinitions.directory, Db.database, onlineCount = { 0 })(
            BotContext(server, plan.ids, timer, ServerPhase.ATTENTE)).filter { it.name in setOf("gestion", "moderation", "signalements") }
        assertEquals(3, features.size)
        features.forEach { it.start() }
        suspend fun handle(event: DiscordEvent) { features.forEach { it.handle(event) } }
        val user = DiscordUser(actor, "Modo")
        val preview = FakeReply()
        handle(DiscordEvent.CommandUsed("timeout", mapOf("membre" to target, "duree" to "1h", "motif" to "Perturbation"), user, plan.ids.channel("discussion"), preview))
        assertEquals(0, calls)
        val button = preview.sent.single().first.buttons.first().id
        val event = DiscordEvent.ButtonPressed(button, user, plan.ids.channel("discussion"), "preview", FakeReply())
        handle(event); handle(event)
        assertEquals(1, calls)
        assertEquals("succeeded", Db.string("select status from discord_moderation_cases"))
        val channel = plan.ids.channel("discussion")!!
        fake.messagesById[channel to "origin"] = ChannelMessage(target, "Membre", "Message à vérifier", timer.now())
        val form = FakeReply()
        handle(DiscordEvent.MessageReported(DiscordUser(reporter, "Témoin"), channel, "origin", form))
        val submitted = FakeReply()
        handle(DiscordEvent.FormSubmitted(form.forms.single().id, mapOf("reason" to "Propos à vérifier"), DiscordUser(reporter, "Témoin"), channel, submitted))
        assertEquals(1L, Db.count("discord_message_reports"))
        assertEquals("publie", Db.string("select delivery_state from discord_message_reports"))
        val destination = plan.ids.channel("signalements")!!
        val cardId = Db.string("select post_message_id from discord_message_reports")!!
        val full = fake.edits.last { it.channelId == destination }.message
        fake.messagesById[destination to cardId] = ChannelMessage("bot", "Oooscar", full.text, timer.now(), bot = true)
        val reportId = Db.long("select id from discord_message_reports")
        handle(DiscordEvent.ButtonPressed(SignalementFeature.ACTION_PREFIX + reportId + ":resolved", user, destination, cardId, FakeReply()))
        assertEquals("resolu", Db.string("select state from discord_message_reports"))
        assertEquals(actor, Db.string("select closed_by from discord_message_reports"))
        assertEquals(1L, JdbcSignalementStore(Db.database).count(timer.now().minusSeconds(1), timer.now().plusSeconds(1)))
    }
}
