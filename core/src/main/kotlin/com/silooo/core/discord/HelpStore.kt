package com.silooo.core.discord

import com.silooo.core.db.Database
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Combien de réponses retenues un membre a pendant un mois. */
data class HelpCount(val userId: String, val answers: Int)

/**
 * L'Aide du mois (`discord_help` et `discord_help_marks`, migration V10). Chaque appel est une transaction à lui.
 */
interface HelpStore {
    /** Le sujet [threadId] a déjà été marqué résolu. */
    suspend fun isResolved(threadId: String): Boolean

    /**
     * Marque le sujet [threadId] résolu par [resolverId] (désigné par [markedBy], à l'instant [at]) et ajoute une réponse
     * retenue à [resolverId] pour le mois [monthStart] (son premier jour). Un sujet ne se marque qu'une fois : rend `false`,
     * sans rien changer, quand il l'est déjà.
     */
    suspend fun mark(threadId: String, resolverId: String, markedBy: String, at: Instant, monthStart: LocalDate): Boolean

    /** Les [limit] membres qui ont le plus de réponses retenues pendant le mois [monthStart], du premier au dernier. */
    suspend fun top(monthStart: LocalDate, limit: Int): List<HelpCount>
}

/**
 * Le SQL de [HelpStore], en minuscules : le garde anti-IA lit les chaînes de ce paquet. Testé sur MariaDB par
 * Testcontainers (`HelpSqlTest`).
 */
class JdbcHelpStore(private val database: Database) : HelpStore {
    override suspend fun isResolved(threadId: String): Boolean =
        database.transaction { connection ->
            connection.prepareStatement("select 1 from discord_help_marks where thread_id = ?").use {
                it.setString(1, threadId)
                it.executeQuery().use { rows -> rows.next() }
            }
        }

    override suspend fun mark(threadId: String, resolverId: String, markedBy: String, at: Instant, monthStart: LocalDate): Boolean =
        database.transaction { connection ->
            val marked = connection.prepareStatement(
                "insert ignore into discord_help_marks (thread_id, resolver_id, marked_by, marked_at) values (?, ?, ?, ?)",
            ).use {
                it.setString(1, threadId)
                it.setString(2, resolverId)
                it.setString(3, markedBy)
                it.setObject(4, LocalDateTime.ofInstant(at, ZoneOffset.UTC))
                it.executeUpdate()
            }
            if (marked == 0) return@transaction false
            connection.prepareStatement(
                "insert into discord_help (month_start, discord_user_id, answers) values (?, ?, 1) " +
                    "on duplicate key update answers = answers + 1",
            ).use {
                it.setObject(1, monthStart)
                it.setString(2, resolverId)
                it.executeUpdate()
            }
            true
        }

    override suspend fun top(monthStart: LocalDate, limit: Int): List<HelpCount> =
        database.transaction { connection ->
            connection.prepareStatement(
                "select discord_user_id, answers from discord_help where month_start = ? and answers > 0 " +
                    "order by answers desc, discord_user_id asc limit ?",
            ).use {
                it.setObject(1, monthStart)
                it.setInt(2, limit)
                it.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) HelpCount(rows.getString(1), rows.getInt(2)) else null }.toList()
                }
            }
        }
}
