package com.silooo.core.vote

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [VotifierServer] sur de vraies sockets, port 0 (le système choisit un port libre) : l'accueil, un vote accepté, les refus, un
 * gestionnaire qui échoue. Les règles du protocole sont dans [VotifierProtocolTest] ; ici, le déroulé d'une connexion.
 */
class VotifierServerTest {
    private val received = CopyOnWriteArrayList<VotifierVote>()
    @Volatile
    private var failing: Exception? = null
    private lateinit var scope: CoroutineScope
    private lateinit var server: VotifierServer

    @BeforeEach
    fun startServer() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server = VotifierServer(VotifierSettings(0, VotifierProtocolTest.TOKEN)) { vote ->
            failing?.let { throw it }
            received += vote
        }
        server.start(scope)
    }

    @AfterEach
    fun stopServer() {
        scope.cancel()
    }

    private fun connect(): Socket = Socket(InetAddress.getLoopbackAddress(), server.localPort).apply { soTimeout = 5_000 }

    /** La première ligne que le service envoie. */
    private fun Socket.greeting(): String {
        val line = ByteArrayOutputStream()
        val input = getInputStream()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            line.write(b)
        }
        return line.toString(Charsets.US_ASCII)
    }

    /** Tout ce que le service écrit jusqu'à la fermeture. */
    private fun Socket.readReply(): String = getInputStream().readBytes().toString(Charsets.UTF_8)

    private fun challengeOf(greeting: String): String {
        assertTrue(greeting.startsWith("VOTIFIER 2 "), greeting)
        return greeting.removePrefix("VOTIFIER 2 ")
    }

    private fun message(challenge: String, username: String = "Kr", token: String = VotifierProtocolTest.TOKEN): ByteArray {
        val payload = buildJsonObject {
            put("serviceName", "Hubyy")
            put("username", username)
            put("address", "203.0.113.7")
            put("timestamp", 1762344000000L)
            put("challenge", challenge)
        }.toString()
        val signature = Base64.getEncoder().encodeToString(Votifier2.sign(payload, token.toByteArray(Charsets.UTF_8)))
        return buildJsonObject {
            put("payload", payload)
            put("signature", signature)
        }.toString().toByteArray(Charsets.UTF_8)
    }

    private fun frame(message: ByteArray, magic: Int = Votifier2.MAGIC): ByteArray =
        byteArrayOf((magic shr 8).toByte(), magic.toByte(), (message.size shr 8).toByte(), message.size.toByte()) + message

    private fun Socket.send(bytes: ByteArray) {
        getOutputStream().write(bytes)
        getOutputStream().flush()
    }

    private fun status(reply: String) = Json.parseToJsonElement(reply.trim()).jsonObject

    @Test
    @DisplayName("Une connexion reçoit VOTIFIER 2 et un défi, et chaque connexion a le sien")
    fun greetsWithAFreshChallenge() {
        val first = connect().use { challengeOf(it.greeting()) }
        val second = connect().use { challengeOf(it.greeting()) }

        assertEquals(24, first.length)
        assertFalse(first == second, "deux connexions, deux défis")
    }

    @Test
    @DisplayName("Un vote bien signé, avec le défi reçu, est remis au gestionnaire et la réponse est ok")
    fun acceptsAGoodVote() {
        connect().use { socket ->
            val challenge = challengeOf(socket.greeting())
            socket.send(frame(message(challenge)))
            val reply = socket.readReply()

            assertEquals("{\"status\":\"ok\"}\r\n", reply)
        }

        assertEquals(listOf(VotifierVote("Hubyy", "Kr", "203.0.113.7", "1762344000000")), received.toList())
    }

    @Test
    @DisplayName("Une mauvaise signature est refusée : réponse d'erreur, gestionnaire jamais appelé")
    fun refusesABadSignature() {
        connect().use { socket ->
            val challenge = challengeOf(socket.greeting())
            socket.send(frame(message(challenge, token = "un-autre-jeton-factice")))
            val reply = status(socket.readReply())

            assertEquals("error", reply.getValue("status").jsonPrimitive.content)
            assertEquals("BadSignature", reply.getValue("cause").jsonPrimitive.content)
        }

        assertTrue(received.isEmpty())
    }

    @Test
    @DisplayName("Un défi qui n'est pas celui de la connexion est refusé : une réponse enregistrée ne se rejoue pas")
    fun refusesAReplayedChallenge() {
        val old = connect().use { challengeOf(it.greeting()) }
        connect().use { socket ->
            socket.greeting()
            socket.send(frame(message(old)))
            val reply = status(socket.readReply())

            assertEquals("BadChallenge", reply.getValue("cause").jsonPrimitive.content)
        }

        assertTrue(received.isEmpty())
    }

    @Test
    @DisplayName("Sans la magie, le message est refusé : NoMagic")
    fun refusesAMissingMagic() {
        connect().use { socket ->
            val challenge = challengeOf(socket.greeting())
            socket.send(frame(message(challenge), magic = 0x1234))
            val reply = status(socket.readReply())

            assertEquals("NoMagic", reply.getValue("cause").jsonPrimitive.content)
        }

        assertTrue(received.isEmpty())
    }

    @Test
    @DisplayName("Une longueur au-dessus de la limite est refusée sans lire le message : TooLarge")
    fun refusesAnOversizedLength() {
        connect().use { socket ->
            socket.greeting()
            socket.send(byteArrayOf(0x73, 0x3A, 0xFF.toByte(), 0xFF.toByte()))
            val reply = status(socket.readReply())

            assertEquals("TooLarge", reply.getValue("cause").jsonPrimitive.content)
        }
    }

    @Test
    @DisplayName("Un message coupé par la fermeture de l'émetteur est refusé : Truncated")
    fun refusesATruncatedMessage() {
        connect().use { socket ->
            val challenge = challengeOf(socket.greeting())
            val whole = frame(message(challenge))
            socket.send(whole.copyOf(whole.size - 15))
            socket.shutdownOutput()
            val reply = status(socket.readReply())

            assertEquals("Truncated", reply.getValue("cause").jsonPrimitive.content)
        }

        assertTrue(received.isEmpty())
    }

    @Test
    @DisplayName("Un gestionnaire qui échoue répond Unavailable : le site sait qu'il doit réessayer")
    fun answersUnavailableWhenTheHandlerFails() {
        failing = IllegalStateException("la base est tombée")
        connect().use { socket ->
            val challenge = challengeOf(socket.greeting())
            socket.send(frame(message(challenge)))
            val text = socket.readReply()
            val reply = status(text)

            assertEquals("error", reply.getValue("status").jsonPrimitive.content)
            assertEquals("Unavailable", reply.getValue("cause").jsonPrimitive.content)
            assertFalse(text.contains("la base est tombée"), "le message de l'exception reste dans le journal")
        }

        assertTrue(received.isEmpty())
    }

    @Test
    @DisplayName("Après un refus, le serveur reçoit encore : un message mauvais n'arrête rien")
    fun keepsServingAfterARefusal() {
        connect().use { socket ->
            socket.greeting()
            socket.send(byteArrayOf(1, 2, 3, 4))
            socket.readReply()
        }

        connect().use { socket ->
            val challenge = challengeOf(socket.greeting())
            socket.send(frame(message(challenge, username = "Autre")))
            assertEquals("{\"status\":\"ok\"}\r\n", socket.readReply())
        }

        assertEquals(listOf("Autre"), received.map { it.username })
    }

    @Test
    @DisplayName("Plusieurs votes à la suite sont tous remis, chacun sur sa connexion")
    fun receivesSeveralVotes() {
        for (name in listOf("Un", "Deux", "Trois")) {
            connect().use { socket ->
                val challenge = challengeOf(socket.greeting())
                socket.send(frame(message(challenge, username = name)))
                assertEquals("{\"status\":\"ok\"}\r\n", socket.readReply())
            }
        }

        assertEquals(listOf("Un", "Deux", "Trois"), received.map { it.username })
    }

    @Test
    @DisplayName("Un émetteur qui ne dit rien ne bloque pas les autres connexions")
    fun silentSenderDoesNotBlockOthers() {
        val silent = connect()
        try {
            silent.greeting()
            // Pendant que ce silencieux attend, une autre connexion passe.
            connect().use { socket ->
                val challenge = challengeOf(socket.greeting())
                socket.send(frame(message(challenge)))
                assertEquals("{\"status\":\"ok\"}\r\n", socket.readReply())
            }
        } finally {
            silent.close()
        }

        assertEquals(1, received.size)
    }
}
