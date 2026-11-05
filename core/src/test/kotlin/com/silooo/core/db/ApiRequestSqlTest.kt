package com.silooo.core.db

import com.silooo.core.api.ActionResult
import com.silooo.core.api.Idempotency
import com.silooo.core.api.Refusal
import com.silooo.core.api.RefusalCodes
import io.ktor.http.HttpStatusCode
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `api_requests` et l'idempotence sur la vraie MariaDB : le SQL du magasin, l'atomicité du marqueur et de
 * l'action, la reprise d'un marqueur abandonné, la purge. Ignoré sans Docker (voir [MariaDbSupport]).
 */
class ApiRequestSqlTest {
    private val id = "6f1c2b7e-0d4a-4a53-9c1e-3f6a8d2b5e90"
    private val database get() = MariaDbSupport.database
    private val idempotency get() = Idempotency(database)

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    private fun answer(n: Int) = ActionResult.ok(buildJsonObject { put("n", n) })

    private fun state(requestId: String = id): String? =
        MariaDbSupport.string("SELECT JSON_VALUE(response, '$.state') FROM api_requests WHERE request_id = ?", requestId)

    @Test
    @DisplayName("Première fois : la ligne est écrite, la réponse gardée, et le rejeu ne refait pas l'action")
    fun storesAndReplays() = runBlocking {
        MariaDbSupport.insertPlayer("2535428000000001")
        var runs = 0
        val action: (java.sql.Connection) -> ActionResult = { connection ->
            runs++
            connection.prepareStatement("INSERT INTO game_events (xuid, kind, payload) VALUES (NULL, 'ANNONCE', '{}')").use { it.executeUpdate() }
            answer(runs)
        }

        val first = idempotency.run(id, "collect", action)
        val second = idempotency.run(id, "collect", action)

        assertEquals(1, runs)
        assertEquals(200, first.status)
        assertEquals(first, second)
        assertEquals(1L, MariaDbSupport.count("api_requests"))
        assertEquals(1L, MariaDbSupport.count("game_events"), "l'effet de l'action n'est écrit qu'une fois")
        assertEquals("FAIT", state())
        assertEquals("collect", MariaDbSupport.string("SELECT endpoint FROM api_requests WHERE request_id = ?", id))
    }

    @Test
    @DisplayName("Une réponse sans corps (204) survit à l'aller-retour par la base")
    fun noContentRoundTrip() = runBlocking {
        idempotency.run(id, "fireStaff") { ActionResult.noContent() }
        val replay = idempotency.run(id, "fireStaff") { error("ne doit pas tourner") }
        assertEquals(204, replay.status)
        assertNull(replay.body)
    }

    @Test
    @DisplayName("Le marqueur est visible des autres connexions pendant l'action")
    fun markerIsVisibleWhileRunning() = runBlocking {
        var seenState: String? = null
        idempotency.run(id, "collect") {
            seenState = state() // une autre connexion : le marqueur est déjà validé
            answer(1)
        }
        assertEquals("EN_COURS", seenState)
    }

    @Test
    @DisplayName("Un refus du jeu annule les écritures de l'action, mais le refus est gardé et rejoué")
    fun refusalRollsBackButIsKept() = runBlocking {
        var runs = 0
        val action: (java.sql.Connection) -> ActionResult = { connection ->
            runs++
            connection.prepareStatement("INSERT INTO game_events (xuid, kind, payload) VALUES (NULL, 'ANNONCE', '{}')").use { it.executeUpdate() }
            throw Refusal(HttpStatusCode.Conflict, RefusalCodes.NOT_ENOUGH_DONAR, 40.0)
        }

        val first = idempotency.run(id, "buyLevels", action)
        val second = idempotency.run(id, "buyLevels", action)

        assertEquals(1, runs)
        assertEquals(409, first.status)
        assertEquals(RefusalCodes.NOT_ENOUGH_DONAR, first.body!!.jsonObject["reason"]!!.jsonPrimitive.content)
        assertEquals(first, second)
        assertEquals(0L, MariaDbSupport.count("game_events"), "l'écriture de l'action refusée est annulée")
        assertEquals("FAIT", state())
    }

    @Test
    @DisplayName("Une panne annule l'action et retire le marqueur : le nouvel essai du même requestId s'exécute")
    fun failureRollsBackAndReleases() = runBlocking {
        assertThrows<IllegalStateException> {
            runBlocking {
                idempotency.run(id, "collect") { connection ->
                    connection.prepareStatement("INSERT INTO game_events (xuid, kind, payload) VALUES (NULL, 'ANNONCE', '{}')").use { it.executeUpdate() }
                    error("panne")
                }
            }
        }
        assertEquals(0L, MariaDbSupport.count("game_events"))
        assertEquals(0L, MariaDbSupport.count("api_requests"), "le marqueur est retiré")

        assertEquals(200, idempotency.run(id, "collect") { answer(1) }.status)
    }

    @Test
    @DisplayName("Deux appels en même temps avec le même requestId : un seul agit, l'autre reçoit 409 CONFLIT_REESSAYER")
    fun concurrentCallsConflict() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var runs = 0

        val slow = async(Dispatchers.IO) {
            idempotency.run(id, "collect") {
                runs++
                started.countDown()
                assertTrue(release.await(30, TimeUnit.SECONDS))
                answer(1)
            }
        }
        assertTrue(started.await(30, TimeUnit.SECONDS), "le premier appel est en cours")

