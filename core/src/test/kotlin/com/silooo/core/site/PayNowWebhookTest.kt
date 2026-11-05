package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.sitedata.InMemoryShopDeliveryStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Le webhook de PayNow : la classe qui isole le format supposé, puis la route avec un faux dépôt. Le SQL est dans `ShopDeliverySqlTest`. */
class PayNowWebhookTest {
    private val secret = "secret-de-test"
    private val body = """{"order_id":"PN-1001","gamertag":"Kr Test","gemmes":500}"""

    private fun sign(text: String, key: String = secret): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
        return java.util.HexFormat.of().formatHex(mac.doFinal(text.toByteArray()))
    }

    // --- la classe

    @Test
    @DisplayName("PayNowWebhook : la signature est l'HMAC-SHA256 hexadécimal du corps brut ; absente, fausse ou d'un autre secret, elle est refusée")
    fun signature() {
        val webhook = PayNowWebhook(secret)
        assertTrue(webhook.isAuthentic(body.toByteArray(), sign(body)))
        assertTrue(webhook.isAuthentic(body.toByteArray(), " " + sign(body).uppercase() + " "))
        assertFalse(webhook.isAuthentic(body.toByteArray(), null))
        assertFalse(webhook.isAuthentic(body.toByteArray(), ""))
        assertFalse(webhook.isAuthentic(body.toByteArray(), sign(body, "autre")))
        assertFalse(webhook.isAuthentic("$body ".toByteArray(), sign(body)))
    }

    @Test
    @DisplayName("PayNowWebhook : une commande valide se lit ; l'identifiant et les Gemmes peuvent être des nombres ou des textes, les champs en plus sont ignorés")
    fun reading() {
        val webhook = PayNowWebhook(secret)
        val order = webhook.read(body.toByteArray())
        assertEquals(PayNowWebhook.Reading.Order("PN-1001", "Kr Test", 500), order)
        assertEquals(
            PayNowWebhook.Reading.Order("42", "Kr", 100),
            webhook.read("""{"order_id":42,"gamertag":" Kr ","gemmes":"100","autre":{"a":1}}""".toByteArray()),
        )
    }

    @Test
    @DisplayName("PayNowWebhook : un champ manquant, nul, vide, une Gemme nulle ou non entière, un corps illisible : refusés, le message nomme le champ sans citer le corps")
    fun refusals() {
        val webhook = PayNowWebhook(secret)
        fun refusal(text: String) = assertInstanceOf(PayNowWebhook.Reading.Refused::class.java, webhook.read(text.toByteArray())).message
        assertTrue(refusal("""{"gamertag":"Kr","gemmes":5}""").contains("order_id"))
        assertTrue(refusal("""{"order_id":"1","gemmes":5}""").contains("gamertag"))
        assertTrue(refusal("""{"order_id":"1","gamertag":"Kr"}""").contains("gemmes"))
        assertTrue(refusal("""{"order_id":"1","gamertag":"  ","gemmes":5}""").contains("gamertag"))
        assertTrue(refusal("""{"order_id":null,"gamertag":"Kr","gemmes":5}""").contains("order_id"))
        for (gems in listOf("0", "-3", "1.5", "\"beaucoup\"", "true")) {
            assertTrue(refusal("""{"order_id":"1","gamertag":"Kr","gemmes":$gems}""").contains("gemmes"), gems)
        }
        assertTrue(refusal("""{"order_id":"${"x".repeat(65)}","gamertag":"Kr","gemmes":5}""").contains("order_id"))
        assertTrue(refusal("""{"order_id":"1","gamertag":"${"x".repeat(33)}","gemmes":5}""").contains("gamertag"))
        for (garbage in listOf("pas du json SECRET-CANARI", "[1,2]", "", "\"texte\"")) {
            assertFalse(refusal(garbage).contains("CANARI"), garbage)
        }
    }

    // --- la route

    private class Rig(val store: InMemoryShopDeliveryStore, val client: HttpClient) {
        suspend fun send(text: String, signature: String?, contentType: ContentType = ContentType.Application.Json): HttpResponse =
            client.post("/paynow/webhook") {
                signature?.let { header(PayNowWebhook.SIGNATURE_HEADER, it) }
                header(HttpHeaders.ContentType, contentType.toString())
                setBody(text)
            }
    }

    private fun withRig(
        mode: SiteMode = SiteMode.ATTENTE,
        webhookSecret: String? = secret,
        block: suspend ApplicationTestBuilder.(Rig) -> Unit,
    ) = testApplication {
        val store = InMemoryShopDeliveryStore()
        application {
            siteModule(
                Dependencies(
                    PassThroughDatabase(), "cle", definitions = TestDefinitions.loaded, siteMode = mode, shopDeliveryStore = store,
                    payNowWebhookSecret = webhookSecret,
                ),
            )
        }
        block(Rig(store, createClient { followRedirects = false }))
    }

    @Test
    @DisplayName("Sans secret posé : 503, rien d'écrit, quel que soit le mode")
    fun notConfigured() {
        for (mode in SiteMode.entries) {
            withRig(mode, webhookSecret = null) { rig ->
                assertEquals(HttpStatusCode.ServiceUnavailable, rig.send(body, sign(body)).status)
                assertEquals(HttpStatusCode.ServiceUnavailable, rig.send(body, sign(body)).status)
                assertTrue(rig.store.rows.isEmpty())
            }
        }
        withRig(webhookSecret = "   ") { rig -> assertEquals(HttpStatusCode.ServiceUnavailable, rig.send(body, sign(body)).status) }
    }

    @Test
    @DisplayName("Signature absente ou fausse : 401 sans corps utile, rien d'écrit")
    fun unauthorized() = withRig { rig ->
        for (signature in listOf(null, "", "abcdef", sign(body, "autre"), sign(body + " "))) {
            val response = rig.send(body, signature)
            assertEquals(HttpStatusCode.Unauthorized, response.status, "signature $signature")
            assertEquals("", response.bodyAsText())
        }
        assertTrue(rig.store.rows.isEmpty())
    }

    @Test
    @DisplayName("Champ manquant ou Gemmes nulles : 400 qui nomme le champ, sans écho du corps, rien d'écrit")
    fun badRequest() = withRig { rig ->
        val missing = """{"order_id":"PN-1","gamertag":"Kr","note":"CANARI"}"""
        val response = rig.send(missing, sign(missing))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val text = response.bodyAsText()
        assertTrue(text.contains("gemmes"), text)
        assertFalse(text.contains("CANARI") || text.contains("PN-1"), text)
        val zero = """{"order_id":"PN-2","gamertag":"Kr","gemmes":0}"""
        assertEquals(HttpStatusCode.BadRequest, rig.send(zero, sign(zero)).status)
        assertTrue(rig.store.rows.isEmpty())
    }

    @Test
    @DisplayName("Commande valide : 200 {\"received\": true} et une ligne ; en attente comme en ouvert ; sans cache")
    fun accepted() {
        for (mode in SiteMode.entries) {
            withRig(mode) { rig ->
                val response = rig.send(body, sign(body))
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("""{"received":true}""", response.bodyAsText())
                assertTrue(response.headers[HttpHeaders.ContentType]!!.startsWith("application/json"))
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                val row = rig.store.rows.values.single()
                assertEquals(Triple("PN-1001", "Kr Test", 500L), Triple(row.orderId, row.gamertag, row.gemmes))
            }
        }
    }

    @Test
    @DisplayName("Rejeu du même order_id : 200 et toujours une seule ligne, la première")
    fun replay() = withRig { rig ->
        assertEquals(HttpStatusCode.OK, rig.send(body, sign(body)).status)
        val again = """{"order_id":"PN-1001","gamertag":"Autre","gemmes":9}"""
        val response = rig.send(again, sign(again))
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"received":true}""", response.bodyAsText())
        assertEquals(1, rig.store.rows.size)
        assertEquals(500L, rig.store.rows.getValue("PN-1001").gemmes)
    }

    @Test
    @DisplayName("Corps trop gros : refusé (413), qu'il soit annoncé ou non, rien d'écrit")
    fun tooBig() = withRig { rig ->
        val padded = """{"order_id":"PN-9","gamertag":"Kr","gemmes":5,"note":"${"x".repeat(5000)}"}"""
        assertEquals(HttpStatusCode.PayloadTooLarge, rig.send(padded, sign(padded)).status)
        assertTrue(rig.store.rows.isEmpty())
    }

    @Test
    @DisplayName("Un GET sur le webhook est refusé (405) : il ne répond qu'aux POST")
    fun onlyPost() = withRig { rig ->
        assertEquals(HttpStatusCode.MethodNotAllowed, rig.client.get("/paynow/webhook").status)
    }
}
