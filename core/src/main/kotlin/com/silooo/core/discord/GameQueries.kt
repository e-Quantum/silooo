package com.silooo.core.discord

import com.silooo.core.db.Database
import java.sql.Connection

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
        c.prepareStatement("select distinct discord_user_id from discord_managed_game_roles").use {
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
}
