package com.silooo.core.registration

import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.Random

/**
 * Un compte du site (`web_accounts`, migration des comptes). Ni l'identifiant Discord ni le vrai XUID Xbox n'y sont :
 * ils ne sortent jamais de la base ([WebAccountStore] les reçoit et les cherche, rien ne les rend).
 */
data class WebAccount(
    val id: Long,
    /** L'empreinte du gamertag (`Empreinte.duGamertag`, la clé de `players`) ; `null` tant que le gamertag n'est pas connu. */
    val playerXuid: String?,
    /** Le gamertag tel que Xbox ou le jeu l'a donné ; `null` tant qu'il n'est pas connu. */
    val gamertag: String?,
    /** Le compte est entré par Xbox (le vrai XUID est en base, à part, jamais lu par les pages). */
    val xbox: Boolean,
    /** Un compte Discord est relié. */
    val discord: Boolean,
    val createdAt: Instant,
)

/** Ce que devient la pose d'un gamertag sur un compte du site. */
enum class PlayerOutcome {
    /** Le gamertag et son empreinte sont posés (ou l'étaient déjà, à l'identique). */
    SET,

    /** Cette empreinte est déjà celle d'un autre compte : rien n'est écrit. */
    PLAYER_TAKEN,

    /** Le compte n'existe pas. */
    NOT_FOUND,
}

/**
 * Les accès SQL de `web_accounts`. Comme les autres dépôts du service, chaque méthode reçoit la [Connection] de la
 * transaction ; le faux des tests (`InMemoryWebStores`) ne l'ouvre jamais.
 */
interface WebAccountStore {
    fun find(connection: Connection, id: Long): WebAccount?

    fun findByXbox(connection: Connection, xboxXuid: String): WebAccount?

    fun findByDiscord(connection: Connection, discordUserId: String): WebAccount?

    fun findByPlayer(connection: Connection, playerXuid: String): WebAccount?

    /** Crée un compte entré par Xbox : le gamertag et son empreinte sont connus tout de suite. L'appelant a vérifié qu'aucun compte ne porte [xboxXuid] ni [playerXuid]. */
    fun createXbox(connection: Connection, xboxXuid: String, gamertag: String, playerXuid: String, now: Instant): WebAccount

    /** Crée un compte entré par Discord : seul l'identifiant Discord est connu. L'appelant a vérifié qu'aucun compte ne porte [discordUserId]. */
    fun createDiscord(connection: Connection, discordUserId: String, now: Instant): WebAccount

    /** Pose le vrai XUID Xbox sur un compte qui n'en avait pas ; `false` si le compte n'existe pas ou en a déjà un autre. */
    fun attachXbox(connection: Connection, id: Long, xboxXuid: String): Boolean

    /** Pose (ou remplace) le gamertag et son empreinte sur le compte [id]. */
    fun setPlayer(connection: Connection, id: Long, gamertag: String, playerXuid: String): PlayerOutcome

    /** Retire le gamertag et son empreinte du compte [id] (« Délier » depuis le site) ; `false` si rien n'a changé. */
    fun clearPlayer(connection: Connection, id: Long): Boolean

    /** Relie [discordUserId] au compte [id] : [LinkOutcome.NOT_REGISTERED] si le compte n'existe pas. */
    fun setDiscord(connection: Connection, id: Long, discordUserId: String): LinkOutcome

    /** Retire le compte Discord du compte [id] ; `false` si rien n'a changé. */
    fun clearDiscord(connection: Connection, id: Long): Boolean

    /** Note une connexion (`last_login_at`). */
    fun touch(connection: Connection, id: Long, now: Instant)

    /**
     * L'identifiant Discord du compte, pour l'écrire dans `site_registrations` (le bot et le jeu la lisent) : une écriture du
     * service, jamais une page ni un journal.
     */
    fun discordUserId(connection: Connection, id: Long): String?
}

