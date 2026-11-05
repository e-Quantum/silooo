package com.silooo.core.discord

import com.silooo.core.db.Database
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Les trois sortes de lot, telles qu'elles s'écrivent en base (`discord_giveaways.prize_kind`, migration V10). */
object GiveawayPrize {
    const val COSMETIC = "cosmetique"
    const val GEMS = "gemmes"
    const val INGOTS = "lingots"
    val ALL = listOf(COSMETIC, GEMS, INGOTS)
}

/**
 * Un giveaway (`discord_giveaways`, migration V10). [prizeAmount] : le nombre de Gemmes ou de Lingots, 0 pour un
 * cosmétique, que [prizeLabel] nomme par son identifiant de `definitions/`. [messageId] : la carte, une fois postée.
 * [drawnAt] et [winnerId] restent vides tant que le tirage n'a pas eu lieu ; un giveaway tiré sans participant a une
 * date de tirage et pas de gagnant.
 */
data class Giveaway(
    val id: Int,
    val title: String,
    val prizeKind: String,
    val prizeAmount: Long,
    val prizeLabel: String?,
    val channelId: String,
    val messageId: String?,
    val endsAt: Instant,
    val drawnAt: Instant?,
    val winnerId: String?,
    val createdBy: String,
    val createdAt: Instant,
)

/** Les giveaways tels que le bot les retient, du lancement au tirage. Chaque appel est une transaction à lui. */
interface GiveawayStore {
    /** Écrit un giveaway sans carte ni participant ; rend son numéro. */
    suspend fun create(
        title: String,
        prizeKind: String,
        prizeAmount: Long,
        prizeLabel: String?,
        channelId: String,
        endsAt: Instant,
        createdBy: String,
        at: Instant,
    ): Int

    /** La carte du giveaway est postée. */
    suspend fun carded(id: Int, messageId: String)

    /** Inscrit [userId] ; faux s'il l'est déjà (ou si le giveaway n'existe pas). */
    suspend fun enter(id: Int, userId: String): Boolean

    /** Les participants du giveaway, dans l'ordre d'inscription. */
    suspend fun entries(id: Int): List<String>

    /** Les giveaways pas encore tirés, du premier à finir au dernier. */
    suspend fun open(): List<Giveaway>

    /** Clôt le giveaway : [winnerId] est le gagnant, `null` quand personne n'a participé. */
    suspend fun drawn(id: Int, winnerId: String?, at: Instant)

    /** Combien de giveaways dont la carte est postée ont été lancés entre [from] (compris) et [to] (exclu). */
    suspend fun countCreatedBetween(from: Instant, to: Instant): Int

    /** Garde le lot gagné sur l'identifiant Discord ; le crédit en jeu viendra par le contrat du jeu, à la liaison. */
    suspend fun addPrize(userId: String, giveawayId: Int, kind: String, amount: Long, label: String?, at: Instant)
}

/**
 * Le SQL de [GiveawayStore] sur `discord_giveaways`, `discord_giveaway_entries` et `discord_prizes`, en minuscules :
 * le garde anti-IA lit les chaînes de ce paquet. Testé sur MariaDB par Testcontainers (`GiveawaySqlTest`).
 */
class JdbcGiveawayStore(private val database: Database) : GiveawayStore {
    override suspend fun create(
        title: String,
        prizeKind: String,
        prizeAmount: Long,
        prizeLabel: String?,
        channelId: String,
        endsAt: Instant,
        createdBy: String,
        at: Instant,
    ): Int =
        database.transaction { connection ->
            connection.prepareStatement(
                "insert into discord_giveaways (title, prize_kind, prize_amount, prize_label, channel_id, ends_at, created_by, created_at) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS,
            ).use {
                it.setString(1, title)
                it.setString(2, prizeKind)
                it.setLong(3, prizeAmount)
                it.setString(4, prizeLabel)
                it.setString(5, channelId)
                it.setObject(6, utc(endsAt))
                it.setString(7, createdBy)
                it.setObject(8, utc(at))
                it.executeUpdate()
                it.generatedKeys.use { keys ->
                    check(keys.next()) { "discord_giveaways : aucun numéro rendu" }
                    keys.getInt(1)
                }
            }
        }

