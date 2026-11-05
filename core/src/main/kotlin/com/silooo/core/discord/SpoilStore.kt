package com.silooo.core.discord

import com.silooo.core.db.Database
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Un spoil des coulisses (`discord_spoils`, migration V10), déposé par le staff avec `/spoil`. [nextThreshold] : les
 * réactions qui publient le suivant. [messageId] et [publishedAt] restent vides tant qu'il attend ; [reactions] :
 * le dernier compte des membres qui ont réagi.
 */
data class Spoil(
    val number: Int,
    val text: String,
    val image: ImageFile?,
    val nextThreshold: Int,
    val createdBy: String,
    val createdAt: Instant,
    val messageId: String? = null,
    val publishedAt: Instant? = null,
    val reactions: Int = 0,
)

/** Les spoils tels que le bot les retient, du dépôt à la publication. Chaque appel est une transaction à lui. */
interface SpoilStore {
    /** Dépose un spoil ; rend son numéro, le dernier plus un (1 pour le premier). */
    suspend fun add(text: String, image: ImageFile?, nextThreshold: Int, createdBy: String, at: Instant): Int

    /** Le dernier spoil publié, ou `null` quand aucun ne l'est. */
    suspend fun current(): Spoil?

    /** Le spoil déposé qui attend, le plus petit numéro non publié, ou `null`. */
    suspend fun nextPending(): Spoil?

    /** Les [limit] derniers spoils publiés, du plus récent au plus ancien. */
    suspend fun lastPublished(limit: Int): List<Spoil>

    suspend fun published(number: Int, messageId: String, at: Instant)

    suspend fun reactions(number: Int, count: Int)
}

/**
 * Le SQL de [SpoilStore] sur `discord_spoils`, en minuscules : le garde anti-IA lit les chaînes de ce paquet.
 * Testé sur MariaDB par Testcontainers (`SpoilSqlTest`).
 */
class JdbcSpoilStore(private val database: Database) : SpoilStore {
    override suspend fun add(text: String, image: ImageFile?, nextThreshold: Int, createdBy: String, at: Instant): Int =
        database.transaction { connection ->
            val number = connection.prepareStatement("select coalesce(max(spoil_number), 0) + 1 from discord_spoils").use {
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
            connection.prepareStatement(
                "insert into discord_spoils (spoil_number, spoil_text, image_name, image, next_threshold, created_by, created_at) " +
                    "values (?, ?, ?, ?, ?, ?, ?)",
            ).use {
                it.setInt(1, number)
                it.setString(2, text)
                it.setString(3, image?.name)
                it.setBytes(4, image?.bytes)
                it.setInt(5, nextThreshold)
                it.setString(6, createdBy)
                it.setObject(7, utc(at))
                it.executeUpdate()
            }
            number
        }

    override suspend fun current(): Spoil? =
        one("where published_at is not null order by spoil_number desc limit 1")

    override suspend fun nextPending(): Spoil? =
        one("where published_at is null order by spoil_number asc limit 1")

    override suspend fun lastPublished(limit: Int): List<Spoil> =
        database.transaction { connection ->
            connection.prepareStatement(SELECT + "where published_at is not null order by spoil_number desc limit ?").use {
                it.setInt(1, limit)
                it.executeQuery().use { rows -> generateSequence { if (rows.next()) read(rows) else null }.toList() }
            }
        }

    override suspend fun published(number: Int, messageId: String, at: Instant) {
        database.transaction { connection ->
            connection.prepareStatement("update discord_spoils set message_id = ?, published_at = ? where spoil_number = ?").use {
                it.setString(1, messageId)
                it.setObject(2, utc(at))
                it.setInt(3, number)
                it.executeUpdate()
            }
        }
    }

    override suspend fun reactions(number: Int, count: Int) {
        database.transaction { connection ->
            connection.prepareStatement("update discord_spoils set reactions = ? where spoil_number = ?").use {
                it.setInt(1, count)
                it.setInt(2, number)
                it.executeUpdate()
            }
        }
    }

    /** Une requête de lecture dont la fin ([tail]) est écrite par le code, jamais par l'extérieur. */
    private suspend fun one(tail: String): Spoil? =
        database.transaction { connection: Connection ->
            connection.prepareStatement(SELECT + tail).use {
                it.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
            }
        }

    private fun read(rows: ResultSet): Spoil {
        val imageName = rows.getString(4)
        val imageBytes = rows.getBytes(5)
        return Spoil(
            number = rows.getInt(1),
            text = rows.getString(2),
            image = if (imageName != null && imageBytes != null) ImageFile(imageName, imageBytes) else null,
            nextThreshold = rows.getInt(3),
            createdBy = rows.getString(6),
            createdAt = instant(rows.getObject(7, LocalDateTime::class.java)),
            messageId = rows.getString(8),
            publishedAt = rows.getObject(9, LocalDateTime::class.java)?.let(::instant),
            reactions = rows.getInt(10),
        )
    }

    private fun utc(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)

    private fun instant(dateTime: LocalDateTime): Instant = dateTime.toInstant(ZoneOffset.UTC)

    private companion object {
        const val SELECT = "select spoil_number, spoil_text, next_threshold, image_name, image, created_by, created_at, " +
            "message_id, published_at, reactions from discord_spoils "
    }
}
