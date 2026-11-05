package com.silooo.core.registration

import java.sql.Connection
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

/** Les deux moments où une inscription peut devenir admise. */
enum class AdmissionMoment { REGISTRATION, DISCORD_LINK }

/**
 * La règle d'admission, en un seul endroit (`SILOOO_SITE_ADMISSION`). `admitted_at` est posé :
 *  - avec [TOUS], dès l'inscription ;
 *  - avec [DISCORD], au moment où le compte Discord est relié.
 * Kr peut aussi admettre à la main (requête dans `core/README.md`). Le site ne retire jamais une admission.
 */
enum class SiteAdmission(val word: String) {
    DISCORD("discord"),
    TOUS("tous"),
    ;

    /** `true` quand l'inscription doit être admise à ce moment. */
    fun admitsAt(moment: AdmissionMoment): Boolean = when (this) {
        TOUS -> true
        DISCORD -> moment == AdmissionMoment.DISCORD_LINK
    }

    companion object {
        fun parse(raw: String): SiteAdmission? = entries.firstOrNull { it.word == raw.trim().lowercase() }
    }
}
