package com.silooo.core.discord

import com.silooo.core.db.Database
import java.sql.Connection
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Lectures des seules preuves actives et des vues existantes du jeu. */
class JdbcGameProfileQueries(private val database: Database) : GameProfileQueries {
    override suspend fun profiles(): List<GameProfile> = database.transaction { c -> c.queryProfiles(null) }
    override suspend fun profile(discordId: String): GameProfile? = database.transaction { c -> c.queryProfiles(discordId).firstOrNull() }

    private fun Connection.queryProfiles(discordId: String?): List<GameProfile> {
        val sql = """select g.discord_user_id, g.player_xuid, p.gamertag, c.id as company_id, c.course_number, m.role, r.pioneer,
            (r.admitted_at is not null) as beta
            from discord_game_links g
            join players p on p.xuid = g.player_xuid
            join site_registrations r on r.gamertag_key = g.gamertag_key and r.discord_user_id = g.discord_user_id
            left join company_members m on m.xuid = p.xuid
            left join companies c on c.id = m.company_id
            where (? is null or g.discord_user_id = ?)
            order by g.discord_user_id"""
        return prepareStatement(sql).use { statement ->
            statement.setString(1, discordId); statement.setString(2, discordId)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) {
                    val rawCourse = rows.getInt("course_number").let { if (rows.wasNull()) null else it }
                    add(GameProfile(
                        discordId = rows.getString("discord_user_id"),
                        xuid = rows.getString("player_xuid"),
                        gamertag = rows.getString("gamertag"),
                        companyId = rows.getLong("company_id").let { if (rows.wasNull()) null else it },
                        courseNumber = rawCourse?.minus(1),
                        companyRole = rows.getString("role"),
                        pioneer = rows.getBoolean("pioneer"), beta = rows.getBoolean("beta"),
                        prestige = rawCourse?.minus(1) ?: 0,
                    ))
                }
            } }
        }
    }
}

class JdbcManagedGameRoleStore(private val database: Database) : ManagedGameRoleStore {
    override suspend fun roleKeys(discordId: String): Set<String> = database.transaction { c ->
        c.prepareStatement("select role_key from discord_managed_game_roles where discord_user_id = ?").use {
            it.setString(1, discordId); it.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
    }
    override suspend fun knownUsers(): Set<String> = database.transaction { c ->
        c.prepareStatement("select discord_user_id from discord_managed_game_roles union select discord_user_id from discord_managed_game_profiles").use {
            it.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
    }
    override suspend fun replace(discordId: String, roleKeys: Set<String>) { database.transaction { c ->
        c.prepareStatement("delete from discord_managed_game_roles where discord_user_id = ?").use { it.setString(1, discordId); it.executeUpdate() }
        c.prepareStatement("insert into discord_managed_game_roles (discord_user_id, role_key) values (?, ?)").use { ps ->
            roleKeys.forEach { key -> ps.setString(1, discordId); ps.setString(2, key); ps.addBatch() }
            if (roleKeys.isNotEmpty()) ps.executeBatch()
        }
    } }
    override suspend fun appliedNickname(discordId: String): String? = database.transaction { c ->
        c.prepareStatement("select applied_nickname from discord_managed_game_profiles where discord_user_id = ?").use {
            it.setString(1, discordId); it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }
    override suspend fun appliedNickname(discordId: String, nickname: String?) { database.transaction { c ->
        if (nickname == null) {
            c.prepareStatement("delete from discord_managed_game_profiles where discord_user_id = ?").use {
                it.setString(1, discordId); it.executeUpdate()
            }
        } else {
            c.prepareStatement("insert into discord_managed_game_profiles (discord_user_id, applied_nickname) values (?, ?) " +
                "on duplicate key update applied_nickname = values(applied_nickname)").use {
                it.setString(1, discordId); it.setString(2, nickname); it.executeUpdate()
            }
        }
    } }
    override suspend fun claimInvitation(discordId: String, at: Instant): Boolean = database.transaction { c ->
        c.prepareStatement("insert ignore into discord_game_invitations (discord_user_id, attempted_at, status) values (?, ?, 'attempting')").use {
            it.setString(1, discordId); it.setObject(2, LocalDateTime.ofInstant(at, ZoneOffset.UTC)); it.executeUpdate() == 1
        }
    }
    override suspend fun finishInvitation(discordId: String, status: String) { database.transaction { c ->
        require(status in setOf("sent", "dm_closed", "discord_refused"))
        c.prepareStatement("update discord_game_invitations set status = ? where discord_user_id = ?").use {
            it.setString(1, status); it.setString(2, discordId); it.executeUpdate()
        }
    } }
}
