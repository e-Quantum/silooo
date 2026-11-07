package com.silooo.core.discord

import com.silooo.core.db.Database
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

enum class ModerationAction { WARN, TIMEOUT, RESTORE }
enum class ModerationStatus { RESERVED, SUCCEEDED, FAILED }

data class ModerationCase(
    val id: String,
    val actorId: String,
    val targetId: String,
    val action: ModerationAction,
    val reason: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val status: ModerationStatus,
    val timeoutDurationSeconds: Long? = null,
    val failure: String? = null,
)

interface ModerationStore {
    suspend fun reserve(case: ModerationCase): Boolean
    suspend fun get(id: String): ModerationCase?
    /** Atomic state transition ensures an external Discord action can be attempted at most once. */
    suspend fun claim(id: String, actorId: String, now: Instant): ModerationCase?
    suspend fun finish(id: String, succeeded: Boolean, failure: String?, at: Instant)
    suspend fun cancel(id: String, actorId: String, at: Instant): Boolean
    suspend fun purge(before: Instant): Int
    suspend fun history(targetId: String, since: Instant, limit: Int, offset: Int = 0): List<ModerationCase>
}

class JdbcModerationStore(private val database: Database, private val retentionDays: Int) : ModerationStore {
    init { require(retentionDays in 1..3650) }
    override suspend fun reserve(case: ModerationCase): Boolean = database.transaction { c ->
        c.prepareStatement("delete from discord_moderation_cases where created_at < ?").use { it.setObject(1, utc(case.createdAt.minusSeconds(86400L * retentionDays))); it.executeUpdate() }
        c.prepareStatement("insert ignore into discord_moderation_cases (case_id, actor_id, target_id, action, reason, created_at, expires_at, timeout_duration_seconds, status) values (?, ?, ?, ?, ?, ?, ?, ?, 'reserved')").use {
            it.setString(1, case.id); it.setString(2, case.actorId); it.setString(3, case.targetId); it.setString(4, case.action.name.lowercase())
            it.setString(5, case.reason); it.setObject(6, utc(case.createdAt)); it.setObject(7, utc(case.expiresAt)); if (case.timeoutDurationSeconds == null) it.setNull(8, java.sql.Types.BIGINT) else it.setLong(8, case.timeoutDurationSeconds); it.executeUpdate() == 1
        }
    }

    override suspend fun get(id: String): ModerationCase? = database.transaction { c ->
        c.prepareStatement("select case_id, actor_id, target_id, action, reason, created_at, expires_at, timeout_duration_seconds, status, failure from discord_moderation_cases where case_id = ?").use {
            it.setString(1, id); it.executeQuery().use { rs -> if (rs.next()) row(rs) else null }
        }
    }

    override suspend fun claim(id: String, actorId: String, now: Instant): ModerationCase? = database.transaction { c ->
        c.prepareStatement("update discord_moderation_cases set status = 'failed', failure = 'expired', finished_at = ? where case_id = ? and actor_id = ? and status = 'reserved' and attempted_at is null and expires_at <= ?").use {
            it.setObject(1, utc(now)); it.setString(2, id); it.setString(3, actorId); it.setObject(4, utc(now)); it.executeUpdate()
        }
        val updated = c.prepareStatement("update discord_moderation_cases set attempted_at = ? where case_id = ? and actor_id = ? and status = 'reserved' and attempted_at is null and expires_at > ?").use {
            it.setObject(1, utc(now)); it.setString(2, id); it.setString(3, actorId); it.setObject(4, utc(now)); it.executeUpdate()
        }
        if (updated != 1) null else c.prepareStatement("select case_id, actor_id, target_id, action, reason, created_at, expires_at, timeout_duration_seconds, status, failure from discord_moderation_cases where case_id = ?").use {
            it.setString(1, id); it.executeQuery().use { rs -> if (rs.next()) row(rs) else null }
        }
    }

    override suspend fun finish(id: String, succeeded: Boolean, failure: String?, at: Instant) {
        database.transaction { c -> c.prepareStatement("update discord_moderation_cases set status = ?, failure = ?, finished_at = ? where case_id = ? and status = 'reserved' and attempted_at is not null").use {
            it.setString(1, if (succeeded) "succeeded" else "failed"); it.setString(2, failure?.take(500)); it.setObject(3, utc(at)); it.setString(4, id); it.executeUpdate()
        } }
    }

    override suspend fun cancel(id: String, actorId: String, at: Instant): Boolean = database.transaction { c ->
        c.prepareStatement("update discord_moderation_cases set status = 'failed', failure = 'cancelled', finished_at = ? where case_id = ? and actor_id = ? and status = 'reserved' and attempted_at is null").use {
            it.setObject(1, utc(at)); it.setString(2, id); it.setString(3, actorId); it.executeUpdate() == 1
        }
    }

    override suspend fun purge(before: Instant): Int = database.transaction { c ->
        c.prepareStatement("delete from discord_moderation_cases where created_at < ?").use {
            it.setObject(1, utc(before)); it.executeUpdate()
        }
    }

    override suspend fun history(targetId: String, since: Instant, limit: Int, offset: Int): List<ModerationCase> = database.transaction { c ->
        c.prepareStatement("select case_id, actor_id, target_id, action, reason, created_at, expires_at, timeout_duration_seconds, status, failure from discord_moderation_cases where target_id = ? and created_at >= ? order by created_at desc, case_id desc limit ? offset ?").use {
            it.setString(1, targetId); it.setObject(2, utc(since)); it.setInt(3, limit.coerceIn(1, 100)); it.setInt(4, offset.coerceAtLeast(0)); it.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
        }
    }

    private fun row(rs: java.sql.ResultSet) = ModerationCase(
        rs.getString(1), rs.getString(2), rs.getString(3), ModerationAction.valueOf(rs.getString(4).uppercase()), rs.getString(5),
        instant(rs.getObject(6, LocalDateTime::class.java)), instant(rs.getObject(7, LocalDateTime::class.java)),
        ModerationStatus.valueOf(rs.getString(9).uppercase()), rs.getLong(8).let { if (rs.wasNull()) null else it }, rs.getString(10),
    )
    private fun utc(i: Instant) = LocalDateTime.ofInstant(i, ZoneOffset.UTC)
    private fun instant(d: LocalDateTime) = d.toInstant(ZoneOffset.UTC)
}
