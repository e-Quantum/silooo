package com.silooo.core.discord

import com.silooo.core.db.Database
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * L'XP de tchat d'un membre (`discord_chat_xp`, migration V10). [level] : le dernier niveau franchi, il ne baisse pas
 * quand un message supprimé reprend ses points. [weekStart] : le lundi où la semaine comptée a commencé.
 * [lastCountedAt] : le dernier message qui a compté, pour n'en compter qu'un par intervalle.
 */
data class XpRecord(
    val userId: String,
    val points: Int,
    val level: Int,
    val weekPoints: Int,
    val weekStart: LocalDate,
    val lastCountedAt: Instant?,
)

/** L'XP de tchat telle que le bot la retient. Chaque appel est une transaction à lui. */
interface XpStore {
    /** La ligne du membre, ou `null` s'il n'a jamais compté un message. */
    suspend fun record(userId: String): XpRecord?

    /**
     * Compte le message [messageId] de [userId] : [points] s'ajoutent au total et à la semaine, [at] devient son dernier
     * message compté, et le message s'écrit pour pouvoir être repris. La ligne se crée au premier message, avec
     * [weekStart] ; ensuite [weekStart] ne change que par [resetWeek]. Rend la ligne mise à jour.
     */
    suspend fun count(userId: String, messageId: String, points: Int, at: Instant, weekStart: LocalDate): XpRecord

    /**
     * Reprend les points du message [messageId] au total et à la semaine, jamais sous zéro. Rend les points de ce
     * message, ou `null` s'il n'avait pas compté (ou s'il est d'une semaine déjà purgée).
     */
    suspend fun uncount(messageId: String): Int?

    /** Le membre a franchi le niveau [level]. */
    suspend fun levelReached(userId: String, level: Int)

    /** Les [limit] membres qui ont le plus de points cette semaine, du premier au dernier, ceux qui n'en ont pas exclus. */
    suspend fun weekTop(limit: Int): List<XpRecord>

    /** Remet les points de la semaine à zéro pour tout le monde, pose [weekStart] et purge les messages d'avant ce lundi. */
    suspend fun resetWeek(weekStart: LocalDate)

    /** Le plus ancien lundi de semaine encore posé, ou `null` sans aucune ligne : plus ancien que la semaine courante, un lundi a été manqué. */
    suspend fun oldestWeekStart(): LocalDate?
}

/**
 * Le SQL de [XpStore] sur `discord_chat_xp` et `discord_xp_messages`, en minuscules : le garde anti-IA lit les chaînes de
 * ce paquet. Testé sur MariaDB par Testcontainers (`XpSqlTest`).
 */
class JdbcXpStore(private val database: Database) : XpStore {
    override suspend fun record(userId: String): XpRecord? = database.transaction { connection -> read(connection, userId) }

    override suspend fun count(userId: String, messageId: String, points: Int, at: Instant, weekStart: LocalDate): XpRecord =
        database.transaction { connection ->
            connection.prepareStatement(
                "insert into discord_chat_xp (discord_user_id, points, week_points, week_start, last_counted_at, updated_at) " +
                    "values (?, ?, ?, ?, ?, ?) " +
                    "on duplicate key update points = points + values(points), week_points = week_points + values(week_points), " +
                    "last_counted_at = values(last_counted_at), updated_at = values(updated_at)",
            ).use {
                it.setString(1, userId)
                it.setInt(2, points)
                it.setInt(3, points)
                it.setObject(4, weekStart)
                it.setObject(5, utc(at))
                it.setObject(6, utc(at))
                it.executeUpdate()
            }
            connection.prepareStatement(
                "insert into discord_xp_messages (message_id, discord_user_id, points, counted_at) values (?, ?, ?, ?)",
            ).use {
                it.setString(1, messageId)
                it.setString(2, userId)
                it.setInt(3, points)
                it.setObject(4, utc(at))
                it.executeUpdate()
            }
            checkNotNull(read(connection, userId)) { "la ligne d'XP vient d'être écrite" }
        }

    override suspend fun uncount(messageId: String): Int? =
        database.transaction { connection ->
            val counted = connection.prepareStatement("select discord_user_id, points from discord_xp_messages where message_id = ?").use {
                it.setString(1, messageId)
                it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getInt(2) else null }
            } ?: return@transaction null
            connection.prepareStatement("delete from discord_xp_messages where message_id = ?").use {
                it.setString(1, messageId)
                it.executeUpdate()
            }
            connection.prepareStatement(
                "update discord_chat_xp set points = greatest(points - ?, 0), week_points = greatest(week_points - ?, 0), " +
                    "updated_at = utc_timestamp(3) where discord_user_id = ?",
            ).use {
                it.setInt(1, counted.second)
                it.setInt(2, counted.second)
                it.setString(3, counted.first)
                it.executeUpdate()
            }
            counted.second
        }

    override suspend fun levelReached(userId: String, level: Int) {
        database.transaction { connection ->
            connection.prepareStatement("update discord_chat_xp set chat_level = ? where discord_user_id = ?").use {
                it.setInt(1, level)
                it.setString(2, userId)
                it.executeUpdate()
            }
        }
    }

    override suspend fun weekTop(limit: Int): List<XpRecord> =
        database.transaction { connection ->
            connection.prepareStatement(
                SELECT + "from discord_chat_xp where week_points > 0 order by week_points desc, discord_user_id asc limit ?",
            ).use {
                it.setInt(1, limit)
                it.executeQuery().use { rows -> generateSequence { if (rows.next()) row(rows) else null }.toList() }
            }
        }

    override suspend fun resetWeek(weekStart: LocalDate) {
        database.transaction { connection ->
            connection.prepareStatement("update discord_chat_xp set week_points = 0, week_start = ?").use {
                it.setObject(1, weekStart)
                it.executeUpdate()
            }
            connection.prepareStatement("delete from discord_xp_messages where counted_at < ?").use {
                it.setObject(1, LocalDateTime.of(weekStart, LocalTime.MIDNIGHT))
                it.executeUpdate()
            }
        }
    }

    override suspend fun oldestWeekStart(): LocalDate? =
        database.transaction { connection ->
            connection.prepareStatement("select min(week_start) from discord_chat_xp").use {
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getObject(1, LocalDate::class.java)
                }
            }
        }

    private fun read(connection: Connection, userId: String): XpRecord? =
        connection.prepareStatement(SELECT + "from discord_chat_xp where discord_user_id = ?").use {
            it.setString(1, userId)
            it.executeQuery().use { rows -> if (rows.next()) row(rows) else null }
        }

    private fun row(rows: ResultSet): XpRecord =
        XpRecord(
            userId = rows.getString(1),
            points = rows.getInt(2),
            level = rows.getInt(3),
            weekPoints = rows.getInt(4),
            weekStart = rows.getObject(5, LocalDate::class.java),
            lastCountedAt = rows.getObject(6, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC),
        )

    private fun utc(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)

    private companion object {
        const val SELECT = "select discord_user_id, points, chat_level, week_points, week_start, last_counted_at "
    }
}
