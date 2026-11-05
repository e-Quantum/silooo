package com.silooo.core.discord

import com.silooo.core.db.Database
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Combien de messages différents un membre a réagi pendant une semaine. */
data class ReactionCount(val userId: String, val given: Int)

/**
 * Le top réactions de la semaine (`discord_reactions` et `discord_reaction_marks`, migration V10). Chaque appel est une
 * transaction à lui.
 */
interface ReactionStore {
    /**
     * Compte la réaction de [userId] sur [messageId] à la semaine [weekStart] (son lundi), à condition que le membre n'ait
     * pas déjà été compté sur ce message ; [at] date la marque. Rend `true` quand la réaction compte.
     */
    suspend fun add(messageId: String, userId: String, at: Instant, weekStart: LocalDate): Boolean

    /** Les [limit] membres qui ont donné le plus de réactions pendant la semaine [weekStart], du premier au dernier. */
    suspend fun top(weekStart: LocalDate, limit: Int): List<ReactionCount>

    /** La plus ancienne semaine qui a des réactions comptées, ou `null` sans aucune. */
    suspend fun oldestWeekStart(): LocalDate?

    /** Oublie les semaines d'avant [weekStart] et les marques posées avant [markedBefore]. */
    suspend fun purge(weekStart: LocalDate, markedBefore: Instant)
}

/**
 * Le SQL de [ReactionStore], en minuscules : le garde anti-IA lit les chaînes de ce paquet. Testé sur MariaDB par
 * Testcontainers (`ReactionSqlTest`).
 */
class JdbcReactionStore(private val database: Database) : ReactionStore {
    override suspend fun add(messageId: String, userId: String, at: Instant, weekStart: LocalDate): Boolean =
        database.transaction { connection ->
            val marked = connection.prepareStatement(
                "insert ignore into discord_reaction_marks (message_id, discord_user_id, marked_at) values (?, ?, ?)",
            ).use {
                it.setString(1, messageId)
                it.setString(2, userId)
                it.setObject(3, utc(at))
                it.executeUpdate()
            }
            if (marked == 0) return@transaction false
            connection.prepareStatement(
                "insert into discord_reactions (week_start, discord_user_id, reactions_given) values (?, ?, 1) " +
                    "on duplicate key update reactions_given = reactions_given + 1",
            ).use {
                it.setObject(1, weekStart)
                it.setString(2, userId)
                it.executeUpdate()
            }
            true
        }

    override suspend fun top(weekStart: LocalDate, limit: Int): List<ReactionCount> =
        database.transaction { connection ->
            connection.prepareStatement(
                "select discord_user_id, reactions_given from discord_reactions where week_start = ? and reactions_given > 0 " +
                    "order by reactions_given desc, discord_user_id asc limit ?",
            ).use {
                it.setObject(1, weekStart)
                it.setInt(2, limit)
                it.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) ReactionCount(rows.getString(1), rows.getInt(2)) else null }.toList()
                }
            }
        }

    override suspend fun oldestWeekStart(): LocalDate? =
        database.transaction { connection ->
            connection.prepareStatement("select min(week_start) from discord_reactions").use {
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getObject(1, LocalDate::class.java)
                }
            }
        }

    override suspend fun purge(weekStart: LocalDate, markedBefore: Instant) {
        database.transaction { connection ->
            connection.prepareStatement("delete from discord_reactions where week_start < ?").use {
                it.setObject(1, weekStart)
                it.executeUpdate()
            }
            connection.prepareStatement("delete from discord_reaction_marks where marked_at < ?").use {
                it.setObject(1, utc(markedBefore))
                it.executeUpdate()
            }
        }
    }

    private fun utc(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
}
