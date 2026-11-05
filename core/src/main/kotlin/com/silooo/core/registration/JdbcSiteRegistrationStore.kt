package com.silooo.core.registration

import java.sql.Connection
import java.sql.SQLIntegrityConstraintViolationException

/** Le SQL de [SiteRegistrationStore] sur `site_registrations` (V7). Testé sur MariaDB par Testcontainers (`SiteRegistrationSqlTest`). */
class JdbcSiteRegistrationStore : SiteRegistrationStore {
    override fun insert(connection: Connection, key: String, gamertag: String, admitted: Boolean): Boolean =
        try {
            // Un INSERT simple : la clé primaire refuse la seconde écriture (ni INSERT IGNORE, qui tait aussi une valeur trop longue,
            // ni ON DUPLICATE KEY, dont le nombre de lignes change selon le réglage du pilote). InnoDB n'annule que cette instruction.
            connection.prepareStatement(
                "INSERT INTO site_registrations (gamertag_key, gamertag, admitted_at) VALUES (?, ?, IF(?, UTC_TIMESTAMP(3), NULL))",
            ).use {
                it.setString(1, key)
                it.setString(2, gamertag)
                it.setBoolean(3, admitted)
                it.executeUpdate() == 1
            }
        } catch (e: SQLIntegrityConstraintViolationException) {
            false
        }

    override fun find(connection: Connection, key: String): SiteRegistration? =
        connection.prepareStatement(
            "SELECT gamertag, discord_user_id IS NOT NULL, admitted_at IS NOT NULL FROM site_registrations WHERE gamertag_key = ?",
        ).use {
            it.setString(1, key)
            it.executeQuery().use { rows ->
                if (rows.next()) SiteRegistration(key, rows.getString(1), rows.getBoolean(2), rows.getBoolean(3)) else null
            }
        }

    override fun countPioneers(connection: Connection): Long =
        connection.prepareStatement("SELECT COUNT(*) FROM site_registrations WHERE pioneer = TRUE").use {
            it.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }

    override fun link(connection: Connection, key: String, discordUserId: String, admit: Boolean): LinkOutcome {
        val updated = try {
            connection.prepareStatement(
                "UPDATE site_registrations SET discord_user_id = ?, linked_at = UTC_TIMESTAMP(3), " +
                    "admitted_at = IF(?, COALESCE(admitted_at, UTC_TIMESTAMP(3)), admitted_at) " +
                    "WHERE gamertag_key = ? AND discord_user_id IS NULL",
            ).use {
                it.setString(1, discordUserId)
                it.setBoolean(2, admit)
                it.setString(3, key)
                it.executeUpdate()
            }
        } catch (e: SQLIntegrityConstraintViolationException) {
            // La clé unique uq_site_registrations_discord : ce compte est déjà relié à un autre gamertag.
            return LinkOutcome.DISCORD_TAKEN
        }
        if (updated == 1) return LinkOutcome.LINKED
        return if (find(connection, key) == null) LinkOutcome.NOT_REGISTERED else LinkOutcome.ALREADY_LINKED
    }

    override fun admit(connection: Connection, key: String): Boolean =
        connection.prepareStatement("UPDATE site_registrations SET admitted_at = UTC_TIMESTAMP(3) WHERE gamertag_key = ? AND admitted_at IS NULL").use {
            it.setString(1, key)
            it.executeUpdate() == 1
        }
}
