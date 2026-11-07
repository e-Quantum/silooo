package com.silooo.core.discord

import com.silooo.core.db.Database
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

data class Signalement(
    val id: Long,
    val reporterId: String,
    val channelId: String,
    val messageId: String,
    val reason: String,
    val state: String,
    val assignedTo: String?,
    val createdAt: Instant,
    val postChannelId: String? = null,
    val postMessageId: String? = null,
    val deliveryState: String = "reserve",
    val lastActionBy: String? = null,
    val closedBy: String? = null,
)

/** Persistance des signalements. Les contenus gardés sont limités aux messages explicitement signalés. */
interface SignalementStore {
    /** Réserve durablement le signalement avant toute publication ; null si doublon ou cooldown. */
    suspend fun create(reporterId: String, channelId: String, messageId: String, reason: String, at: Instant, cooldown: java.time.Duration): Signalement?
    suspend fun setState(id: Long, state: String, staffId: String, at: Instant): Boolean
    suspend fun posted(id: Long, channelId: String, messageId: String)
    suspend fun publicationFailed(id: Long)
    suspend fun expired(before: Instant): List<Signalement>
    suspend fun purge(id: Long, before: Instant): Int
    suspend fun count(from: Instant, until: Instant): Long
}

class JdbcSignalementStore(private val database: Database) : SignalementStore {
    override suspend fun create(reporterId: String, channelId: String, messageId: String, reason: String, at: Instant, cooldown: java.time.Duration): Signalement? =
        database.transaction { c ->
            c.prepareStatement("insert ignore into discord_message_report_cooldowns (reporter_id, last_reported_at) values (?, ?)").use {
                it.setString(1, reporterId); it.setObject(2, utc(Instant.EPOCH)); it.executeUpdate()
            }
            c.prepareStatement("select last_reported_at from discord_message_report_cooldowns where reporter_id = ? for update").use {
                it.setString(1, reporterId)
                it.executeQuery().use { rows ->
                    rows.next()
                    val last = rows.getObject(1, LocalDateTime::class.java).toInstant(ZoneOffset.UTC)
                    if (last != Instant.EPOCH && at.isBefore(last.plus(cooldown))) return@transaction null
                }
            }
            c.prepareStatement("select id from discord_message_reports where reporter_id = ? and message_id = ? limit 1").use {
                it.setString(1, reporterId); it.setString(2, messageId)
                it.executeQuery().use { rows -> if (rows.next()) return@transaction null }
            }
            c.prepareStatement("insert into discord_message_reports (reporter_id, channel_id, message_id, reason, state, created_at, updated_at) values (?, ?, ?, ?, 'nouveau', ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS).use {
                it.setString(1, reporterId); it.setString(2, channelId); it.setString(3, messageId); it.setString(4, reason)
                it.setObject(5, utc(at)); it.setObject(6, utc(at)); it.executeUpdate()
                val created = it.generatedKeys.use { keys -> keys.next(); Signalement(keys.getLong(1), reporterId, channelId, messageId, reason, "nouveau", null, at) }
                c.prepareStatement("update discord_message_report_cooldowns set last_reported_at = ? where reporter_id = ?").use {
                    it.setObject(1, utc(at)); it.setString(2, reporterId); it.executeUpdate()
                }
                created
            }
        }

    override suspend fun setState(id: Long, state: String, staffId: String, at: Instant): Boolean = database.transaction { c ->
        require(state in setOf("pris", "resolu", "sans-suite"))
        val allowed = if (state == "pris") "state = 'nouveau'" else "state in ('nouveau', 'pris')"
        c.prepareStatement("update discord_message_reports set state = ?, assigned_to = case when ? = 'pris' then ? else assigned_to end, last_action_by = ?, closed_by = case when ? in ('resolu', 'sans-suite') then ? else closed_by end, updated_at = ? where id = ? and $allowed").use {
            it.setString(1, state); it.setString(2, state); it.setString(3, staffId); it.setString(4, staffId); it.setString(5, state); it.setString(6, staffId); it.setObject(7, utc(at)); it.setLong(8, id); it.executeUpdate() == 1
        }
    }

    override suspend fun posted(id: Long, channelId: String, messageId: String) { database.transaction { c ->
        c.prepareStatement("update discord_message_reports set post_channel_id = ?, post_message_id = ?, delivery_state = 'publie' where id = ?").use {
            it.setString(1, channelId); it.setString(2, messageId); it.setLong(3, id); it.executeUpdate()
        }
    } }

    override suspend fun publicationFailed(id: Long) { database.transaction { c ->
        c.prepareStatement("update discord_message_reports set delivery_state = 'echec' where id = ? and delivery_state = 'reserve'").use {
            it.setLong(1, id); it.executeUpdate()
        }
    } }

    override suspend fun expired(before: Instant): List<Signalement> = database.transaction { c ->
        c.prepareStatement(SELECT + "where created_at < ? order by id").use {
            it.setObject(1, utc(before)); it.executeQuery().use { rows -> generateSequence { if (rows.next()) read(rows) else null }.toList() }
        }
    }

    override suspend fun purge(id: Long, before: Instant): Int = database.transaction { c ->
        val reporter = c.prepareStatement("select reporter_id from discord_message_reports where id = ?").use {
            it.setLong(1, id); it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        } ?: return@transaction 0
        val deleted = c.prepareStatement("delete from discord_message_reports where id = ?").use { it.setLong(1, id); it.executeUpdate() }
        c.prepareStatement("select reporter_id from discord_message_report_cooldowns where reporter_id = ? for update").use { it.setString(1, reporter); it.executeQuery().use { rows -> rows.next() } }
        c.prepareStatement("delete from discord_message_report_cooldowns where reporter_id = ? and last_reported_at < ? and not exists (select 1 from discord_message_reports where reporter_id = ?)").use {
            it.setString(1, reporter); it.setObject(2, utc(before)); it.setString(3, reporter); it.executeUpdate()
        }
        deleted
    }

    override suspend fun count(from: Instant, until: Instant): Long = database.transaction { c ->
        c.prepareStatement("select count(*) from discord_message_reports where created_at >= ? and created_at < ?").use {
            it.setObject(1, utc(from)); it.setObject(2, utc(until)); it.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }
    }

    private fun utc(value: Instant) = LocalDateTime.ofInstant(value, ZoneOffset.UTC)

    private fun read(rows: java.sql.ResultSet) = Signalement(
        rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5),
        rows.getString(6), rows.getString(7), rows.getObject(8, LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
        rows.getString(9), rows.getString(10), rows.getString(11), rows.getString(12), rows.getString(13),
    )

    private companion object {
        const val SELECT = "select id, reporter_id, channel_id, message_id, reason, state, assigned_to, created_at, post_channel_id, post_message_id, delivery_state, last_action_by, closed_by from discord_message_reports "
    }
}
