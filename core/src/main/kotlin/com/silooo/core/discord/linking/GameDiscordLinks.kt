package com.silooo.core.discord.linking

import com.silooo.core.api.Refusal
import com.silooo.core.api.RefusalCodes
import com.silooo.core.db.Database
import com.silooo.core.db.Xuid
import com.silooo.core.db.setXuid
import com.silooo.core.registration.AdmissionPath
import com.silooo.core.registration.LinkCodes
import com.silooo.core.registration.SiteAdmission
import com.silooo.core.registration.SiteRegistrations
import io.ktor.http.HttpStatusCode
import java.math.BigInteger
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.HexFormat

data class GameLinkCode(val code: String, val expiresAt: Instant)

enum class GameLinkStatus { LINKED, INVALID_CODE, EXPIRED_CODE, DISCORD_TAKEN, PLAYER_TAKEN, RATE_LIMITED }

data class VerifiedDiscordLink(val xuid: String, val gamertag: String, val linkedAt: Instant)

data class GameLinkResult(val status: GameLinkStatus, val link: VerifiedDiscordLink? = null)

/**
 * Preuve D3 : code affiché en jeu, consommé par le Discord authentifié. Les identités de connexion du site sont
 * seulement lues pour refuser leurs conflits. La saisie du code n'accorde ni récompense ni qualité de pionnier : seule la
 * salle d'attente inscrit pionnier, avant l'émission ([enrollLobbyVisitor]). Elle admet en bêta (décision 21 du site, chemin
 * a : Discord relié en jeu), par [SiteAdmission], comme la salle d'attente (chemin b).
 * Toutes les mutations D3 prennent le même petit verrou SQL, partagé entre instances du service.
 */
