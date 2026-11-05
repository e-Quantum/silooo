package com.silooo.core.vote

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.Idempotency
import com.silooo.core.api.JoinResponse
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.NeverUsedApiRequestStore
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.db.RecordingConnection
import com.silooo.core.economy.EngineFixture
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.InMemorySessionStore
import com.silooo.core.session.TestBootstrap
import com.silooo.core.session.TestEconomy
import com.silooo.core.siloooModule
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le rattrapage des votes au `join`, par HTTP et avec des faux (pas de base) : `join` appelle [com.silooo.core.session.PendingVotes]
 * à côté des livraisons de Gemmes, et le solde de la réponse montre déjà les Lingots du vote. Que la ligne marquée, les Lingots et
 * l'annonce tiennent en une seule transaction se joue sur MariaDB, dans `VoteSqlTest`.
 */
class VoteJoinTest {
    private val kr = EngineFixture.KR
    private val rules = TestDefinitions.loaded.vote

    private class Harness {
        val clock = MutableClock(EngineFixture.START)
        val sessions = InMemorySessionStore(EngineFixture.START)
        val votes = InMemoryVoteStore(sessions)
        val database = PassThroughDatabase()
        val service = VoteService(TestDefinitions.loaded, votes, clock) { emptySet() }

        /** Un vote reçu pour [gamertag] avant que le joueur ne vienne. */
        fun receive(gamertag: String) = service.receiveIn(RecordingConnection().connection, IncomingVote(gamertag, "Hubyy"))
    }

    private fun Harness.dependencies(pending: PendingVotesChoice = PendingVotesChoice.WIRED) = Dependencies(
        database = database,
        definitions = TestDefinitions.loaded,
        apiKey = KEY,
        // join n'a pas de requestId : si l'idempotence servait, ce magasin le dirait.
        idempotency = Idempotency(database, NeverUsedApiRequestStore),
        economyFigures = TestEconomy(),
        companyBootstrap = TestBootstrap,
        sessionStore = sessions,
        clock = clock,
        voteStore = votes,
        voteService = service,
        pendingVotes = if (pending == PendingVotesChoice.WIRED) service else null,
    )

    private enum class PendingVotesChoice { WIRED, ABSENT }

    private suspend fun ApplicationTestBuilder.join(gamertag: String = "Kr"): JoinResponse {
        val response = client.post("/v1/session/join") {
            header(SERVER_KEY_HEADER, KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"xuid":"${kr.text}","gamertag":"$gamertag"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return SiloooJson.decodeFromString(JoinResponse.serializer(), response.bodyAsText())
    }

    @Test
    @DisplayName("join : un joueur dont le pseudo avait des votes en attente voit leurs Lingots dans la réponse, une fois ; un second join ne verse rien")
    fun joinCatchesUpWaitingVotes() {
        val app = Harness()
        val first = app.receive("Kr")
        val second = app.receive("kr")
        assertTrue(app.votes.rows.none { it.rewarded })
        testApplication {
            application { siloooModule(app.dependencies()) }

            val firstJoin = join()
            assertEquals(2 * rules.reward.lingotsPerVote, firstJoin.balances.lingots)
            assertTrue(app.votes.rows.all { it.rewarded && it.xuid == kr })
            assertEquals(setOf(first.voteId.toString(), second.voteId.toString()), app.votes.credits.map { it.refId }.toSet())
            assertEquals(setOf("vote"), app.votes.credits.map { it.reason }.toSet())
            assertEquals(listOf(GameEventKind.ANNONCE, GameEventKind.ANNONCE), app.votes.published.map { it.kind })
            assertEquals(listOf(kr, kr), app.votes.published.map { it.xuid })
            assertEquals(setOf("vote"), app.votes.published.map { it.payload.getValue("action").jsonPrimitive.content }.toSet())

            val secondJoin = join()
            assertEquals(2 * rules.reward.lingotsPerVote, secondJoin.balances.lingots)
            assertEquals(2, app.votes.credits.size)
            assertEquals(2, app.votes.published.size)
        }
    }

    @Test
    @DisplayName("join sans rattrapage branché (Dependencies.pendingVotes nul) : les votes attendent, rien n'est versé")
    fun joinWithoutCatchUpLeavesVotesWaiting() {
        val app = Harness()
        app.receive("Kr")
        testApplication {
            application { siloooModule(app.dependencies(PendingVotesChoice.ABSENT)) }

            assertEquals(0L, join().balances.lingots)
            assertFalse(app.votes.rows.single().rewarded)
            assertTrue(app.votes.credits.isEmpty())
        }
    }

    @Test
    @DisplayName("join d'un joueur sans vote en attente : le solde ne bouge pas, aucune annonce")
    fun joinWithNothingWaiting() {
        val app = Harness()
        app.receive("Quelqu'un d'autre")
        testApplication {
            application { siloooModule(app.dependencies()) }

            assertEquals(0L, join().balances.lingots)
            assertTrue(app.votes.credits.isEmpty())
            assertTrue(app.votes.published.isEmpty())
        }
    }

    private companion object {
        const val KEY = "cle-de-test"
    }
}
