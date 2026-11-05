package com.silooo.core.discord

import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * L'envoyeur de webhook sans réseau : un faux transport rend les réponses, les tests lisent le JSON posté, la coupure à 2000 caractères,
 * la nouvelle tentative et ce que les journaux ne disent jamais. Un seul test parle à un faux serveur sur la boucle locale
 * (le transport JDK lui-même) ; aucune adresse réelle n'apparaît nulle part.
 */
class DiscordWebhookTest {
    private val ok = DiscordReply(204)
    private val secret = "https://exemple.invalid/api/webhooks/1234/jeton-de-test"

    private fun content(body: String): String = Json.parseToJsonElement(body).jsonObject.getValue("content").jsonPrimitive.content

    private fun send(transport: FakeTransport, pauses: MutableList<Long> = mutableListOf(), text: String): Boolean =
        runBlocking { DiscordWebhook(transport) { pauses += it }.send(text) }

    // --- le corps

    @Test
    @DisplayName("Le corps est {\"content\": …} avec les mentions coupées, et le texte passe tel quel (accents, guillemets, sauts de ligne)")
    fun bodyHoldsTheContentAndNoMentions() {
        assertEquals("""{"content":"Bonjour","allowed_mentions":{"parse":[]}}""", DiscordWebhook.bodyOf("Bonjour"))

        val text = "L'usine \"Verrerie\" de Zoé\nest à l'arrêt faute de courant."
        val json = Json.parseToJsonElement(DiscordWebhook.bodyOf(text)).jsonObject
        assertEquals(text, json.getValue("content").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), json.getValue("allowed_mentions").jsonObject.getValue("parse"))
    }

    @Test
    @DisplayName("send pose un POST par message : le corps JSON d'un texte court, une seule fois")
    fun sendPostsTheBody() {
        val transport = FakeTransport(ok)

        assertTrue(send(transport, text = "Les Forges a fait son 1er prestige."))

        assertEquals(listOf(DiscordWebhook.bodyOf("Les Forges a fait son 1er prestige.")), transport.bodies)
    }

    @Test
    @DisplayName("Un message vide ou fait d'espaces ne part pas, et send le dit")
    fun aBlankMessageIsNotSent() {
        val transport = FakeTransport(ok)

        assertFalse(send(transport, text = "   \n "))
        assertFalse(send(transport, text = ""))

        assertEquals(emptyList<String>(), transport.bodies)
    }

    // --- la coupure à 2000 caractères

    @Test
    @DisplayName("Un message de plus de 2000 caractères part en plusieurs envois, chacun de 2000 au plus, dans l'ordre, sans rien perdre")
    fun aLongMessageIsSplitAndSentInOrder() {
        val words = (1..1200).map { "mot$it" }
        val text = words.joinToString(" ")
        assertTrue(text.length > 2 * DISCORD_LIMIT)
        val transport = FakeTransport(ok)

        assertTrue(send(transport, text = text))

        val parts = transport.bodies.map { content(it) }
        assertTrue(parts.size >= 3)
        assertTrue(parts.all { it.length <= DISCORD_LIMIT }, "chaque envoi tient dans la limite")
        assertEquals(words, parts.joinToString(" ").split(" "), "les mots sont tous là, dans l'ordre, sans mot coupé")
    }

    @Test
    @DisplayName("Si un morceau est refusé, les suivants ne partent pas, et send rend faux")
    fun aRefusedPartStopsTheRest() {
        val transport = FakeTransport(DiscordReply(404))

        assertFalse(send(transport, text = "a".repeat(DISCORD_LIMIT) + " " + "b".repeat(50)))

        assertEquals(1, transport.bodies.size)
    }

    @Test
    @DisplayName("splitForDiscord : un texte court reste entier, un texte vide ne donne rien")
    fun splitKeepsShortTexts() {
        assertEquals(listOf("Bonjour"), splitForDiscord("Bonjour"))
        assertEquals(listOf("a".repeat(DISCORD_LIMIT)), splitForDiscord("a".repeat(DISCORD_LIMIT)))
        assertEquals(emptyList<String>(), splitForDiscord(""))
        assertEquals(emptyList<String>(), splitForDiscord(" \n "))
    }

    @Test
    @DisplayName("splitForDiscord coupe de préférence à un saut de ligne, sinon à une espace, sinon en pleine chaîne")
    fun splitPrefersNewlinesThenSpaces() {
        val first = "a".repeat(1990)
        val second = "b".repeat(100)
        assertEquals(listOf(first, second), splitForDiscord("$first\n$second"))

        assertEquals(listOf("un deux", "trois", "quatre"), splitForDiscord("un deux trois quatre", limit = 10))
        assertEquals(listOf("un", "deux", "trois"), splitForDiscord("un\ndeux trois", limit = 5))

        assertEquals(listOf("abcd", "efgh", "ij"), splitForDiscord("abcdefghij", limit = 4))
    }

    @Test
    @DisplayName("splitForDiscord ne sépare jamais les deux moitiés d'un caractère hors du plan de base")
    fun splitKeepsSurrogatePairsTogether() {
        val smiley = "😀"
        val parts = splitForDiscord("ab$smiley$smiley", limit = 3)

        assertEquals(listOf("ab", smiley, smiley), parts)
        for (part in parts) {
            assertTrue(part.none { Character.isSurrogate(it) } || part == smiley, "aucune moitié seule : $part")
        }
        assertThrows<IllegalArgumentException> { splitForDiscord("x", limit = 1) }
    }

    // --- la nouvelle tentative

    @Test
    @DisplayName("Un 5xx est retenté une fois, après une pause, et le second essai réussit")
    fun aServerErrorIsRetriedOnce() {
        val transport = FakeTransport(DiscordReply(503), ok)
        val pauses = mutableListOf<Long>()

        assertTrue(send(transport, pauses, "Bonjour"))

        assertEquals(2, transport.bodies.size)
        assertEquals(transport.bodies[0], transport.bodies[1], "le même message")
        assertEquals(listOf(DiscordWebhook.RETRY_PAUSE.toMillis()), pauses)
    }

    @Test
    @DisplayName("Une panne de réseau (exception) est retentée une fois ; deux pannes de suite : faux, et pas plus de deux essais")
    fun aNetworkFailureIsRetriedOnce() {
        val flaky = FakeTransport(IOException("réseau coupé"), ok)
        assertTrue(send(flaky, text = "Bonjour"))
        assertEquals(2, flaky.bodies.size)

        val down = FakeTransport(IOException("réseau coupé"))
        val pauses = mutableListOf<Long>()
        assertFalse(send(down, pauses, "Bonjour"))
        assertEquals(DiscordWebhook.ATTEMPTS, down.bodies.size)
        assertEquals(1, pauses.size, "une seule pause : il n'y a qu'une nouvelle tentative")
    }

    @Test
    @DisplayName("Un 429 attend ce que Discord demande, borné entre 0,2 et 10 secondes, puis retente une fois")
    fun aRateLimitWaitsAsAskedWithinBounds() {
        fun waited(retryAfter: Long?): Long {
            val pauses = mutableListOf<Long>()
            assertTrue(send(FakeTransport(DiscordReply(429, retryAfter), ok), pauses, "Bonjour"))
            return pauses.single()
        }

        assertEquals(1500L, waited(1500))
        assertEquals(10_000L, waited(600_000), "une attente démesurée est ramenée à 10 s")
        assertEquals(200L, waited(5), "une attente nulle ou minuscule est portée à 0,2 s")
        assertEquals(DiscordWebhook.RETRY_PAUSE.toMillis(), waited(null), "sans Retry-After, la pause par défaut")
    }

    @Test
    @DisplayName("Un refus qui ne passera pas (400, 401, 404 : webhook supprimé) n'est pas retenté")
    fun aHardRefusalIsNotRetried() {
        for (status in listOf(400, 401, 403, 404)) {
            val transport = FakeTransport(DiscordReply(status))
            val pauses = mutableListOf<Long>()

            assertFalse(send(transport, pauses, "Bonjour"), "statut $status")

            assertEquals(1, transport.bodies.size, "statut $status : un seul essai")
            assertEquals(emptyList<Long>(), pauses)
        }
    }

    @Test
    @DisplayName("Une annulation de la coroutine traverse l'envoyeur : elle n'est ni avalée ni retentée")
    fun cancellationPropagates() {
        val transport = FakeTransport(CancellationException("annulé"))

        assertThrows<CancellationException> { send(transport, text = "Bonjour") }

        assertEquals(1, transport.bodies.size)
    }

    // --- ce que les journaux ne disent pas

    @Test
    @DisplayName("Un échec se note sur une ligne sans l'adresse du webhook, ni le message de l'erreur, ni le texte envoyé")
    fun logsNeverHoldTheAddressOrTheText() {
        LogCapture().use { logs ->
            send(FakeTransport(IOException("échec vers $secret")), text = "Les Forges a fait son 1er prestige.")
            send(FakeTransport(DiscordReply(500)), text = "Les Forges a fait son 1er prestige.")

            assertTrue(logs.lines.isNotEmpty())
            for (line in logs.lines) {
                assertFalse(line.contains("exemple.invalid"), line)
                assertFalse(line.contains("jeton-de-test"), line)
                assertFalse(line.contains("échec vers"), line)
                assertFalse(line.contains("Forges"), line)
            }
            assertTrue(logs.lines.any { it.contains("IOException") }, "le nom de l'exception, oui")
            assertTrue(logs.lines.any { it.contains("500") }, "le code HTTP, oui")
        }
    }

    // --- l'envoyeur éteint, l'adresse

    @Test
    @DisplayName("DiscordOff est éteint : il ne prend rien")
    fun offTakesNothing() {
        assertFalse(DiscordOff.enabled)
        assertFalse(runBlocking { DiscordOff.send("Bonjour") })
        assertTrue(DiscordWebhook(FakeTransport(ok)).enabled)
    }

    @Test
    @DisplayName("L'adresse du webhook exige https et un hôte, et ne s'affiche jamais")
    fun theAddressIsHttpsOnlyAndNeverPrinted() {
        val address = checkNotNull(WebhookAddress.parse("  $secret  "))
        assertEquals(URI(secret), address.uri)
        assertFalse(address.toString().contains("exemple.invalid"), address.toString())
        assertFalse(address.toString().contains("jeton-de-test"), address.toString())

        for (bad in listOf("", "   ", "http://exemple.invalid/hook", "ftp://exemple.invalid/hook", "exemple.invalid/hook", "https://", "https:///hook", "pas une adresse", "https://exem ple.invalid/x")) {
            assertNull(WebhookAddress.parse(bad), "« $bad » devrait être refusée")
        }
    }

    // --- le transport JDK, sur un faux serveur de la boucle locale

    private fun withServer(handler: HttpHandler, block: (WebhookAddress) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/hook", handler)
        server.start()
        try {
            block(WebhookAddress.unchecked(URI("http://127.0.0.1:${server.address.port}/hook")))
        } finally {
            server.stop(0)
        }
    }

    @Test
    @DisplayName("Le transport JDK poste le corps en JSON UTF-8 sur l'adresse et rend le code de la réponse")
    fun theJdkTransportPostsJson() {
        var method: String? = null
        var type: String? = null
        var received: String? = null
        withServer({ exchange ->
            method = exchange.requestMethod
            type = exchange.requestHeaders.getFirst("Content-Type")
            received = String(exchange.requestBody.readBytes(), Charsets.UTF_8)
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }) { address ->
            val body = DiscordWebhook.bodyOf("Zoé offre un boost collectif.")
            val reply = runBlocking { HttpDiscordTransport(address).post(body) }

            assertEquals(DiscordReply(204), reply)
            assertEquals("POST", method)
            assertTrue(type!!.startsWith("application/json"), type)
            assertEquals(body, received)
        }
    }

    @Test
    @DisplayName("Le transport JDK rend l'attente du 429 (Retry-After, en secondes avec décimales) en millisecondes")
    fun theJdkTransportReadsRetryAfter() {
        withServer({ exchange ->
            exchange.responseHeaders.add("Retry-After", "1.5")
            exchange.sendResponseHeaders(429, -1)
            exchange.close()
        }) { address ->
            assertEquals(DiscordReply(429, 1500L), runBlocking { HttpDiscordTransport(address).post("{}") })
        }
    }

    @Test
    @DisplayName("Le transport JDK lève une IOException quand le serveur ne répond pas dans le délai")
    fun theJdkTransportTimesOut() {
        withServer({ exchange ->
            Thread.sleep(1500)
            runCatching { exchange.sendResponseHeaders(204, -1) }
            exchange.close()
        }) { address ->
            val transport = HttpDiscordTransport(address, timeout = Duration.ofMillis(200))
            assertThrows<IOException> { runBlocking { transport.post("{}") } }
        }
    }
}
