package com.silooo.core.discord

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Le journal du bot : une ligne dans `#journal-du-bot`, rien si le serveur n'a pas le salon, un refus de Discord avalé. */
class BotJournalTest {
    /** Un Discord qui refuse tout message, comme un droit retiré au bot. */
    private class RefusingServer(private val inner: FakeDiscordServer) : DiscordServer by inner {
        var attempts = 0

        override suspend fun post(channelId: String, message: BotMessage): String {
            attempts++
            throw DiscordRefusal("le bot n'a plus le droit d'écrire dans ce salon")
        }

        override suspend fun send(channelId: String, text: String) {
            post(channelId, BotMessage(text))
        }
    }

    private fun context(server: DiscordServer, ids: ServerIds) = BotContext(server, ids, FakeTimer(), ServerPhase.ATTENTE)

    private suspend fun journalChannel(fake: FakeDiscordServer): String {
        val category = fake.createCategory(CategorySpec("Staff", null))
        return fake.createChannel(category, ChannelSpec("journal-du-bot", ChannelKind.TEXTE, null, emptySet(), null, false))
    }

    @Test
    @DisplayName("Une ligne part dans #journal-du-bot, en texte seul, sans mention")
    fun writesALine() = runBlocking {
        val fake = FakeDiscordServer()
        val channel = journalChannel(fake)
        BotJournal(context(fake, ServerIds(emptyMap(), mapOf("journal-du-bot" to channel)))).line("Admission : Kr Test admis par <@42>.")
        val post = fake.posted.single()
        assertEquals(channel, post.channelId)
        assertEquals("Admission : Kr Test admis par <@42>.", post.message.text)
        assertTrue(post.message.mentionUserIds.isEmpty() && post.message.mentionRoleIds.isEmpty() && post.message.buttons.isEmpty())
    }

    @Test
    @DisplayName("Un serveur sans le salon du journal : rien n'est écrit, rien ne casse")
    fun withoutTheChannel() = runBlocking {
        val fake = FakeDiscordServer()
        BotJournal(context(fake, ServerIds.NONE)).line("Admission : Kr Test admis par <@42>.")
        assertTrue(fake.posted.isEmpty())
    }

    @Test
    @DisplayName("Un refus de Discord est avalé : la ligne est perdue, le geste qu'on journalise continue")
    fun swallowsARefusal() = runBlocking {
        val fake = FakeDiscordServer()
        val channel = journalChannel(fake)
        val server = RefusingServer(fake)
        BotJournal(context(server, ServerIds(emptyMap(), mapOf("journal-du-bot" to channel)))).line("Admission : Kr Test refusé par <@42>.")
        assertEquals(1, server.attempts)
        assertTrue(fake.posted.isEmpty())
    }
}
