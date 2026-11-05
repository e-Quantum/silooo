package com.silooo.core.vote

import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Le décodage, la signature et le défi de Votifier 2, en fonctions pures sur des octets : aucune socket. Le jeton est un faux
 * évident ([TOKEN]) ; le vrai ne vit que dans l'environnement du conteneur.
 */
class VotifierProtocolTest {
    private val token = TOKEN.toByteArray(Charsets.UTF_8)

    private fun payload(
        challenge: String = CHALLENGE,
        username: String = "Kr",
        serviceName: String = "Hubyy",
    ): String = buildJsonObject {
        put("serviceName", serviceName)
        put("username", username)
        put("address", "203.0.113.7")
        put("timestamp", 1762344000000L)
        put("challenge", challenge)
    }.toString()

    private fun signature(payload: String, key: String = TOKEN): String =
        Base64.getEncoder().encodeToString(Votifier2.sign(payload, key.toByteArray(Charsets.UTF_8)))

    /** Le message signé : `{"payload":"…","signature":"…"}`. */
    private fun message(payload: String, signature: String = signature(payload)): ByteArray =
        buildJsonObject {
            put("payload", payload)
            put("signature", signature)
        }.toString().toByteArray(Charsets.UTF_8)

    /** L'en-tête (magie, longueur) puis le message. */
    private fun frame(message: ByteArray, magic: Int = Votifier2.MAGIC, length: Int = message.size): ByteArray =
        byteArrayOf((magic shr 8).toByte(), magic.toByte(), (length shr 8).toByte(), length.toByte()) + message

    private fun failureOf(frame: ByteArray, challenge: String = CHALLENGE): VotifierFailure =
        assertThrows<VotifierException> { Votifier2.decode(frame, token, challenge) }.failure

    // --- le cas bon

    @Test
    @DisplayName("Un message signé avec le bon jeton et le bon défi donne le vote : service, pseudo, adresse, heure")
    fun goodMessageGivesTheVote() {
        val vote = Votifier2.decode(frame(message(payload())), token, CHALLENGE)

        assertEquals(VotifierVote(serviceName = "Hubyy", username = "Kr", address = "203.0.113.7", timestamp = "1762344000000"), vote)
    }

    @Test
    @DisplayName("L'heure et l'adresse peuvent manquer ou être des chaînes ; le pseudo est rogné")
    fun optionalFieldsAndTrimming() {
        val text = buildJsonObject {
            put("serviceName", " Hubyy ")
            put("username", " Kr ")
            put("timestamp", "1762344000000")
            put("challenge", CHALLENGE)
        }.toString()

        val vote = Votifier2.decode(frame(message(text)), token, CHALLENGE)

        assertEquals("Hubyy", vote.serviceName)
        assertEquals("Kr", vote.username)
        assertEquals("", vote.address)
        assertEquals("1762344000000", vote.timestamp)
    }

    @Test
    @DisplayName("Les octets après la longueur annoncée sont ignorés")
    fun trailingBytesAreIgnored() {
        val bytes = frame(message(payload())) + "\r\n".toByteArray()

        assertEquals("Kr", Votifier2.decode(bytes, token, CHALLENGE).username)
    }

    // --- signature

    @Test
    @DisplayName("Un message signé avec un autre jeton est refusé : signature")
    fun wrongTokenIsRefused() {
        val text = payload()

        assertEquals(VotifierFailure.BAD_SIGNATURE, failureOf(frame(message(text, signature(text, "un-autre-jeton-factice")))))
    }

    @Test
    @DisplayName("Un payload changé après la signature est refusé : signature")
    fun alteredPayloadIsRefused() {
        val signed = payload()
        val altered = payload(username = "Autre")

        assertEquals(VotifierFailure.BAD_SIGNATURE, failureOf(frame(message(altered, signature(signed)))))
    }

    @Test
    @DisplayName("Une signature qui n'est pas du base64, ou qui est vide, est refusée : signature")
    fun garbledSignatureIsRefused() {
        val text = payload()

        assertEquals(VotifierFailure.BAD_SIGNATURE, failureOf(frame(message(text, "!!! pas du base64 !!!"))))
        assertEquals(VotifierFailure.BAD_SIGNATURE, failureOf(frame(message(text, ""))))
    }

    @Test
    @DisplayName("La signature est vérifiée avant que le payload soit lu : un payload illisible mal signé est refusé pour sa signature")
    fun signatureComesBeforeThePayloadIsRead() {
        val garbage = "ceci n'est pas du JSON"

        assertEquals(VotifierFailure.BAD_SIGNATURE, failureOf(frame(message(garbage, signature(garbage, "un-autre-jeton-factice")))))
        assertEquals(VotifierFailure.BAD_JSON, failureOf(frame(message(garbage))), "bien signé mais illisible")
    }

