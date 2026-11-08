package com.silooo.core.contract

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.MariaDbSupport
import com.silooo.core.discord.linking.GameDiscordLinks
import com.silooo.core.discord.linking.GameLinkStatus
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

/** La salle d'attente sur une vraie MariaDB : inscription pionnier, code, liaison Discord, puis première vraie partie. */
class LobbyLinkRoutesTest {
    private val now = Instant.parse("2026-11-07T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val contract = OpenApi.load()
    @BeforeEach fun prepare() = MariaDbSupport.prepare()

    private fun dependencies() =
        Dependencies(database = MariaDbSupport.database, apiKey = "test-key", definitions = TestDefinitions.loaded, clock = clock)

    @Test fun `Un visiteur inconnu est inscrit pionnier, relie son Discord, puis joue`() = testApplication {
        val deps = dependencies()
        application { siloooModule(deps) }
        val id = UUID.randomUUID().toString()
        suspend fun issue(requestId: String, gamertag: String = "Ana Bedrock") = client.post("/v1/lobby/link-codes") {
            header("X-Silooo-Key", "test-key")
            contentType(ContentType.Application.Json)
            setBody("""{"requestId":"$requestId","actor":"123456","gamertag":"$gamertag"}""")
        }
        val first = issue(id)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val json = Json.parseToJsonElement(first.bodyAsText())
        val schema = contract.operations.single { it.id == "issueLobbyLinkCode" }.responses.getValue(200)!!
        assertTrue(contract.violations(schema, json).isEmpty())
        assertEquals(first.bodyAsText(), issue(id).bodyAsText())

        assertEquals("Ana Bedrock", MariaDbSupport.string("SELECT gamertag FROM players WHERE xuid = 123456"))
        assertEquals(0L, MariaDbSupport.count("company_members"))
        assertEquals(1L, MariaDbSupport.long("SELECT pioneer FROM site_registrations WHERE gamertag_key = 'ana bedrock'"))
        // Chemin b : le Pionnier de la salle d'attente entre en bêta, sans Discord relié.
        assertNotNull(MariaDbSupport.string("SELECT admitted_at FROM site_registrations WHERE gamertag_key = 'ana bedrock'"))
        assertEquals("regle:pionnier-salle", MariaDbSupport.string("SELECT admitted_by FROM beta_access WHERE player_xuid = 123456"))

        val code = json.jsonObject.getValue("code").jsonPrimitive.content
        val linked = GameDiscordLinks(MariaDbSupport.database, clock).redeem("42", code)
        assertEquals(GameLinkStatus.LINKED, linked.status)
        assertEquals("42", MariaDbSupport.string("SELECT discord_user_id FROM site_registrations WHERE gamertag_key = 'ana bedrock'"))
        assertEquals(1L, MariaDbSupport.long("SELECT pioneer FROM site_registrations WHERE gamertag_key = 'ana bedrock'"))

        val join = client.post("/v1/session/join") {
            header("X-Silooo-Key", "test-key")
            contentType(ContentType.Application.Json)
            setBody("""{"xuid":"123456","gamertag":"Ana Bedrock"}""")
        }
        assertEquals(HttpStatusCode.OK, join.status, join.bodyAsText())
        assertEquals(1L, MariaDbSupport.count("company_members"))
    }

    @Test fun `Une inscription existante passe pionnier et est admise`() = testApplication {
        MariaDbSupport.insertPlayer("7", "KrTest")
        MariaDbSupport.update("INSERT INTO site_registrations (gamertag_key, gamertag, pioneer) VALUES ('kr neuf', 'Kr Neuf', false)")
        application { siloooModule(dependencies()) }
        val response = client.post("/v1/lobby/link-codes") {
            header("X-Silooo-Key", "test-key")
            contentType(ContentType.Application.Json)
            setBody("""{"requestId":"${UUID.randomUUID()}","actor":"7","gamertag":"Kr Neuf"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("Kr Neuf", MariaDbSupport.string("SELECT gamertag FROM players WHERE xuid = 7"))
        assertEquals(1L, MariaDbSupport.long("SELECT pioneer FROM site_registrations WHERE gamertag_key = 'kr neuf'"))
        assertNotNull(MariaDbSupport.string("SELECT admitted_at FROM site_registrations WHERE gamertag_key = 'kr neuf'"))
        assertEquals("regle:pionnier-salle", MariaDbSupport.string("SELECT admitted_by FROM beta_access WHERE player_xuid = 7"))
        assertEquals(1L, MariaDbSupport.count("discord_game_codes"))
    }
}
