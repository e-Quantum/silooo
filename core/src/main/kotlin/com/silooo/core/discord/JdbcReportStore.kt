package com.silooo.core.discord

import com.silooo.core.db.Database
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class JdbcReportStore(private val database: Database) : ReportStore {
    override suspend fun beginCapture(at: Instant): Instant = database.transaction { connection ->
        connection.prepareStatement("insert ignore into discord_report_capture (id, started_at) values (1, ?)").use {
            it.setObject(1, utc(at))
            it.executeUpdate()
        }
        connection.prepareStatement("select started_at from discord_report_capture where id = 1").use {
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.getObject(1, LocalDateTime::class.java).toInstant(ZoneOffset.UTC)
            }
        }
    }

    override suspend fun joined(userId: String, at: Instant, returnAfterDays: Int) = event("join", userId, at)
    override suspend fun left(userId: String, at: Instant) = event("leave", userId, at)
    override suspend fun onboardingCompleted(userId: String, at: Instant) = event("onboard", userId, at)
    override suspend fun tutorialDmFailed(userId: String, at: Instant) = event("dm_failed", userId, at)
    override suspend fun message(messageId: String, userId: String, channelId: String, at: Instant) =
        event("message", userId, at, messageId)

    private suspend fun event(kind: String, userId: String, at: Instant, id: String = userId + ":" + at.toEpochMilli()) {
        database.transaction { connection ->
            connection.prepareStatement(
                "insert ignore into discord_report_events (kind, event_id, user_id, occurred_at) values (?, ?, ?, ?)",
            ).use {
                it.setString(1, kind)
                it.setString(2, id)
                it.setString(3, userId)
                it.setObject(4, utc(at))
                it.executeUpdate()
            }
        }
    }

    override suspend fun counts(
        activityFrom: Instant,
        activityUntil: Instant,
        returnAfterDays: Int,
        zone: ZoneId,
    ): ReportCounts = database.transaction { connection ->
        val totals = mutableMapOf<String, Long>()
        val days = mutableMapOf<LocalDate, Long>()
        var returns = 0L
        connection.prepareStatement(
            "select e.kind, e.occurred_at, (select max(l.occurred_at) from discord_report_events l " +
                "where e.kind = 'join' and l.user_id = e.user_id and l.kind = 'leave' and l.occurred_at < e.occurred_at " +
                "and not exists (select 1 from discord_report_events j where j.user_id = e.user_id " +
                "and j.kind = 'join' and j.occurred_at > l.occurred_at and j.occurred_at < e.occurred_at)) " +
                "from discord_report_events e where e.occurred_at >= ? and e.occurred_at < ?",
        ).use {
            it.setObject(1, utc(activityFrom))
            it.setObject(2, utc(activityUntil))
            it.executeQuery().use { rows ->
                while (rows.next()) {
                    val kind = rows.getString(1)
                    val at = rows.getObject(2, LocalDateTime::class.java).toInstant(ZoneOffset.UTC)
                    totals[kind] = (totals[kind] ?: 0) + 1
                    if (kind == "message") {
                        val day = at.atZone(zone).toLocalDate()
                        days[day] = (days[day] ?: 0) + 1
                    }
                    if (kind == "join") {
                        val left = rows.getObject(3, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
                        if (left != null && Duration.between(left, at) >= Duration.ofDays(returnAfterDays.toLong())) returns++
                    }
                }
            }
        }
        ReportCounts(totals["join"] ?: 0, totals["onboard"] ?: 0, returns, totals["dm_failed"] ?: 0, days)
    }

    override suspend fun claim(weekStart: LocalDate, at: Instant): Boolean = database.transaction { connection ->
        connection.prepareStatement("insert ignore into discord_report_claims (week_start, claimed_at) values (?, ?)").use {
            it.setObject(1, weekStart)
            it.setObject(2, utc(at))
            it.executeUpdate() == 1
        }
    }

    override suspend fun finished(weekStart: LocalDate, sent: Boolean) {
        database.transaction { connection ->
            connection.prepareStatement("update discord_report_claims set status = ? where week_start = ?").use {
                it.setString(1, if (sent) "sent" else "failed")
                it.setObject(2, weekStart)
                it.executeUpdate()
            }
        }
    }

    private fun utc(at: Instant): LocalDateTime = LocalDateTime.ofInstant(at, ZoneOffset.UTC)
}
