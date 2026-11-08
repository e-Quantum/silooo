package com.silooo.core.registration

import java.sql.Connection
import java.time.Clock
import java.util.Locale

/** Une inscription telle que le site la montre. L'identifiant Discord n'y est pas : il ne sort jamais de la base. */
data class SiteRegistration(
    val key: String,
    /** Le gamertag tel que le joueur l'a écrit. */
    val gamertag: String,
    val linked: Boolean,
    val admitted: Boolean,
)

/** Ce que devient une demande de relier un compte Discord à une inscription. */
enum class LinkOutcome {
    /** Le compte est relié (et l'inscription admise si la règle le dit). */
    LINKED,

    /** Cette inscription avait déjà un compte relié : rien n'est écrit. */
    ALREADY_LINKED,

    /** Ce compte Discord est déjà relié à une autre inscription : rien n'est écrit. */
    DISCORD_TAKEN,

    /** La clé n'est pas inscrite. */
    NOT_REGISTERED,
}

/**
 * Les accès SQL de `site_registrations` (V7). Le site n'écrit nulle part ailleurs. Comme les autres dépôts du
 * service, chaque méthode reçoit la [Connection] de la transaction ; le faux des tests ne l'ouvre jamais.
 */
interface SiteRegistrationStore {
    /**
     * Inscrit [gamertag] sous [key] ; `false` si la clé existe déjà (rien n'est modifié). [admitted] pose `admitted_at`.
     * [pioneer] pose `pioneer` : vrai pour une inscription d'avant l'ouverture (le titre Pionnier que le jeu donne à `join`),
     * faux pour un compte relié depuis le jeu après l'ouverture.
     */
    fun insert(connection: Connection, key: String, gamertag: String, admitted: Boolean, pioneer: Boolean = true): Boolean

    fun find(connection: Connection, key: String): SiteRegistration?

    /** Le nombre de lignes `pioneer`. */
    fun countPioneers(connection: Connection): Long

    /** Range [discordUserId] sur l'inscription [key] ; [admit] pose `admitted_at` si l'inscription ne l'a pas encore. */
    fun link(connection: Connection, key: String, discordUserId: String, admit: Boolean): LinkOutcome

    /**
     * Retire `discord_user_id` et `linked_at` de l'inscription [key] (« Délier » depuis le site) ; `true` si la ligne a changé.
     * L'admission n'est pas retirée : une admission n'est jamais retirée.
     */
    fun unlink(connection: Connection, key: String): Boolean

    /**
     * Pose `admitted_at` à maintenant (UTC) sur l'inscription [key] si elle ne l'a pas encore ; `true` si la ligne a changé.
     * Le staff admet par ici depuis `#admissions` (bot Discord) ; une admission n'est jamais retirée.
     */
    fun admit(connection: Connection, key: String): Boolean
}

/**
 * Le gamertag d'une inscription : sa clé. La clé est le seul lien entre le site et le jeu : la route de `join` calcule
 * [key] sur le gamertag qu'elle reçoit et cherche cette clé dans `site_registrations`. Le site ne contrôle plus la forme
 * d'un gamertag : il le tient de Xbox (`/connexion/xbox/`) ou du jeu (le code de `/compte/lier/`), jamais d'une saisie.
 */
object SiteRegistrations {
    private val SPACES = Regex("\\s+")

    /** La clé d'un gamertag : sans espace aux bords, espaces réduits à un seul, minuscules. */
    fun key(gamertag: String): String = gamertag.trim().replace(SPACES, " ").lowercase(Locale.ROOT)
}

/**
 * Les trois chemins par lesquels un joueur entre en bêta (décision 21 de `docs/site-passation.md` : l'admission est
 * automatique, le staff n'admet personne). [admittedBy] est ce que `beta_access.admitted_by` garde, un chemin par mot.
 */
enum class AdmissionPath(val admittedBy: String) {
    /** (a) Le joueur a relié son compte Discord en jeu : la preuve D3, un code montré en jeu et saisi sur Discord. */
    DISCORD_IN_GAME("regle:discord-jeu"),

    /** (b) Il est Pionnier par le code de la salle d'attente (`issueLobbyLinkCode`). */
    LOBBY_PIONEER("regle:pionnier-salle"),

    /** (c) Il s'est inscrit sur le site avec la connexion Microsoft (Xbox), avant ou après l'ouverture. */
    MICROSOFT("regle:microsoft"),
}

/**
 * La règle d'admission, en un seul endroit. Un joueur entre en bêta par un des [AdmissionPath] et par rien d'autre : une
 * inscription par Discord seul, une saisie sur `/compte/lier/` sans preuve du jeu, ou un lien Discord posé sur le site
 * n'admettent personne. [admit] pose `beta_access` (ce que `session/join` lira en mode `beta`) et `admitted_at` de
 * `site_registrations` (V7, que le bot lit), les deux ensemble ; une admission n'est jamais retirée. Le site reste
 * celui qui écrit : le staff n'admet plus, et il n'y a plus de réglage.
 */
class SiteAdmission(
    private val betaAccess: BetaAccessStore = JdbcBetaAccessStore(),
    private val registrations: SiteRegistrationStore = JdbcSiteRegistrationStore(),
    private val clock: Clock = Clock.systemUTC(),
) {
    /** `true` si le joueur vient d'entrer, `false` s'il l'était déjà. [gamertag] donne la clé de V7, [playerXuid] l'empreinte de `beta_access`. */
    fun admit(connection: Connection, gamertag: String, playerXuid: String, path: AdmissionPath): Boolean {
        registrations.admit(connection, SiteRegistrations.key(gamertag))
        return betaAccess.admit(connection, playerXuid, path.admittedBy, clock.instant())
    }
}
