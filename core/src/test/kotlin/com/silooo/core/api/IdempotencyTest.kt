package com.silooo.core.api

import com.silooo.core.db.ApiRequestStore
import com.silooo.core.db.InMemoryApiRequestStore
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.db.StoredResponse
import com.silooo.core.db.VersionConflict
import io.ktor.http.HttpStatusCode
import java.sql.Connection
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * La logique de [Idempotency] avec des faux (pas de base). Le SQL et l'atomicité réelle sont dans
 * `ApiRequestSqlTest`, sur MariaDB.
 */
class IdempotencyTest {
    private val database = PassThroughDatabase()
    private val store = InMemoryApiRequestStore()
    private val idempotency = Idempotency(database, store)
    private val id = "6f1c2b7e-0d4a-4a53-9c1e-3f6a8d2b5e90"

    private fun answer(n: Int): ActionResult = ActionResult.ok(buildJsonObject { put("n", n) })

    private fun StoredResponse.n(): Int = body!!.jsonObject["n"]!!.jsonPrimitive.content.toInt()

    @Test
    @DisplayName("Première fois : l'action tourne une fois et sa réponse est gardée")
    fun firstRunExecutesAndKeeps() = runBlocking {
        var runs = 0
        val stored = idempotency.run(id, "collect") { runs++; answer(7) }

        assertEquals(1, runs)
        assertEquals(200, stored.status)
        assertEquals(7, stored.n())
        assertTrue(store.has(id))
        assertEquals(false, store.isPending(id), "le marqueur est remplacé par la réponse")
    }

    @Test
    @DisplayName("Rejeu : la réponse gardée repart sans refaire l'action")
    fun replayDoesNotRedo() = runBlocking {
        var runs = 0
        val first = idempotency.run(id, "collect") { runs++; answer(runs) }
        val second = idempotency.run(id, "collect") { runs++; answer(runs) }
        val third = idempotency.run(id, "collect") { runs++; answer(runs) }

        assertEquals(1, runs, "l'action ne se refait pas")
        assertEquals(first, second)
        assertEquals(first, third)
    }

    @Test
    @DisplayName("Une réponse sans corps (204) est gardée et rejouée telle quelle")
    fun noContentIsKept() = runBlocking {
        var runs = 0
        val first = idempotency.run(id, "fireStaff") { runs++; ActionResult.noContent() }
        val second = idempotency.run(id, "fireStaff") { runs++; ActionResult.noContent() }

        assertEquals(1, runs)
        assertEquals(204, first.status)
        assertNull(second.body)
        assertEquals(first, second)
    }

    @Test
    @DisplayName("Requête encore en cours : 409 CONFLIT_REESSAYER, et la seconde action ne tourne pas")
    fun inProgressGivesConflict() = runBlocking {
        var innerRuns = 0
        var inner: StoredResponse? = null
        val outer = idempotency.run(id, "collect") {
            // Le même requestId arrive pendant que le premier appel tourne encore.
            inner = runBlocking { idempotency.run(id, "collect") { innerRuns++; answer(2) } }
            answer(1)
        }

        assertEquals(0, innerRuns)
        assertEquals(409, inner!!.status)
        assertEquals(RefusalCodes.CONFLICT_RETRY, inner.body!!.jsonObject["reason"]!!.jsonPrimitive.content)
        assertEquals(1, outer.n(), "le premier appel n'est pas gêné")
        assertEquals(1, idempotency.run(id, "collect") { answer(99) }.n(), "et son rejeu rend sa réponse")
    }

    @Test
    @DisplayName("Un refus du jeu annule l'action, est gardé, et se rejoue à l'identique")
    fun refusalIsKeptAndReplayed() = runBlocking {
        var runs = 0
        val refuse: (Connection) -> ActionResult = { runs++; throw Refusal(HttpStatusCode.Conflict, RefusalCodes.NOT_ENOUGH_DONAR, 12.5) }

        val first = idempotency.run(id, "buyLevels", refuse)
        val second = idempotency.run(id, "buyLevels", refuse)

        assertEquals(1, runs, "le refus rejoué ne refait pas l'action")
        assertEquals(409, first.status)
        assertEquals(RefusalCodes.NOT_ENOUGH_DONAR, first.body!!.jsonObject["reason"]!!.jsonPrimitive.content)
        assertEquals(12.5, first.body.jsonObject["missing"]!!.jsonPrimitive.content.toDouble())
        assertEquals(first, second)
        assertEquals(1, database.rollbacks, "l'action refusée est annulée")
    }

