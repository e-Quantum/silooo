package com.silooo.core.api

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.FakeDatabase
import com.silooo.core.server.ServerPresence
import com.silooo.core.siloooModule
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests de l'API sans base ni Docker : le module Ktor reçoit un faux [FakeDatabase].
 * Le contrat est dans api/silooo-core.openapi.yaml (opération `heartbeat`, schéma `cleServeur`).
 */
class ApiTest {
    private val key = "cle-de-test"
    private val now = Instant.parse("2026-11-05T12:00:00Z")

    private class Harness(val database: FakeDatabase, val presence: ServerPresence)

    private fun withApp(block: suspend ApplicationTestBuilder.(Harness) -> Unit) = testApplication {
        val database = FakeDatabase()
        val presence = ServerPresence(Clock.fixed(now, ZoneOffset.UTC))
        application { siloooModule(Dependencies(database, key, presence, TestDefinitions.loaded)) }
        block(Harness(database, presence))
    }

    private suspend fun ApplicationTestBuilder.heartbeat(body: String, withKey: String? = key): HttpResponse =
        client.post("/v1/server/heartbeat") {
            if (withKey != null) header(SERVER_KEY_HEADER, withKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun assertError(response: HttpResponse, status: HttpStatusCode, reason: String) {
        assertEquals(status, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        val body = SiloooJson.decodeFromString(ErrorBody.serializer(), response.bodyAsText())
        assertEquals(reason, body.reason)
    }

    // --- heartbeat, conforme au contrat : POST, { serverId, onlineXuids }, 204 sans corps

    @Test
    @DisplayName("heartbeat avec la bonne clé : 204 sans corps, et le serveur est noté")
    fun heartbeatWithKey() = withApp { app ->
        val response = heartbeat("""{"serverId":"bds-1","onlineXuids":["2535428000000001","2535428000000002"]}""")
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("", response.bodyAsText())

        val beat = app.presence.latest("bds-1")
        assertNotNull(beat)
        assertEquals(now, beat!!.receivedAt)
        assertEquals(setOf("2535428000000001", "2535428000000002"), beat.onlineXuids)
        assertEquals(0, app.database.attempts, "le heartbeat ne touche pas à la base")
    }

    @Test
    @DisplayName("heartbeat sans joueur en ligne : 204")
    fun heartbeatWithNobodyOnline() = withApp { app ->
        assertEquals(HttpStatusCode.NoContent, heartbeat("""{"serverId":"bds-1","onlineXuids":[]}""").status)
        assertEquals(emptySet<String>(), app.presence.latest("bds-1")!!.onlineXuids)
    }

    @Test
    @DisplayName("heartbeat : un corps qui n'a pas la forme du contrat donne 400 REQUETE_INVALIDE")
    fun heartbeatMalformed() = withApp { app ->
        val bodies = listOf(
            "pas du json",
            """{"onlineXuids":[]}""", // serverId manque
            """{"serverId":"bds-1"}""", // onlineXuids manque
            """{"serverId":"","onlineXuids":[]}""",
            """{"serverId":"bds-1","onlineXuids":["abc"]}""", // pas un XUID
            """{"serverId":"bds-1","onlineXuids":[2535428000000001]}""", // un nombre : le XUID voyage en chaîne
            """{"serverId":"bds-1","onlineXuids":["123456789012345678901"]}""", // 21 chiffres
        )
        for (body in bodies) {
            assertError(heartbeat(body), HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
        }
        assertNull(app.presence.latest("bds-1"), "un corps refusé ne note rien")
    }

    // --- la clé X-Silooo-Key sur tout /v1

    @Test
    @DisplayName("Sans en-tête X-Silooo-Key : 401 CLE_INVALIDE, et la route ne tourne pas")
    fun missingKey() = withApp { app ->
        assertError(heartbeat("""{"serverId":"bds-1","onlineXuids":[]}""", withKey = null), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_KEY)
        assertNull(app.presence.latest("bds-1"))
    }

    @Test
    @DisplayName("Avec une fausse clé : 401 CLE_INVALIDE, et la route ne tourne pas")
    fun wrongKey() = withApp { app ->
        for (wrong in listOf("autre-cle", "", "cle-de-tes", "cle-de-test-", "CLE-DE-TEST")) {
            assertError(
                heartbeat("""{"serverId":"bds-1","onlineXuids":[]}""", withKey = wrong),
                HttpStatusCode.Unauthorized,
                ErrorCodes.INVALID_KEY,
            )
        }
        assertNull(app.presence.latest("bds-1"))
    }

    @Test
    @DisplayName("La clé est exigée aussi sur les routes pas écrites : 401 et non 501")
    fun keyIsRequiredBeforeNotDone() = withApp {
        assertError(client.get("/v1/events?after=0"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_KEY)
        assertError(client.get("/v1/n-importe-quoi"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_KEY)
    }

    // --- le filet 501 PAS_FAIT

    @Test
    @DisplayName("Une route du contrat pas encore écrite répond 501 PAS_FAIT")
    fun notDoneRoutes() = withApp { app ->
        suspend fun check(response: HttpResponse) = assertError(response, HttpStatusCode.NotImplemented, ErrorCodes.NOT_DONE)

        // join, leave, pollEvents, les routes d'entreprise getCompany, collect, buyLevels, restoreStep et celles du personnel (getCandidates, hire, assignStaff, fireStaff) sont écrits (SessionRoutesTest, EventsRoutesTest, CompanyRoutesTest, StaffRoutesTest) : le filet couvre le reste du contrat.
        // todayOrder, deliverOrder et getFindings sont écrits (OrderRoutesTest, FindingRoutesTest).
        check(
            client.post("/v1/market/listings") {
                header(SERVER_KEY_HEADER, key)
                contentType(ContentType.Application.Json)
                setBody("""{"requestId":"6f1c2b7e-0d4a-4a53-9c1e-3f6a8d2b5e90","actor":"2535428000000001"}""")
            },
        )
        check(
            client.post("/v1/companies/1/incidents/1/resolve") {
                header(SERVER_KEY_HEADER, key)
                contentType(ContentType.Application.Json)
                setBody("""{"requestId":"6f1c2b7e-0d4a-4a53-9c1e-3f6a8d2b5e91","actor":"2535428000000001"}""")
            },
        )
        check(client.get("/v1/n-importe-quoi/profond/comme/ca") { header(SERVER_KEY_HEADER, key) })
        check(client.get("/v1") { header(SERVER_KEY_HEADER, key) })
        assertEquals(0, app.database.attempts)
    }

    @Test
    @DisplayName("Hors de /v1, rien n'est protégé ni inventé : 404")
    fun outsideV1() = withApp {
        assertEquals(HttpStatusCode.NotFound, client.get("/").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/healthz").status)
    }

    @Test
    @DisplayName("La clé n'est jamais renvoyée dans une réponse")
    fun keyIsNeverEchoed() = withApp {
        val response = heartbeat("""{"serverId":"bds-1","onlineXuids":[]}""", withKey = "cle-de-test-fausse")
        assertTrue(!response.bodyAsText().contains(key))
    }
}
