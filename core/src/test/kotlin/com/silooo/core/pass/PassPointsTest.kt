package com.silooo.core.pass

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.Idempotency
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.db.InMemoryApiRequestStore
import com.silooo.core.db.RecordingConnection
import com.silooo.core.db.RecordingDatabase
import com.silooo.core.db.Xuid
import com.silooo.core.definitions.Definitions
import com.silooo.core.definitions.DefinitionsLoader
import com.silooo.core.definitions.MissionEvent
import com.silooo.core.economy.EngineFixture
import com.silooo.core.economy.InMemoryCompanyStore
import com.silooo.core.findings.InMemoryFindingStore
import com.silooo.core.mission.InMemoryMissionStore
import com.silooo.core.mission.MissionService
import com.silooo.core.orders.InMemoryOrderStore
import com.silooo.core.server.ServerPresence
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.InMemorySessionStore
import com.silooo.core.siloooModule
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
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les points que le pass compte : un prestige (chaque associé), la commande du jour qui se termine (chaque joueur qui a livré), une
 * mission terminée. Chaque test lance l'action du jeu elle-même (la route, le service) avec un vrai [PassService] sur un faux dépôt
 * ([InMemoryPassStore]) et lit les points. Les chiffres sortent de `pass.json` et des autres `definitions/`, jamais d'un littéral.
 * Hors saison, aucun point ne monte ; sans pass branché, rien ne change pour l'action (les autres tests du dépôt le montrent : ils n'en
 * branchent pas).
 */
class PassPointsTest {
    private val key = "cle-de-test"
    private val kr = EngineFixture.KR
    private val friend = EngineFixture.OTHER
    private val stranger = EngineFixture.STRANGER

    private val definitions = TestDefinitions.loaded
    private val pass = definitions.pass
    private val seasonId = pass.season.id

