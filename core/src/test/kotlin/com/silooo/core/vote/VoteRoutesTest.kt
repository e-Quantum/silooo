package com.silooo.core.vote

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.ErrorBody
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.api.VotesToday
import com.silooo.core.db.RecordingConnection
import com.silooo.core.db.RecordingDatabase
import com.silooo.core.db.Xuid
import com.silooo.core.economy.EngineFixture
import com.silooo.core.session.InMemorySessionStore
import com.silooo.core.siloooModule
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `todayVotes` avec des faux (pas de base) : un faux dépôt des votes ([InMemoryVoteStore]), une horloge que le test avance, et les
 * `definitions/` du dépôt. Les chiffres attendus viennent de `vote.json`, jamais d'un littéral. Le SQL est dans `VoteSqlTest`, le
 * contrat dans `ContractTest`.
 */
class VoteRoutesTest {
    private val key = "cle-de-test"
    private val rules = TestDefinitions.loaded.vote
    private val kr = EngineFixture.KR
    private val other = EngineFixture.OTHER

    private class Harness(
        val sessions: InMemorySessionStore,
        val store: InMemoryVoteStore,
        val service: VoteService,
        val clock: MutableClock,
        val recording: RecordingConnection,
    ) {
        val connection get() = recording.connection

        fun join(xuid: Xuid, gamertag: String) {
            sessions.enterPlayer(connection, xuid, gamertag)
        }

        fun vote(gamertag: String) = service.receiveIn(connection, IncomingVote(gamertag, "Hubyy"))
    }

    private fun withApp(block: suspend ApplicationTestBuilder.(Harness) -> Unit) = testApplication {
        val start = Instant.parse("2026-11-05T12:00:00Z")
        val clock = MutableClock(start)
        val sessions = InMemorySessionStore(start)
        val store = InMemoryVoteStore(sessions)
        val definitions = TestDefinitions.loaded
        val service = VoteService(definitions, store, clock) { emptySet() }
        val recording = RecordingConnection()
        val database = RecordingDatabase(recording)
        application {
            siloooModule(
                Dependencies(
                    database = database,
                    definitions = definitions,
                    apiKey = key,
                    sessionStore = sessions,
                    clock = clock,
                    voteStore = store,
                    voteService = service,
                ),
            )
        }
        block(Harness(sessions, store, service, clock, recording))
    }

    private suspend fun ApplicationTestBuilder.get(path: String): HttpResponse = client.get(path) { header(SERVER_KEY_HEADER, key) }

    private suspend fun ApplicationTestBuilder.today(viewer: String? = null): HttpResponse =
        get(if (viewer == null) "/v1/votes/today" else "/v1/votes/today?xuid=$viewer")

    private suspend fun votesOf(response: HttpResponse): VotesToday {
        assertEquals(HttpStatusCode.OK, response.status, "corps : " + response.bodyAsText())
        return SiloooJson.decodeFromString(VotesToday.serializer(), response.bodyAsText())
    }

