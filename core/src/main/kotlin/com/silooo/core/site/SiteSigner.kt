package com.silooo.core.site

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Ce que le cookie d'une connexion en cours porte, entre le départ vers Xbox ou Discord et le retour : l'`state` attendu,
 * la porte (`x` ou `d`) et, pour Discord, la case « Rejoindre aussi le Discord ».
 */
internal class OAuthStart(val state: String, val door: Char, val joinGuild: Boolean)

/**
 * Signe la seule chose que le site confie au navigateur sans la garder en base : le cookie d'une connexion en cours
 * (la session, elle, vit dans `web_sessions` ; son cookie est un jeton aléatoire). Une valeur est
 * `base64(charge).base64(HMAC-SHA256)` ; la charge commence par une lettre qui dit ce que c'est (`o` départ OAuth), pour
 * qu'on ne puisse pas se servir de l'une à la place d'une autre. Le secret est tiré au démarrage
 * (`Dependencies.siteSessionSecret`) : un redémarrage invalide une connexion en cours, qui recommence.
 */
internal class SiteSigner(secret: ByteArray, private val clock: Clock) {
    private val key = SecretKeySpec(secret, "HmacSHA256")
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** La valeur du cookie d'une connexion en cours, qui expire dans [START_LIFETIME]. */
    fun startCookie(start: OAuthStart): String {
        val expires = clock.instant().plus(START_LIFETIME).epochSecond
        return sign("o|$expires|${start.state}|${start.door}|${if (start.joinGuild) 1 else 0}")
    }

    fun readStart(cookie: String?): OAuthStart? {
        val parts = verify(cookie)?.split('|') ?: return null
        if (parts.size != 5 || parts[0] != "o") return null
        val expires = parts[1].toLongOrNull() ?: return null
        if (expires <= clock.instant().epochSecond || parts[2].isEmpty() || parts[3].length != 1) return null
        return OAuthStart(parts[2], parts[3][0], parts[4] == "1")
    }

    /** Un état OAuth2 aléatoire, à usage unique. */
    fun newState(): String = randomHex(16)

    /** Le jeton d'une session (32 octets aléatoires) : le cookie le porte, la base n'en garde que [hash]. */
    fun newToken(): String = encoder.encodeToString(ByteArray(32).also { random.nextBytes(it) })

    /** Le jeton anti-CSRF d'une session. */
    fun newCsrf(): String = randomHex(16)

    /** L'empreinte SHA-256 d'un jeton, en hexadécimal (64 caractères). */
    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

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
        /** Le temps laissé au visiteur pour revenir de Xbox ou de Discord. */
        val START_LIFETIME: Duration = Duration.ofMinutes(10)
        private const val MAX_VALUE_LENGTH = 512
    }
}
