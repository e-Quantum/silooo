package com.silooo.core.api

import com.silooo.core.Dependencies
import com.silooo.core.db.InMemoryApiRequestStore
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.siloooModule
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `call.idempotent(...)` vu par HTTP : la vérification de `requestId` et `actor`, le rejeu, le refus gardé, le 204,
 * la variante `DELETE`. Les routes de ce test sont des routes d'essai, ajoutées à côté du module ; la logique
 * de [Idempotency] a ses tests (`IdempotencyTest`, `ApiRequestSqlTest`).
 */
class IdempotentCallTest {
    private val key = "cle-de-test"
    private val id = "6f1c2b7e-0d4a-4a53-9c1e-3f6a8d2b5e90"
    private val kr = "2535428000000001"

    private class Harness(val database: PassThroughDatabase, val store: InMemoryApiRequestStore, val runs: AtomicInteger)

    private fun withRoutes(block: suspend ApplicationTestBuilder.(Harness) -> Unit) = testApplication {
        val database = PassThroughDatabase()
        val store = InMemoryApiRequestStore()
        val runs = AtomicInteger()
        val dependencies = Dependencies(database = database, apiKey = key, idempotency = Idempotency(database, store))
        application {
            siloooModule(dependencies)
            routing {
                route("/v1") {
                    post("/test/earn") {
                        val action = call.receive<ActionBase>()
                        call.idempotent(dependencies, action, "earn") { _, actor ->
                            ActionResult.ok(buildJsonObject { put("run", runs.incrementAndGet()); put("actor", actor.text) })
                        }
                    }
                    post("/test/spend") {
                        val action = call.receive<ActionBase>()
                        call.idempotent(dependencies, action, "spend") { _, _ ->
                            runs.incrementAndGet()
                            throw Refusal(HttpStatusCode.Conflict, RefusalCodes.NOT_ENOUGH_DONAR, 40.0)
                        }
                    }
                    delete("/test/staff") {
                        val query = call.request.queryParameters
                        call.idempotent(dependencies, query["requestId"].orEmpty(), query["actor"].orEmpty(), "fire") { _, _ ->
                            runs.incrementAndGet()
                            ActionResult.noContent()
                        }
                    }
                }
            }
        }
        block(Harness(database, store, runs))
    }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String): HttpResponse =
        client.post(path) {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun action(requestId: String = id, actor: String = kr) = """{"requestId":"$requestId","actor":"$actor"}"""

    @Test
    @DisplayName("Première fois : l'action tourne et sa réponse part ; le rejeu renvoie la même réponse sans refaire l'action")
    fun replayReturnsTheKeptResponse() = withRoutes { app ->
        val first = post("/v1/test/earn", action())
        val second = post("/v1/test/earn", action())

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals("""{"run":1,"actor":"$kr"}""", first.bodyAsText())
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(first.bodyAsText(), second.bodyAsText())
        assertEquals(1, app.runs.get(), "l'action ne se refait pas")
        assertTrue(app.store.has(id))
    }

    @Test
    @DisplayName("Un requestId en majuscules et le même en minuscules sont la même requête")
    fun requestIdIsCaseInsensitive() = withRoutes { app ->
        post("/v1/test/earn", action(id))
        val replay = post("/v1/test/earn", action(id.uppercase()))

        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals(1, app.runs.get())
        assertEquals(1, app.store.size)
    }

    @Test
    @DisplayName("Deux requestId différents sont deux actions")
    fun differentRequestIdsAreDifferentActions() = withRoutes { app ->
        post("/v1/test/earn", action("6f1c2b7e-0d4a-4a53-9c1e-3f6a8d2b5e91"))
        post("/v1/test/earn", action("6f1c2b7e-0d4a-4a53-9c1e-3f6a8d2b5e92"))
        assertEquals(2, app.runs.get())
    }

    @Test
    @DisplayName("requestId qui n'est pas un UUID, actor qui n'est pas un XUID, corps incomplet : 400 REQUETE_INVALIDE, rien n'est écrit")
    fun badActionIsRefused() = withRoutes { app ->
        val bad = listOf(
            action(requestId = "pas-un-uuid"),
            action(requestId = ""),
            action(actor = "abc"),
            action(actor = "123456789012345678901"),
            """{"actor":"$kr"}""",
            """{"requestId":"$id"}""",
            "pas du json",
        )
        for (body in bad) {
            val response = post("/v1/test/earn", body)
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertEquals("""{"reason":"REQUETE_INVALIDE"}""", response.bodyAsText(), body)
        }
        assertEquals(0, app.runs.get())
        assertEquals(0, app.store.size, "aucune ligne d'idempotence pour une requête refusée à l'entrée")
    }

    @Test
    @DisplayName("Un refus du jeu garde son statut et son corps (avec missing), et se rejoue à l'identique")
    fun refusalIsKeptAndReplayed() = withRoutes { app ->
        val first = post("/v1/test/spend", action())
        val second = post("/v1/test/spend", action())

        assertEquals(HttpStatusCode.Conflict, first.status)
        assertEquals("""{"reason":"PAS_ASSEZ_DE_DONAR","missing":40.0}""", first.bodyAsText())
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertEquals(first.bodyAsText(), second.bodyAsText())
        assertEquals(1, app.runs.get())
    }

    @Test
    @DisplayName("Même requête encore en cours : 409 CONFLIT_REESSAYER, l'action ne tourne pas")
    fun inProgressIsAConflict() = withRoutes { app ->
        app.database.transaction { app.store.claim(it, id, "earn", "un-autre-appel", Duration.ofMinutes(1)) }

        val response = post("/v1/test/earn", action())

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("""{"reason":"CONFLIT_REESSAYER"}""", response.bodyAsText())
        assertEquals(0, app.runs.get())
        assertTrue(app.store.isPending(id), "le marqueur de l'autre appel reste")
    }

    @Test
    @DisplayName("Un requestId déjà servi à une autre opération : 400, l'action ne tourne pas")
    fun requestIdReusedOnAnotherOperation() = withRoutes { app ->
        post("/v1/test/earn", action())
        val response = post("/v1/test/spend", action())

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("""{"reason":"REQUETE_INVALIDE"}""", response.bodyAsText())
        assertEquals(1, app.runs.get(), "seul le premier appel a agi")
    }

    @Test
    @DisplayName("DELETE : requestId et actor en paramètres ; 204 sans corps, rejoué sans refaire l'action ; paramètres absents ou faux : 400")
    fun deleteVariant() = withRoutes { app ->
        suspend fun delete(query: String): HttpResponse = client.delete("/v1/test/staff$query") { header(SERVER_KEY_HEADER, key) }

        val first = delete("?requestId=$id&actor=$kr")
        val second = delete("?requestId=$id&actor=$kr")

        assertEquals(HttpStatusCode.NoContent, first.status)
        assertEquals("", first.bodyAsText())
        assertEquals(HttpStatusCode.NoContent, second.status)
        assertEquals(1, app.runs.get())

        for (query in listOf("", "?requestId=$id", "?actor=$kr", "?requestId=x&actor=$kr", "?requestId=$id&actor=abc")) {
            assertEquals(HttpStatusCode.BadRequest, delete(query).status, query)
        }
        assertEquals(1, app.runs.get())
        assertFalse(app.store.isPending(id))
    }
}