    @Test
    @DisplayName("PAS_LE_PDG garde son statut 403")
    fun refusalKeepsItsStatus() = runBlocking {
        val stored = idempotency.run(id, "invite") { throw Refusal(HttpStatusCode.Forbidden, RefusalCodes.NOT_THE_CEO) }
        assertEquals(403, stored.status)
        assertNull(stored.body!!.jsonObject["missing"], "pas de somme quand le refus n'en porte pas")
    }

    @Test
    @DisplayName("CONFLIT_REESSAYER n'est pas gardé : le nouvel essai du même requestId s'exécute")
    fun conflictIsNotKept() = runBlocking {
        val first = idempotency.run(id, "collect") { throw VersionConflict() }
        assertEquals(409, first.status)
        assertEquals(RefusalCodes.CONFLICT_RETRY, first.body!!.jsonObject["reason"]!!.jsonPrimitive.content)
        assertEquals(false, store.has(id), "le marqueur est retiré")

        val retry = idempotency.run(id, "collect") { answer(5) }
        assertEquals(200, retry.status)
        assertEquals(5, retry.n())
    }

    @Test
    @DisplayName("Une panne retire le marqueur et remonte : le nouvel essai est permis")
    fun failureReleasesTheMarker() = runBlocking {
        assertThrows<IllegalStateException> { runBlocking { idempotency.run(id, "collect") { error("panne") } } }
        assertEquals(false, store.has(id))

        assertEquals(3, idempotency.run(id, "collect") { answer(3) }.n())
    }

    @Test
    @DisplayName("Un requestId déjà servi à une autre opération : 400 REQUETE_INVALIDE, l'action ne tourne pas")
    fun otherEndpointIsRefused() = runBlocking {
        idempotency.run(id, "collect") { answer(1) }

        var runs = 0
        val stored = idempotency.run(id, "buyLevels") { runs++; answer(2) }

        assertEquals(0, runs)
        assertEquals(400, stored.status)
        assertEquals(ErrorCodes.BAD_REQUEST, stored.body!!.jsonObject["reason"]!!.jsonPrimitive.content)
    }

    @Test
    @DisplayName("Marqueur repris par un autre appel pendant l'action : l'action est annulée et la réponse est 409")
    fun lostMarkerCancelsTheAction() = runBlocking {
        val stealing = object : ApiRequestStore by store {
            override fun complete(connection: Connection, requestId: String, owner: String, response: StoredResponse) = false
        }
        var runs = 0
        val stored = Idempotency(database, stealing).run(id, "collect") { runs++; answer(1) }

        assertEquals(1, runs)
        assertEquals(409, stored.status)
        assertEquals(RefusalCodes.CONFLICT_RETRY, stored.body!!.jsonObject["reason"]!!.jsonPrimitive.content)
        assertEquals(1, database.rollbacks, "les effets de l'action sont annulés")
    }

    @Test
    @DisplayName("Deux requestId différents ne se gênent pas")
    fun differentIdsAreIndependent() = runBlocking {
        val other = "0d9a4c1e-77b2-4f0e-8a64-5c2d1e9f3b17"
        assertEquals(1, idempotency.run(id, "collect") { answer(1) }.n())
        assertEquals(2, idempotency.run(other, "collect") { answer(2) }.n())
        assertEquals(1, idempotency.run(id, "collect") { answer(9) }.n())
        assertEquals(2, idempotency.run(other, "collect") { answer(9) }.n())
    }

    @Test
    @DisplayName("Un endpoint de plus de 64 caractères est refusé avant toute écriture")
    fun endpointTooLong() {
        assertThrows<IllegalArgumentException> {
            runBlocking { idempotency.run(id, "x".repeat(Idempotency.ENDPOINT_MAX + 1)) { answer(1) } }
        }
        assertEquals(0, database.transactions)
    }

    @Test
    @DisplayName("La purge efface les lignes plus vieilles que la durée gardée, et seulement celles-là")
    fun purgeRemovesOldRows() = runBlocking {
        idempotency.run(id, "collect") { answer(1) }
        store.now = store.now.plus(Duration.ofHours(25))
        val recent = "0d9a4c1e-77b2-4f0e-8a64-5c2d1e9f3b17"
        idempotency.run(recent, "collect") { answer(2) }

        assertEquals(1, idempotency.purge(Duration.ofHours(24)))
        assertEquals(false, store.has(id))
        assertEquals(true, store.has(recent))
    }

    @Test
    @DisplayName("Le corps d'un refus suit le schéma Refused de l'OpenAPI")
    fun refusedBodyShape() {
        val body = SiloooJson.encodeToJsonElement(RefusedBody.serializer(), RefusedBody(RefusalCodes.NOT_THE_CEO))
        assertEquals(setOf("reason"), (body as JsonObject).keys)
    }
}