class GameDiscordLinks(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val generate: () -> String = { LinkCodes.generate() },
) {
    private val admission = SiteAdmission(clock = clock)

    /** Transaction de l'appelant, notamment celle de l'idempotence HTTP. Une nouvelle intention remplace l'ancien code. */
    fun issue(connection: Connection, actor: Xuid): GameLinkCode {
        lock(connection)
        player(connection, actor) ?: throw Refusal(HttpStatusCode.Conflict, RefusalCodes.UNKNOWN_PLAYER)
        val now = now()
        repeat(MAX_DRAWS) {
            val code = generate()
            check(LinkCodes.normalize(code) == code) { "Le générateur a produit un code de liaison invalide" }
            val hash = hash(code)
            if (code(connection, hash) == null) {
                connection.prepareStatement(
                    "insert into discord_game_codes (player_xuid, code_hash, created_at, expires_at) values (?, ?, ?, ?) " +
                        "on duplicate key update code_hash = values(code_hash), created_at = values(created_at), expires_at = values(expires_at)",
                ).use {
                    it.setXuid(1, actor)
                    it.setString(2, hash)
                    it.setObject(3, now.sql())
                    it.setObject(4, now.plus(LinkCodes.LIFETIME).sql())
                    it.executeUpdate()
                }
                return GameLinkCode(code, now.plus(LinkCodes.LIFETIME))
            }
        }
        error("Aucun code de liaison libre après $MAX_DRAWS tirages")
    }

    /**
     * La salle d'attente, avant l'ouverture : le visiteur n'est pas encore connu du service. Sa ligne `players` est créée sans
     * entreprise (`join` la créera à sa première vraie partie ; une ligne connue garde ses dates, seul le gamertag suit Xbox),
     * son inscription `site_registrations` est posée ou passée pionnier, puis admise en bêta (chemin b, [SiteAdmission]), puis
     * le code est émis comme par [issue]. Même verrou en tête que [redeem], donc le même ordre de verrous.
     */
    fun enrollLobbyVisitor(connection: Connection, actor: Xuid, gamertag: String): GameLinkCode {
        lock(connection)
        val known = player(connection, actor)
        if (known == null) {
            connection.prepareStatement("insert into players (xuid, floodgate_uuid, gamertag) values (?, ?, ?)").use {
                it.setXuid(1, actor)
                it.setString(2, actor.floodgateUuid.toString())
                it.setString(3, gamertag)
                it.executeUpdate()
            }
        } else if (known != gamertag) {
            connection.prepareStatement("update players set gamertag = ? where xuid = ?").use {
                it.setString(1, gamertag)
                it.setXuid(2, actor)
                it.executeUpdate()
            }
        }
        val key = SiteRegistrations.key(gamertag)
        val existing = registration(connection, "gamertag_key = ?", key)
        if (existing == null) {
            connection.prepareStatement(
                "insert into site_registrations (gamertag_key, gamertag, pioneer) values (?, ?, true)",
            ).use {
                it.setString(1, key)
                it.setString(2, gamertag)
                it.executeUpdate()
            }
        } else {
            connection.prepareStatement("update site_registrations set pioneer = true where gamertag_key = ?").use {
                it.setString(1, key)
                it.executeUpdate()
            }
        }
        admission.admit(connection, gamertag, actor.text, AdmissionPath.LOBBY_PIONEER)
        return issue(connection, actor)
    }

    /** Les refus gardent le code, mais comptent une saisie ; la consommation et les deux liaisons sont atomiques. */
    suspend fun redeem(discordId: String, typed: String): GameLinkResult {
        requireDiscordId(discordId)
        return database.transaction { connection ->
            lock(connection)
            val now = now()
            if (!attempt(connection, discordId, now)) return@transaction result(GameLinkStatus.RATE_LIMITED)
            val normalized = LinkCodes.normalize(typed) ?: return@transaction result(GameLinkStatus.INVALID_CODE)
            val hash = hash(normalized)
            val code = code(connection, hash) ?: return@transaction result(GameLinkStatus.INVALID_CODE)
            if (!now.isBefore(code.expiresAt)) return@transaction result(GameLinkStatus.EXPIRED_CODE)
            val gamertag = player(connection, code.xuid) ?: return@transaction result(GameLinkStatus.INVALID_CODE)
            val key = SiteRegistrations.key(gamertag)

            val discordLink = proof(connection, "g.discord_user_id = ?", discordId)
            if (discordLink != null && discordLink.profile.xuid != code.xuid.text) {
                return@transaction result(GameLinkStatus.DISCORD_TAKEN)
            }
            val playerLink = proof(connection, "g.player_xuid = ?", code.xuid.number)
            if (playerLink != null && playerLink.discordId != discordId) {
                return@transaction result(GameLinkStatus.PLAYER_TAKEN)
            }
            val keyLink = proof(connection, "g.gamertag_key = ?", key)
            if (keyLink != null && keyLink.profile.xuid != code.xuid.text) {
                return@transaction result(GameLinkStatus.PLAYER_TAKEN)
            }

            // Verrous des lignes existantes, et des plages uniques absentes, contre les autres flux du site.
            val registration = registration(connection, "gamertag_key = ?", key)
            if (registration?.discordId != null && registration.discordId != discordId) {
                return@transaction result(GameLinkStatus.PLAYER_TAKEN)
            }
            val discordRegistration = registration(connection, "discord_user_id = ?", discordId)
            if (discordRegistration != null && discordRegistration.key != key) {
                return@transaction result(GameLinkStatus.DISCORD_TAKEN)
            }
            val discordAccount = account(connection, "discord_user_id = ?", discordId)
            if (discordAccount?.xuid != null && discordAccount.xuid != code.xuid.text) {
                return@transaction result(GameLinkStatus.DISCORD_TAKEN)
            }
            val playerAccount = account(connection, "player_xuid = ?", code.xuid.number)
            if (playerAccount?.discordId != null && playerAccount.discordId != discordId) {
                return@transaction result(GameLinkStatus.PLAYER_TAKEN)
            }
            val linkedAt = nextLinkedAt(connection, discordId, now)

            if (registration == null) {
                connection.prepareStatement(
                    "insert into site_registrations (gamertag_key, gamertag, discord_user_id, linked_at, pioneer) values (?, ?, ?, ?, false)",
                ).use {
                    it.setString(1, key)
                    it.setString(2, gamertag)
                    it.setString(3, discordId)
                    it.setObject(4, linkedAt.sql())
                    it.executeUpdate()
                }
            } else if (registration.discordId == null) {
                connection.prepareStatement(
                    "update site_registrations set discord_user_id = ?, linked_at = ? where gamertag_key = ? and discord_user_id is null",
                ).use {
                    it.setString(1, discordId)
                    it.setObject(2, linkedAt.sql())
                    it.setString(3, key)
                    check(it.executeUpdate() == 1) { "L'inscription a changé pendant la preuve Discord" }
                }
            }
            // Un nouveau code peut confirmer à nouveau la même paire ; linked_at change aussi pour invalider les anciennes confirmations.
            if (playerLink == null) {
                connection.prepareStatement(
                    "insert into discord_game_links (player_xuid, discord_user_id, gamertag_key, linked_at) values (?, ?, ?, ?)",
                ).use {
                    it.setXuid(1, code.xuid)
                    it.setString(2, discordId)
                    it.setString(3, key)
                    it.setObject(4, linkedAt.sql())
                    it.executeUpdate()
                }
            } else {
                connection.prepareStatement(
                    "update discord_game_links set gamertag_key = ?, linked_at = ? where player_xuid = ? and discord_user_id = ?",
                ).use {
                    it.setString(1, key)
                    it.setObject(2, linkedAt.sql())
                    it.setXuid(3, code.xuid)
                    it.setString(4, discordId)
                    check(it.executeUpdate() == 1)
                }
            }
            connection.prepareStatement("delete from discord_game_codes where code_hash = ?").use {
                it.setString(1, hash)
                check(it.executeUpdate() == 1)
            }
            // Chemin a : le compte Discord est relié au joueur par la preuve du jeu, donc le joueur entre en bêta.
            admission.admit(connection, gamertag, code.xuid.text, AdmissionPath.DISCORD_IN_GAME)
            // Une première preuve par membre, conservée après déliaison : les confirmations ne gonflent pas le rapport.
            connection.prepareStatement(
                "insert ignore into discord_report_events (kind, event_id, user_id, occurred_at) values ('linked', ?, ?, ?)",
            ).use {
                it.setString(1, discordId)
                it.setString(2, discordId)
                it.setObject(3, now.sql())
                it.executeUpdate()
            }
            GameLinkResult(GameLinkStatus.LINKED, VerifiedDiscordLink(code.xuid.text, gamertag, linkedAt))
        }
    }

    /** Le site peut retirer sa paire : une preuve orpheline ne donne alors plus accès aux fonctions du jeu. */
    suspend fun findByDiscord(discordId: String): VerifiedDiscordLink? {
        requireDiscordId(discordId)
        return database.transaction { connection ->
            connection.prepareStatement(
                "select p.xuid, p.gamertag, g.linked_at from discord_game_links g " +
                    "join players p on p.xuid = g.player_xuid " +
                    "join site_registrations s on s.gamertag_key = g.gamertag_key and s.discord_user_id = g.discord_user_id " +
                    "where g.discord_user_id = ?",
            ).use {
                it.setString(1, discordId)
                it.executeQuery().use { rows -> if (rows.next()) rows.profile() else null }
            }
        }
    }

    /** Retire seulement la preuve et la paire correspondante, après vérification atomique du profil confirmé. */
    suspend fun unlink(
        discordId: String,
        expectedXuid: String? = null,
        expectedLinkedAt: Instant? = null,
    ): VerifiedDiscordLink? {
        requireDiscordId(discordId)
        return database.transaction { connection ->
            lock(connection)
            val proof = proof(connection, "g.discord_user_id = ?", discordId) ?: return@transaction null
            if (expectedXuid != null && proof.profile.xuid != expectedXuid) return@transaction null
            if (expectedLinkedAt != null && proof.profile.linkedAt != expectedLinkedAt) return@transaction null
            connection.prepareStatement(
                "update site_registrations set discord_user_id = null, linked_at = null where gamertag_key = ? and discord_user_id = ?",
            ).use {
                it.setString(1, proof.key)
                it.setString(2, discordId)
                it.executeUpdate()
            }
            connection.prepareStatement("delete from discord_game_links where discord_user_id = ?").use {
                it.setString(1, discordId)
                check(it.executeUpdate() == 1)
            }
            // Un code encore affiché avant la déliaison ne doit pas permettre de la rétablir.
            connection.prepareStatement("delete from discord_game_codes where player_xuid = ?").use {
                it.setXuid(1, checkNotNull(Xuid.parse(proof.profile.xuid)))
                it.executeUpdate()
            }
            proof.profile
        }
    }

    private fun lock(connection: Connection) {
        // Le no-op prend un verrou exclusif conservé jusqu'au commit/rollback. Fonctionne aussi après remise à zéro des tests.
        connection.prepareStatement("insert into discord_game_link_guard (id) values (1) on duplicate key update id = 1").use {
            it.executeUpdate()
        }
    }

    private fun player(connection: Connection, xuid: Xuid): String? =
        connection.prepareStatement("select gamertag from players where xuid = ? for update").use {
            it.setXuid(1, xuid)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private data class Code(val xuid: Xuid, val expiresAt: Instant)

    private fun code(connection: Connection, hash: String): Code? =
        connection.prepareStatement("select player_xuid, expires_at from discord_game_codes where code_hash = ? for update").use {
            it.setString(1, hash)
            it.executeQuery().use { rows ->
                if (rows.next()) Code(checkNotNull(Xuid.parse(rows.getString(1))), rows.instant("expires_at")) else null
            }
        }

    private data class Proof(val profile: VerifiedDiscordLink, val discordId: String, val key: String)

    private fun proof(connection: Connection, where: String, value: Any): Proof? =
        connection.prepareStatement(
            "select p.xuid, p.gamertag, g.linked_at, g.discord_user_id, g.gamertag_key from discord_game_links g " +
                "join players p on p.xuid = g.player_xuid where $where for update",
        ).use {
            it.bindValue(value)
            it.executeQuery().use { rows ->
                if (rows.next()) Proof(rows.profile(), rows.getString("discord_user_id"), rows.getString("gamertag_key")) else null
            }
        }

    private data class Registration(val key: String, val discordId: String?)

    private fun registration(connection: Connection, where: String, value: String): Registration? =
        connection.prepareStatement("select gamertag_key, discord_user_id from site_registrations where $where for update").use {
            it.setString(1, value)
            it.executeQuery().use { rows -> if (rows.next()) Registration(rows.getString(1), rows.getString(2)) else null }
        }

    private data class Account(val xuid: String?, val discordId: String?)

    private fun account(connection: Connection, where: String, value: Any): Account? =
        connection.prepareStatement("select player_xuid, discord_user_id from web_accounts where $where for update").use {
            it.bindValue(value)
            it.executeQuery().use { rows -> if (rows.next()) Account(rows.getString(1), rows.getString(2)) else null }
        }

    private fun attempt(connection: Connection, discordId: String, now: Instant): Boolean {
        val previous = connection.prepareStatement(
            "select window_started_at, attempts from discord_game_link_attempts where discord_user_id = ? for update",
        ).use {
            it.setString(1, discordId)
            it.executeQuery().use { rows -> if (rows.next()) rows.instant("window_started_at") to rows.getInt("attempts") else null }
        }
        val sameWindow = previous != null && now.isBefore(previous.first.plus(LinkCodes.LIFETIME))
        if (sameWindow && checkNotNull(previous).second >= MAX_ATTEMPTS) return false
        connection.prepareStatement(
            "insert into discord_game_link_attempts (discord_user_id, window_started_at, attempts) values (?, ?, ?) " +
                "on duplicate key update window_started_at = values(window_started_at), attempts = values(attempts)",
        ).use {
            it.setString(1, discordId)
            it.setObject(2, (if (sameWindow) checkNotNull(previous).first else now).sql())
            it.setInt(3, if (sameWindow) checkNotNull(previous).second + 1 else 1)
            it.executeUpdate()
        }
        return true
    }

    private fun nextLinkedAt(connection: Connection, discordId: String, now: Instant): Instant {
        val previous = connection.prepareStatement("select last_linked_at from discord_game_link_attempts where discord_user_id = ?").use {
            it.setString(1, discordId)
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.getObject(1, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
            }
        }
        val next = if (previous != null && !now.isAfter(previous)) previous.plusMillis(1) else now
        connection.prepareStatement("update discord_game_link_attempts set last_linked_at = ? where discord_user_id = ?").use {
            it.setObject(1, next.sql())
            it.setString(2, discordId)
            it.executeUpdate()
        }
        return next
    }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MILLIS)
    private fun result(status: GameLinkStatus) = GameLinkResult(status)
    private fun requireDiscordId(id: String) = require(DISCORD_ID.matches(id)) { "Identifiant Discord invalide" }

    private companion object {
        const val MAX_DRAWS = 20
        const val MAX_ATTEMPTS = 5
        val DISCORD_ID = Regex("[0-9]{1,20}")
        fun PreparedStatement.bindValue(value: Any) {
            if (value is BigInteger) setBigDecimal(1, BigDecimal(value)) else setObject(1, value)
        }
        fun hash(code: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.US_ASCII)))
        fun Instant.sql(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
        fun ResultSet.instant(column: String): Instant = getObject(column, LocalDateTime::class.java).toInstant(ZoneOffset.UTC)
        fun ResultSet.profile() = VerifiedDiscordLink(getString("xuid"), getString("gamertag"), instant("linked_at"))
    }
}
