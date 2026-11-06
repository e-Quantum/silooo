package com.silooo.core.discord

import com.silooo.core.db.Database
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

enum class ReminderCategory(val column: String) {
    SILO("silo"), VOTE("vote"), VOTE_STREAK("vote_streak"), ORDER_DONE("order_done"),
    COMPANY_INVITE("company_invite"), PRESTIGE("prestige"), GEMS("gems");

    companion object { fun from(value: String): ReminderCategory? = entries.firstOrNull { it.name.equals(value, true) || it.column == value } }
}

data class ReminderPreferences(
    val userId: String,
    val timeZone: String? = null,
    val enabled: Set<ReminderCategory> = emptySet(),
)

data class ReminderDelivery(
    val id: String,
    val userId: String,
    val category: ReminderCategory,
    val message: String,
    val occurredAt: Instant,
    val proofXuid: String,
    val proofLinkedAt: Instant,
)
data class ReminderSourceEvent(val id: Long, val xuid: String?, val kind: String, val payload: String, val occurredAt: Instant)

/** Persistent opt-in and delivery claims. A delivery id is the source event's stable id, never a generated retry id. */
interface ReminderStore {
    suspend fun preferences(userId: String): ReminderPreferences?
    suspend fun set(userId: String, category: ReminderCategory?, enabled: Boolean, zone: String?, at: Instant)
    suspend fun enqueue(delivery: ReminderDelivery): Boolean
    suspend fun cursor(): Long
    suspend fun eventsAfter(id: Long, limit: Int = 250): List<ReminderSourceEvent>
    suspend fun linkedRecipients(xuid: String?, category: ReminderCategory): List<Pair<String, String>>
    suspend fun advanceCursor(id: Long)
    suspend fun due(now: Instant, defaultZone: ZoneId, rules: ReminderRules): List<ReminderDelivery>
    suspend fun claim(deliveryId: String, now: Instant, defaultZone: ZoneId, rules: ReminderRules): Boolean
    suspend fun finish(deliveryId: String, sent: Boolean, failure: String?, now: Instant)
}

/** MariaDB storage for preferences, a durable delivery ledger and the three-per-local-day budget. */
class JdbcReminderStore(private val database: Database) : ReminderStore {
    override suspend fun preferences(userId: String): ReminderPreferences? = database.transaction { c ->
        c.prepareStatement("select time_zone, silo, vote, vote_streak, order_done, company_invite, prestige, gems from discord_reminder_preferences where discord_user_id = ?").use {
            it.setString(1, userId)
            it.executeQuery().use { rs ->
                if (!rs.next()) null else {
                    val enabled = ReminderCategory.entries.filterIndexed { index, _ -> rs.getBoolean(index + 2) }.toSet()
                    ReminderPreferences(userId, rs.getString(1), enabled)
                }
            }
        }
    }

    override suspend fun set(userId: String, category: ReminderCategory?, enabled: Boolean, zone: String?, at: Instant) {
        database.transaction { c ->
            c.prepareStatement("insert ignore into discord_reminder_preferences (discord_user_id, updated_at) values (?, ?)").use {
                it.setString(1, userId); it.setObject(2, utc(at)); it.executeUpdate()
            }
            if (category != null) c.prepareStatement("update discord_reminder_preferences set ${category.column} = ?, updated_at = ? where discord_user_id = ?").use {
                it.setBoolean(1, enabled); it.setObject(2, utc(at)); it.setString(3, userId); it.executeUpdate()
            }
            if (zone != null) c.prepareStatement("update discord_reminder_preferences set time_zone = ?, updated_at = ? where discord_user_id = ?").use {
                it.setString(1, zone); it.setObject(2, utc(at)); it.setString(3, userId); it.executeUpdate()
            }
        }
    }

    override suspend fun enqueue(delivery: ReminderDelivery): Boolean = database.transaction { c ->
        c.prepareStatement("insert ignore into discord_reminder_deliveries (delivery_id, discord_user_id, category, proof_xuid, proof_linked_at, message, occurred_at) values (?, ?, ?, ?, ?, ?, ?)").use {
            it.setString(1, delivery.id); it.setString(2, delivery.userId); it.setString(3, delivery.category.column)
            it.setString(4, delivery.proofXuid); it.setObject(5, utc(delivery.proofLinkedAt)); it.setString(6, delivery.message)
            it.setObject(7, utc(delivery.occurredAt)); it.executeUpdate() == 1
        }
    }

