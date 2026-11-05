package com.silooo.core.discord

import com.silooo.core.db.Database
import java.sql.Connection
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Ce que le bot retient d'un membre du QG (`discord_members`, migration V10). */
data class MemberRecord(
    val userId: String,
    val firstJoinedAt: Instant,
    val joinedAt: Instant,
    val onboardedAt: Instant? = null,
    /** Le rôle de plateforme choisi à l'accueil guidé : `console`, `telephone` ou `pc` ; `null` : aucun. */
    val platform: String? = null,
    val greetedAt: Instant? = null,
    val tutorialAt: Instant? = null,
    /** Messages privés fermés : le tuto n'est pas parti, le bouton « Mon tuto » reste. */
    val tutorialRefused: Boolean = false,
    val leftAt: Instant? = null,
)

/**
 * Les membres tels que le bot les retient, d'une arrivée à un départ. Chaque appel est une transaction à lui ; un
 * membre arrivé pendant que le bot était éteint se crée au premier événement qui le concerne.
 */
interface MemberStore {
    /** Une arrivée, ou un retour : la première arrivée ne change pas, le départ s'efface. */
    suspend fun joined(userId: String, at: Instant)

    /** La fin de l'accueil guidé, avec le rôle de plateforme choisi. */
    suspend fun onboarded(userId: String, at: Instant, platform: String?)

    suspend fun greeted(userId: String, at: Instant)

    /** Le tuto envoyé en privé, ou refusé ([refused]) parce que les messages privés sont fermés. */
    suspend fun tutorialSent(userId: String, at: Instant, refused: Boolean)

    suspend fun left(userId: String, at: Instant)

    suspend fun member(userId: String): MemberRecord?
}

/**
 * Le SQL de [MemberStore] sur `discord_members`, en minuscules : le garde anti-IA lit les chaînes de ce paquet.
 * Testé sur MariaDB par Testcontainers (`MemberSqlTest`).
 */
class JdbcMemberStore(private val database: Database) : MemberStore {
    override suspend fun joined(userId: String, at: Instant) {
        database.transaction { connection ->
            connection.prepareStatement(
                "insert into discord_members (discord_user_id, first_joined_at, joined_at) values (?, ?, ?) " +
                    "on duplicate key update joined_at = values(joined_at), left_at = null",
            ).use {
                it.setString(1, userId)
                it.setObject(2, utc(at))
                it.setObject(3, utc(at))
                it.executeUpdate()
            }
        }
    }

    override suspend fun onboarded(userId: String, at: Instant, platform: String?) {
        database.transaction { connection ->
            val updated = connection.prepareStatement(
                "update discord_members set onboarded_at = ?, platform = ? where discord_user_id = ?",
            ).use {
                it.setObject(1, utc(at))
                it.setString(2, platform)
                it.setString(3, userId)
                it.executeUpdate()
            }
            if (updated == 0) {
                connection.prepareStatement(
                    "insert into discord_members (discord_user_id, first_joined_at, joined_at, onboarded_at, platform) " +
                        "values (?, ?, ?, ?, ?)",
                ).use {
                    it.setString(1, userId)
                    it.setObject(2, utc(at))
                    it.setObject(3, utc(at))
                    it.setObject(4, utc(at))
                    it.setString(5, platform)
                    it.executeUpdate()
                }
            }
        }
    }

    override suspend fun greeted(userId: String, at: Instant) {
        stamp("greeted_at", userId, at)
    }

    override suspend fun tutorialSent(userId: String, at: Instant, refused: Boolean) {
        database.transaction { connection ->
            connection.prepareStatement(
                "update discord_members set tutorial_at = ?, tutorial_refused = ? where discord_user_id = ?",
            ).use {
                it.setObject(1, utc(at))
                it.setBoolean(2, refused)
                it.setString(3, userId)
                it.executeUpdate()
            }
        }
    }

    override suspend fun left(userId: String, at: Instant) {
        stamp("left_at", userId, at)
    }

    override suspend fun member(userId: String): MemberRecord? = database.transaction { connection -> read(connection, userId) }

    /** Pose une date dans une colonne connue du code : [column] ne vient jamais de l'extérieur. */
    private suspend fun stamp(column: String, userId: String, at: Instant) {
        database.transaction { connection ->
            connection.prepareStatement("update discord_members set " + column + " = ? where discord_user_id = ?").use {
                it.setObject(1, utc(at))
                it.setString(2, userId)
                it.executeUpdate()
            }
        }
    }

    private fun read(connection: Connection, userId: String): MemberRecord? =
        connection.prepareStatement(
            "select first_joined_at, joined_at, onboarded_at, platform, greeted_at, tutorial_at, tutorial_refused, left_at " +
                "from discord_members where discord_user_id = ?",
        ).use {
            it.setString(1, userId)
            it.executeQuery().use { rows ->
                if (!rows.next()) return null
                MemberRecord(
                    userId = userId,
                    firstJoinedAt = instant(rows.getObject(1, LocalDateTime::class.java)),
                    joinedAt = instant(rows.getObject(2, LocalDateTime::class.java)),
                    onboardedAt = rows.getObject(3, LocalDateTime::class.java)?.let(::instant),
                    platform = rows.getString(4),
                    greetedAt = rows.getObject(5, LocalDateTime::class.java)?.let(::instant),
                    tutorialAt = rows.getObject(6, LocalDateTime::class.java)?.let(::instant),
                    tutorialRefused = rows.getBoolean(7),
                    leftAt = rows.getObject(8, LocalDateTime::class.java)?.let(::instant),
                )
            }
        }

    private fun utc(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)

    private fun instant(dateTime: LocalDateTime): Instant = dateTime.toInstant(ZoneOffset.UTC)
}
