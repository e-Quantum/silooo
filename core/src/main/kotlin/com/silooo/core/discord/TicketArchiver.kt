package com.silooo.core.discord

import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** Une page ou un envoi à la fois : mémoire bornée et progression conservée avant chaque effet externe. */
class TicketArchiver(
    private val context: BotContext,
    private val tickets: TicketStore,
    private val archives: TicketArchiveStore,
    private val rules: TicketArchiveRules,
) {
    suspend fun start() {
        context.timer.every(Duration.ofSeconds(rules.retrySeconds)) { tick() }
        tick()
    }

    suspend fun request(ticket: Ticket, by: String): Boolean {
        val destination = context.ids.channel(ServerPlan.JOURNAL_ID) ?: return false
        val job = archives.begin(ticket.id, by, destination, context.timer.now())
        drive(job)
        return archives.find(ticket.id)?.state == TicketArchiveState.DONE
    }

    suspend fun tick() {
        val cutoff = context.timer.now() - Duration.ofDays(rules.retentionDays)
        for (job in archives.pending(rules.jobsPerTick, cutoff)) drive(job)
    }

    private suspend fun drive(candidate: TicketArchive) {
        val token = UUID.randomUUID().toString()
        if (archives.acquire(candidate.id, token, context.timer.now(), context.timer.now() + Duration.ofSeconds(rules.leaseSeconds)) == null) return
        try {
            repeat(rules.stepsPerTick) {
                val job = archives.find(candidate.ticketId) ?: return
                if (job.leaseToken != token) return
                try {
                    if (job.state != TicketArchiveState.PURGING && job.state != TicketArchiveState.DONE) privateDestination(job)
                    val changed = when (job.state) {
                        TicketArchiveState.COLLECTING -> collect(job)
                        TicketArchiveState.DELIVERING -> deliver(job)
                        TicketArchiveState.DELETING -> deleteSource(job)
                        TicketArchiveState.DONE -> {
                            if (job.finishedAt == null || job.finishedAt > context.timer.now() - Duration.ofDays(rules.retentionDays)) return
                            archives.purge(job)
                        }
                        TicketArchiveState.PURGING -> purge(job)
                    }
                    if (!changed) return
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val current = archives.find(candidate.ticketId)
                    if (current?.leaseToken == token) archives.failure(current, when (e) {
                        is ArchiveFailure -> e.code
                        is TicketArchiveBlocked -> e.code
                        else -> "discord-or-storage-refused"
                    })
                    log.warn("Bot Discord, archive du ticket {} : reprise différée.", candidate.ticketId)
                    return
                }
            }
        } finally {
            withContext(NonCancellable) {
                try { archives.release(candidate.id, token) }
                catch (_: Exception) { log.warn("Bot Discord, archive du ticket {} : le bail expirera avant la reprise.", candidate.ticketId) }
            }
        }
    }

    private suspend fun requireLease(job: TicketArchive) {
        val current = archives.find(job.ticketId) ?: throw ArchiveFailure("lease-lost")
        if (current.leaseToken != job.leaseToken || current.revision != job.revision ||
            current.leaseUntil == null || current.leaseUntil <= context.timer.now()) throw ArchiveFailure("lease-lost")
    }

    private suspend fun privateDestination(job: TicketArchive) {
        if (context.ids.channel(ServerPlan.JOURNAL_ID) != job.channelId || context.server.publicChannelAccess(job.channelId).view) {
            throw ArchiveFailure("destination-not-private")
        }
    }

    private suspend fun collect(job: TicketArchive): Boolean {
        val ticket = tickets.byId(job.ticketId) ?: throw ArchiveFailure("ticket-missing")
        if (job.beforeId == null) context.server.freezeTicketForArchive(ticket.channelId)
        val page = context.server.transcriptPage(ticket.channelId, job.beforeId, rules.pageSize).messages
        if (page.size > rules.pageSize || page.zipWithNext().any { (a, b) -> archiveMessageNumber(a.id) >= archiveMessageNumber(b.id) } ||
            (job.beforeId != null && page.any { archiveMessageNumber(it.id) >= archiveMessageNumber(job.beforeId) })) throw ArchiveFailure("invalid-history-cursor")
        val selected = page.filter { job.afterId == null || archiveMessageNumber(it.id) > archiveMessageNumber(job.afterId) }
        val complete = page.size < rules.pageSize || page.any { job.afterId != null && archiveMessageNumber(it.id) <= archiveMessageNumber(job.afterId) }
        val head = if (job.beforeId == null) page.lastOrNull()?.id ?: job.headId else job.headId
        val body = if (selected.isNotEmpty() || job.nextPart == 0) transcriptBody(job, selected) else null
        if (body != null && body.toByteArray(Charsets.UTF_8).size > rules.maxPartBytes) throw ArchiveFailure("archive-part-too-large")
        return archives.append(job, body, page.firstOrNull()?.id ?: job.beforeId, head, complete)
    }

    private suspend fun deliver(job: TicketArchive): Boolean {
        var current = job
        var part = archives.nextPart(job.id) ?: return archives.deleting(job)
        if (!part.sending) {
            val anchor = context.server.transcriptPage(job.channelId, null, 1).messages.lastOrNull()?.id
            if (!archives.sending(job, part.number, anchor)) return false
            current = archives.find(job.ticketId) ?: return false
            if (current.leaseToken != job.leaseToken) return false
            part = archives.nextPart(job.id) ?: return false
        }
        val messageId = when (val found = context.server.findArchivePart(job.channelId, part.reference, part.fileName, part.anchorId, rules.lookupPages)) {
            is ArchiveLookup.Found -> found.messageId
            ArchiveLookup.Incomplete -> throw ArchiveFailure("archive-delivery-uncertain")
            ArchiveLookup.Absent -> {
                requireLease(current)
                val body = part.body ?: throw ArchiveFailure("archive-body-missing")
                context.server.post(job.channelId, BotMessage(
                    TicketTexts.journalClosed(job.ticketId, tickets.byId(job.ticketId)?.kind.orEmpty(),
                        tickets.byId(job.ticketId)?.userId.orEmpty(), job.requestedBy) + "\n" + part.reference,
                    document = TextFile(part.fileName, body.toByteArray(Charsets.UTF_8)), nonce = archiveNonce(part.reference),
                ))
            }
        }
        return archives.delivered(current, part.number, messageId)
    }

    private suspend fun deleteSource(job: TicketArchive): Boolean {
        val ticket = tickets.byId(job.ticketId) ?: throw ArchiveFailure("ticket-missing")
        val latest = try {
            context.server.freezeTicketForArchive(ticket.channelId)
            context.server.transcriptPage(ticket.channelId, null, 1).messages.lastOrNull()?.id
        } catch (_: DiscordChannelMissing) { null }
        // Les nouveaux messages arrivés pendant l'archive doivent eux aussi être conservés avant la suppression.
        if (latest != null && (job.headId == null || archiveMessageNumber(latest) > archiveMessageNumber(job.headId))) return archives.collectNew(job)
        requireLease(job)
        tickets.close(ticket.id, job.requestedBy, context.timer.now())
        requireLease(job)
        context.server.removeArchivedTicketChannel(ticket.channelId)
        return archives.finish(job, context.timer.now())
    }

    private suspend fun purge(job: TicketArchive): Boolean {
        val part = archives.nextPart(job.id, undeliveredOnly = false) ?: return archives.remove(job)
        part.messageId?.let { context.server.deleteMessage(job.channelId, it) }
        return archives.removePart(job, part.number)
    }

    private class ArchiveFailure(val code: String) : Exception()
    private companion object { val log = LoggerFactory.getLogger(TicketArchiver::class.java) }
}
