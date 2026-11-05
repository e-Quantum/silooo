package com.silooo.core.site.pages

/**
 * Ce que les pages des comptes reçoivent des routes (`site/AccountRoutes.kt`). Aucune vue ne porte un identifiant
 * Discord ni un XUID Xbox : les pages ne peuvent pas les afficher.
 */

/** Les adresses des comptes, partagées entre les routes et les pages. */
internal object AccountUrls {
    const val CONNEXION = "/connexion/"
    const val XBOX = "/connexion/xbox/"
    const val XBOX_RETURN = "/connexion/xbox/retour/"
    const val DISCORD = "/connexion/discord/"
    const val DISCORD_RETURN = "/connexion/discord/retour/"
    const val LOGOUT = "/deconnexion/"
    const val PIONNIERS = "/pionniers/"
    const val INSCRIT = "/pionniers/etat/"
    const val COMPTE = "/compte/"
    const val LIER = "/compte/lier/"
    const val DELIER = "/compte/lier/delier/"
}

/**
 * Les deux portes (`/connexion/` et `/pionniers/`, maquette SiteInscription, écran 1). [xbox] et [discord] disent si
 * l'application est configurée ; une porte sans application est annoncée « bientôt », jamais un lien mort.
 * [inscription] : la page des pionniers (la promesse de la bêta et du Pionnier) ; sinon la connexion simple.
 */
internal class ConnexionView(val xbox: Boolean, val discord: Boolean, val inscription: Boolean)

/**
 * « C'est noté ! » (`/pionniers/etat/`, maquette écran 2). [gamertag] : inscrit par Xbox, le gamertag est réservé ;
 * `null` : inscrit par Discord, le bot prévient, et le compte du jeu se relie plus tard. [lier] : le lien vers
 * [AccountUrls.LIER] s'affiche (la page du compte existe dans ce mode et le gamertag manque encore).
 */
internal class InscritView(val gamertag: String?, val lier: Boolean)

/** Les pages qui ne sont qu'une phrase et un retour. Le compte enfant a sa page à lui ([microsoftRefused]). */
internal enum class AccountMessage(val title: String, val text: String, val backUrl: String, val backLabel: String) {
    TOO_MANY("Trop d'essais", "Trop d'essais. Réessaie dans une minute.", AccountUrls.CONNEXION, "Retour à la connexion"),
    FORM_EXPIRED("Formulaire expiré", "Le formulaire a expiré. Envoie-le de nouveau.", AccountUrls.COMPTE, "Retour à mon compte"),
    XBOX_FAILED("Connexion Xbox", "La connexion Xbox n'a pas abouti. Reprends depuis le début.", AccountUrls.CONNEXION, "Retour à la connexion"),
    XBOX_DOWN("Connexion Xbox", "Xbox ne répond pas pour l'instant. Réessaie dans un moment.", AccountUrls.CONNEXION, "Retour à la connexion"),
    XBOX_NO_PROFILE("Connexion Xbox", "Ce compte Microsoft n'a pas de profil Xbox. Crée-le sur xbox.com, puis reviens.", AccountUrls.CONNEXION, "Retour à la connexion"),
    XBOX_NOT_IN_COUNTRY("Connexion Xbox", "Xbox Live n'existe pas dans ton pays : Microsoft refuse la connexion.", AccountUrls.CONNEXION, "Retour à la connexion"),
    GAMERTAG_TAKEN("Connexion Xbox", "Ce gamertag est déjà relié à un autre compte du site.", AccountUrls.CONNEXION, "Retour à la connexion"),
    DISCORD_FAILED("Connexion Discord", "La connexion Discord n'a pas abouti. Reprends depuis le début.", AccountUrls.CONNEXION, "Retour à la connexion"),
    DISCORD_DOWN("Connexion Discord", "Discord ne répond pas pour l'instant. Réessaie dans un moment.", AccountUrls.CONNEXION, "Retour à la connexion"),
    DISCORD_TAKEN("Relier Discord", "Ce compte Discord est déjà relié à un autre joueur.", AccountUrls.COMPTE, "Retour à mon compte"),
}

/**
 * « Mon compte » (`/compte/`), réduit à ce que cette branche sait : le compte du jeu, le Discord, l'inscription. Le tableau
 * de l'entreprise de la maquette SiteCompte vient avec `claude/site-compte`.
 */
internal class CompteView(
    /** Le gamertag connu, ou `null` : le compte du jeu n'est pas encore relié. */
    val gamertag: String?,
    val xbox: Boolean,
    val discord: Boolean,
    /** « Relier Discord » est proposé : l'application existe et le compte n'a pas de Discord. */
    val discordButton: Boolean,
    /** Inscrit avant l'ouverture (liste d'attente). */
    val pionnier: Boolean,
    /** Le jeton anti-CSRF des formulaires (déconnexion). */
    val csrf: String,
)

/** Les trois états de `/compte/lier/`. */
internal enum class LierKind {
    /** Un code à taper en jeu (maquette SiteLier, écran 1). */
    CODE,

    /** Le compte du jeu est relié (écran 2). */
    LINKED,

    /** Compte entré par Xbox : le jeu est déjà connu, c'est le Discord qui se relie ([AccountUrls.DISCORD]). */
    XBOX,
}

/** « Lier mon compte » (`/compte/lier/`). */
internal class LierView(
    val kind: LierKind,
    /** Le code, en [LierKind.CODE] seulement. */
    val code: String?,
    /** Les secondes qu'il reste au code, en [LierKind.CODE] seulement. */
    val secondsLeft: Long?,
    /** Le gamertag relié, en [LierKind.LINKED] (et en [LierKind.XBOX]). */
    val gamertag: String?,
    /** « Relier Discord » est proposé en [LierKind.XBOX] : l'application existe. */
    val discordButton: Boolean,
    val csrf: String,
)
