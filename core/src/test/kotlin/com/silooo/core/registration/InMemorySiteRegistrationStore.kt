package com.silooo.core.registration

import java.sql.Connection

/**
 * Le faux de [SiteRegistrationStore] : une table en mémoire avec les mêmes règles que V7 (clé primaire, identifiant Discord
 * unique). Il n'ouvre jamais la connexion. Le SQL se teste sur MariaDB, dans `SiteRegistrationSqlTest`.
 */
class InMemorySiteRegistrationStore : SiteRegistrationStore {
    class Row(val gamertag: String, var discord: String? = null, var admitted: Boolean = false, val pioneer: Boolean = true)

    val rows = linkedMapOf<String, Row>()

    override fun insert(connection: Connection, key: String, gamertag: String, admitted: Boolean): Boolean {
        if (key in rows) return false
        rows[key] = Row(gamertag, admitted = admitted)
        return true
    }

    override fun find(connection: Connection, key: String): SiteRegistration? =
        rows[key]?.let { SiteRegistration(key, it.gamertag, it.discord != null, it.admitted) }

    override fun countPioneers(connection: Connection): Long = rows.values.count { it.pioneer }.toLong()

    override fun link(connection: Connection, key: String, discordUserId: String, admit: Boolean): LinkOutcome {
        val row = rows[key] ?: return LinkOutcome.NOT_REGISTERED
        if (row.discord != null) return LinkOutcome.ALREADY_LINKED
        if (rows.values.any { it.discord == discordUserId }) return LinkOutcome.DISCORD_TAKEN
        row.discord = discordUserId
        if (admit) row.admitted = true
        return LinkOutcome.LINKED
    }

    override fun admit(connection: Connection, key: String): Boolean {
        val row = rows[key] ?: return false
        if (row.admitted) return false
        row.admitted = true
        return true
    }
}

/** Le faux fournisseur de « Relier Discord » : il rend l'identifiant qu'on lui a donné, ou échoue. */
class FakeDiscordIdentity(var userId: String = "413000000000000001", var failing: Boolean = false) : DiscordIdentity {
    var lastRedirectUri: String? = null
    var lastCode: String? = null

    override fun authorizeUrl(state: String, redirectUri: String): String =
        "https://discord.test/oauth2/authorize?state=$state&redirect_uri=$redirectUri"

    override suspend fun userId(code: String, redirectUri: String): String {
        lastCode = code
        lastRedirectUri = redirectUri
        if (failing) throw DiscordException("Discord a refusé (test)")
        return userId
    }
}
