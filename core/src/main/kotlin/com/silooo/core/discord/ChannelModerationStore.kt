package com.silooo.core.discord

import com.silooo.core.db.Database
import java.sql.Connection
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface ChannelModerationStore {
    suspend fun reserve(case: ChannelModerationCase): Boolean
    suspend fun get(id: String): ChannelModerationCase?
    suspend fun lock(channelId: String): ChannelModerationLock?
    suspend fun claim(id: String, actorId: String, now: Instant, recoverySeconds: Long): ChannelModerationCase?
    suspend fun cancel(id: String, actorId: String, now: Instant): Boolean
    suspend fun finish(id: String, status: ChannelCaseStatus, affected: Int?, now: Instant)
    suspend fun purge(before: Instant): Int
}

/** Les seuls états rejouables sont des lectures. Une tentative externe n'est jamais réémise au démarrage. */
class JdbcChannelModerationStore(private val database: Database) : ChannelModerationStore {
    override suspend fun reserve(case: ChannelModerationCase): Boolean = database.transaction { c ->
        c.prepareStatement("insert ignore into discord_channel_cases (case_id, actor_id, channel_id, reason, created_at, expires_at, plan_json, status) values (?, ?, ?, ?, ?, ?, ?, 'reserved')").use {
            it.setString(1, case.id); it.setString(2, case.actorId); it.setString(3, case.channelId); it.setString(4, case.reason)
            it.setObject(5, utc(case.createdAt)); it.setObject(6, utc(case.expiresAt)); it.setString(7, json.encodeToString(case.plan)); it.executeUpdate() == 1
        }
    }
    override suspend fun get(id: String) = database.transaction { read(it, id) }
    override suspend fun lock(channelId: String) = database.transaction { readLock(it, channelId) }

    override suspend fun claim(id: String, actorId: String, now: Instant, recoverySeconds: Long): ChannelModerationCase? = database.transaction { c ->
        require(recoverySeconds in 60..3600)
        val case = read(c, id, true) ?: return@transaction null
        if (case.actorId != actorId || case.status != ChannelCaseStatus.RESERVED || case.expiresAt <= now) return@transaction null
        when (case.plan.action) {
            ChannelAction.LOCK -> {
                val inserted = c.prepareStatement("insert ignore into discord_channel_locks (channel_id, original_json, locked_json, version, active_case_id, available_after, lock_id) values (?, ?, ?, 1, ?, ?, ?)").use {
                    it.setString(1, case.channelId); it.setString(2, json.encodeToString(case.plan.before.everyone))
                    it.setString(3, json.encodeToString(case.plan.after.everyone)); it.setString(4, id)
                    it.setObject(5, utc(now.plusSeconds(recoverySeconds))); it.setString(6, id); it.executeUpdate()
                }
                if (inserted != 1) return@transaction null
            }
            ChannelAction.UNLOCK -> {
                val lock = readLock(c, case.channelId, true) ?: return@transaction null
                if (lock.id != case.plan.lockId || lock.version != case.plan.lockVersion || lock.availableAfter > now) return@transaction null
                c.prepareStatement("update discord_channel_locks set version = version + 1, active_case_id = ?, available_after = ? where channel_id = ?").use {
                    it.setString(1, id); it.setObject(2, utc(now.plusSeconds(recoverySeconds))); it.setString(3, case.channelId); it.executeUpdate()
                }
            }
            else -> Unit
        }
        c.prepareStatement("update discord_channel_cases set status = 'attempted', attempted_at = ? where case_id = ?").use {
            it.setObject(1, utc(now)); it.setString(2, id); check(it.executeUpdate() == 1)
        }
        case.copy(status = ChannelCaseStatus.ATTEMPTED)
    }

    override suspend fun cancel(id: String, actorId: String, now: Instant) = database.transaction { c ->
        c.prepareStatement("update discord_channel_cases set status = 'failed', finished_at = ? where case_id = ? and actor_id = ? and status = 'reserved'").use {
            it.setObject(1, utc(now)); it.setString(2, id); it.setString(3, actorId); it.executeUpdate() == 1
        }
    }

