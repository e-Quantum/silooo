package com.silooo.core.site

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Ce que le cookie de session porte : la clé du gamertag inscrit et, pendant « Relier Discord », l'`state` attendu au retour. */
internal class PioneerSession(val key: String, val state: String? = null)

/**
 * Signe les deux seules choses que le site confie au navigateur : le jeton caché du formulaire et le cookie de
 * session. Un jeton ou un cookie est `base64(charge).base64(HMAC-SHA256)` ; la charge commence par une lettre qui dit
 * ce que c'est (`f` formulaire, `c` cookie), pour qu'on ne puisse pas se servir de l'un à la place de l'autre. Le secret
 * est tiré au démarrage (`Dependencies.siteSessionSecret`) : un redémarrage invalide tout, une inscription en cours recommence.
 */
internal class SiteSigner(secret: ByteArray, private val clock: Clock) {
    private val key = SecretKeySpec(secret, "HmacSHA256")
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** Le jeton caché d'un formulaire : valable [FORM_TOKEN_LIFETIME]. */
    fun formToken(): String = sign("f|${clock.millis()}|${randomHex(8)}")

    fun isValidFormToken(token: String?): Boolean {
        val parts = verify(token)?.split('|') ?: return false
        if (parts.size != 3 || parts[0] != "f") return false
        val issued = parts[1].toLongOrNull() ?: return false
        val age = clock.millis() - issued
        // Un jeton « du futur » (plus d'une minute) n'a pas pu être émis par ce service.
        return age >= -CLOCK_SLACK_MILLIS && age <= FORM_TOKEN_LIFETIME.toMillis()
    }

    /** La valeur du cookie de session, qui expire dans [SESSION_LIFETIME]. */
    fun sessionCookie(session: PioneerSession): String {
        val expires = clock.instant().plus(SESSION_LIFETIME).epochSecond
        return sign("c|$expires|${session.key}|${session.state.orEmpty()}")
    }

    fun readSession(cookie: String?): PioneerSession? {
        val parts = verify(cookie)?.split('|') ?: return null
        if (parts.size != 4 || parts[0] != "c") return null
        val expires = parts[1].toLongOrNull() ?: return null
        if (expires <= clock.instant().epochSecond || parts[2].isEmpty()) return null
        return PioneerSession(parts[2], parts[3].ifEmpty { null })
    }

    /** Un état OAuth2 aléatoire, à usage unique. */
    fun newState(): String = randomHex(16)

    /** Compare deux textes en temps constant. */
    fun sameText(a: String?, b: String?): Boolean =
        a != null && b != null && MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun sign(payload: String): String {
        val body = encoder.encodeToString(payload.toByteArray(Charsets.UTF_8))
        return body + "." + encoder.encodeToString(mac(body))
    }

    /** La charge d'une valeur dont la signature est bonne, ou `null`. */
    private fun verify(value: String?): String? {
        if (value == null || value.length > MAX_VALUE_LENGTH) return null
        val dot = value.indexOf('.')
        if (dot <= 0) return null
        val body = value.substring(0, dot)
        val signature = try {
            decoder.decode(value.substring(dot + 1))
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (!MessageDigest.isEqual(signature, mac(body))) return null
        return try {
            String(decoder.decode(body), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun mac(body: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(body.toByteArray(Charsets.UTF_8))

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    companion object {
        val FORM_TOKEN_LIFETIME: Duration = Duration.ofMinutes(10)
        val SESSION_LIFETIME: Duration = Duration.ofHours(1)
        private const val CLOCK_SLACK_MILLIS = 60_000L
        private const val MAX_VALUE_LENGTH = 512
    }
}
