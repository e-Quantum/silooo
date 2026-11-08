package com.silooo.core.discord

import com.silooo.core.db.Database
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class TicketArchiveState(val value: String) {
    COLLECTING("collecting"), DELIVERING("delivering"), DELETING("deleting"), DONE("done"), PURGING("purging");
    companion object { fun read(value: String) = entries.single { it.value == value } }
}

data class TicketArchive(
    val id: String, val ticketId: Int, val requestedBy: String, val requestedAt: Instant,
    val channelId: String, val state: TicketArchiveState = TicketArchiveState.COLLECTING,
    val beforeId: String? = null, val afterId: String? = null, val headId: String? = null,
    val nextPart: Int = 0, val revision: Long = 0, val leaseToken: String? = null,
    val leaseUntil: Instant? = null, val finishedAt: Instant? = null, val lastError: String? = null,
)

data class TicketArchivePart(
    val archiveId: String, val number: Int, val body: String?, val sending: Boolean = false,
    val anchorId: String? = null, val messageId: String? = null,
) {
    val reference: String get() = "ticket-archive:" + archiveId + ":" + number
    val fileName: String get() = "ticket-" + archiveId + "-" + number + ".txt"
}

/** Chaque modification vérifie le bail ET la révision : un ancien processus ne peut plus valider son travail. */
interface TicketArchiveStore {
    suspend fun begin(ticketId: Int, by: String, channelId: String, at: Instant): TicketArchive
    suspend fun find(ticketId: Int): TicketArchive?
    suspend fun pending(limit: Int, expiredBefore: Instant): List<TicketArchive>
    suspend fun acquire(id: String, token: String, now: Instant, until: Instant): TicketArchive?
    suspend fun release(id: String, token: String)
    suspend fun append(job: TicketArchive, body: String?, nextBefore: String?, headId: String?, complete: Boolean): Boolean
    suspend fun nextPart(id: String, undeliveredOnly: Boolean = true): TicketArchivePart?
    suspend fun sending(job: TicketArchive, part: Int, anchorId: String?): Boolean
    suspend fun delivered(job: TicketArchive, part: Int, messageId: String): Boolean
    suspend fun deleting(job: TicketArchive): Boolean
    suspend fun collectNew(job: TicketArchive): Boolean
    suspend fun finish(job: TicketArchive, at: Instant): Boolean
    suspend fun purge(job: TicketArchive): Boolean
    suspend fun removePart(job: TicketArchive, part: Int): Boolean
    suspend fun remove(job: TicketArchive): Boolean
    suspend fun failure(job: TicketArchive, code: String): Boolean
}