        val duplicate = idempotency.run(id, "collect") { runs++; answer(2) }
        release.countDown()
        val first = slow.await()

        assertEquals(409, duplicate.status)
        assertEquals(RefusalCodes.CONFLICT_RETRY, duplicate.body!!.jsonObject["reason"]!!.jsonPrimitive.content)
        assertEquals(200, first.status)
        assertEquals(1, runs, "l'action ne tourne qu'une fois")
        assertEquals(first, idempotency.run(id, "collect") { error("ne doit pas tourner") })
    }

    @Test
    @DisplayName("Vingt appels simultanés du même requestId : l'action tourne une seule fois")
    fun manyConcurrentCalls() = runBlocking {
        MariaDbSupport.insertPlayer("2535428000000001")
        val runs = java.util.concurrent.atomic.AtomicInteger()
        val results = (1..20).map {
            async(Dispatchers.IO) { idempotency.run(id, "collect") { runs.incrementAndGet(); answer(1) } }
        }.map { it.await() }

        assertEquals(1, runs.get())
        // Chaque réponse est soit la réponse gardée (200), soit CONFLIT_REESSAYER (409) : jamais autre chose.
        assertTrue(results.all { it.status == 200 || it.status == 409 }, results.toString())
        assertTrue(results.any { it.status == 200 })
        assertEquals(1L, MariaDbSupport.count("api_requests"))
    }

    @Test
    @DisplayName("Un marqueur abandonné depuis plus d'une minute est repris")
    fun staleMarkerIsTakenOver() = runBlocking {
        MariaDbSupport.update(
            "INSERT INTO api_requests (request_id, endpoint, response, created_at) " +
                "VALUES (?, 'collect', '{\"state\":\"EN_COURS\",\"owner\":\"processus-tombe\"}', UTC_TIMESTAMP(3) - INTERVAL 5 MINUTE)",
            id,
        )
        var runs = 0
        val stored = idempotency.run(id, "collect") { runs++; answer(1) }

        assertEquals(1, runs)
        assertEquals(200, stored.status)
        assertEquals("FAIT", state())
    }

    @Test
    @DisplayName("Un marqueur récent n'est pas repris : 409")
    fun recentMarkerIsRespected() = runBlocking {
        MariaDbSupport.update(
            "INSERT INTO api_requests (request_id, endpoint, response) VALUES (?, 'collect', '{\"state\":\"EN_COURS\",\"owner\":\"autre\"}')",
            id,
        )
        var runs = 0
        val stored = idempotency.run(id, "collect") { runs++; answer(1) }

        assertEquals(0, runs)
        assertEquals(409, stored.status)
        assertEquals("EN_COURS", state(), "le marqueur de l'autre n'est pas touché")
    }

    @Test
    @DisplayName("Un requestId déjà servi à une autre opération : 400, rien n'est écrit")
    fun otherEndpoint() = runBlocking {
        idempotency.run(id, "collect") { answer(1) }
        var runs = 0
        val stored = idempotency.run(id, "buyLevels") { runs++; answer(2) }
        assertEquals(0, runs)
        assertEquals(400, stored.status)
        assertEquals("collect", MariaDbSupport.string("SELECT endpoint FROM api_requests WHERE request_id = ?", id))
    }

    @Test
    @DisplayName("complete et release ne touchent que le marqueur de leur propriétaire")
    fun ownerChecks() {
        val store = JdbcApiRequestStore()
        runBlocking {
            database.transaction { store.claim(it, id, "collect", "alice", Duration.ofMinutes(1)) }
            assertFalse(database.transaction { store.complete(it, id, "bob", StoredResponse(200, null)) })
            database.transaction { store.release(it, id, "bob") }
            assertEquals("EN_COURS", state(), "ni complete ni release de bob n'ont d'effet")

            assertTrue(database.transaction { store.complete(it, id, "alice", StoredResponse(204, null)) })
            assertEquals("FAIT", state())
            database.transaction { store.release(it, id, "alice") }
            assertEquals("FAIT", state(), "une réponse gardée ne se retire pas par release")
        }
    }

    @Test
    @DisplayName("La purge efface les réponses plus vieilles que la durée gardée, par lots, et laisse les autres")
    fun purgeByAge() = runBlocking {
        val total = Idempotency.PURGE_BATCH + 500
        MariaDbSupport.update(
            "INSERT INTO api_requests (request_id, endpoint, response, created_at) " +
                "SELECT UUID(), 'collect', '{\"state\":\"FAIT\",\"status\":204}', UTC_TIMESTAMP(3) - INTERVAL 3 DAY FROM seq_1_to_$total",
        )
        MariaDbSupport.update(
            "INSERT INTO api_requests (request_id, endpoint, response) VALUES (?, 'collect', '{\"state\":\"FAIT\",\"status\":204}')",
            id,
        )

        assertEquals(total, idempotency.purge(Duration.ofHours(24)))
        assertEquals(1L, MariaDbSupport.count("api_requests"))
        assertEquals("FAIT", state(), "la ligne récente reste")
        assertEquals(0, idempotency.purge(Duration.ofHours(24)))
    }

    @Test
    @DisplayName("Les 36 caractères d'un requestId UUID tiennent dans la colonne")
    fun uuidFitsTheColumn() = runBlocking {
        val longest = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        idempotency.run(longest, "x".repeat(Idempotency.ENDPOINT_MAX)) { answer(1) }
        assertEquals(longest, MariaDbSupport.string("SELECT request_id FROM api_requests"))
    }
}
