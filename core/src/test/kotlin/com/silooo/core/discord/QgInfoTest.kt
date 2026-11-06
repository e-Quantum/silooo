package com.silooo.core.discord

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class QgInfoTest {
    @Test
    fun `Les informations du QG retrouvent leurs messages au redemarrage`() = runBlocking {
        val server = FakeDiscordServer()
        val category = server.createCategory(CategorySpec("Infos", null))
        val spec = ChannelSpec("rules", ChannelKind.TEXTE, null, emptySet(), null, false)
        val rules = server.createChannel(category, spec)
        val join = server.createChannel(category, spec.copy(name = "rejoindre"))
        server.rulesChannel = rules
        val context = BotContext(server, ServerIds(emptyMap(), mapOf("rejoindre" to join)), FakeTimer(Instant.EPOCH), ServerPhase.ATTENTE)
        val store = InMemoryBotMessageStore()
        QgInfoFeature(context, store).start()
        QgInfoFeature(context, store).start()
        assertEquals(setOf(rules, join), server.posted.map { it.channelId }.toSet())
        assertEquals(2, server.posted.size)
        assertEquals(2, server.edits.size)
        val instructions = server.posted.single { it.channelId == join }.message.text
        assertTrue(instructions.contains("n'est pas encore ouvert"))
        assertFalse(instructions.contains("play.silooo.com"))
    }

    @Test
    fun `Sans salon officiel le bot ne cree aucun reglement ailleurs`() = runBlocking {
        val server = FakeDiscordServer()
        val context = BotContext(server, ServerIds(emptyMap(), emptyMap()), FakeTimer(Instant.EPOCH), ServerPhase.ATTENTE)
        QgInfoFeature(context, InMemoryBotMessageStore()).start()
        assertTrue(server.posted.isEmpty())
    }
}
