package com.silooo.core.discord

import com.silooo.core.discord.jda.authorizedModerator
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ModerationAuthorizationTest {
    @Test fun nativePowerAloneCannotBypassAssignedStaffRoleAndHierarchy() {
        val allowed = setOf("staff", "mod")
        assertTrue(authorizedModerator(setOf("mod"), allowed, true, true, false))
        assertFalse(authorizedModerator(setOf("admin"), allowed, true, true, false))
        assertFalse(authorizedModerator(setOf("mod"), emptySet(), true, true, false))
        assertFalse(authorizedModerator(setOf("mod"), allowed, false, true, false))
        assertFalse(authorizedModerator(setOf("mod"), allowed, true, false, false))
        assertFalse(authorizedModerator(setOf("mod"), allowed, true, true, true))
    }

    @Test fun closedServerHoldsMemberSanctionsOutsideStaff() = runBlocking {
        val fake = FakeDiscordServer()
        var calls = 0
        val delegate = object : DiscordServer by fake {
            override suspend fun timeoutMember(actorId: String, targetId: String, until: Instant?, reason: String, allowedRoleIds: Set<String>) { calls++ }
        }
        val closed = ClosedServer(delegate, setOf("staff"))
        try { closed.timeoutMember("actor", "target", null, "Test", setOf("staff")); fail<Unit>("Action sur un membre dans le QG fermé") }
        catch (_: DiscordRefusal) { }
        assertEquals(0, calls)
        assertEquals(1, closed.held)
        fake.memberRoles["target"] = mutableSetOf("staff")
        closed.timeoutMember("actor", "target", null, "Test", setOf("staff"))
        assertEquals(1, calls)
    }

    @Test fun managementPanelUsesCurrentRolesAndHidesInaccessibleStaffChannels() = runBlocking {
        val fake = FakeDiscordServer()
        val server = object : DiscordServer by fake {
            override suspend fun canReadChannel(channelId: String, userId: String) = channelId == "journal"
        }
        val context = BotContext(server, ServerIds(mapOf("staff" to "staff-role", "moderation" to "mod-role"),
            mapOf("journal-du-bot" to "journal", "signalements" to "secret")), FakeTimer(), ServerPhase.ATTENTE)
        val feature = ManagementFeature(context)
        val forgedSnapshot = DiscordUser("member", "Membre", roleIds = setOf("staff-role"))
        val denied = FakeReply()
        feature.handle(DiscordEvent.CommandUsed("gestion", emptyMap(), forgedSnapshot, "chat", denied))
        assertTrue(denied.sent.single().first.text.contains("réservée"))
        fake.memberRoles["member"] = mutableSetOf("mod-role")
        val reply = FakeReply()
        feature.handle(DiscordEvent.CommandUsed("gestion", emptyMap(), DiscordUser("member", "Mod"), "chat", reply))
        val (card, private) = reply.sent.single()
        assertTrue(private)
        assertTrue(card.text.contains("/timeout"))
        assertFalse(card.text.contains("/annonce"))
        assertFalse(card.text.contains("<#secret>"))
    }
}
