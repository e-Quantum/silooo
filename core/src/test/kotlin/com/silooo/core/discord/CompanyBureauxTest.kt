package com.silooo.core.discord

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CompanyBureauxTest {
    @Test fun bureauRemainsPublicWithStaffAndCurrentMembersAndIsReused() = runBlocking {
        val server = FakeDiscordServer()
        val staff = server.createRole(RoleSpec("Staff", null, false, false))
        val mod = server.createRole(RoleSpec("Modération", null, false, false))
        val context = BotContext(server, ServerIds(mapOf("staff" to staff, "moderation" to mod), emptyMap()), FakeTimer(), ServerPhase.BETA)
        val first = ensureCompanyBureau(context, 7, "Acier", null, setOf("99", "100"))
        assertTrue(server.publicChannelAccess(first).view)
        assertEquals(setOf(staff, mod), server.channelSpecs.getValue(first).writers)
        assertEquals(emptySet<String>(), server.channelSpecs.getValue(first).memberViewers)
        assertEquals(first, ensureCompanyBureau(context, 7, "Acier", first, setOf("100")))
        assertEquals(setOf("100"), server.bureauWriters[first])
        assertEquals(1, server.channels.size)
        assertEquals(1, server.categories.size)
    }
    @Test fun waitingPhaseDoesNotCreateBureaux() = runBlocking {
        val server = FakeDiscordServer()
        val context = BotContext(server, ServerIds.NONE, FakeTimer(), ServerPhase.ATTENTE)
        try { ensureCompanyBureau(context, 7, "Acier", null, emptySet()); fail<Unit>("Bureau créé avant la bêta") }
        catch (_: DiscordRefusal) { }
        assertTrue(server.channels.isEmpty())
    }
}
