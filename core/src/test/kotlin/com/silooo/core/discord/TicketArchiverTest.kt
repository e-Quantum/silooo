package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TicketArchiverTest {
    private val rules = TicketArchiveRules(100, 8, 3, 30, 300, 30, 10, 4_000_000)
    private val now = Instant.parse("2026-11-08T10:00:00Z")
    private inner class Setup(count: Int = 1) {
        val server = FakeDiscordServer()
        val tickets = InMemoryTicketStore()
        val archives = InMemoryTicketArchiveStore()
        val timer = FakeTimer(now)
        val category = runBlocking { server.createCategory(CategorySpec("aide", null)) }
        val journal = runBlocking { server.createChannel(category, ChannelSpec("journal", ChannelKind.TEXTE, null, emptySet(), emptySet(), false)) }
        val source = runBlocking { server.createChannel(category, ChannelSpec("ticket", ChannelKind.TEXTE, null, null, emptySet(), false, setOf("42"))) }
        val ticket = runBlocking { tickets.open("42", "achat", source, now); tickets.byId(1)!! }
        init { server.transcripts[source] = (1..count).map { message(it) }.toMutableList() }
        fun message(n: Int) = TranscriptMessage((1000 + n).toString(), "42", "Ana", "texte-$n", now + Duration.ofSeconds(n.toLong()))
        fun context(gateway: DiscordServer = server) = BotContext(gateway, ServerIds(emptyMap(), mapOf(ServerPlan.JOURNAL_ID to journal)), timer, ServerPhase.ATTENTE)
        fun worker(gateway: DiscordServer = server, storage: TicketArchiveStore = archives, config: TicketArchiveRules = rules) = TicketArchiver(context(gateway), tickets, storage, config)
        fun exports() = server.posted.filter { it.message.document != null }
        fun contents() = exports().map { it.message.document!!.bytes.toString(Charsets.UTF_8) }
        suspend fun drain(worker: TicketArchiver = worker()) { repeat(40) { if (archives.find(1)?.state == TicketArchiveState.DONE) return; worker.tick() }; fail<Unit>("La fermeture ne se termine pas") }
    }

    @Test fun `more than 200 messages are preserved once with attachment and embed references`() = runBlocking {
        val s = Setup(251)
        s.server.transcripts.getValue(s.source)[0] = s.message(1).copy(text = "é𝄞".repeat(1500),
            attachments = listOf(TranscriptAttachment("99", "preuve.png", "https://cdn.discordapp.com/attachments/99/preuve.png", 125, "image/png")),
            embeds = listOf("{\"title\":\"Une carte\"}"), editedAt = now + Duration.ofSeconds(2))
        assertTrue(s.worker().request(s.ticket, "77"))
        assertEquals(3, s.exports().size)
        val all = s.contents().joinToString("\n")
        for (i in 1..251) assertEquals(1, Regex("\\| message " + (1000 + i) + "(?: \\||\\n)").findAll(all).count())
        assertTrue(all.contains("é𝄞".repeat(1500)))
        assertTrue(all.contains("preuve.png | 99 | 125 octets | image/png"))
        assertTrue(all.contains("https://cdn.discordapp.com/attachments/99/preuve.png"))
        assertTrue(all.contains("{\"title\":\"Une carte\"}"))
        assertEquals(listOf(s.source), s.server.deleted)
        assertEquals("77", s.tickets.byId(1)?.closedBy)
        assertTrue(s.server.transcriptReads.all { it.third <= 100 })
    }

    @Test fun `large history progresses across bounded ticks and process restarts`() = runBlocking {
        val s = Setup(1001)
        assertFalse(s.worker().request(s.ticket, "42"))
        assertEquals(8, s.server.transcriptReads.count { it.first == s.source })
        assertNull(s.tickets.byId(1)?.closedAt)
        assertTrue(s.server.deleted.isEmpty())
        s.drain(s.worker())
        assertEquals(11, s.exports().size)
        assertEquals(1001, Regex("\\| message [0-9]+").findAll(s.contents().joinToString("\n")).count())
    }

    @Test fun `lost post response reconciles the existing upload without reposting`() = runBlocking {
        val s = Setup()
        var calls = 0
        val unreliable = object : DiscordServer by s.server {
            override suspend fun post(channelId: String, message: BotMessage): String {
                calls++
                s.server.post(channelId, message)
                throw DiscordRefusal("réponse perdue")
            }
        }
        assertFalse(s.worker(unreliable).request(s.ticket, "42"))
        assertEquals(1, s.exports().size)
        assertNull(s.tickets.byId(1)?.closedAt)
        s.drain(s.worker(unreliable))
        assertEquals(1, calls)
        assertEquals(1, s.exports().size)
    }

    @Test fun `upload acknowledged before database failure is recovered by its stable reference`() = runBlocking {
        val s = Setup()
        var fail = true
        val storage = object : TicketArchiveStore by s.archives {
            override suspend fun delivered(job: TicketArchive, part: Int, messageId: String): Boolean {
                if (fail) { fail = false; throw IllegalStateException("base indisponible") }
                return s.archives.delivered(job, part, messageId)
            }
        }
        assertFalse(s.worker(storage = storage).request(s.ticket, "42"))
        assertEquals(1, s.exports().size)
        s.drain(s.worker(storage = storage))
        assertEquals(1, s.exports().size)
    }

    @Test fun `capture commit followed by a lost response does not read or store that page twice`() = runBlocking {
        val s = Setup(250)
        var fail = true
        val storage = object : TicketArchiveStore by s.archives {
            override suspend fun append(job: TicketArchive, body: String?, nextBefore: String?, headId: String?, complete: Boolean): Boolean {
                val done = s.archives.append(job, body, nextBefore, headId, complete)
                if (fail) { fail = false; throw IllegalStateException("réponse perdue") }
                return done
            }
        }
        assertFalse(s.worker(storage = storage).request(s.ticket, "42"))
        s.drain(s.worker(storage = storage))
        assertEquals(1, s.server.transcriptReads.count { it.first == s.source && it.second == null && it.third == 100 })
        assertEquals(3, s.exports().size)
        assertEquals(250, Regex("\\| message [0-9]+").findAll(s.contents().joinToString("\n")).count())
    }

    @Test fun `failed channel deletion resumes after the ticket has been closed in SQL`() = runBlocking {
        val s = Setup()
        var fail = true
        val gateway = object : DiscordServer by s.server {
            override suspend fun removeArchivedTicketChannel(channelId: String) {
                if (fail) { fail = false; throw DiscordRefusal("suppression refusée") }
                s.server.removeArchivedTicketChannel(channelId)
            }
        }
        assertFalse(s.worker(gateway).request(s.ticket, "42"))
        assertNotNull(s.tickets.byId(1)?.closedAt)
        assertEquals(TicketArchiveState.DELETING, s.archives.find(1)?.state)
        assertTrue(s.server.deleted.isEmpty())
        s.drain(s.worker(gateway))
        assertEquals(1, s.exports().size)
        assertEquals(listOf(s.source), s.server.deleted)
    }

    @Test fun `lost channel deletion acknowledgement is safe after restart`() = runBlocking {
        val s = Setup()
        val gateway = object : DiscordServer by s.server {
            override suspend fun removeArchivedTicketChannel(channelId: String) {
                s.server.removeArchivedTicketChannel(channelId)
                throw DiscordRefusal("réponse perdue")
            }
        }
        assertFalse(s.worker(gateway).request(s.ticket, "42"))
        s.drain(s.worker())
        assertEquals(listOf(s.source), s.server.deleted)
        assertEquals(TicketArchiveState.DONE, s.archives.find(1)?.state)
    }

    @Test fun `messages arriving during export are added before source deletion`() = runBlocking {
        val s = Setup(2)
        var append = true
        val gateway = object : DiscordServer by s.server {
            override suspend fun post(channelId: String, message: BotMessage): String {
                val id = s.server.post(channelId, message)
                if (append) { append = false; s.server.transcripts.getValue(s.source).add(s.message(3)) }
                return id
            }
        }
        s.worker(gateway).request(s.ticket, "42")
        s.drain(s.worker(gateway))
        val all = s.contents().joinToString("\n")
        for (i in 1..3) assertEquals(1, Regex("\\| message " + (1000 + i) + "\\n").findAll(all).count())
        assertEquals(2, s.exports().size)
        assertEquals(listOf(s.source), s.server.deleted)
    }

    @Test fun `public archive destination blocks reading and exporting private data`() = runBlocking {
        val s = Setup()
        s.server.publicAccessOverrides[s.journal] = PublicChannelAccess(true, true, false)
        assertFalse(s.worker().request(s.ticket, "42"))
        assertTrue(s.server.transcriptReads.isEmpty())
        assertTrue(s.exports().isEmpty())
        assertTrue(s.server.deleted.isEmpty())
        assertEquals("destination-not-private", s.archives.find(1)?.lastError)
        s.server.publicAccessOverrides.clear()
        s.drain()
    }

    @Test fun `incomplete lookup never resends or deletes the ticket`() = runBlocking {
        val s = Setup()
        s.server.archiveLookupIncomplete = true
        assertFalse(s.worker().request(s.ticket, "42"))
        assertTrue(s.exports().isEmpty())
        assertNull(s.tickets.byId(1)?.closedAt)
        assertEquals("archive-delivery-uncertain", s.archives.find(1)?.lastError)
        s.server.archiveLookupIncomplete = false
        s.drain()
        assertEquals(1, s.exports().size)
    }

    @Test fun `oversized part is never truncated and keeps its source`() = runBlocking {
        val s = Setup()
        s.server.transcripts.getValue(s.source)[0] = s.message(1).copy(text = "x".repeat(2000))
        assertFalse(s.worker(config = rules.copy(maxPartBytes = 1024)).request(s.ticket, "42"))
        assertEquals("archive-part-too-large", s.archives.find(1)?.lastError)
        assertTrue(s.exports().isEmpty())
        assertTrue(s.server.deleted.isEmpty())
        s.drain()
        assertTrue(s.contents().single().contains("x".repeat(2000)))
    }

    @Test fun `invalid history cursor stops instead of silently omitting older messages`() = runBlocking {
        val s = Setup(150)
        val gateway = object : DiscordServer by s.server {
            override suspend fun transcriptPage(channelId: String, beforeId: String?, limit: Int) = s.server.transcriptPage(channelId, null, limit)
        }
        assertFalse(s.worker(gateway).request(s.ticket, "42"))
        assertEquals("invalid-history-cursor", s.archives.find(1)?.lastError)
        assertTrue(s.server.deleted.isEmpty())
        s.drain()
        assertEquals(150, Regex("\\| message [0-9]+").findAll(s.contents().joinToString("\n")).count())
    }

    @Test fun `retention clears SQL content first and resumes deletion of Discord copies`() = runBlocking {
        val s = Setup(101)
        assertTrue(s.worker().request(s.ticket, "42"))
        s.timer.advance(Duration.ofDays(31))
        val refused = object : DiscordServer by s.server {
            override suspend fun deleteMessage(channelId: String, messageId: String) { throw DiscordRefusal("purge refusée") }
        }
        s.worker(refused).tick()
        assertEquals(TicketArchiveState.PURGING, s.archives.find(1)?.state)
        assertTrue(s.archives.parts.values.all { it.body == null })
        s.worker().tick()
        assertNull(s.archives.find(1))
        assertTrue(s.archives.parts.isEmpty())
        assertEquals(2, s.server.deletedMessages.size)
        assertEquals(listOf(s.source), s.server.deleted)
    }

    @Test fun `cancellation preserves durable progress and releases the lease`() = runBlocking {
        val s = Setup()
        val gateway = object : DiscordServer by s.server {
            override suspend fun transcriptPage(channelId: String, beforeId: String?, limit: Int): TranscriptPage { throw CancellationException("arrêt") }
        }
        assertThrows(CancellationException::class.java) { runBlocking { s.worker(gateway).request(s.ticket, "42") } }
        assertNull(s.archives.find(1)?.leaseToken)
        assertTrue(s.server.deleted.isEmpty())
        s.drain()
    }
    @Test fun `ticket is made read only before capture and denied locking keeps it intact`() = runBlocking {
        val s = Setup()
        val denied = object : DiscordServer by s.server {
            override suspend fun freezeTicketForArchive(channelId: String) { throw DiscordRefusal("verrouillage refusé") }
        }
        assertFalse(s.worker(denied).request(s.ticket, "42"))
        assertTrue(s.server.transcriptReads.isEmpty())
        assertTrue(s.exports().isEmpty())
        assertTrue(s.server.deleted.isEmpty())
        s.drain()
        assertTrue(s.source in s.server.frozenTickets)
    }

    @Test fun `a worker whose lease was replaced cannot close SQL or delete the source`() = runBlocking {
        val s = Setup()
        val replacement = java.util.UUID.randomUUID().toString()
        var replace = true
        val gateway = object : DiscordServer by s.server {
            override suspend fun transcriptPage(channelId: String, beforeId: String?, limit: Int): TranscriptPage {
                val page = s.server.transcriptPage(channelId, beforeId, limit)
                if (channelId == s.source && limit == 1 && replace) {
                    replace = false
                    val job = s.archives.find(1)!!
                    s.archives.acquire(job.id, replacement, now + Duration.ofSeconds(301), now + Duration.ofSeconds(601))
                }
                return page
            }
        }
        assertFalse(s.worker(gateway).request(s.ticket, "42"))
        assertNull(s.tickets.byId(1)?.closedAt)
        assertTrue(s.server.deleted.isEmpty())
        assertEquals(replacement, s.archives.find(1)?.leaseToken)
        s.timer.advance(Duration.ofSeconds(602))
        s.drain()
    }

    @Test fun `actual coroutine cancellation releases the lease without waiting for its expiry`() = runBlocking {
        val s = Setup()
        val entered = CompletableDeferred<Unit>()
        val gateway = object : DiscordServer by s.server {
            override suspend fun transcriptPage(channelId: String, beforeId: String?, limit: Int): TranscriptPage {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val running = launch { s.worker(gateway).request(s.ticket, "42") }
        entered.await()
        running.cancelAndJoin()
        assertNull(s.archives.find(1)?.leaseToken)
        assertTrue(s.server.deleted.isEmpty())
        s.drain()
    }

    @Test fun `a child thread prevents deleting data outside the captured history`() = runBlocking {
        val s = Setup()
        s.server.ticketThreads += s.source
        assertFalse(s.worker().request(s.ticket, "42"))
        assertEquals("source-has-threads", s.archives.find(1)?.lastError)
        assertTrue(s.exports().isEmpty())
        assertNull(s.tickets.byId(1)?.closedAt)
        assertTrue(s.server.deleted.isEmpty())
        s.server.ticketThreads.clear()
        s.drain()
    }

}
