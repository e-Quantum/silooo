package com.silooo.core.registration

import com.silooo.core.db.Xuid
import com.silooo.core.db.isDuplicateKey
import com.silooo.core.db.setXuid
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/*
 * Le SQL des tables de V11 : `web_accounts`, `waitlist`, `discord_links`, `beta_access` et `web_sessions`. Testé sur MariaDB
 * par Testcontainers (`WebStoresSqlTest`). Comme les autres dépôts du service, chaque méthode reçoit la `Connection` de la
 * transaction.
 *
 * Les dates s'écrivent avec l'horloge du service (`DATETIME(3)`, UTC), pas avec celle de la base. Les empreintes voyagent en
 * chaîne décimale non signée côté Kotlin et en `BIGINT UNSIGNED` côté SQL : `setXuid` passe un entier, `getString` relit des
 * chiffres. Ni l'identifiant Discord ni le XUID Xbox ne sortent de ce fichier dans un message d'erreur.
 */

private fun utc(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)

private fun ResultSet.instantOrNull(column: Int): Instant? = getObject(column, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)

private fun ResultSet.instant(column: Int): Instant = checkNotNull(instantOrNull(column)) { "colonne $column : date absente" }

/** L'empreinte lue comme un XUID ; une chaîne qui n'en est pas un est une erreur de l'appelant, pas une donnée. */
private fun xuidOf(playerXuid: String): Xuid = requireNotNull(Xuid.parse(playerXuid)) { "L'empreinte n'est pas un XUID valable" }

/** `web_accounts`. */
class JdbcWebAccountStore : WebAccountStore {
    private companion object {
        const val COLUMNS = "id, player_xuid, gamertag, xbox_xuid IS NOT NULL, discord_user_id IS NOT NULL, created_at"
    }

    private fun one(connection: Connection, where: String, bind: (PreparedStatement) -> Unit): WebAccount? =
        connection.prepareStatement("SELECT $COLUMNS FROM web_accounts WHERE $where").use {
            bind(it)
            it.executeQuery().use { rows ->
                if (rows.next()) {
                    WebAccount(
                        id = rows.getLong(1),
                        playerXuid = rows.getString(2),
                        gamertag = rows.getString(3),
                        xbox = rows.getBoolean(4),
                        discord = rows.getBoolean(5),
                        createdAt = rows.instant(6),
                    )
                } else {
                    null
                }
            }
        }

    override fun find(connection: Connection, id: Long): WebAccount? = one(connection, "id = ?") { it.setLong(1, id) }

    override fun findByXbox(connection: Connection, xboxXuid: String): WebAccount? = one(connection, "xbox_xuid = ?") { it.setString(1, xboxXuid) }

    override fun findByDiscord(connection: Connection, discordUserId: String): WebAccount? =
        one(connection, "discord_user_id = ?") { it.setString(1, discordUserId) }

    override fun findByPlayer(connection: Connection, playerXuid: String): WebAccount? {
        val xuid = Xuid.parse(playerXuid) ?: return null
        return one(connection, "player_xuid = ?") { it.setXuid(1, xuid) }
    }

    override fun createXbox(connection: Connection, xboxXuid: String, gamertag: String, playerXuid: String, now: Instant): WebAccount {
        val id = connection.prepareStatement(
            "INSERT INTO web_accounts (xbox_xuid, gamertag, player_xuid, created_at) VALUES (?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setString(1, xboxXuid)
            it.setString(2, gamertag)
            it.setXuid(3, xuidOf(playerXuid))
            it.setObject(4, utc(now))
            it.executeUpdate()
            it.generatedId()
        }
        return WebAccount(id, playerXuid, gamertag, xbox = true, discord = false, createdAt = now.truncatedTo(ChronoUnit.MILLIS))
    }

    override fun createDiscord(connection: Connection, discordUserId: String, now: Instant): WebAccount {
        val id = connection.prepareStatement(
            "INSERT INTO web_accounts (discord_user_id, created_at) VALUES (?, ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setString(1, discordUserId)
            it.setObject(2, utc(now))
            it.executeUpdate()
            it.generatedId()
        }
        return WebAccount(id, playerXuid = null, gamertag = null, xbox = false, discord = true, createdAt = now.truncatedTo(ChronoUnit.MILLIS))
    }

    private fun PreparedStatement.generatedId(): Long =
        generatedKeys.use { keys ->
            check(keys.next()) { "web_accounts : pas d'identifiant rendu" }
            keys.getLong(1)
        }

