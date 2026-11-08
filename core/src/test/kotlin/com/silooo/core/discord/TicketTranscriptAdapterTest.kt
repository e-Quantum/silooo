package com.silooo.core.discord

import com.silooo.core.discord.jda.JdaMessages
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TicketTranscriptAdapterTest {
    @Test fun `transcript is uploaded as a plain text file without mentions`() {
        val file = TextFile("ticket-test.txt", "é𝄞".toByteArray(Charsets.UTF_8))
        val data = JdaMessages.create(BotMessage("Archive <@42> @everyone", document = file))
        assertEquals("ticket-test.txt", data.files.single().name)
        assertTrue(data.toData().getObject("allowed_mentions").getArray("parse").isEmpty)
        assertEquals("Archive <@42> @everyone", data.content)
        data.close()
    }
    @Test fun `documents reject executable names and incompatible card layout`() {
        assertThrows(IllegalArgumentException::class.java) { TextFile("archive.html", byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { BotMessage("texte", document = TextFile("a.txt", byteArrayOf(1)), card = true) }
        assertEquals(24, archiveNonce("ticket-archive:42:1").length)
        assertEquals(archiveNonce("ticket-archive:42:1"), archiveNonce("ticket-archive:42:1"))
        assertNotEquals(archiveNonce("ticket-archive:42:1"), archiveNonce("ticket-archive:42:2"))
    }
}
