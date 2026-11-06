package com.silooo.core.contract

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.MariaDbSupport
import com.silooo.core.siloooModule
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GameDiscordLinkRoutesTest {
    private val now = Instant.parse("2026-11-06T12:00:00Z")
    private val contract = OpenApi.load()
    @BeforeEach fun prepare() = MariaDbSupport.prepare()

    @Test fun `Le code emis suit le contrat et son rejeu ne le renouvelle pas`() = testApplication {
        MariaDbSupport.insertPlayer("7", "KrTest")
        application { siloooModule(Dependencies(database = MariaDbSupport.database, apiKey = "test-key", definitions = TestDefinitions.loaded, clock = Clock.fixed(now, ZoneOffset.UTC))) }
        val id = UUID.randomUUID().toString()
        suspend fun issue(requestId: String) = client.post("/v1/discord/link-codes") {
            header("X-Silooo-Key", "test-key")
            contentType(ContentType.Application.Json)
            setBody("""{"requestId":"$requestId","actor":"7"}""")
        }
        val first = issue(id)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val body = first.bodyAsText()
        val json = Json.parseToJsonElement(body)
        val schema = contract.operations.single { it.id == "issueDiscordLinkCode" }.responses.getValue(200)!!
        assertTrue(contract.violations(schema, json).isEmpty())
        assertEquals(now.plusSeconds(600).toString(), json.jsonObject.getValue("expiresAt").jsonPrimitive.content)
        assertEquals(body, issue(id).bodyAsText())
        val refreshed = Json.parseToJsonElement(issue(UUID.randomUUID().toString()).bodyAsText())
        assertNotEquals(json.jsonObject.getValue("code"), refreshed.jsonObject.getValue("code"))
    }

    @Test fun `La cle le corps et le joueur sont verifies avant emission`() = testApplication {
        application { siloooModule(Dependencies(database = MariaDbSupport.database, apiKey = "test-key", definitions = TestDefinitions.loaded)) }
        suspend fun issue(body: String, key: String? = "test-key") = client.post("/v1/discord/link-codes") {
            if (key != null) header("X-Silooo-Key", key)
            contentType(ContentType.Application.Json); setBody(body)
        }
        val body = """{"requestId":"${UUID.randomUUID()}","actor":"7"}"""
        assertEquals(HttpStatusCode.Unauthorized, issue(body, null).status)
        assertEquals(HttpStatusCode.BadRequest, issue("""{"requestId":"pas-un-uuid","actor":"7"}""").status)
        assertEquals(HttpStatusCode.BadRequest, issue("""{"requestId":"${UUID.randomUUID()}","actor":"x"}""").status)
        val missing = issue(body)
        assertEquals(HttpStatusCode.Conflict, missing.status, missing.bodyAsText())
        assertEquals("JOUEUR_INCONNU", Json.parseToJsonElement(missing.bodyAsText()).jsonObject.getValue("reason").jsonPrimitive.content)
    }
}