    private val inSeason: Instant = pass.season.startDay.atTime(12, 0).toInstant(ZoneOffset.UTC)
    private val afterSeason: Instant = pass.season.endDay.plusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC)
    private val beforeSeason: Instant = pass.season.startDay.minusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC)

    private fun requestId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    // --- les missions

    private class MissionBench(definitions: Definitions, at: Instant) {
        val sessions = InMemorySessionStore(at)
        val clock = MutableClock(at)
        val passStore = InMemoryPassStore(sessions)
        val service = MissionService(definitions, InMemoryMissionStore(sessions), PassService(definitions, passStore, sessions, clock))
        val connection = RecordingConnection().connection

        fun player(xuid: Xuid) {
            sessions.enterPlayer(connection, xuid, "Joueur")
        }
    }

    /** Les définitions du dépôt, avec une chaîne de missions écrite ici : une mission de trois encaissements, puis une de un. */
    private fun definitionsWithLongMission() = DefinitionsLoader.parse(
        economy = TestDefinitions.text(DefinitionsLoader.ECONOMY_FILE),
        factories = TestDefinitions.text(DefinitionsLoader.FACTORIES_FILE),
        shop = TestDefinitions.text(DefinitionsLoader.SHOP_FILE),
        world = TestDefinitions.text(DefinitionsLoader.WORLD_FILE),
        missions = """
            {"version": 1, "missions": [
              {"id": "premiere", "evenement": "collect", "cible": 3, "recompense": "lingotsHorsPrestige.missionBienvenue"},
              {"id": "seconde", "evenement": "collect", "cible": 1, "recompense": "lingotsHorsPrestige.missionBienvenue"}
            ]}
        """.trimIndent(),
        personnel = TestDefinitions.text(DefinitionsLoader.PERSONNEL_FILE),
        incidents = TestDefinitions.text(DefinitionsLoader.INCIDENTS_FILE),
        city = TestDefinitions.text(DefinitionsLoader.CITY_FILE),
        vote = TestDefinitions.text(DefinitionsLoader.VOTE_FILE),
        pass = TestDefinitions.text(DefinitionsLoader.PASS_FILE),
    )

    @Test
    @DisplayName("Une mission terminée rapporte parMission points ; l'avancement d'une mission qui n'est pas finie n'en rapporte pas ; chaque mission en rapporte une fois")
    fun aCompletedMissionGivesPoints() {
        val bench = MissionBench(definitionsWithLongMission(), inSeason)
        bench.player(kr)

        repeat(2) { bench.service.record(bench.connection, kr, MissionEvent.COLLECT) }
        assertEquals(0, bench.passStore.pointsOf(kr, seasonId), "la mission n'est pas finie")

        bench.service.record(bench.connection, kr, MissionEvent.COLLECT)
        assertEquals(pass.points.perMission, bench.passStore.pointsOf(kr, seasonId))

        bench.service.record(bench.connection, kr, MissionEvent.COLLECT)
        assertEquals(2 * pass.points.perMission, bench.passStore.pointsOf(kr, seasonId), "la seconde mission, terminée en un encaissement")

        bench.service.record(bench.connection, kr, MissionEvent.COLLECT)
        assertEquals(2 * pass.points.perMission, bench.passStore.pointsOf(kr, seasonId), "plus de mission : plus de point")
    }

    @Test
    @DisplayName("Les missions du dépôt (une mission d'un encaissement) rapportent leurs points et ne touchent que le joueur concerné")
    fun theRepositoryMissionsGivePoints() {
        val bench = MissionBench(definitions, inSeason)
        bench.player(kr)
        bench.player(friend)

        bench.service.record(bench.connection, kr, MissionEvent.COLLECT)

        assertEquals(pass.points.perMission, bench.passStore.pointsOf(kr, seasonId))
        assertEquals(0, bench.passStore.pointsOf(friend, seasonId))
        assertEquals(mapOf(kr to seasonId), bench.passStore.rows.keys.associate { it.first to it.second })
    }

    @Test
    @DisplayName("Une mission terminée avant ou après la saison ne rapporte aucun point, et ses Lingots sont versés comme d'habitude")
    fun noMissionPointsOutsideTheSeason() {
        listOf(beforeSeason, afterSeason).forEach { moment ->
            val bench = MissionBench(definitions, moment)
            bench.player(kr)

            bench.service.record(bench.connection, kr, MissionEvent.COLLECT)

            assertEquals(0, bench.passStore.pointsOf(kr, seasonId))
            assertTrue(bench.passStore.rows.isEmpty(), "aucune ligne de pass n'est créée hors saison")
            assertEquals(definitions.economy.lingotsOutsidePrestige.welcomeMission, bench.sessions.balances.getValue(kr).lingots)
        }
    }

    // --- les routes qui ont besoin d'une entreprise

    private fun settle(fixture: EngineFixture, at: Instant) {
        fixture.clock.now = at
        fixture.store.now = at
    }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String): HttpResponse =
        client.post(path) {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    // --- le prestige

    private fun withPrestigeApp(at: Instant, block: suspend ApplicationTestBuilder.(EngineFixture, InMemoryPassStore) -> Unit) = testApplication {
        val fixture = EngineFixture()
        settle(fixture, at)
        val database = RecordingDatabase(fixture.recording)
        val passStore = InMemoryPassStore(fixture.store)
        application {
            siloooModule(
                Dependencies(
                    database = database,
                    definitions = fixture.definitions,
                    apiKey = key,
                    economy = fixture.engine,
                    idempotency = Idempotency(database, InMemoryApiRequestStore(at)),
                    sessionStore = fixture.store,
                    economyFigures = fixture.figures,
                    companyBootstrap = fixture.bootstrap,
                    companyStore = InMemoryCompanyStore(fixture.store),
                    missionStore = InMemoryMissionStore(fixture.store),
                    findingStore = InMemoryFindingStore(fixture.store),
                    serverPresence = ServerPresence(clock = fixture.clock),
                    clock = fixture.clock,
                    pass = PassService(fixture.definitions, passStore, fixture.store, fixture.clock),
                ),
            )
        }
        block(fixture, passStore)
    }

    /** Une entreprise du PDG [kr] à la course 1, avec [earned] Donar gagnés (le seuil du prestige, par défaut). */
    private fun prestigeCompany(fixture: EngineFixture, earned: Double = fixture.engine.prestigeThreshold(1)): CompanyRecord {
        val base = fixture.newCompany(kr)
        return fixture.update(
            base.copy(
                courseNumber = 1,
                era = fixture.engine.eraAt(1),
                stars = fixture.engine.starsAt(1),
                donar = earned,
                donarEarned = earned,
            ),
        )
    }

    @Test
    @DisplayName("Un prestige rapporte parPrestige points à chaque associé de l'entreprise (même sans part de Lingots), et à personne d'autre")
    fun prestigeGivesPointsToEveryAssociate() = withPrestigeApp(inSeason) { fixture, passStore ->
        val company = prestigeCompany(fixture)
        fixture.addMember(company.id, friend, lingots = 0)
        fixture.store.enterPlayer(fixture.connection, stranger, "Étranger")

        val response = post("/v1/companies/${company.id}/prestige", """{"requestId":"${requestId(1)}","actor":"${kr.text}"}""")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(pass.points.perPrestige, passStore.pointsOf(kr, seasonId))
        assertEquals(pass.points.perPrestige, passStore.pointsOf(friend, seasonId))
        assertEquals(0, passStore.pointsOf(stranger, seasonId))
    }

    @Test
    @DisplayName("Un prestige refusé (seuil non atteint) ne rapporte aucun point")
    fun aRefusedPrestigeGivesNothing() = withPrestigeApp(inSeason) { fixture, passStore ->
        val company = prestigeCompany(fixture, earned = fixture.engine.prestigeThreshold(1) / 2)

        val response = post("/v1/companies/${company.id}/prestige", """{"requestId":"${requestId(1)}","actor":"${kr.text}"}""")

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertTrue(passStore.rows.isEmpty())
    }

    @Test
    @DisplayName("Un prestige rejoué avec le même requestId ne rapporte les points qu'une fois")
    fun aReplayedPrestigeGivesPointsOnce() = withPrestigeApp(inSeason) { fixture, passStore ->
        val company = prestigeCompany(fixture)
        val body = """{"requestId":"${requestId(1)}","actor":"${kr.text}"}"""

        assertEquals(HttpStatusCode.OK, post("/v1/companies/${company.id}/prestige", body).status)
        assertEquals(HttpStatusCode.OK, post("/v1/companies/${company.id}/prestige", body).status)

        assertEquals(pass.points.perPrestige, passStore.pointsOf(kr, seasonId))
    }

    @Test
    @DisplayName("Un prestige avant ou après la saison ne rapporte aucun point")
    fun noPrestigePointsOutsideTheSeason() {
        listOf(beforeSeason, afterSeason).forEach { moment ->
            withPrestigeApp(moment) { fixture, passStore ->
                val company = prestigeCompany(fixture)

                val response = post("/v1/companies/${company.id}/prestige", """{"requestId":"${requestId(1)}","actor":"${kr.text}"}""")

                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                assertTrue(passStore.rows.isEmpty(), "aucune ligne de pass hors saison")
            }
        }
    }

    // --- la commande du jour

    private fun withOrderApp(at: Instant, block: suspend ApplicationTestBuilder.(EngineFixture, InMemoryPassStore) -> Unit) = testApplication {
        val fixture = EngineFixture()
        settle(fixture, at)
        val database = RecordingDatabase(fixture.recording)
        val passStore = InMemoryPassStore(fixture.store)
        application {
            siloooModule(
                Dependencies(
                    database = database,
                    definitions = fixture.definitions,
                    apiKey = key,
                    economy = fixture.engine,
                    idempotency = Idempotency(database, InMemoryApiRequestStore(at)),
                    sessionStore = fixture.store,
                    economyFigures = fixture.figures,
                    companyBootstrap = fixture.bootstrap,
                    orderStore = InMemoryOrderStore(fixture.store),
                    clock = fixture.clock,
                    pass = PassService(fixture.definitions, passStore, fixture.store, fixture.clock),
                ),
            )
        }
        block(fixture, passStore)
    }

    private val target: Long get() = definitions.economy.dailyOrder.targetUnits

    /** Une entreprise du PDG [owner] avec de quoi payer dix fois la commande entière : la caisse ne limite pas le test. */
    private fun orderCompany(fixture: EngineFixture, owner: Xuid) {
        val created = fixture.newCompany(owner)
        val rate = fixture.figuresOf(created.id).ratePerHour
        val cost = target * definitions.economy.dailyOrder.minutesOfIncomePerUnit / 60.0 * rate
        fixture.update(created.copy(donar = 10 * cost))
    }

    private suspend fun ApplicationTestBuilder.deliver(quantity: Long, n: Int, actor: Xuid): HttpResponse =
        post("/v1/orders/today/deliveries", """{"requestId":"${requestId(n)}","actor":"${actor.text}","quantity":$quantity}""")

    @Test
    @DisplayName("La commande du jour qui se termine rapporte parCommande points à chaque joueur qui a livré, une fois, et rien à qui n'a pas livré ni avant la fin")
    fun orderCompletionGivesPointsToEveryContributor() = withOrderApp(inSeason) { fixture, passStore ->
        orderCompany(fixture, kr)
        orderCompany(fixture, friend)
        fixture.store.enterPlayer(fixture.connection, stranger, "Étranger")

        assertEquals(HttpStatusCode.OK, deliver(target / 2, n = 1, actor = kr).status)
        assertTrue(passStore.rows.isEmpty(), "la commande n'est pas finie : aucun point")

        val last = deliver(target, n = 2, actor = friend)
        assertEquals(HttpStatusCode.OK, last.status, last.bodyAsText())

        assertEquals(pass.points.perOrder, passStore.pointsOf(kr, seasonId))
        assertEquals(pass.points.perOrder, passStore.pointsOf(friend, seasonId))
        assertEquals(0, passStore.pointsOf(stranger, seasonId))

        assertEquals(HttpStatusCode.OK, deliver(1, n = 3, actor = kr).status, "la commande est finie : la livraison suivante ne prend rien")
        assertEquals(pass.points.perOrder, passStore.pointsOf(kr, seasonId), "les points ne se versent qu'une fois par commande")
    }

    @Test
    @DisplayName("La commande du jour qui se termine avant ou après la saison ne rapporte aucun point")
    fun noOrderPointsOutsideTheSeason() {
        listOf(beforeSeason, afterSeason).forEach { moment ->
            withOrderApp(moment) { fixture, passStore ->
                orderCompany(fixture, kr)

                assertEquals(HttpStatusCode.OK, deliver(target, n = 1, actor = kr).status)

                assertTrue(passStore.rows.isEmpty(), "aucune ligne de pass hors saison")
            }
        }
    }
}
