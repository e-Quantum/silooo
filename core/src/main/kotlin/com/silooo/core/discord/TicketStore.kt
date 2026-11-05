package com.silooo.core.discord

import com.silooo.core.db.Database
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Un ticket (`discord_tickets`, migration V10) : une demande d'un membre, son salon privé. [kind] : le choix du menu
 * (achat, compte, sanction, createurs). [channelId] est vide entre la ligne et la création du salon. [closedAt] et
 * [closedBy] restent vides tant que le ticket est ouvert.
 */
data class Ticket(
    val id: Int,
    val userId: String,
    val kind: String,
    val channelId: String,
    val openedAt: Instant,
    val closedAt: Instant? = null,
    val closedBy: String? = null,
)

/** Les tickets, de l'ouverture à la fermeture. Chaque appel est une transaction à lui. */
interface TicketStore {
    /** Écrit la ligne d'un ticket ouvert et rend son numéro ; [channelId] est vide quand le salon n'existe pas encore. */
    suspend fun open(userId: String, kind: String, channelId: String, at: Instant): Int

    /** Le salon du ticket [id] vient d'être créé. */
    suspend fun channelCreated(id: Int, channelId: String)

    /** Le ticket [id], ouvert ou fermé, ou `null` s'il n'existe pas. */
    suspend fun byId(id: Int): Ticket?

    /** Le ticket ouvert de [userId], ou `null` : un membre n'en a qu'un à la fois. */
    suspend fun openOf(userId: String): Ticket?

    /** Le ticket dont le salon est [channelId], ouvert ou fermé, ou `null`. */
    suspend fun byChannel(channelId: String): Ticket?

    /** Tous les tickets ouverts, du plus ancien au plus récent. */
    suspend fun openOnes(): List<Ticket>

    /** Ferme le ticket [id], par [by] (l'identifiant d'un membre, ou du bot) ; un ticket déjà fermé ne change pas. */
    suspend fun close(id: Int, by: String, at: Instant)
}

/**
 * Le SQL de [TicketStore] sur `discord_tickets`, en minuscules : le garde anti-IA lit les chaînes de ce paquet.
 * Testé sur MariaDB par Testcontainers (`TicketSqlTest`).
 */
class JdbcTicketStore(private val database: Database) : TicketStore {
    override suspend fun open(userId: String, kind: String, channelId: String, at: Instant): Int =
        database.transaction { connection ->
            connection.prepareStatement(
                "insert into discord_tickets (discord_user_id, kind, channel_id, opened_at) values (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS,
            ).use {
                it.setString(1, userId)
                it.setString(2, kind)
                it.setString(3, channelId)
                it.setObject(4, utc(at))
                it.executeUpdate()
                it.generatedKeys.use { keys ->
                    keys.next()
                    keys.getInt(1)
                }
            }
        }

    override suspend fun channelCreated(id: Int, channelId: String) {
        database.transaction { connection ->
            connection.prepareStatement("update discord_tickets set channel_id = ? where id = ?").use {
                it.setString(1, channelId)
                it.setInt(2, id)
                it.executeUpdate()
            }
        }
    }

    override suspend fun byId(id: Int): Ticket? = one("where id = ?") { it.setInt(1, id) }

    override suspend fun openOf(userId: String): Ticket? =
        one("where discord_user_id = ? and closed_at is null order by id desc limit 1") { it.setString(1, userId) }

    override suspend fun byChannel(channelId: String): Ticket? =
        one("where channel_id = ? order by id desc limit 1") { it.setString(1, channelId) }

    override suspend fun openOnes(): List<Ticket> =
        database.transaction { connection ->
            connection.prepareStatement(SELECT + "where closed_at is null order by id asc").use {
                it.executeQuery().use { rows -> generateSequence { if (rows.next()) read(rows) else null }.toList() }
            }
        }

    override suspend fun close(id: Int, by: String, at: Instant) {
        database.transaction { connection ->
            connection.prepareStatement("update discord_tickets set closed_at = ?, closed_by = ? where id = ? and closed_at is null").use {
                it.setObject(1, utc(at))
                it.setString(2, by)
                it.setInt(3, id)
                it.executeUpdate()
            }
        }
    }

    /** Une lecture dont la fin ([tail]) est écrite par le code, jamais par l'extérieur ; [bind] pose ses paramètres. */
    private suspend fun one(tail: String, bind: (PreparedStatement) -> Unit): Ticket? =
        database.transaction { connection ->
            connection.prepareStatement(SELECT + tail).use {
                bind(it)
                it.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
            }
        }

    private fun read(rows: ResultSet): Ticket =
        Ticket(
            id = rows.getInt(1),
            userId = rows.getString(2),
            kind = rows.getString(3),
            channelId = rows.getString(4),
            openedAt = instant(rows.getObject(5, LocalDateTime::class.java)),
            closedAt = rows.getObject(6, LocalDateTime::class.java)?.let(::instant),
            closedBy = rows.getString(7),
        )

    private fun utc(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)

    private fun instant(dateTime: LocalDateTime): Instant = dateTime.toInstant(ZoneOffset.UTC)

    private companion object {
        const val SELECT = "select id, discord_user_id, kind, channel_id, opened_at, closed_at, closed_by from discord_tickets "
    }
}
