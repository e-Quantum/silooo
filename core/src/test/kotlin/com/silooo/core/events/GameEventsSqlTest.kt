package com.silooo.core.events

import com.silooo.core.Dependencies
import com.silooo.core.api.EventsPage
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.MariaDbSupport
import com.silooo.core.db.Xuid
import com.silooo.core.siloooModule
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** `publishEvent` et `GET /v1/events` sur la vraie MariaDB. Ignoré sans Docker. */
class GameEventsSqlTest {
    private val database get() = MariaDbSupport.database
    private val kr = Xuid.parse("2535428000000001")!!

    @BeforeEach
    fun prepare() {
        MariaDbSupport.prepare()
        MariaDbSupport.insertPlayer(kr.text)
    }

    private suspend fun publish(kind: GameEventKind, xuid: Xuid? = null, n: Int = 0): Long =
        database.transaction { publishEvent(it, kind, xuid, buildJsonObject { put("n", n) }) }

    @Test
    @DisplayName("publishEvent écrit la ligne et rend un identifiant qui croît")
    fun publishWritesTheRow() = runBlocking {
        val first = publish(GameEventKind.GEMMES_RECUES, kr, 1)
        val second = publish(GameEventKind.ANNONCE, null, 2)

        assertTrue(second > first)
        assertEquals(2L, MariaDbSupport.count("game_events"))
        assertEquals("GEMMES_RECUES", MariaDbSupport.string("SELECT kind FROM game_events WHERE id = ?", first))
        assertEquals("2535428000000001", MariaDbSupport.string("SELECT CAST(xuid AS CHAR) FROM game_events WHERE id = ?", first))
        assertNull(MariaDbSupport.string("SELECT xuid FROM game_events WHERE id = ?", second), "null : pour tout le serveur")
        assertEquals("2", MariaDbSupport.string("SELECT JSON_VALUE(payload, '$.n') FROM game_events WHERE id = ?", second))
    }

    @Test
    @DisplayName("Un événement publié dans une transaction annulée n'existe pas")
    fun publishRollsBackWithItsTransaction() = runBlocking {
        assertThrows<IllegalStateException> {
            runBlocking {
                database.transaction { connection ->
                    publishEvent(connection, GameEventKind.INCIDENT, kr)
                    error("ce qui causait l'événement a échoué")
                }
            }
        }
        assertEquals(0L, MariaDbSupport.count("game_events"))
    }

    @Test
    @DisplayName("readAfter : strictement après le curseur, du plus ancien au plus récent, limite respectée")
    fun readAfterIsStrictOrderedAndBounded() = runBlocking {
        val ids = (1..5).map { publish(GameEventKind.ANNONCE, null, it) }
        val store = JdbcEventStore()

        val all = database.transaction { store.readAfter(it, 0, 500) }
        assertEquals(ids, all.map { it.id })

        val afterSecond = database.transaction { store.readAfter(it, ids[1], 500) }
        assertEquals(ids.drop(2), afterSecond.map { it.id })

        val limited = database.transaction { store.readAfter(it, 0, 2) }
        assertEquals(ids.take(2), limited.map { it.id })

        assertEquals(emptyList<Long>(), database.transaction { store.readAfter(it, ids.last(), 500) }.map { it.id })
    }

    @Test
    @DisplayName("readAfter rend le XUID en chaîne (même au-delà de 2^63), null pour tout le serveur, et la charge utile")
    fun readAfterShapesTheRows() = runBlocking {
        val huge = Xuid.parse("18446744073709551615")!!
        MariaDbSupport.insertPlayer(huge.text)
        publish(GameEventKind.GEMMES_RECUES, huge, 1)
        publish(GameEventKind.ANNONCE, null, 2)

        val events = database.transaction { JdbcEventStore().readAfter(it, 0, 10) }

        assertEquals("18446744073709551615", events[0].xuid)
        assertEquals("GEMMES_RECUES", events[0].kind)
        assertNull(events[1].xuid)
        assertEquals("2", events[1].payload.getValue("n").toString())
    }

    @Test
    @DisplayName("Une charge utile avec accents et caractères hors du plan de base fait l'aller-retour")
    fun unicodePayloadRoundTrips() = runBlocking {
        val text = "Récolte de l'équipe 🌾 — bravo"
        database.transaction { publishEvent(it, GameEventKind.ANNONCE, null, buildJsonObject { put("texte", text) }) }
        val event = database.transaction { JdbcEventStore().readAfter(it, 0, 1) }.single()
        assertEquals(text, event.payload.getValue("texte").toString().trim('"'))
    }

    @Test
    @DisplayName("GET /v1/events lit la vraie base, page par page, avec le curseur")
    fun routeReadsTheRealTable() = testApplication {
        application { siloooModule(Dependencies(database = MariaDbSupport.database, apiKey = "cle-de-test")) }
        val ids = (1..5).map { publish(GameEventKind.ANNONCE, null, it) }

        suspend fun page(after: Long, limit: Int): EventsPage {
            val response = client.get("/v1/events?after=$after&limit=$limit") { header(SERVER_KEY_HEADER, "cle-de-test") }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return SiloooJson.decodeFromString(EventsPage.serializer(), response.bodyAsText())
        }

        val first = page(0, 2)
        assertEquals(ids.take(2), first.events.map { it.id })
        assertEquals(ids[1], first.cursor)

        val second = page(first.cursor, 2)
        assertEquals(ids.subList(2, 4), second.events.map { it.id })

        val third = page(second.cursor, 2)
        assertEquals(listOf(ids[4]), third.events.map { it.id })
        assertEquals(ids[4], third.cursor)

        val empty = page(third.cursor, 2)
        assertEquals(emptyList<Long>(), empty.events.map { it.id })
        assertEquals(third.cursor, empty.cursor, "rien de neuf : le curseur ne bouge pas")
    }
}
