package com.silooo.core.lobby

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.contract.OpenApi
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

/** Les records de la salle d'attente sur une vraie MariaDB (V17). Ignoré sans Docker : la CI le joue. */
class LobbySqlTest {
    private val now = Instant.parse("2026-11-07T12:00:00Z")
    private val contract = OpenApi.load()
    @BeforeEach fun prepare() = MariaDbSupport.prepare()

    private fun dependencies(at: Instant = now) = Dependencies(
        database = MariaDbSupport.database, apiKey = "test-key", definitions = TestDefinitions.loaded,
        clock = Clock.fixed(at, ZoneOffset.UTC), lobby = LobbyDefinitions.load(TestDefinitions.directory),
    )

    @Test fun `Record, semaine, changement de gamertag sur une seule ligne, plafond refuse`() = testApplication {
        application { siloooModule(dependencies()) }
        suspend fun run(actor: String, gamertag: String, score: Int, seconds: Int) = client.post("/v1/lobby/runs") {
            header("X-Silooo-Key", "test-key")
            contentType(ContentType.Application.Json)
            setBody("""{"requestId":"${UUID.randomUUID()}","actor":"$actor","gamertag":"$gamertag","score":$score,"seconds":$seconds}""")
        }
        val first = run("11", "Ana", 20, 30)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val schema = contract.operations.single { it.id == "recordLobbyRun" }.responses.getValue(200)!!
        assertTrue(contract.violations(schema, Json.parseToJsonElement(first.bodyAsText())).isEmpty())
        assertEquals(HttpStatusCode.OK, run("11", "Ana Bedrock", 8, 30).status)
        assertEquals(HttpStatusCode.OK, run("12", "Kr", 9, 30).status)

        assertEquals(2L, MariaDbSupport.count("lobby_records"))
        assertEquals("Ana Bedrock", MariaDbSupport.string("SELECT gamertag FROM lobby_records WHERE xuid = 11"))
        assertEquals(20L, MariaDbSupport.long("SELECT best FROM lobby_records WHERE xuid = 11"))
        assertEquals(2L, MariaDbSupport.count("lobby_week_bests"))

        val tooFast = run("12", "Kr", 1000, 10)
        assertEquals(HttpStatusCode.BadRequest, tooFast.status)
        assertEquals(9L, MariaDbSupport.long("SELECT best FROM lobby_records WHERE xuid = 12"))

        val board = Json.parseToJsonElement(client.get("/v1/lobby/board") { header("X-Silooo-Key", "test-key") }.bodyAsText()).jsonObject
        val names = board.getValue("allTime").jsonArray.map { it.jsonObject.getValue("gamertag").jsonPrimitive.content }
        assertEquals(listOf("Ana Bedrock", "Kr"), names)
    }

    @Test fun `La semaine suivante repart de zero, la precedente reste pour le podium`() = testApplication {
        val store = JdbcLobbyStore()
        val service = LobbyService(LobbyDefinitions.load(TestDefinitions.directory), store) { emptyList() }
        val nextWeek = Instant.parse("2026-11-10T12:00:00Z")
        MariaDbSupport.database.transaction { service.run(it, com.silooo.core.db.Xuid.parse("11")!!, "Ana", 20, now) }
        val result = MariaDbSupport.database.transaction { service.run(it, com.silooo.core.db.Xuid.parse("11")!!, "Ana", 3, nextWeek) }
        assertEquals(20, result.best)
        assertEquals(3, result.weekBest)
        assertEquals(2L, MariaDbSupport.count("lobby_week_bests"))
    }

    @Test fun `Le visiteur relie et pionnier se lit`() = testApplication {
        MariaDbSupport.insertPlayer("13", "Zoe")
        MariaDbSupport.update("INSERT INTO site_registrations (gamertag_key, gamertag, pioneer) VALUES ('zoe', 'Zoe', true)")
        application { siloooModule(dependencies()) }
        val body = client.get("/v1/lobby/players/13") { header("X-Silooo-Key", "test-key") }.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals(false, json.getValue("linked").jsonPrimitive.boolean)
        assertEquals(true, json.getValue("pioneer").jsonPrimitive.boolean)
        val unknown = Json.parseToJsonElement(client.get("/v1/lobby/players/99") { header("X-Silooo-Key", "test-key") }.bodyAsText()).jsonObject
        assertEquals(0, unknown.getValue("best").jsonPrimitive.int)
    }
}
