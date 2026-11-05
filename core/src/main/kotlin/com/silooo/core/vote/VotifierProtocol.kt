package com.silooo.core.vote

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Les réglages de la réception des votes (`SILOOO_VOTIFIER_TOKEN`, `SILOOO_VOTIFIER_PORT`). Sans jeton, la réception est éteinte
 * et ce type n'existe pas : [com.silooo.core.Config] rend `null`. Le jeton est le secret que Kr déclare aussi sur Hubyy ; il ne
 * s'écrit ni dans le dépôt, ni dans un journal, ni dans [toString].
 *
 * Le port 0 laisse le système choisir (les tests de [VotifierServer]).
 */
class VotifierSettings(val port: Int, val token: String) {
    init {
        require(token.isNotBlank()) { "Le jeton Votifier ne peut pas être vide." }
        require(port in 0..65535) { "Le port Votifier doit être entre 0 et 65535." }
    }

    override fun toString() = "VotifierSettings(port=$port, token=***)"
}

/** Un vote tel que le service de vote l'annonce, une fois la signature et le défi vérifiés. */
data class VotifierVote(
    /** `serviceName` : le site de vote qui envoie. */
    val serviceName: String,
    /** `username` : le pseudo que le joueur a saisi sur le site. */
    val username: String,
    /** `address` : l'adresse du votant, telle que le site l'annonce (vide si absente). Gardée pour mémoire, jamais écrite en base. */
    val address: String,
    /** `timestamp` : l'heure que le site annonce (vide si absente). Le service date le vote avec sa propre horloge. */
    val timestamp: String,
)

/** Ce qui peut faire refuser un message Votifier, avec le mot que la réponse JSON met dans `cause` et la phrase de `error`. */
enum class VotifierFailure(val cause: String, val explanation: String) {
    NO_MAGIC("NoMagic", "Le message ne commence pas par la magie Votifier (0x733A)."),
    TRUNCATED("Truncated", "Le message est plus court que la longueur annoncée."),
    TOO_LARGE("TooLarge", "Le message annonce une longueur au-dessus de ce que le service accepte."),
    BAD_JSON("BadJson", "Le message n'est pas un JSON lisible avec payload et signature."),
    BAD_SIGNATURE("BadSignature", "La signature ne correspond pas au jeton."),
    BAD_CHALLENGE("BadChallenge", "Le défi ne correspond pas à celui que le service a envoyé."),
    BAD_PAYLOAD("BadPayload", "Le vote doit porter un serviceName et un username lisibles, de 1 à 32 caractères."),
    UNAVAILABLE("Unavailable", "Le service n'a pas pu enregistrer le vote, réessayer plus tard."),
}

/** Un message refusé : [failure] dit pourquoi, et rien du message n'est repris dans le texte (ni jeton, ni signature). */
class VotifierException(val failure: VotifierFailure) : Exception("${failure.cause} : ${failure.explanation}")

/**
 * Le protocole Votifier 2 côté réception, en fonctions pures sur des octets : aucune socket ici, tout se teste sans réseau.
 *
 * Déroulé d'une connexion ([VotifierServer]) :
 *  1. le service envoie `VOTIFIER 2 <défi>\n` ([greeting]) ;
 *  2. le site répond la magie `0x733A`, la longueur du message sur 2 octets, puis ce message : un JSON
 *     `{"payload":"<chaîne JSON>","signature":"<base64 du HMAC-SHA256 de payload, avec le jeton comme clé>"}` ;
 *  3. le `payload` décodé porte `serviceName`, `username`, `address`, `timestamp` et `challenge`, qui doit être le défi envoyé ;
 *  4. le service répond [OK] ou [errorReply], puis ferme.
 *
 * La signature se vérifie avant que le `payload` soit lu : rien n'est interprété tant que l'émetteur n'a pas prouvé qu'il a le jeton.
 */
object Votifier2 {
    /** La magie qui ouvre un message Votifier 2. */
    const val MAGIC = 0x733A

    /** La magie (2 octets) et la longueur du message (2 octets). */
    const val HEADER_SIZE = 4

    /**
     * La plus grande longueur de message acceptée. Un vrai message tient en quelques centaines d'octets ; l'en-tête permet
     * jusqu'à 65535, ce que le service n'a aucune raison de lire. Une limite technique, pas un chiffre du jeu.
     */
    const val MAX_MESSAGE_SIZE = 4096

    /** La plus longue valeur de `username` et de `serviceName` : la taille des colonnes `gamertag` et `site` de `server_votes`. */
    const val MAX_NAME_LENGTH = 32

    /** La réponse de succès. NuVotifier termine la ligne par `\r\n`, comme ici. */
    val OK: ByteArray = "{\"status\":\"ok\"}\r\n".toByteArray(Charsets.UTF_8)

    private const val CHALLENGE_BYTES = 18

    /** `VOTIFIER 2 <défi>\n`, la première ligne que le service envoie. */
    fun greeting(challenge: String): ByteArray = "VOTIFIER 2 $challenge\n".toByteArray(Charsets.US_ASCII)