    private suspend fun assertBadRequest(response: HttpResponse) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCodes.BAD_REQUEST, SiloooJson.decodeFromString(ErrorBody.serializer(), response.bodyAsText()).reason)
    }

    @Test
    @DisplayName("todayVotes sans vote : les chiffres de vote.json, zéro vote, objectif non atteint, la part du joueur à zéro, rien d'écrit")
    fun todayWithoutVotes() = withApp { app ->
        val votes = votesOf(today())

        assertEquals("2026-11-05", votes.day)
        assertEquals(0, votes.votes)
        assertEquals(rules.party.votesPerDay, votes.goal)
        assertFalse(votes.partyReached)
        assertEquals(rules.reward.lingotsPerVote, votes.rewardLingots)
        assertEquals(rules.party.lingotsPerPlayer, votes.partyRewardLingots)
        assertEquals(rules.streak.days, votes.streakGoalDays)
        assertEquals(rules.streak.bonusLingots, votes.streakBonusLingots)
        assertEquals(0, votes.mine.votes)
        assertEquals(0, votes.mine.streak)
        assertTrue(app.store.rows.isEmpty())
        assertEquals(0, app.recording.statements.size)
    }

    @Test
    @DisplayName("todayVotes sans xuid donne la part du joueur à zéro, même quand des votes ont eu lieu")
    fun withoutXuidMineIsZero() = withApp { app ->
        app.join(kr, "Kr")
        app.vote("Kr")
        app.vote("Inconnu")

        val votes = votesOf(today())

        assertEquals(2, votes.votes)
        assertEquals(0, votes.mine.votes)
        assertEquals(0, votes.mine.streak)
    }

    @Test
    @DisplayName("todayVotes avec xuid : les votes du joueur aujourd'hui et sa série ; les autres joueurs ne comptent que dans le total")
    fun withXuidMineCountsThePlayer() = withApp { app ->
        app.join(kr, "Kr")
        app.join(other, "Autre")
        app.vote("Kr")
        app.clock.advance(Duration.ofDays(1))
        app.vote("Kr")
        app.vote("Kr")
        app.vote("Autre")

        val mine = votesOf(today(kr.text))
        val theirs = votesOf(today(other.text))
        val nobody = votesOf(today(EngineFixture.STRANGER.text))

        assertEquals("2026-11-06", mine.day)
        assertEquals(3, mine.votes)
        assertEquals(2, mine.mine.votes)
        assertEquals(2, mine.mine.streak)
        assertEquals(1, theirs.mine.votes)
        assertEquals(1, theirs.mine.streak)
        assertEquals(3, theirs.votes)
        assertEquals(0, nobody.mine.votes, "un xuid bien formé qui n'a jamais voté")
        assertEquals(0, nobody.mine.streak)
    }

    @Test
    @DisplayName("todayVotes dit que la Vote party est atteinte quand le total du jour atteint l'objectif, et reste vrai ensuite")
    fun partyReachedFlag() = withApp { app ->
        repeat(rules.party.votesPerDay - 1) { app.vote("Inconnu$it") }
        assertFalse(votesOf(today()).partyReached)

        app.vote("Dernier")
        val atGoal = votesOf(today())
        app.vote("Encore")
        val after = votesOf(today())

        assertTrue(atGoal.partyReached)
        assertEquals(rules.party.votesPerDay, atGoal.votes)
        assertTrue(after.partyReached)
        assertEquals(rules.party.votesPerDay + 1, after.votes)
    }

    @Test
    @DisplayName("todayVotes change de jour à minuit UTC : le décompte repart de zéro et la série d'hier reste en vie")
    fun newDayAtUtcMidnight() = withApp { app ->
        app.join(kr, "Kr")
        app.clock.now = Instant.parse("2026-11-05T23:59:59Z")
        app.vote("Kr")
        assertEquals(1, votesOf(today(kr.text)).votes)

        app.clock.now = Instant.parse("2026-11-06T00:00:00Z")
        val next = votesOf(today(kr.text))

        assertEquals("2026-11-06", next.day)
        assertEquals(0, next.votes)
        assertEquals(0, next.mine.votes)
        assertEquals(1, next.mine.streak, "voté hier : la série vit jusqu'à la fin d'aujourd'hui")
    }

    @Test
    @DisplayName("todayVotes : un xuid mal formé donne 400 REQUETE_INVALIDE")
    fun badXuidIsRefused() = withApp { app ->
        assertBadRequest(get("/v1/votes/today?xuid=pas-un-xuid"))
        assertBadRequest(get("/v1/votes/today?xuid="))
        assertBadRequest(get("/v1/votes/today?xuid=-12"))
        assertTrue(app.store.rows.isEmpty())
    }

    @Test
    @DisplayName("todayVotes demande la clé du serveur : sans elle, 401")
    fun theServerKeyIsRequired() = withApp {
        val response = client.get("/v1/votes/today")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    @DisplayName("todayVotes est en lecture seule : lire plusieurs fois ne crée ni vote, ni Lingots, ni événement")
    fun readingWritesNothing() = withApp { app ->
        app.join(kr, "Kr")
        app.vote("Kr")
        val rows = app.store.rows.toList()
        val credits = app.store.credits.toList()
        val events = app.store.published.size

        repeat(3) {
            votesOf(today())
            votesOf(today(kr.text))
        }

        assertEquals(rows, app.store.rows.toList())
        assertEquals(credits, app.store.credits.toList())
        assertEquals(events, app.store.published.size)
        assertEquals(0, app.recording.statements.size)
    }
}