/** Une ligne de `waitlist` : un compte inscrit avant l'ouverture. */
data class WaitlistEntry(val accountId: Long, val createdAt: Instant, val pioneerGivenAt: Instant?)

/** Les accès SQL de `waitlist`. Le Pionnier lui-même est donné par le jeu (`player_cosmetics`) : le site ne pose que `pioneer_given_at` quand le jeu le lui dit. */
interface WaitlistStore {
    /** Inscrit le compte ; `false` s'il l'est déjà (rien n'est modifié). */
    fun insert(connection: Connection, accountId: Long, now: Instant): Boolean

    fun find(connection: Connection, accountId: Long): WaitlistEntry?

    fun count(connection: Connection): Long
}

/** Un code de liaison (`discord_links`) : le compte Discord qui l'a demandé, et jusqu'à quand il vaut. */
data class LinkCode(val code: String, val accountId: Long, val createdAt: Instant, val expiresAt: Instant) {
    fun expired(now: Instant): Boolean = !now.isBefore(expiresAt)
}

/** Les accès SQL de `discord_links` : un seul code vivant par compte. */
interface DiscordLinkStore {
    /** Pose [code] pour le compte, à la place de son code précédent s'il en avait un. */
    fun replace(connection: Connection, accountId: Long, code: String, now: Instant, expiresAt: Instant)

    fun findByAccount(connection: Connection, accountId: Long): LinkCode?

    fun findByCode(connection: Connection, code: String): LinkCode?

    /** Efface le code ; `false` s'il n'existait pas. */
    fun delete(connection: Connection, code: String): Boolean

    /** Efface les codes expirés à [now] ; rend combien. */
    fun purge(connection: Connection, now: Instant): Int
}

/** Les codes de « Relier Discord » : six caractères sans O, 0, I ni 1, valables dix minutes (kit « Les comptes »). */
object LinkCodes {
    const val LENGTH = 6
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val LIFETIME: Duration = Duration.ofMinutes(10)

    private val secure = SecureRandom()

    fun generate(random: Random = secure): String = buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** Ce qu'un joueur a tapé, ramené à la forme du code : majuscules, sans espace ni tiret ; `null` si ce n'est pas un code. */
    fun normalize(typed: String): String? {
        val cleaned = typed.trim().uppercase().replace(" ", "").replace("-", "")
        return cleaned.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }
}

/** Une empreinte admise en bêta (`beta_access`) : qui l'a admise, quand. */
data class BetaAccess(val playerXuid: String, val admittedBy: String, val admittedAt: Instant)

/** Les accès SQL de `beta_access`. Une admission n'est jamais retirée. */
interface BetaAccessStore {
    /** Admet l'empreinte ; `false` si elle l'était déjà (rien n'est modifié). */
    fun admit(connection: Connection, playerXuid: String, by: String, now: Instant): Boolean

    fun find(connection: Connection, playerXuid: String): BetaAccess?

    fun count(connection: Connection): Long
}

/** Une session du site (`web_sessions`) : le cookie n'en porte que le jeton, la base n'en garde que l'empreinte SHA-256. */
data class WebSession(
    val tokenHash: String,
    val accountId: Long,
    /** Le jeton anti-CSRF des formulaires de cette session. */
    val csrf: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/** Les accès SQL de `web_sessions`. */
interface WebSessionStore {
    fun create(connection: Connection, session: WebSession)

    /** La session vivante à [now], ou `null`. */
    fun find(connection: Connection, tokenHash: String, now: Instant): WebSession?

    /** Ferme la session ; `false` si elle n'existait pas. */
    fun delete(connection: Connection, tokenHash: String): Boolean

    /** Ferme toutes les sessions du compte ; rend combien. */
    fun deleteAll(connection: Connection, accountId: Long): Int

    /** Efface les sessions expirées à [now] ; rend combien. */
    fun purge(connection: Connection, now: Instant): Int
}

/** Les sessions du site : trente jours (kit « Sessions »). */
object WebSessions {
    val LIFETIME: Duration = Duration.ofDays(30)
}
