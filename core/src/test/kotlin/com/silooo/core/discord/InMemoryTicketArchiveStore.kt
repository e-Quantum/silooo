package com.silooo.core.discord

import java.time.Instant
import java.util.UUID

class InMemoryTicketArchiveStore : TicketArchiveStore {
    val jobs = linkedMapOf<String, TicketArchive>()
    val parts = linkedMapOf<Pair<String, Int>, TicketArchivePart>()
    override suspend fun begin(ticketId: Int, by: String, channelId: String, at: Instant): TicketArchive =
        find(ticketId) ?: TicketArchive(UUID.randomUUID().toString(), ticketId, by, at, channelId).also { jobs[it.id] = it }
    override suspend fun find(ticketId: Int) = jobs.values.firstOrNull { it.ticketId == ticketId }
    override suspend fun pending(limit: Int, expiredBefore: Instant) = jobs.values.filter {
        it.state != TicketArchiveState.DONE || (it.finishedAt != null && it.finishedAt <= expiredBefore)
    }.sortedWith(compareBy<TicketArchive> { it.requestedAt }.thenBy { it.id }).take(limit)
    override suspend fun acquire(id: String, token: String, now: Instant, until: Instant): TicketArchive? {
        val j = jobs[id] ?: return null
        if (j.leaseToken != null && j.leaseUntil != null && j.leaseUntil > now) return null
        return j.copy(leaseToken = token, leaseUntil = until).also { jobs[id] = it }
    }
    override suspend fun release(id: String, token: String) { jobs[id]?.takeIf { it.leaseToken == token }?.let { jobs[id] = it.copy(leaseToken = null, leaseUntil = null) } }
    private fun change(job: TicketArchive, state: TicketArchiveState?, clear: Boolean = true, update: () -> Unit): Boolean {
        val current = jobs[job.id] ?: return false
        if (job.leaseToken == null || current.leaseToken != job.leaseToken || current.revision != job.revision || (state != null && current.state != state)) return false
        update()
        jobs[job.id]?.let { jobs[job.id] = it.copy(revision = it.revision + 1, lastError = if (clear) null else it.lastError) }
        return true
    }
    override suspend fun append(job: TicketArchive, body: String?, nextBefore: String?, headId: String?, complete: Boolean) = change(job, TicketArchiveState.COLLECTING) {
        if (body != null) { check(job.id to job.nextPart !in parts); parts[job.id to job.nextPart] = TicketArchivePart(job.id, job.nextPart, body) }
        jobs[job.id] = job.copy(beforeId = nextBefore, headId = headId, nextPart = job.nextPart + if (body == null) 0 else 1,
            state = if (complete) TicketArchiveState.DELIVERING else TicketArchiveState.COLLECTING)
    }
    override suspend fun nextPart(id: String, undeliveredOnly: Boolean) = parts.values.filter { it.archiveId == id && (!undeliveredOnly || it.messageId == null) }.maxByOrNull { it.number }
    override suspend fun sending(job: TicketArchive, part: Int, anchorId: String?) = change(job, TicketArchiveState.DELIVERING) {
        val p = parts.getValue(job.id to part); check(!p.sending && p.messageId == null)
        parts[job.id to part] = p.copy(sending = true, anchorId = anchorId)
    }
    override suspend fun delivered(job: TicketArchive, part: Int, messageId: String) = change(job, TicketArchiveState.DELIVERING) {
        val p = parts.getValue(job.id to part); check(p.sending && p.messageId == null)
        parts[job.id to part] = p.copy(messageId = messageId)
    }
    override suspend fun deleting(job: TicketArchive) = change(job, TicketArchiveState.DELIVERING) {
        val all = parts.values.filter { it.archiveId == job.id }; check(all.isNotEmpty() && all.all { it.messageId != null })
        jobs[job.id] = job.copy(state = TicketArchiveState.DELETING)
    }
    override suspend fun collectNew(job: TicketArchive) = change(job, TicketArchiveState.DELETING) {
        jobs[job.id] = job.copy(state = TicketArchiveState.COLLECTING, beforeId = null, afterId = job.headId)
    }
    override suspend fun finish(job: TicketArchive, at: Instant) = change(job, TicketArchiveState.DELETING) { jobs[job.id] = job.copy(state = TicketArchiveState.DONE, finishedAt = at) }
    override suspend fun purge(job: TicketArchive) = change(job, TicketArchiveState.DONE) {
        parts.keys.filter { it.first == job.id }.forEach { key -> parts[key] = parts.getValue(key).copy(body = null) }
        jobs[job.id] = job.copy(state = TicketArchiveState.PURGING)
    }
    override suspend fun removePart(job: TicketArchive, part: Int) = change(job, TicketArchiveState.PURGING) { check(parts.remove(job.id to part) != null) }
    override suspend fun remove(job: TicketArchive) = change(job, TicketArchiveState.PURGING) { check(parts.keys.none { it.first == job.id }); jobs.remove(job.id) }
    override suspend fun failure(job: TicketArchive, code: String) = change(job, null, clear = false) { jobs[job.id] = job.copy(lastError = code) }
}