    override suspend fun cursor(): Long = database.transaction { c ->
        c.prepareStatement("select last_event_id from discord_reminder_cursor where id = 1").use { it.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L } }
    }

    override suspend fun eventsAfter(id: Long, limit: Int): List<ReminderSourceEvent> = database.transaction { c ->
        c.prepareStatement("select id, xuid, kind, payload, created_at from game_events where id > ? order by id limit ?").use {
            it.setLong(1, id); it.setInt(2, limit); it.executeQuery().use { rs -> buildList {
                while (rs.next()) add(ReminderSourceEvent(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), instant(rs.getObject(5, LocalDateTime::class.java))))
            } }
        }
    }

    /** Only currently proven game links are candidates; callers revalidate through GameDiscordLinks before reading preferences. */
    override suspend fun linkedRecipients(xuid: String?, category: ReminderCategory): List<Pair<String, String>> = database.transaction { c ->
        val sql = "select g.discord_user_id, cast(g.player_xuid as char) from discord_game_links g " +
            "join site_registrations s on s.gamertag_key = g.gamertag_key and s.discord_user_id = g.discord_user_id " +
            "where 1 = 1" + if (xuid == null) "" else " and g.player_xuid = ?"
        c.prepareStatement(sql).use {
            if (xuid != null) it.setString(1, xuid)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
        }
    }

    override suspend fun advanceCursor(id: Long) {
        database.transaction { c -> c.prepareStatement(
            "insert into discord_reminder_cursor (id, last_event_id) values (1, ?) " +
                "on duplicate key update last_event_id = greatest(last_event_id, values(last_event_id))",
        ).use { it.setLong(1, id); it.executeUpdate() } }
    }

    override suspend fun due(now: Instant, defaultZone: ZoneId, rules: ReminderRules): List<ReminderDelivery> = database.transaction { c ->
        c.prepareStatement("select d.delivery_id, d.discord_user_id, d.category, d.message, d.occurred_at, d.proof_xuid, d.proof_linked_at, p.time_zone from discord_reminder_deliveries d join discord_reminder_preferences p on p.discord_user_id = d.discord_user_id where d.status = 'pending' and d.occurred_at <= ? order by (d.category = 'gems') desc, d.occurred_at, d.delivery_id limit 500").use {
            it.setObject(1, utc(now)); it.executeQuery().use { rs -> buildList {
                while (rs.next()) {
                    val cat = ReminderCategory.from(rs.getString(3)) ?: continue
                    val local = now.atZone(rs.getString(8)?.let { zone -> runCatching { ZoneId.of(zone) }.getOrNull() } ?: defaultZone)
                    val quiet = local.hour >= rules.nightStartHour || local.hour < rules.nightEndHour
                    if (quiet && cat != ReminderCategory.GEMS) continue
                    add(ReminderDelivery(
                        rs.getString(1), rs.getString(2), cat, rs.getString(4), instant(rs.getObject(5, LocalDateTime::class.java)),
                        rs.getString(6), instant(rs.getObject(7, LocalDateTime::class.java)),
                    ))
                }
            } }
        }
    }

    override suspend fun claim(deliveryId: String, now: Instant, defaultZone: ZoneId, rules: ReminderRules): Boolean = database.transaction { c ->
        val record = c.prepareStatement("select d.discord_user_id, d.category, p.time_zone from discord_reminder_deliveries d join discord_reminder_preferences p on p.discord_user_id = d.discord_user_id where d.delivery_id = ? and d.status = 'pending' for update").use {
            it.setString(1, deliveryId); it.executeQuery().use { rs -> if (rs.next()) Triple(rs.getString(1), ReminderCategory.from(rs.getString(2)), rs.getString(3)) else null }
        } ?: return@transaction false
        val (userId, category, zoneText) = record
        if (category == null) return@transaction false
        // Serialize different deliveries for one member before checking the shared daily budget.
        c.prepareStatement("select discord_user_id from discord_reminder_preferences where discord_user_id = ? for update").use {
            it.setString(1, userId); it.executeQuery().use { rs -> if (!rs.next()) return@transaction false }
        }
        val enabled = c.prepareStatement("select ${category.column} from discord_reminder_preferences where discord_user_id = ?").use {
            it.setString(1, userId); it.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
        if (!enabled) {
            c.prepareStatement("update discord_reminder_deliveries set status = 'failed', failure = 'opt-out', finished_at = ? where delivery_id = ? and status = 'pending'").use { it.setObject(1, utc(now)); it.setString(2, deliveryId); it.executeUpdate() }
            return@transaction false
        }
        val zone = zoneText?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: defaultZone
        val local = now.atZone(zone)
        val quiet = local.hour >= rules.nightStartHour || local.hour < rules.nightEndHour
        if (category != ReminderCategory.GEMS && quiet) return@transaction false
        val day: LocalDate = local.toLocalDate()
        val count = c.prepareStatement("select count(*) from discord_reminder_deliveries where discord_user_id = ? and attempted_at >= ? and attempted_at < ?").use {
            it.setString(1, userId); it.setObject(2, utc(day.atStartOfDay(zone).toInstant())); it.setObject(3, utc(day.plusDays(1).atStartOfDay(zone).toInstant()))
            it.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
        if (count >= rules.perDay) {
            c.prepareStatement("update discord_reminder_deliveries set status = 'failed', failure = 'daily-limit', finished_at = ? where delivery_id = ? and status = 'pending'").use { it.setObject(1, utc(now)); it.setString(2, deliveryId); it.executeUpdate() }
            return@transaction false
        }
        c.prepareStatement("update discord_reminder_deliveries set status = 'claimed', attempted_at = ? where delivery_id = ? and status = 'pending'").use {
            it.setObject(1, utc(now)); it.setString(2, deliveryId); it.executeUpdate() == 1
        }
    }

    override suspend fun finish(deliveryId: String, sent: Boolean, failure: String?, now: Instant) {
        database.transaction { c -> c.prepareStatement("update discord_reminder_deliveries set status = ?, failure = ?, finished_at = ? where delivery_id = ? and status in ('pending','claimed')").use {
            it.setString(1, if (sent) "sent" else "failed"); it.setString(2, failure?.take(255)); it.setObject(3, utc(now)); it.setString(4, deliveryId); it.executeUpdate()
        } }
    }

    private fun utc(value: Instant) = LocalDateTime.ofInstant(value, ZoneOffset.UTC)
    private fun instant(value: LocalDateTime) = value.toInstant(ZoneOffset.UTC)
}