    @Test
    @DisplayName("sign : le HMAC-SHA256 de la RFC 4231 (cas 2)")
    fun signMatchesTheRfcVector() {
        val mac = Votifier2.sign("what do ya want for nothing?", "Jefe".toByteArray())

        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac.joinToString("") { "%02x".format(it) })
    }

    // --- défi

    @Test
    @DisplayName("Un défi qui n'est pas celui que le service a envoyé est refusé, même bien signé : défi")
    fun wrongChallengeIsRefused() {
        assertEquals(VotifierFailure.BAD_CHALLENGE, failureOf(frame(message(payload(challenge = "un-vieux-defi")))))
        assertEquals(VotifierFailure.BAD_CHALLENGE, failureOf(frame(message(payload())), challenge = "un-autre-defi"))
    }

    @Test
    @DisplayName("Un payload sans défi est refusé : défi")
    fun missingChallengeIsRefused() {
        val text = buildJsonObject {
            put("serviceName", "Hubyy")
            put("username", "Kr")
        }.toString()

        assertEquals(VotifierFailure.BAD_CHALLENGE, failureOf(frame(message(text))))
    }

    @Test
    @DisplayName("newChallenge : 24 caractères sûrs, différents d'un appel à l'autre ; greeting : VOTIFIER 2, le défi, un retour à la ligne")
    fun challengeAndGreeting() {
        val random = SecureRandom()
        val first = Votifier2.newChallenge(random)
        val second = Votifier2.newChallenge(random)

        assertEquals(24, first.length)
        assertTrue(first.all { it.isLetterOrDigit() || it == '-' || it == '_' }, first)
        assertNotEquals(first, second)
        assertEquals("VOTIFIER 2 $first\n", Votifier2.greeting(first).toString(Charsets.US_ASCII))
    }

    // --- forme du message

    @Test
    @DisplayName("Un JSON tronqué est refusé : JSON")
    fun truncatedJsonIsRefused() {
        val whole = message(payload())
        val cut = whole.copyOf(whole.size - 20)

        assertEquals(VotifierFailure.BAD_JSON, failureOf(frame(cut)), "la longueur annoncée est celle du morceau, le JSON est coupé")
    }

    @Test
    @DisplayName("Un message plus court que la longueur annoncée est refusé : tronqué")
    fun shorterThanAnnouncedIsRefused() {
        val whole = message(payload())

        assertEquals(VotifierFailure.TRUNCATED, failureOf(frame(whole.copyOf(whole.size - 20), length = whole.size)))
    }

    @Test
    @DisplayName("Sans la magie 0x733A, le message est refusé : magie ; une magie sans longueur est tronquée")
    fun missingMagicIsRefused() {
        val body = message(payload())

        assertEquals(VotifierFailure.NO_MAGIC, failureOf(frame(body, magic = 0x1234)))
        assertEquals(VotifierFailure.NO_MAGIC, failureOf(body), "un JSON nu, sans en-tête")
        assertEquals(VotifierFailure.NO_MAGIC, failureOf(ByteArray(0)))
        assertEquals(VotifierFailure.NO_MAGIC, failureOf(byteArrayOf(0x73)))
        assertEquals(VotifierFailure.TRUNCATED, failureOf(byteArrayOf(0x73, 0x3A)))
        assertEquals(VotifierFailure.TRUNCATED, failureOf(byteArrayOf(0x73, 0x3A, 0x00)))
    }

    @Test
    @DisplayName("bodyLength : la longueur annoncée sur deux octets ; au-dessus de la limite technique, trop grand")
    fun bodyLengthReadsTwoBytes() {
        assertEquals(0x0102, Votifier2.bodyLength(byteArrayOf(0x73, 0x3A, 0x01, 0x02)))
        assertEquals(Votifier2.MAX_MESSAGE_SIZE, Votifier2.bodyLength(frame(ByteArray(0), length = Votifier2.MAX_MESSAGE_SIZE)))
        assertEquals(
            VotifierFailure.TOO_LARGE,
            assertThrows<VotifierException> { Votifier2.bodyLength(frame(ByteArray(0), length = Votifier2.MAX_MESSAGE_SIZE + 1)) }.failure,
        )
        assertEquals(
            VotifierFailure.TOO_LARGE,
            assertThrows<VotifierException> { Votifier2.bodyLength(byteArrayOf(0x73, 0x3A, 0xFF.toByte(), 0xFF.toByte())) }.failure,
        )
    }

    @Test
    @DisplayName("Un message qui n'est pas un objet JSON avec payload et signature en chaînes est refusé : JSON")
    fun wrongShapeIsRefused() {
        fun refused(json: String) = assertEquals(VotifierFailure.BAD_JSON, failureOf(frame(json.toByteArray(Charsets.UTF_8))), json)

        refused("")
        refused("pas du json")
        refused("[]")
        refused("""{"signature":"abc"}""")
        refused("""{"payload":"{}"}""")
        refused("""{"payload":1,"signature":"abc"}""")
        refused("""{"payload":null,"signature":"abc"}""")
    }

    @Test
    @DisplayName("Un payload qui n'est pas un objet JSON, bien signé, est refusé : JSON")
    fun payloadMustBeAnObject() {
        for (text in listOf("[]", "12", "\"texte\"", "{")) {
            assertEquals(VotifierFailure.BAD_JSON, failureOf(frame(message(text))), text)
        }
    }

    @Test
    @DisplayName("Un pseudo ou un site absent, vide, trop long ou avec un caractère de contrôle est refusé : vote illisible")
    fun badNamesAreRefused() {
        fun refused(text: String) = assertEquals(VotifierFailure.BAD_PAYLOAD, failureOf(frame(message(text))), text)

        refused(payload(username = ""))
        refused(payload(username = "   "))
        refused(payload(username = "a".repeat(Votifier2.MAX_NAME_LENGTH + 1)))
        refused(payload(username = "Kr\nAutre"))
        refused(payload(serviceName = ""))
        refused(payload(serviceName = "s".repeat(Votifier2.MAX_NAME_LENGTH + 1)))
        refused(buildJsonObject { put("username", "Kr"); put("challenge", CHALLENGE) }.toString())
        refused(buildJsonObject { put("serviceName", "Hubyy"); put("challenge", CHALLENGE) }.toString())
        refused(buildJsonObject { put("serviceName", 12); put("username", "Kr"); put("challenge", CHALLENGE) }.toString())
    }

    @Test
    @DisplayName("Un pseudo de la longueur maximale passe")
    fun longestNameIsAccepted() {
        val name = "é".repeat(Votifier2.MAX_NAME_LENGTH)

        assertEquals(name, Votifier2.decode(frame(message(payload(username = name))), token, CHALLENGE).username)
    }

    // --- réponses et réglages

    @Test
    @DisplayName("La réponse de succès est {\"status\":\"ok\"} et un retour à la ligne")
    fun okReply() {
        assertEquals("{\"status\":\"ok\"}\r\n", Votifier2.OK.toString(Charsets.UTF_8))
    }

    @Test
    @DisplayName("La réponse d'échec porte status, cause et error pour chaque cause, sans jeton")
    fun errorReplies() {
        for (failure in VotifierFailure.entries) {
            val text = Votifier2.errorReply(failure).toString(Charsets.UTF_8)
            assertTrue(text.endsWith("\r\n"), text)
            val json: JsonObject = Json.parseToJsonElement(text.trim()).jsonObject
            assertEquals("error", json.getValue("status").jsonPrimitive.content)
            assertEquals(failure.cause, json.getValue("cause").jsonPrimitive.content)
            assertEquals(failure.explanation, json.getValue("error").jsonPrimitive.content)
            assertFalse(text.contains(TOKEN), text)
        }
        assertEquals(VotifierFailure.entries.size, VotifierFailure.entries.map { it.cause }.toSet().size, "chaque cause a son mot")
    }

    @Test
    @DisplayName("Les réglages cachent le jeton, et refusent un jeton vide ou un port hors bornes")
    fun settingsHideTheToken() {
        val settings = VotifierSettings(8192, TOKEN)

        assertFalse(settings.toString().contains(TOKEN), settings.toString())
        assertTrue(settings.toString().contains("8192"))
        assertThrows<IllegalArgumentException> { VotifierSettings(8192, "  ") }
        assertThrows<IllegalArgumentException> { VotifierSettings(70000, TOKEN) }
        assertThrows<IllegalArgumentException> { VotifierSettings(-1, TOKEN) }
        assertEquals(0, VotifierSettings(0, TOKEN).port)
    }

    @Test
    @DisplayName("L'exception d'un refus ne reprend ni le jeton ni le message reçu")
    fun exceptionCarriesNoSecret() {
        val text = payload()
        val error = assertThrows<VotifierException> { Votifier2.decode(frame(message(text, signature(text, "un-autre-jeton-factice"))), token, CHALLENGE) }

        assertFalse(error.message.orEmpty().contains(TOKEN))
        assertFalse(error.message.orEmpty().contains("Kr"))
        assertEquals("BadSignature", error.failure.cause)
    }

    companion object {
        /** Un jeton qui n'en est pas un : il ne signe que ces tests. Le vrai ne vit jamais dans le dépôt. */
        const val TOKEN = "jeton-factice-pour-les-tests-seulement"
        const val CHALLENGE = "defi-de-test-0123456789"
    }
}