class JdbcTicketArchiveStore(private val database: Database) : TicketArchiveStore {
    override suspend fun begin(ticketId: Int, by: String, channelId: String, at: Instant): TicketArchive = database.transaction { c ->
        c.prepareStatement("insert into discord_ticket_archives (archive_id, ticket_id, requested_by, requested_at, channel_id, state) values (?, ?, ?, ?, ?, 'collecting') on duplicate key update ticket_id = ticket_id").use {
            it.setString(1, UUID.randomUUID().toString()); it.setInt(2, ticketId); it.setString(3, by)
            it.setObject(4, utc(at)); it.setString(5, channelId); it.executeUpdate()
        }
        checkNotNull(find(c, "ticket_id = ?") { it.setInt(1, ticketId) })
    }
    override suspend fun find(ticketId: Int): TicketArchive? = database.transaction { c -> find(c, "ticket_id = ?") { it.setInt(1, ticketId) } }
    override suspend fun pending(limit: Int, expiredBefore: Instant): List<TicketArchive> = database.transaction { c ->
        c.prepareStatement(SELECT + "where state <> 'done' or finished_at <= ? order by requested_at, archive_id limit ?").use {
            it.setObject(1, utc(expiredBefore)); it.setInt(2, limit)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
        }
    }
    override suspend fun acquire(id: String, token: String, now: Instant, until: Instant): TicketArchive? = database.transaction { c ->
        val count = c.prepareStatement("update discord_ticket_archives set lease_token = ?, lease_until = ? where archive_id = ? and (lease_token is null or lease_until <= ?)").use {
            it.setString(1, token); it.setObject(2, utc(until)); it.setString(3, id); it.setObject(4, utc(now)); it.executeUpdate()
        }
        if (count == 1) find(c, "archive_id = ?") { it.setString(1, id) } else null
    }
    override suspend fun release(id: String, token: String) { database.transaction { c ->
        c.prepareStatement("update discord_ticket_archives set lease_token = null, lease_until = null where archive_id = ? and lease_token = ?").use {
            it.setString(1, id); it.setString(2, token); it.executeUpdate()
        }
    } }
    override suspend fun append(job: TicketArchive, body: String?, nextBefore: String?, headId: String?, complete: Boolean): Boolean = change(job, TicketArchiveState.COLLECTING) { c ->
        if (body != null) c.prepareStatement("insert into discord_ticket_archive_parts (archive_id, part_number, body) values (?, ?, ?)").use {
            it.setString(1, job.id); it.setInt(2, job.nextPart); it.setString(3, body); it.executeUpdate()
        }
        c.prepareStatement("update discord_ticket_archives set before_id = ?, head_id = ?, next_part = ?, state = ? where archive_id = ?").use {
            it.setString(1, nextBefore); it.setString(2, headId); it.setInt(3, job.nextPart + if (body == null) 0 else 1)
            it.setString(4, if (complete) TicketArchiveState.DELIVERING.value else job.state.value); it.setString(5, job.id); it.executeUpdate()
        }
    }
    override suspend fun nextPart(id: String, undeliveredOnly: Boolean): TicketArchivePart? = database.transaction { c ->
        c.prepareStatement(PART_SELECT + "where archive_id = ? " + (if (undeliveredOnly) "and message_id is null " else "") + "order by part_number desc limit 1").use {
            it.setString(1, id); it.executeQuery().use { rows -> if (rows.next()) part(rows) else null }
        }
    }
    override suspend fun sending(job: TicketArchive, part: Int, anchorId: String?): Boolean = change(job, TicketArchiveState.DELIVERING) { c ->
        c.prepareStatement("update discord_ticket_archive_parts set sending = true, anchor_id = ? where archive_id = ? and part_number = ? and sending = false and message_id is null").use {
            it.setString(1, anchorId); it.setString(2, job.id); it.setInt(3, part); check(it.executeUpdate() == 1)
        }
    }
    override suspend fun delivered(job: TicketArchive, part: Int, messageId: String): Boolean = change(job, TicketArchiveState.DELIVERING) { c ->
        c.prepareStatement("update discord_ticket_archive_parts set message_id = ? where archive_id = ? and part_number = ? and sending = true and message_id is null").use {
            it.setString(1, messageId); it.setString(2, job.id); it.setInt(3, part); check(it.executeUpdate() == 1)
        }
    }
    override suspend fun deleting(job: TicketArchive): Boolean = change(job, TicketArchiveState.DELIVERING) { c ->
        c.prepareStatement("select count(*), sum(case when message_id is null then 1 else 0 end) from discord_ticket_archive_parts where archive_id = ?").use {
            it.setString(1, job.id); it.executeQuery().use { rows -> rows.next(); check(rows.getInt(1) > 0 && rows.getInt(2) == 0) }
        }
        state(c, job.id, TicketArchiveState.DELETING)
    }
    override suspend fun collectNew(job: TicketArchive): Boolean = change(job, TicketArchiveState.DELETING) { c ->
        c.prepareStatement("update discord_ticket_archives set state = 'collecting', before_id = null, after_id = head_id where archive_id = ?").use {
            it.setString(1, job.id); it.executeUpdate()
        }
    }
    override suspend fun finish(job: TicketArchive, at: Instant): Boolean = change(job, TicketArchiveState.DELETING) { c ->
        c.prepareStatement("update discord_ticket_archives set state = 'done', finished_at = ? where archive_id = ?").use {
            it.setObject(1, utc(at)); it.setString(2, job.id); it.executeUpdate()
        }
    }
    override suspend fun purge(job: TicketArchive): Boolean = change(job, TicketArchiveState.DONE) { c ->
        c.prepareStatement("update discord_ticket_archive_parts set body = null where archive_id = ?").use { it.setString(1, job.id); it.executeUpdate() }
        state(c, job.id, TicketArchiveState.PURGING)
    }
    override suspend fun removePart(job: TicketArchive, part: Int): Boolean = change(job, TicketArchiveState.PURGING) { c ->
        c.prepareStatement("delete from discord_ticket_archive_parts where archive_id = ? and part_number = ?").use {
            it.setString(1, job.id); it.setInt(2, part); check(it.executeUpdate() == 1)
        }
    }
    override suspend fun remove(job: TicketArchive): Boolean = change(job, TicketArchiveState.PURGING) { c ->
        c.prepareStatement("select count(*) from discord_ticket_archive_parts where archive_id = ?").use {
            it.setString(1, job.id); it.executeQuery().use { rows -> rows.next(); check(rows.getInt(1) == 0) }
        }
        c.prepareStatement("delete from discord_ticket_archives where archive_id = ?").use { it.setString(1, job.id); it.executeUpdate() }
    }
    override suspend fun failure(job: TicketArchive, code: String): Boolean = change(job, null, clearError = false) { c ->
        require(code.matches(Regex("[a-z-]{1,64}")))
        c.prepareStatement("update discord_ticket_archives set last_error = ? where archive_id = ?").use { it.setString(1, code); it.setString(2, job.id); it.executeUpdate() }
    }
    private suspend fun change(job: TicketArchive, expected: TicketArchiveState?, clearError: Boolean = true, block: (Connection) -> Unit): Boolean = database.transaction { c ->
        if (job.leaseToken == null) return@transaction false
        val locked = find(c, "archive_id = ? for update") { it.setString(1, job.id) } ?: return@transaction false
        if (locked.leaseToken != job.leaseToken || locked.revision != job.revision || (expected != null && locked.state != expected)) return@transaction false
        block(c)
        c.prepareStatement("update discord_ticket_archives set revision = revision + 1" + (if (clearError) ", last_error = null" else "") + " where archive_id = ?").use {
            it.setString(1, job.id); it.executeUpdate()
        }
        true
    }
    private fun state(c: Connection, id: String, state: TicketArchiveState) {
        c.prepareStatement("update discord_ticket_archives set state = ? where archive_id = ?").use { it.setString(1, state.value); it.setString(2, id); it.executeUpdate() }
    }
    private fun find(c: Connection, where: String, bind: (PreparedStatement) -> Unit): TicketArchive? =
        c.prepareStatement(SELECT + "where " + where).use { bind(it); it.executeQuery().use { rows -> if (rows.next()) read(rows) else null } }
    private fun read(r: ResultSet) = TicketArchive(
        r.getString("archive_id"), r.getInt("ticket_id"), r.getString("requested_by"), instant(r, "requested_at")!!,
        r.getString("channel_id"), TicketArchiveState.read(r.getString("state")), r.getString("before_id"), r.getString("after_id"),
        r.getString("head_id"), r.getInt("next_part"), r.getLong("revision"), r.getString("lease_token"),
        instant(r, "lease_until"), instant(r, "finished_at"), r.getString("last_error"),
    )
    private fun part(r: ResultSet) = TicketArchivePart(r.getString(1), r.getInt(2), r.getString(3), r.getBoolean(4), r.getString(5), r.getString(6))
    private fun instant(r: ResultSet, column: String) = r.getObject(column, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
    private fun utc(at: Instant) = LocalDateTime.ofInstant(at, ZoneOffset.UTC)
    private companion object {
        const val SELECT = "select * from discord_ticket_archives "
        const val PART_SELECT = "select archive_id, part_number, body, sending, anchor_id, message_id from discord_ticket_archive_parts "
    }
}