    override suspend fun finish(id: String, status: ChannelCaseStatus, affected: Int?, now: Instant) {
        require(status in setOf(ChannelCaseStatus.SUCCEEDED, ChannelCaseStatus.FAILED, ChannelCaseStatus.UNCERTAIN))
        database.transaction { c ->
            val case = read(c, id, true) ?: error("Dossier disparu")
            check(case.status == ChannelCaseStatus.ATTEMPTED) { "Tentative déjà terminée" }
            if (case.plan.action in setOf(ChannelAction.LOCK, ChannelAction.UNLOCK)) {
                val delete = (case.plan.action == ChannelAction.LOCK && status == ChannelCaseStatus.FAILED) ||
                    (case.plan.action == ChannelAction.UNLOCK && status == ChannelCaseStatus.SUCCEEDED)
                val query = if (delete) "delete from discord_channel_locks where channel_id = ? and active_case_id = ?"
                    else "update discord_channel_locks set available_after = ? where channel_id = ? and active_case_id = ?"
                c.prepareStatement(query).use {
                    var index = 1
                    if (!delete) {
                        // Un résultat incertain garde la période d'attente initiale ; aucun geste automatique.
                        val lock = readLock(c, case.channelId, true) ?: error("État du verrou disparu")
                        it.setObject(index++, utc(if (status == ChannelCaseStatus.UNCERTAIN) lock.availableAfter else now))
                    }
                    it.setString(index++, case.channelId); it.setString(index, id)
                    check(it.executeUpdate() == 1) { "Une autre opération possède ce verrou" }
                }
            }
            c.prepareStatement("update discord_channel_cases set status = ?, affected = ?, finished_at = ? where case_id = ?").use {
                it.setString(1, status.name.lowercase()); if (affected == null) it.setNull(2, java.sql.Types.INTEGER) else it.setInt(2, affected)
                it.setObject(3, utc(now)); it.setString(4, id); check(it.executeUpdate() == 1)
            }
        }
    }

    override suspend fun purge(before: Instant): Int = database.transaction { c ->
        // Les états de restauration sont indépendants : ils restent jusqu'au déverrouillage explicite.
        c.prepareStatement("delete from discord_channel_cases where created_at < ?").use { it.setObject(1, utc(before)); it.executeUpdate() }
    }
    private fun read(c: Connection, id: String, forUpdate: Boolean = false): ChannelModerationCase? =
        c.prepareStatement("select case_id, actor_id, channel_id, reason, created_at, expires_at, plan_json, status, affected from discord_channel_cases where case_id = ?" + if (forUpdate) " for update" else "").use {
            it.setString(1, id); it.executeQuery().use { rs -> if (!rs.next()) null else ChannelModerationCase(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), instant(rs.getObject(5, LocalDateTime::class.java)),
                instant(rs.getObject(6, LocalDateTime::class.java)), json.decodeFromString<ChannelActionPlan>(rs.getString(7)),
                ChannelCaseStatus.valueOf(rs.getString(8).uppercase()), rs.getInt(9).let { value -> if (rs.wasNull()) null else value },
            ) }
        }
    private fun readLock(c: Connection, channel: String, forUpdate: Boolean = false): ChannelModerationLock? =
        c.prepareStatement("select original_json, locked_json, version, available_after, lock_id from discord_channel_locks where channel_id = ?" + if (forUpdate) " for update" else "").use {
            it.setString(1, channel); it.executeQuery().use { rs -> if (!rs.next()) null else ChannelModerationLock(channel,
                json.decodeFromString<ChannelPermissionValue>(rs.getString(1)), json.decodeFromString<ChannelPermissionValue>(rs.getString(2)), rs.getLong(3), instant(rs.getObject(4, LocalDateTime::class.java)), rs.getString(5)) }
        }
    private fun utc(i: Instant) = LocalDateTime.ofInstant(i, ZoneOffset.UTC)
    private fun instant(i: LocalDateTime) = i.toInstant(ZoneOffset.UTC)
    private companion object { val json = Json { encodeDefaults = true } }
}
