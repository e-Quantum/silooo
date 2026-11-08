package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TicketArchiveSqlTest {
    private val at = Instant.parse("2026-11-08T10:00:00Z")
    private val store get() = JdbcTicketArchiveStore(MariaDbSupport.database)
    private val tickets get() = JdbcTicketStore(MariaDbSupport.database)
    @BeforeEach fun prepare() = MariaDbSupport.prepare()

    @Test fun `concurrent close requests reserve one archive and one original actor`() = runBlocking {
        val id = tickets.open("42", "achat", "9001", at)
        val jobs = coroutineScope { (1..8).map { n -> async(Dispatchers.IO) { store.begin(id, n.toString(), "9002", at) } }.map { it.await() } }
        assertEquals(1, jobs.map { it.id }.distinct().size)
        assertEquals(1, jobs.map { it.requestedBy }.distinct().size)
        assertEquals(1L, MariaDbSupport.count("discord_ticket_archives"))
        assertEquals(jobs.first(), JdbcTicketArchiveStore(MariaDbSupport.database).find(id))
    }

    @Test fun `expired worker cannot append or unlock a new workers lease`() = runBlocking {
        val id = tickets.open("42", "compte", "9001", at)
        val job = store.begin(id, "77", "9002", at)
        val firstToken = UUID.randomUUID().toString()
        val first = store.acquire(job.id, firstToken, at, at + Duration.ofMinutes(1))!!
        assertNull(store.acquire(job.id, UUID.randomUUID().toString(), at, at + Duration.ofMinutes(1)))
        val secondToken = UUID.randomUUID().toString()
        val second = store.acquire(job.id, secondToken, at + Duration.ofMinutes(2), at + Duration.ofMinutes(3))!!
        assertFalse(store.append(first, "texte ancien", "100", "200", false))
        store.release(job.id, firstToken)
        assertEquals(secondToken, store.find(id)?.leaseToken)
        assertTrue(store.append(second, "texte courant", "100", "200", false))
        assertFalse(store.append(second, "double", "100", "200", false))
        assertEquals(1L, MariaDbSupport.count("discord_ticket_archive_parts"))
        assertEquals("texte courant", store.nextPart(job.id)?.body)
    }

    @Test fun `delivery state survives new store and deletion is forbidden while an upload is unacknowledged`() = runBlocking {
        val id = tickets.open("42", "achat", "9001", at)
        val job = store.begin(id, "77", "9002", at)
        var current = store.acquire(job.id, UUID.randomUUID().toString(), at, at + Duration.ofMinutes(5))!!
        assertTrue(store.append(current, "texte é𝄞", "100", "200", true))
        current = store.find(id)!!
        assertThrows(IllegalStateException::class.java) { runBlocking { store.deleting(current) } }
        assertEquals(TicketArchiveState.DELIVERING, store.find(id)?.state)
        assertTrue(store.sending(current, 0, "300"))
        val restarted = JdbcTicketArchiveStore(MariaDbSupport.database)
        assertEquals("texte é𝄞", restarted.nextPart(job.id)?.body)
        assertEquals("300", restarted.nextPart(job.id)?.anchorId)
        assertTrue(restarted.nextPart(job.id)!!.sending)
        current = restarted.find(id)!!
        assertTrue(restarted.delivered(current, 0, "301"))
        assertTrue(restarted.deleting(restarted.find(id)!!))
        assertTrue(restarted.finish(restarted.find(id)!!, at + Duration.ofSeconds(1)))
        val done = restarted.find(id)!!
        assertTrue(restarted.purge(done))
        assertNull(restarted.nextPart(job.id, undeliveredOnly = false)?.body)
        assertTrue(restarted.removePart(restarted.find(id)!!, 0))
        assertTrue(restarted.remove(restarted.find(id)!!))
        assertNull(restarted.find(id))
    }

    @Test fun `full SQL closure and scheduled retention use a disposable MariaDB`() = runBlocking {
        val server = FakeDiscordServer()
        val timer = FakeTimer(at)
        val category = server.createCategory(CategorySpec("aide", null))
        val journal = server.createChannel(category, ChannelSpec("journal", ChannelKind.TEXTE, null, emptySet(), emptySet(), false))
        val source = server.createChannel(category, ChannelSpec("ticket", ChannelKind.TEXTE, null, null, emptySet(), false, setOf("42")))
        val id = tickets.open("42", "achat", source, at)
        server.transcripts[source] = (1..250).map { TranscriptMessage((1000 + it).toString(), "42", "Ana", "message-$it", at) }.toMutableList()
        val context = BotContext(server, ServerIds(emptyMap(), mapOf(ServerPlan.JOURNAL_ID to journal)), timer, ServerPhase.ATTENTE)
        val rules = TicketArchiveRules(100, 8, 3, 30, 300, 30, 10, 4_000_000)
        val worker = TicketArchiver(context, tickets, store, rules)
        assertTrue(worker.request(tickets.byId(id)!!, "77"))
        assertEquals("77", tickets.byId(id)?.closedBy)
        assertEquals(3L, MariaDbSupport.count("discord_ticket_archive_parts"))
        assertEquals(3, server.posted.count { it.message.document != null })
        val restarted = TicketArchiver(context, JdbcTicketStore(MariaDbSupport.database), JdbcTicketArchiveStore(MariaDbSupport.database), rules)
        restarted.tick()
        assertEquals(3, server.posted.count { it.message.document != null })
        timer.advance(Duration.ofDays(31))
        restarted.tick()
        assertEquals(0L, MariaDbSupport.count("discord_ticket_archives"))
        assertEquals(0L, MariaDbSupport.count("discord_ticket_archive_parts"))
        assertEquals(3, server.deletedMessages.size)
    }
}
