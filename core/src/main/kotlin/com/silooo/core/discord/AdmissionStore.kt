package com.silooo.core.discord

import com.silooo.core.db.Database
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Une inscription de la page d'attente du site (`site_registrations`, migration V7), telle que le bot la lit pour sa
 * carte. [discordUserId] : le compte Discord prouvé par la liaison, ou `null` tant que l'inscrit ne l'a pas reliée ; il
 * sert à donner le rôle et à écrire en privé, jamais à s'afficher.
 */
data class Registration(
    val key: String,
    val gamertag: String,
    val discordUserId: String?,
    val createdAt: Instant,
)

/** La carte d'une inscription et la décision du staff (`discord_admissions`, migration V10). */
data class Admission(
    val key: String,
    val messageId: String?,
    val decision: String,
    val decidedBy: String?,
    val decidedAt: Instant?,
)

/** Les valeurs de [Admission.decision], celles de la colonne `decision`. */
object AdmissionDecision {
    const val WAITING = "attente"
    const val ADMITTED = "admis"
    const val REFUSED = "refuse"
    const val LATER = "plus-tard"
}

/** Les cartes d'admission, de l'inscription à la décision. Chaque appel est une transaction à lui. */
interface AdmissionStore {
    /** Les inscriptions qui n'ont pas encore de ligne dans `discord_admissions`, les plus anciennes d'abord. */
    suspend fun newRegistrations(limit: Int): List<Registration>

    /** L'inscription de clé [key], ou `null` si elle n'existe pas. */
    suspend fun registration(key: String): Registration?

    /**
     * Note que la carte de [key] est postée (message [messageId]), décision « attente » : l'inscription n'est plus nouvelle.
     * [at] : l'instant de la carte. La table n'a pas de colonne pour lui (V10 ne bouge pas) : le SQL l'ignore.
     */
    suspend fun carded(key: String, messageId: String, at: Instant)

    /** Enregistre la décision [decision] de [by] pour [key]. */
    suspend fun decide(key: String, decision: String, by: String, at: Instant)

    /** La ligne de [key], ou `null` si aucune carte n'a été postée pour elle. */
    suspend fun admission(key: String): Admission?
}

/**
 * Le SQL de [AdmissionStore] sur `site_registrations` (en lecture seule) et `discord_admissions`, en minuscules : le
 * garde anti-IA lit les chaînes de ce paquet. Testé sur MariaDB par Testcontainers (`AdmissionSqlTest`).
 */
class JdbcAdmissionStore(private val database: Database) : AdmissionStore {
    override suspend fun newRegistrations(limit: Int): List<Registration> =
        database.transaction { connection ->
            connection.prepareStatement(
                REGISTRATION + "left join discord_admissions a on a.gamertag_key = r.gamertag_key " +
                    "where a.gamertag_key is null order by r.created_at asc, r.gamertag_key asc limit ?",
            ).use {
                it.setInt(1, limit)
                it.executeQuery().use { rows -> generateSequence { if (rows.next()) readRegistration(rows) else null }.toList() }
            }
        }

    override suspend fun registration(key: String): Registration? =
        database.transaction { connection ->
            connection.prepareStatement(REGISTRATION + "where r.gamertag_key = ?").use {
                it.setString(1, key)
                it.executeQuery().use { rows -> if (rows.next()) readRegistration(rows) else null }
            }
        }

    override suspend fun carded(key: String, messageId: String, at: Instant) {
        database.transaction { connection ->
            connection.prepareStatement("insert into discord_admissions (gamertag_key, message_id) values (?, ?)").use {
                it.setString(1, key)
                it.setString(2, messageId)
                it.executeUpdate()
            }
        }
    }

    override suspend fun decide(key: String, decision: String, by: String, at: Instant) {
        database.transaction { connection ->
            connection.prepareStatement(
                "update discord_admissions set decision = ?, decided_by = ?, decided_at = ? where gamertag_key = ?",
            ).use {
                it.setString(1, decision)
                it.setString(2, by)
                it.setObject(3, LocalDateTime.ofInstant(at, ZoneOffset.UTC))
                it.setString(4, key)
                it.executeUpdate()
            }
        }
    }

    override suspend fun admission(key: String): Admission? =
        database.transaction { connection ->
            connection.prepareStatement(
                "select gamertag_key, message_id, decision, decided_by, decided_at from discord_admissions where gamertag_key = ?",
            ).use {
                it.setString(1, key)
                it.executeQuery().use { rows ->
                    if (rows.next()) {
                        Admission(
                            key = rows.getString(1),
                            messageId = rows.getString(2),
                            decision = rows.getString(3),
                            decidedBy = rows.getString(4),
                            decidedAt = rows.getObject(5, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC),
                        )
                    } else {
                        null
                    }
                }
            }
        }

    private fun readRegistration(rows: ResultSet): Registration =
        Registration(
            key = rows.getString(1),
            gamertag = rows.getString(2),
            discordUserId = rows.getString(3),
            createdAt = rows.getObject(4, LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
        )

    private companion object {
        const val REGISTRATION = "select r.gamertag_key, r.gamertag, r.discord_user_id, r.created_at from site_registrations r "
    }
}
