package com.silooo.core.events

import com.silooo.core.Dependencies
import com.silooo.core.api.ErrorBody
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.EventsPage
import com.silooo.core.api.GameEvent
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.siloooModule
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.sql.Connection
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Un [EventStore] en mémoire qui note la dernière lecture demandée. */
class InMemoryEventStore(private val events: List<GameEvent>) : EventStore {
    var lastAfter: Long? = null
    var lastLimit: Int? = null

    override fun readAfter(connection: Connection, after: Long, limit: Int): List<GameEvent> {
        lastAfter = after
        lastLimit = limit
        return events.filter { it.id > after }.sortedBy { it.id }.take(limit)
    }
}

/** `pollEvents` avec un faux (pas de base). Le SQL est dans `GameEventsSqlTest`. */
class EventsRoutesTest {
    private val key = "cle-de-test"

    private fun event(id: Long, xuid: String? = null, kind: GameEventKind = GameEventKind.ANNONCE): GameEvent =
        GameEvent(id, xuid, kind.name, buildJsonObject { put("n", id) })

    private fun withApp(events: List<GameEvent>, block: suspend ApplicationTestBuilder.(InMemoryEventStore, PassThroughDatabase) -> Unit) =
        testApplication {
            val database = PassThroughDatabase()
            val store = InMemoryEventStore(events)
            application { siloooModule(Dependencies(database = database, apiKey = key, eventStore = store)) }
            block(store, database)
        }

    private suspend fun ApplicationTestBuilder.poll(query: String, withKey: String? = key): HttpResponse =
        client.get("/v1/events$query") { if (withKey != null) header(SERVER_KEY_HEADER, withKey) }

    private suspend fun page(response: HttpResponse): EventsPage {
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        return SiloooJson.decodeFromString(EventsPage.serializer(), response.bodyAsText())
    }

    private suspend fun assertBadRequest(response: HttpResponse) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCodes.BAD_REQUEST, SiloooJson.decodeFromString(ErrorBody.serializer(), response.bodyAsText()).reason)
    }

    @Test
    @DisplayName("Rend les événements d'identifiant strictement supérieur à after, du plus ancien au plus récent")
    fun strictlyAfterOldestFirst() = withApp(listOf(event(5), event(3), event(9), event(7))) { _, _ ->
        val result = page(poll("?after=3"))
        assertEquals(listOf(5L, 7L, 9L), result.events.map { it.id })
        assertEquals(9L, result.cursor)
    }

    @Test
    @DisplayName("Sans événement neuf, le curseur reste à after")
    fun cursorStaysWhenNothingNew() = withApp(listOf(event(5))) { _, _ ->
        val result = page(poll("?after=5"))
        assertEquals(emptyList<GameEvent>(), result.events)
        assertEquals(5L, result.cursor)
    }

    @Test
    @DisplayName("limit borne la page, le curseur est le dernier identifiant rendu, et la page suivante reprend là")
    fun limitAndPaging() = withApp((1L..7L).map { event(it) }) { store, _ ->
        val first = page(poll("?after=0&limit=3"))
        assertEquals(listOf(1L, 2L, 3L), first.events.map { it.id })
        assertEquals(3L, first.cursor)

        val second = page(poll("?after=${first.cursor}&limit=3"))
        assertEquals(listOf(4L, 5L, 6L), second.events.map { it.id })

        val third = page(poll("?after=${second.cursor}&limit=3"))
        assertEquals(listOf(7L), third.events.map { it.id })
        assertEquals(7L, third.cursor)
        assertEquals(3, store.lastLimit)
    }

    @Test
    @DisplayName("Sans limit : 100, comme le contrat ; limit 500 passe, 501 est refusé")
    fun limitBounds() = withApp(emptyList()) { store, _ ->
        page(poll("?after=0"))
        assertEquals(EVENTS_DEFAULT_LIMIT, store.lastLimit)
        assertEquals(100, store.lastLimit)

        page(poll("?after=0&limit=500"))
        assertEquals(500, store.lastLimit)
        page(poll("?after=0&limit=1"))
        assertEquals(1, store.lastLimit)

        assertBadRequest(poll("?after=0&limit=501"))
    }

    @Test
    @DisplayName("after manquant ou négatif, limit absurde : 400 REQUETE_INVALIDE, sans lire la base")
    fun badParameters() = withApp(listOf(event(1))) { _, database ->
        for (query in listOf("", "?limit=10", "?after=abc", "?after=-1", "?after=1.5", "?after=0&limit=0", "?after=0&limit=-3", "?after=0&limit=abc", "?after=99999999999999999999")) {
            assertBadRequest(poll(query))
        }
        assertEquals(0, database.transactions)
    }

    @Test
    @DisplayName("Un événement pour tout le serveur a un xuid null ; un événement personnel garde son XUID en chaîne")
    fun xuidIsNullOrString() = withApp(listOf(event(1, xuid = null), event(2, xuid = "2535428000000001", kind = GameEventKind.GEMMES_RECUES))) { _, _ ->
        val result = page(poll("?after=0"))
        assertEquals(null, result.events[0].xuid)
        assertEquals("2535428000000001", result.events[1].xuid)
        assertEquals("GEMMES_RECUES", result.events[1].kind)
        assertEquals(2L, result.events[1].payload.getValue("n").toString().toLong())
    }

    @Test
    @DisplayName("Sans clé : 401, et la base n'est pas lue")
    fun needsTheKey() = withApp(listOf(event(1))) { _, database ->
        val response = poll("?after=0", withKey = null)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, database.transactions)
        assertTrue(response.bodyAsText().contains(ErrorCodes.INVALID_KEY))
    }
}