    override fun attachXbox(connection: Connection, id: Long, xboxXuid: String): Boolean {
        val updated = try {
            connection.prepareStatement("UPDATE web_accounts SET xbox_xuid = ? WHERE id = ? AND xbox_xuid IS NULL").use {
                it.setString(1, xboxXuid)
                it.setLong(2, id)
                it.executeUpdate()
            }
        } catch (e: SQLException) {
            // La clé unique uq_web_accounts_xbox : un autre compte porte déjà ce XUID.
            if (e.isDuplicateKey()) return false else throw e
        }
        if (updated == 1) return true
        // Le compte n'existe pas, ou en a déjà un : seul le même XUID est un succès (rejouer la liaison ne change rien).
        return connection.prepareStatement("SELECT 1 FROM web_accounts WHERE id = ? AND xbox_xuid = ?").use {
            it.setLong(1, id)
            it.setString(2, xboxXuid)
            it.executeQuery().use { rows -> rows.next() }
        }
    }

    override fun setPlayer(connection: Connection, id: Long, gamertag: String, playerXuid: String): PlayerOutcome {
        val updated = try {
            connection.prepareStatement("UPDATE web_accounts SET gamertag = ?, player_xuid = ? WHERE id = ?").use {
                it.setString(1, gamertag)
                it.setXuid(2, xuidOf(playerXuid))
                it.setLong(3, id)
                it.executeUpdate()
            }
        } catch (e: SQLException) {
            // La clé unique uq_web_accounts_player : un autre compte porte déjà cette empreinte. InnoDB n'annule que cette instruction.
            if (e.isDuplicateKey()) return PlayerOutcome.PLAYER_TAKEN else throw e
        }
        // Selon le réglage du pilote, une ligne réécrite à l'identique compte pour zéro : on relit pour la distinguer d'un compte absent.
        if (updated == 1) return PlayerOutcome.SET
        return if (find(connection, id) == null) PlayerOutcome.NOT_FOUND else PlayerOutcome.SET
    }

    override fun clearPlayer(connection: Connection, id: Long): Boolean =
        connection.prepareStatement(
            "UPDATE web_accounts SET gamertag = NULL, player_xuid = NULL WHERE id = ? AND (gamertag IS NOT NULL OR player_xuid IS NOT NULL)",
        ).use {
            it.setLong(1, id)
            it.executeUpdate() == 1
        }

    override fun setDiscord(connection: Connection, id: Long, discordUserId: String): LinkOutcome {
        val updated = try {
            connection.prepareStatement("UPDATE web_accounts SET discord_user_id = ? WHERE id = ? AND discord_user_id IS NULL").use {
                it.setString(1, discordUserId)
                it.setLong(2, id)
                it.executeUpdate()
            }
        } catch (e: SQLException) {
            // La clé unique uq_web_accounts_discord : cet identifiant est déjà celui d'un autre compte.
            if (e.isDuplicateKey()) return LinkOutcome.DISCORD_TAKEN else throw e
        }
        if (updated == 1) return LinkOutcome.LINKED
        return if (find(connection, id) == null) LinkOutcome.NOT_REGISTERED else LinkOutcome.ALREADY_LINKED
    }

    override fun clearDiscord(connection: Connection, id: Long): Boolean =
        connection.prepareStatement("UPDATE web_accounts SET discord_user_id = NULL WHERE id = ? AND discord_user_id IS NOT NULL").use {
            it.setLong(1, id)
            it.executeUpdate() == 1
        }

    override fun touch(connection: Connection, id: Long, now: Instant) {
        connection.prepareStatement("UPDATE web_accounts SET last_login_at = ? WHERE id = ?").use {
            it.setObject(1, utc(now))
            it.setLong(2, id)
            it.executeUpdate()
        }
    }

    override fun discordUserId(connection: Connection, id: Long): String? =
        connection.prepareStatement("SELECT discord_user_id FROM web_accounts WHERE id = ?").use {
            it.setLong(1, id)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
}

/** `waitlist`. */
class JdbcWaitlistStore : WaitlistStore {
    override fun insert(connection: Connection, accountId: Long, now: Instant): Boolean =
        try {
            // Un INSERT simple : la clé primaire refuse la seconde écriture, la clé étrangère un compte qui n'existe pas.
            connection.prepareStatement("INSERT INTO waitlist (account_id, created_at) VALUES (?, ?)").use {
                it.setLong(1, accountId)
                it.setObject(2, utc(now))
                it.executeUpdate() == 1
            }
        } catch (e: SQLException) {
            if (e.isDuplicateKey()) false else throw e
        }

    override fun find(connection: Connection, accountId: Long): WaitlistEntry? =
        connection.prepareStatement("SELECT created_at, pioneer_given_at FROM waitlist WHERE account_id = ?").use {
            it.setLong(1, accountId)
            it.executeQuery().use { rows -> if (rows.next()) WaitlistEntry(accountId, rows.instant(1), rows.instantOrNull(2)) else null }
        }

