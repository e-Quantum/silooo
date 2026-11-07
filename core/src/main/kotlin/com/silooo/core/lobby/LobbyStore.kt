package com.silooo.core.lobby

import com.silooo.core.db.Xuid
import com.silooo.core.db.setXuid
import com.silooo.core.registration.SiteRegistrations
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Une ligne d'un classement : le gamertag du dernier passage et le score. */
data class LobbyScore(val gamertag: String, val score: Int)

/** Ce que la salle d'attente sait d'un visiteur, hors de ses records. */
data class LobbyIdentity(val linked: Boolean, val pioneer: Boolean)

/**
 * Les accès SQL de la salle d'attente (V17) : `lobby_records` (le record de tous les temps, une ligne par XUID) et
 * `lobby_week_bests` (le record de chaque semaine, une ligne par semaine et par XUID, gardée pour le podium du lundi).
 * Chaque méthode reçoit la [Connection] de la transaction ; le faux des tests de contrat ne l'ouvre jamais.
 */
interface LobbyStore {
    /** Garde [score] s'il bat le record de tous les temps ou celui de la semaine [week] ; le gamertag suit toujours. */
    fun record(connection: Connection, xuid: Xuid, gamertag: String, score: Int, week: LocalDate, at: Instant)

    fun best(connection: Connection, xuid: Xuid): Int?

    fun weekBest(connection: Connection, xuid: Xuid, week: LocalDate): Int?

    /** Le nombre de records strictement meilleurs que [score] : le rang est ce nombre plus un. */
    fun betterThan(connection: Connection, score: Int): Int

    fun weekBetterThan(connection: Connection, week: LocalDate, score: Int): Int

    /** Le classement de tous les temps : le meilleur score en tête, à égalité le premier à l'avoir fait. */
    fun top(connection: Connection, size: Int): List<LobbyScore>

    fun weekTop(connection: Connection, week: LocalDate, size: Int): List<LobbyScore>

    fun pioneers(connection: Connection): Long

    fun identity(connection: Connection, xuid: Xuid): LobbyIdentity
}

class JdbcLobbyStore : LobbyStore {
    override fun record(connection: Connection, xuid: Xuid, gamertag: String, score: Int, week: LocalDate, at: Instant) {
        val time = LocalDateTime.ofInstant(at, ZoneOffset.UTC)
        // Le record ne baisse jamais ; à score égal, la date du premier qui l'a fait reste.
        connection.prepareStatement(
            "INSERT INTO lobby_records (xuid, gamertag, best, best_at) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE gamertag = VALUES(gamertag), " +
                "best_at = IF(VALUES(best) > best, VALUES(best_at), best_at), best = GREATEST(best, VALUES(best))",
        ).use {
            it.setXuid(1, xuid)
            it.setString(2, gamertag)
            it.setInt(3, score)
            it.setObject(4, time)
            it.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO lobby_week_bests (week_start, xuid, gamertag, best, best_at) VALUES (?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE gamertag = VALUES(gamertag), " +
                "best_at = IF(VALUES(best) > best, VALUES(best_at), best_at), best = GREATEST(best, VALUES(best))",
        ).use {
            it.setObject(1, week)
            it.setXuid(2, xuid)
            it.setString(3, gamertag)
            it.setInt(4, score)
            it.setObject(5, time)
            it.executeUpdate()
        }
    }

    override fun best(connection: Connection, xuid: Xuid): Int? =
        connection.prepareStatement("SELECT best FROM lobby_records WHERE xuid = ?").use {
            it.setXuid(1, xuid)
            it.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else null }
        }

    override fun weekBest(connection: Connection, xuid: Xuid, week: LocalDate): Int? =
        connection.prepareStatement("SELECT best FROM lobby_week_bests WHERE week_start = ? AND xuid = ?").use {
            it.setObject(1, week)
            it.setXuid(2, xuid)
            it.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else null }
        }

    override fun betterThan(connection: Connection, score: Int): Int =
        connection.prepareStatement("SELECT COUNT(*) FROM lobby_records WHERE best > ?").use {
            it.setInt(1, score)
            it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }

    override fun weekBetterThan(connection: Connection, week: LocalDate, score: Int): Int =
        connection.prepareStatement("SELECT COUNT(*) FROM lobby_week_bests WHERE week_start = ? AND best > ?").use {
            it.setObject(1, week)
            it.setInt(2, score)
            it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }

    override fun top(connection: Connection, size: Int): List<LobbyScore> =
        connection.prepareStatement("SELECT gamertag, best FROM lobby_records ORDER BY best DESC, best_at, xuid LIMIT ?").use {
            it.setInt(1, size)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(LobbyScore(rows.getString(1), rows.getInt(2))) } }
        }

    override fun weekTop(connection: Connection, week: LocalDate, size: Int): List<LobbyScore> =
        connection.prepareStatement(
            "SELECT gamertag, best FROM lobby_week_bests WHERE week_start = ? ORDER BY best DESC, best_at, xuid LIMIT ?",
        ).use {
            it.setObject(1, week)
            it.setInt(2, size)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(LobbyScore(rows.getString(1), rows.getInt(2))) } }
        }

    override fun pioneers(connection: Connection): Long =
        connection.prepareStatement("SELECT COUNT(*) FROM site_registrations WHERE pioneer = TRUE").use {
            it.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }

    override fun identity(connection: Connection, xuid: Xuid): LobbyIdentity {
        val linked = connection.prepareStatement("SELECT 1 FROM discord_game_links WHERE player_xuid = ?").use {
            it.setXuid(1, xuid)
            it.executeQuery().use { rows -> rows.next() }
        }
        val gamertag = connection.prepareStatement("SELECT gamertag FROM players WHERE xuid = ?").use {
            it.setXuid(1, xuid)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        val pioneer = gamertag != null && connection.prepareStatement("SELECT pioneer FROM site_registrations WHERE gamertag_key = ?").use {
            it.setString(1, SiteRegistrations.key(gamertag))
            it.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }
        return LobbyIdentity(linked, pioneer)
    }
}