    /** Un défi neuf, tiré au hasard : 24 caractères sûrs (lettres, chiffres, `-` et `_`), jamais deux fois le même. */
    fun newChallenge(random: SecureRandom): String {
        val bytes = ByteArray(CHALLENGE_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** La réponse d'échec : `{"status":"error","cause":"…","error":"…"}` puis `\r\n`. Ni le jeton ni le message reçu n'y figurent. */
    fun errorReply(failure: VotifierFailure): ByteArray {
        val body = buildJsonObject {
            put("status", "error")
            put("cause", failure.cause)
            put("error", failure.explanation)
        }
        return (body.toString() + "\r\n").toByteArray(Charsets.UTF_8)
    }

    /**
     * La longueur du message que [header] annonce. [header] est ce qui a été lu des 4 premiers octets (moins s'il y en avait
     * moins) : sans la magie, [VotifierFailure.NO_MAGIC] ; magie sans longueur complète, [VotifierFailure.TRUNCATED] ;
     * longueur au-dessus de [MAX_MESSAGE_SIZE], [VotifierFailure.TOO_LARGE].
     */
    fun bodyLength(header: ByteArray): Int {
        if (header.size < 2 || unsigned(header[0], header[1]) != MAGIC) throw VotifierException(VotifierFailure.NO_MAGIC)
        if (header.size < HEADER_SIZE) throw VotifierException(VotifierFailure.TRUNCATED)
        val length = unsigned(header[2], header[3])
        if (length > MAX_MESSAGE_SIZE) throw VotifierException(VotifierFailure.TOO_LARGE)
        return length
    }

    /**
     * Décode un message complet : l'en-tête, puis le message. Vérifie dans cet ordre la magie, la longueur, le JSON extérieur,
     * la signature (avec [token]), le JSON du `payload`, le défi (qui doit égaler [challenge]) et les noms. Rend le vote, ou lève
     * [VotifierException]. Les octets au-delà de la longueur annoncée sont ignorés.
     */
    fun decode(frame: ByteArray, token: ByteArray, challenge: String): VotifierVote {
        val length = bodyLength(frame.copyOfRange(0, minOf(frame.size, HEADER_SIZE)))
        if (frame.size - HEADER_SIZE < length) throw VotifierException(VotifierFailure.TRUNCATED)
        return decodeMessage(frame.copyOfRange(HEADER_SIZE, HEADER_SIZE + length), token, challenge)
    }

    /** Comme [decode], sur le message seul (sans l'en-tête de 4 octets). */
    fun decodeMessage(message: ByteArray, token: ByteArray, challenge: String): VotifierVote {
        val outer = parseObject(message.toString(Charsets.UTF_8)) ?: throw VotifierException(VotifierFailure.BAD_JSON)
        val payload = outer.text("payload", numbers = false) ?: throw VotifierException(VotifierFailure.BAD_JSON)
        val signature = outer.text("signature", numbers = false) ?: throw VotifierException(VotifierFailure.BAD_JSON)

        val provided = try {
            Base64.getDecoder().decode(signature)
        } catch (_: IllegalArgumentException) {
            throw VotifierException(VotifierFailure.BAD_SIGNATURE)
        }
        // Comparaison en temps constant : la durée ne dit rien de la position du premier octet différent.
        if (!MessageDigest.isEqual(sign(payload, token), provided)) throw VotifierException(VotifierFailure.BAD_SIGNATURE)

        val data = parseObject(payload) ?: throw VotifierException(VotifierFailure.BAD_JSON)
        if (data.text("challenge", numbers = false) != challenge) throw VotifierException(VotifierFailure.BAD_CHALLENGE)

        val serviceName = data.text("serviceName", numbers = false)?.trim()
        val username = data.text("username", numbers = false)?.trim()
        if (!isName(serviceName) || !isName(username)) throw VotifierException(VotifierFailure.BAD_PAYLOAD)
        return VotifierVote(
            serviceName = checkNotNull(serviceName),
            username = checkNotNull(username),
            address = data.text("address", numbers = true).orEmpty(),
            timestamp = data.text("timestamp", numbers = true).orEmpty(),
        )
    }

    /** Le HMAC-SHA256 de [payload] (en UTF-8) avec [token] pour clé : ce que le site met en base64 dans `signature`. */
    fun sign(payload: String, token: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token, "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
    }

    /** Un nom de joueur ou de site : de 1 à [MAX_NAME_LENGTH] caractères, sans caractère de contrôle. */
    private fun isName(value: String?): Boolean =
        value != null && value.isNotEmpty() && value.codePointCount(0, value.length) <= MAX_NAME_LENGTH && value.none { it.isISOControl() }

    private fun unsigned(high: Byte, low: Byte): Int = ((high.toInt() and 0xFF) shl 8) or (low.toInt() and 0xFF)

    private fun parseObject(text: String): JsonObject? =
        try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /** La valeur de [key] si c'est une chaîne JSON (ou, avec [numbers], un nombre ou un booléen), sinon `null`. */
    private fun JsonObject.text(key: String, numbers: Boolean): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return if (primitive.isString || numbers) primitive.content else null
    }
}
