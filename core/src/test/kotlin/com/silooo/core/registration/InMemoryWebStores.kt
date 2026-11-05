package com.silooo.core.registration

import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit

/*
 * Les faux des dépôts des comptes du site : des tables en mémoire avec les mêmes règles d'unicité que V11 (clés uniques de
 * `web_accounts`, clé primaire des codes, des admissions et des sessions). Ils n'ouvrent jamais la connexion. Les clés étrangères
 * ne sont pas rejouées (un test qui supprime un compte supprime lui-même ses lignes) ; le SQL, lui, se teste sur MariaDB, dans
 * `WebStoresSqlTest`. Les dates sont ramenées à la milliseconde, comme DATETIME(3).
 */

private fun Instant.millis(): Instant = truncatedTo(ChronoUnit.MILLIS)

/** Le faux de [WebAccountStore]. */
class InMemoryWebAccountStore : WebAccountStore {
    class Row(
        val id: Long,
        var playerXuid: String?,
        var gamertag: String?,
        var xboxXuid: String?,
        var discordUserId: String?,
        val createdAt: Instant,
        var lastLoginAt: Instant? = null,
    )

    val rows = linkedMapOf<Long, Row>()
    private var nextId = 1L

    private fun view(row: Row) = WebAccount(row.id, row.playerXuid, row.gamertag, row.xboxXuid != null, row.discordUserId != null, row.createdAt)

    override fun find(connection: Connection, id: Long): WebAccount? = rows[id]?.let(::view)

    override fun findByXbox(connection: Connection, xboxXuid: String): WebAccount? = rows.values.firstOrNull { it.xboxXuid == xboxXuid }?.let(::view)

    override fun findByDiscord(connection: Connection, discordUserId: String): WebAccount? =
        rows.values.firstOrNull { it.discordUserId == discordUserId }?.let(::view)

    override fun findByPlayer(connection: Connection, playerXuid: String): WebAccount? =
        rows.values.firstOrNull { it.playerXuid == playerXuid }?.let(::view)

    override fun createXbox(connection: Connection, xboxXuid: String, gamertag: String, playerXuid: String, now: Instant): WebAccount {
        check(rows.values.none { it.xboxXuid == xboxXuid }) { "clé unique uq_web_accounts_xbox" }
        check(rows.values.none { it.playerXuid == playerXuid }) { "clé unique uq_web_accounts_player" }
        val row = Row(nextId++, playerXuid, gamertag, xboxXuid, null, now.millis())
        rows[row.id] = row
        return view(row)
    }

    override fun createDiscord(connection: Connection, discordUserId: String, now: Instant): WebAccount {
        check(rows.values.none { it.discordUserId == discordUserId }) { "clé unique uq_web_accounts_discord" }
        val row = Row(nextId++, null, null, null, discordUserId, now.millis())
        rows[row.id] = row
        return view(row)
    }

    override fun attachXbox(connection: Connection, id: Long, xboxXuid: String): Boolean {
        val row = rows[id] ?: return false
        if (row.xboxXuid == xboxXuid) return true
        if (row.xboxXuid != null) return false
        if (rows.values.any { it.xboxXuid == xboxXuid }) return false
        row.xboxXuid = xboxXuid
        return true
    }

    override fun setPlayer(connection: Connection, id: Long, gamertag: String, playerXuid: String): PlayerOutcome {
        val row = rows[id] ?: return PlayerOutcome.NOT_FOUND
        if (rows.values.any { it.id != id && it.playerXuid == playerXuid }) return PlayerOutcome.PLAYER_TAKEN
        row.gamertag = gamertag
        row.playerXuid = playerXuid
        return PlayerOutcome.SET
    }

    override fun clearPlayer(connection: Connection, id: Long): Boolean {
        val row = rows[id] ?: return false
        if (row.gamertag == null && row.playerXuid == null) return false
        row.gamertag = null
        row.playerXuid = null
        return true
    }

    override fun setDiscord(connection: Connection, id: Long, discordUserId: String): LinkOutcome {
        val row = rows[id] ?: return LinkOutcome.NOT_REGISTERED
        if (row.discordUserId != null) return LinkOutcome.ALREADY_LINKED
        if (rows.values.any { it.discordUserId == discordUserId }) return LinkOutcome.DISCORD_TAKEN
        row.discordUserId = discordUserId
        return LinkOutcome.LINKED
    }

