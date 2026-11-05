package com.silooo.core.registration

/**
 * Le fournisseur de « Se connecter avec Xbox » : OAuth Microsoft (autorité `consumers`, portée `XboxLive.signin`), puis
 * jeton utilisateur Xbox Live, puis jeton XSTS (partie de confiance `http://xboxlive.com`), qui porte le gamertag et le
 * XUID (kit « Les comptes », Xbox). Une interface, pour qu'un test pose un faux fournisseur et que rien d'autre que
 * [HttpXboxIdentity] ne parle à Microsoft.
 */
interface XboxIdentity {
    /** L'adresse où envoyer le visiteur pour qu'il autorise ; [state] revient tel quel à [redirectUri]. */
    fun authorizeUrl(state: String, redirectUri: String): String

    /** Échange le [code] de retour contre le profil Xbox. Lève [XboxException] si une étape refuse ou ne répond pas. */
    suspend fun profile(code: String, redirectUri: String): XboxProfile
}

/** Ce que le site garde d'un compte Xbox : le XUID et le gamertag. Aucun e-mail, aucune liste d'amis. */
data class XboxProfile(val xuid: String, val gamertag: String)

/** Pourquoi la connexion Xbox n'a pas abouti ; la page écrit une phrase par cas. */
enum class XboxFailure {
    /** XSTS `2148916238` : compte enfant hors d'une famille Microsoft. */
    CHILD_ACCOUNT,

    /** XSTS `2148916233` : pas de profil Xbox sur ce compte Microsoft. */
    NO_XBOX_PROFILE,

    /** XSTS `2148916235` : Xbox Live n'existe pas dans ce pays. */
    NOT_IN_COUNTRY,

    /** Microsoft ou Xbox Live a refusé (code OAuth invalide, portée refusée, autre code XSTS). */
    REFUSED,

    /** Microsoft ou Xbox Live n'a pas répondu comme prévu (réseau, délai, réponse illisible). */
    UNAVAILABLE,
}

/**
 * Le fournisseur a refusé ou n'a pas répondu. Le message ne contient ni le code, ni un jeton, ni le XUID : il peut aller
 * dans un journal.
 */
class XboxException(val failure: XboxFailure, message: String) : Exception(message)

/** `SILOOO_XBOX_CLIENT_ID` et `SILOOO_XBOX_CLIENT_SECRET` (l'application Azure de Kr), posés ensemble. Le secret ne s'affiche jamais. */
data class XboxConfig(val clientId: String, val clientSecret: String) {
    override fun toString() = "XboxConfig(clientId=$clientId, clientSecret=***)"
}