    override fun count(connection: Connection): Long =
        connection.prepareStatement("SELECT COUNT(*) FROM waitlist").use {
            it.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }
}

/** `discord_links`. */
class JdbcDiscordLinkStore : DiscordLinkStore {
    override fun replace(connection: Connection, accountId: Long, code: String, now: Instant, expiresAt: Instant) {
        // Un seul code vivant par compte : l'ancien part, le nouveau prend sa place dans la même transaction.
        connection.prepareStatement("DELETE FROM discord_links WHERE account_id = ?").use {
            it.setLong(1, accountId)
            it.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO discord_links (code, account_id, created_at, expires_at) VALUES (?, ?, ?, ?)").use {
            it.setString(1, code)
            it.setLong(2, accountId)
            it.setObject(3, utc(now))
            it.setObject(4, utc(expiresAt))
            it.executeUpdate()
        }
    }

    private fun one(connection: Connection, where: String, bind: (PreparedStatement) -> Unit): LinkCode? =
        connection.prepareStatement("SELECT code, account_id, created_at, expires_at FROM discord_links WHERE $where").use {
            bind(it)
            it.executeQuery().use { rows ->
                if (rows.next()) LinkCode(rows.getString(1), rows.getLong(2), rows.instant(3), rows.instant(4)) else null
            }
        }

    override fun findByAccount(connection: Connection, accountId: Long): LinkCode? = one(connection, "account_id = ?") { it.setLong(1, accountId) }

    override fun findByCode(connection: Connection, code: String): LinkCode? = one(connection, "code = ?") { it.setString(1, code) }

    override fun delete(connection: Connection, code: String): Boolean =
        connection.prepareStatement("DELETE FROM discord_links WHERE code = ?").use {
            it.setString(1, code)
            it.executeUpdate() == 1
        }

    override fun purge(connection: Connection, now: Instant): Int =
        // Un code vaut tant que now < expires_at (LinkCode.expired) : il est expiré dès que expires_at <= now.
        connection.prepareStatement("DELETE FROM discord_links WHERE expires_at <= ?").use {
            it.setObject(1, utc(now))
            it.executeUpdate()
        }
}

/** `beta_access`. */
class JdbcBetaAccessStore : BetaAccessStore {
    override fun admit(connection: Connection, playerXuid: String, by: String, now: Instant): Boolean =
        try {
            connection.prepareStatement("INSERT INTO beta_access (player_xuid, admitted_by, admitted_at) VALUES (?, ?, ?)").use {
                it.setXuid(1, xuidOf(playerXuid))
                it.setString(2, by)
                it.setObject(3, utc(now))
                it.executeUpdate() == 1
            }
        } catch (e: SQLException) {
            if (e.isDuplicateKey()) false else throw e
        }

    override fun find(connection: Connection, playerXuid: String): BetaAccess? {
        val xuid = Xuid.parse(playerXuid) ?: return null
        return connection.prepareStatement("SELECT admitted_by, admitted_at FROM beta_access WHERE player_xuid = ?").use {
            it.setXuid(1, xuid)
            it.executeQuery().use { rows -> if (rows.next()) BetaAccess(playerXuid, rows.getString(1), rows.instant(2)) else null }
        }
    }

    override fun count(connection: Connection): Long =
        connection.prepareStatement("SELECT COUNT(*) FROM beta_access").use {
            it.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }
}

/** `web_sessions`. */
class JdbcWebSessionStore : WebSessionStore {
    override fun create(connection: Connection, session: WebSession) {
        connection.prepareStatement("INSERT INTO web_sessions (token_hash, account_id, csrf, created_at, expires_at) VALUES (?, ?, ?, ?, ?)").use {
            it.setString(1, session.tokenHash)
            it.setLong(2, session.accountId)
            it.setString(3, session.csrf)
            it.setObject(4, utc(session.createdAt))
            it.setObject(5, utc(session.expiresAt))
            it.executeUpdate()
        }
    }

    override fun find(connection: Connection, tokenHash: String, now: Instant): WebSession? =
        connection.prepareStatement("SELECT account_id, csrf, created_at, expires_at FROM web_sessions WHERE token_hash = ? AND expires_at > ?").use {
            it.setString(1, tokenHash)
            it.setObject(2, utc(now))
            it.executeQuery().use { rows ->
                if (rows.next()) WebSession(tokenHash, rows.getLong(1), rows.getString(2), rows.instant(3), rows.instant(4)) else null
            }
        }

    override fun delete(connection: Connection, tokenHash: String): Boolean =
        connection.prepareStatement("DELETE FROM web_sessions WHERE token_hash = ?").use {
            it.setString(1, tokenHash)
            it.executeUpdate() == 1
        }

    override fun deleteAll(connection: Connection, accountId: Long): Int =
        connection.prepareStatement("DELETE FROM web_sessions WHERE account_id = ?").use {
            it.setLong(1, accountId)
            it.executeUpdate()
        }

    override fun purge(connection: Connection, now: Instant): Int =
        connection.prepareStatement("DELETE FROM web_sessions WHERE expires_at <= ?").use {
            it.setObject(1, utc(now))
            it.executeUpdate()
        }
}
