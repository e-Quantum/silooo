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
    /** Inscrit [gamertag] sous [key] ; `false` si la clé existe déjà (rien n'est modifié). [admitted] pose `admitted_at`. */
    fun insert(connection: Connection, key: String, gamertag: String, admitted: Boolean): Boolean

    fun find(connection: Connection, key: String): SiteRegistration?

    /** Le nombre de lignes `pioneer`. */
    fun countPioneers(connection: Connection): Long

    /** Range [discordUserId] sur l'inscription [key] ; [admit] pose `admitted_at` si l'inscription ne l'a pas encore. */
    fun link(connection: Connection, key: String, discordUserId: String, admit: Boolean): LinkOutcome

    /**
     * Pose `admitted_at` à maintenant (UTC) sur l'inscription [key] si elle ne l'a pas encore ; `true` si la ligne a changé.
     * Le staff admet par ici depuis `#admissions` (bot Discord) ; une admission n'est jamais retirée.
     */
    fun admit(connection: Connection, key: String): Boolean
}

/** Pourquoi un gamertag est refusé ; la phrase que le visiteur lit est écrite par la page (`site/pages/Pionniers.kt`). */
enum class GamertagProblem { EDGE_SPACE, BAD_CHARACTER, DOUBLE_SPACE, TOO_SHORT, TOO_LONG }

/**
 * Le gamertag d'une inscription : sa forme et sa clé. La clé est le seul lien entre le site et le jeu : la route de
 * `join` calcule [key] sur le gamertag qu'elle reçoit et cherche cette clé dans `site_registrations`.
 */
object SiteRegistrations {
    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 16

    private val SPACES = Regex("\\s+")

    /** La clé d'un gamertag : sans espace aux bords, espaces réduits à un seul, minuscules. */
    fun key(gamertag: String): String = gamertag.trim().replace(SPACES, " ").lowercase(Locale.ROOT)

    /**
     * Contrôle ce que le visiteur a saisi, tel quel : le site refuse, il ne corrige pas. Lettres sans accent, chiffres
     * et espaces simples ; ni espace au début ni à la fin ; de [MIN_LENGTH] à [MAX_LENGTH] caractères. `null` : valable.
     */
    fun problem(raw: String): GamertagProblem? = when {
        raw.startsWith(" ") || raw.endsWith(" ") -> GamertagProblem.EDGE_SPACE
        raw.any { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == ' ') } -> GamertagProblem.BAD_CHARACTER
        raw.contains("  ") -> GamertagProblem.DOUBLE_SPACE
        raw.length < MIN_LENGTH -> GamertagProblem.TOO_SHORT
        raw.length > MAX_LENGTH -> GamertagProblem.TOO_LONG
        else -> null
    }
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
