package com.silooo.core.registration

/**
 * Le faux fournisseur de « Se connecter avec Xbox » : il rend le profil qu'on lui a donné, ou lève [XboxException] avec la
 * raison posée dans [failure]. Il note le dernier code et la dernière adresse de retour reçus.
 */
class FakeXboxIdentity(
    var profile: XboxProfile = XboxProfile("2535400000000001", "Kr Test"),
    var failure: XboxFailure? = null,
) : XboxIdentity {
    var lastCode: String? = null
    var lastRedirectUri: String? = null

    override fun authorizeUrl(state: String, redirectUri: String): String =
        "https://xbox.test/authorize?state=$state&redirect_uri=$redirectUri"

    override suspend fun profile(code: String, redirectUri: String): XboxProfile {
        lastCode = code
        lastRedirectUri = redirectUri
        failure?.let { throw XboxException(it, "Faux fournisseur Xbox : refus simulé ($it)") }
        return profile
    }
}