    override fun clearDiscord(connection: Connection, id: Long): Boolean {
        val row = rows[id] ?: return false
        if (row.discordUserId == null) return false
        row.discordUserId = null
        return true
    }

    override fun touch(connection: Connection, id: Long, now: Instant) {
        rows[id]?.lastLoginAt = now.millis()
    }

    override fun discordUserId(connection: Connection, id: Long): String? = rows[id]?.discordUserId
}

/** Le faux de [WaitlistStore]. */
class InMemoryWaitlistStore : WaitlistStore {
    val entries = linkedMapOf<Long, WaitlistEntry>()

    override fun insert(connection: Connection, accountId: Long, now: Instant): Boolean {
        if (accountId in entries) return false
        entries[accountId] = WaitlistEntry(accountId, now.millis(), null)
        return true
    }

    override fun find(connection: Connection, accountId: Long): WaitlistEntry? = entries[accountId]

    override fun count(connection: Connection): Long = entries.size.toLong()
}

/** Le faux de [DiscordLinkStore] : un seul code par compte, un code n'appartient qu'à un compte. */
class InMemoryDiscordLinkStore : DiscordLinkStore {
    val byCode = linkedMapOf<String, LinkCode>()

    override fun replace(connection: Connection, accountId: Long, code: String, now: Instant, expiresAt: Instant) {
        byCode.values.removeAll { it.accountId == accountId }
        check(code !in byCode) { "clé primaire de discord_links" }
        byCode[code] = LinkCode(code, accountId, now.millis(), expiresAt.millis())
    }

    override fun findByAccount(connection: Connection, accountId: Long): LinkCode? = byCode.values.firstOrNull { it.accountId == accountId }

    override fun findByCode(connection: Connection, code: String): LinkCode? = byCode[code]

    override fun delete(connection: Connection, code: String): Boolean = byCode.remove(code) != null

    override fun purge(connection: Connection, now: Instant): Int {
        val expired = byCode.values.filter { it.expired(now) }.map { it.code }
        expired.forEach { byCode.remove(it) }
        return expired.size
    }
}

/** Le faux de [BetaAccessStore]. */
class InMemoryBetaAccessStore : BetaAccessStore {
    val rows = linkedMapOf<String, BetaAccess>()

    override fun admit(connection: Connection, playerXuid: String, by: String, now: Instant): Boolean {
        if (playerXuid in rows) return false
        rows[playerXuid] = BetaAccess(playerXuid, by, now.millis())
        return true
    }

    override fun find(connection: Connection, playerXuid: String): BetaAccess? = rows[playerXuid]

    override fun count(connection: Connection): Long = rows.size.toLong()
}

/** Le faux de [WebSessionStore] : une session par empreinte de jeton, vivante tant que `now` est avant `expiresAt`. */
class InMemoryWebSessionStore : WebSessionStore {
    val sessions = linkedMapOf<String, WebSession>()

    override fun create(connection: Connection, session: WebSession) {
        check(session.tokenHash !in sessions) { "clé primaire de web_sessions" }
        sessions[session.tokenHash] = session.copy(createdAt = session.createdAt.millis(), expiresAt = session.expiresAt.millis())
    }

    override fun find(connection: Connection, tokenHash: String, now: Instant): WebSession? =
        sessions[tokenHash]?.takeIf { now.isBefore(it.expiresAt) }

    override fun delete(connection: Connection, tokenHash: String): Boolean = sessions.remove(tokenHash) != null

    override fun deleteAll(connection: Connection, accountId: Long): Int {
        val mine = sessions.values.filter { it.accountId == accountId }.map { it.tokenHash }
        mine.forEach { sessions.remove(it) }
        return mine.size
    }

    override fun purge(connection: Connection, now: Instant): Int {
        val expired = sessions.values.filter { !now.isBefore(it.expiresAt) }.map { it.tokenHash }
        expired.forEach { sessions.remove(it) }
        return expired.size
    }
}