    override suspend fun carded(id: Int, messageId: String) {
        database.transaction { connection ->
            connection.prepareStatement("update discord_giveaways set message_id = ? where id = ?").use {
                it.setString(1, messageId)
                it.setInt(2, id)
                it.executeUpdate()
            }
        }
    }

    override suspend fun enter(id: Int, userId: String): Boolean =
        database.transaction { connection ->
            connection.prepareStatement("insert ignore into discord_giveaway_entries (giveaway_id, discord_user_id) values (?, ?)").use {
                it.setInt(1, id)
                it.setString(2, userId)
                it.executeUpdate() == 1
            }
        }

    override suspend fun entries(id: Int): List<String> =
        database.transaction { connection ->
            connection.prepareStatement(
                "select discord_user_id from discord_giveaway_entries where giveaway_id = ? order by entered_at, discord_user_id",
            ).use {
                it.setInt(1, id)
                it.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
            }
        }

    override suspend fun open(): List<Giveaway> =
        database.transaction { connection ->
            connection.prepareStatement(SELECT + "where drawn_at is null order by ends_at, id").use {
                it.executeQuery().use { rows -> generateSequence { if (rows.next()) read(rows) else null }.toList() }
            }
        }

    override suspend fun drawn(id: Int, winnerId: String?, at: Instant) {
        database.transaction { connection ->
            connection.prepareStatement("update discord_giveaways set drawn_at = ?, winner_id = ? where id = ? and drawn_at is null").use {
                it.setObject(1, utc(at))
                it.setString(2, winnerId)
                it.setInt(3, id)
                it.executeUpdate()
            }
        }
    }

    override suspend fun countCreatedBetween(from: Instant, to: Instant): Int =
        database.transaction { connection ->
            connection.prepareStatement(
                "select count(*) from discord_giveaways where created_at >= ? and created_at < ? and message_id is not null",
            ).use {
                it.setObject(1, utc(from))
                it.setObject(2, utc(to))
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    override suspend fun addPrize(userId: String, giveawayId: Int, kind: String, amount: Long, label: String?, at: Instant) {
        database.transaction { connection ->
            connection.prepareStatement(
                "insert into discord_prizes (discord_user_id, giveaway_id, prize_kind, prize_amount, prize_label, won_at) " +
                    "values (?, ?, ?, ?, ?, ?)",
            ).use {
                it.setString(1, userId)
                it.setInt(2, giveawayId)
                it.setString(3, kind)
                it.setLong(4, amount)
                it.setString(5, label)
                it.setObject(6, utc(at))
                it.executeUpdate()
            }
        }
    }

    private fun read(rows: ResultSet): Giveaway =
        Giveaway(
            id = rows.getInt(1),
            title = rows.getString(2),
            prizeKind = rows.getString(3),
            prizeAmount = rows.getLong(4),
            prizeLabel = rows.getString(5),
            channelId = rows.getString(6),
            messageId = rows.getString(7),
            endsAt = instant(rows.getObject(8, LocalDateTime::class.java)),
            drawnAt = rows.getObject(9, LocalDateTime::class.java)?.let(::instant),
            winnerId = rows.getString(10),
            createdBy = rows.getString(11),
            createdAt = instant(rows.getObject(12, LocalDateTime::class.java)),
        )

    private fun utc(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)

    private fun instant(dateTime: LocalDateTime): Instant = dateTime.toInstant(ZoneOffset.UTC)

    private companion object {
        const val SELECT = "select id, title, prize_kind, prize_amount, prize_label, channel_id, message_id, ends_at, drawn_at, " +
            "winner_id, created_by, created_at from discord_giveaways "
    }
}
