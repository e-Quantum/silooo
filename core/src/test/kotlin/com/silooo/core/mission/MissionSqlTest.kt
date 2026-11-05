package com.silooo.core.mission

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.ErrorBody
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.Idempotency
import com.silooo.core.api.MissionState
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.RefusedBody
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.MariaDbSupport
import com.silooo.core.db.Xuid
import com.silooo.core.economy.DefinitionsBootstrap
import com.silooo.core.economy.EconomyEngine
import com.silooo.core.economy.EngineFigures
import com.silooo.core.economy.EngineFixture.Companion.KR
import com.silooo.core.economy.EngineFixture.Companion.OTHER
import com.silooo.core.economy.EngineFixture.Companion.START
import com.silooo.core.economy.EngineFixture.Companion.hours
import com.silooo.core.events.GameEventKind
import com.silooo.core.server.ServerPresence
import com.silooo.core.session.JdbcSessionStore
import com.silooo.core.session.SessionService
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
import java.sql.Connection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les missions sur la vraie MariaDB, par HTTP, avec le vrai moteur, le vrai dépôt SQL et la vraie idempotence : ce que
 * les tests de route et `MissionServiceTest` ne peuvent pas voir, c'est-à-dire ce qui arrive vraiment dans
 * `player_missions`, `player_balances`, `currency_ledger` et `game_events`, dans une seule transaction avec le `collect`
 * qui les cause. Ignoré sans Docker (voir [MariaDbSupport]) : la CI les rejoue.
 *
 * Les chiffres attendus (cible, récompense) sortent de `definitions/`, jamais d'un littéral recopié.
 */
class MissionSqlTest {
    private val database get() = MariaDbSupport.database
    private val definitions = TestDefinitions.loaded
    private val engine = EconomyEngine(definitions)
    private val store = JdbcSessionStore()
    private val clock = MutableClock(START)
    private val presence = ServerPresence(clock = clock)
    private val key = "cle-de-test"

    private val first = definitions.missions.missions.first()
    private val reward = definitions.missionRewards.getValue(first.id)

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    // --- le service et l'entreprise de départ

    private fun dependencies(missionStore: MissionStore): Dependencies = Dependencies(
        database = database,
        definitions = definitions,
        apiKey = key,
        economy = engine,
        idempotency = Idempotency(database),
        sessionStore = store,
        economyFigures = EngineFigures(engine, store, clock),
        companyBootstrap = DefinitionsBootstrap(engine, definitions),
        missionStore = missionStore,
        serverPresence = presence,
    )

    /** Crée l'entreprise de [KR] par le vrai `join`, puis monte le service sur la base. */
    private fun withCompany(missionStore: MissionStore = JdbcMissionStore(), block: suspend ApplicationTestBuilder.(Long) -> Unit) {
        val joined = runBlocking {
            SessionService(database, store, EngineFigures(engine, store, clock), DefinitionsBootstrap(engine, definitions)).join(KR, "Kr")
        }
        testApplication {
            application { siloooModule(dependencies(missionStore)) }
            block(joined.companyId)
        }
    }

    /** Un dépôt qui écrit comme le vrai, sauf la publication d'événement : la dernière écriture de la mission échoue. */
    private class FailingPublish(private val real: MissionStore = JdbcMissionStore()) : MissionStore by real {
        override fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long =
            throw IllegalStateException("publication impossible (test)")
    }

    // --- HTTP

    private fun requestId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    private suspend fun ApplicationTestBuilder.get(path: String): HttpResponse = client.get(path) { header(SERVER_KEY_HEADER, key) }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String): HttpResponse =
        client.post(path) {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.collect(companyId: Long, n: Int = 1, actor: Xuid = KR) =
        post("/v1/companies/$companyId/collect", """{"requestId":"${requestId(n)}","actor":"${actor.text}"}""")

    private suspend fun ApplicationTestBuilder.missions(xuid: String): HttpResponse = get("/v1/players/$xuid/missions")

    private suspend fun ApplicationTestBuilder.stateOf(xuid: Xuid): MissionState {
        val response = missions(xuid.text)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return SiloooJson.decodeFromString(MissionState.serializer(), response.bodyAsText())
    }

    /** Un associé de l'entreprise [companyId] : son premier `join` lui crée une entreprise, qu'on remplace par celle-ci. */
    private suspend fun ApplicationTestBuilder.addPartner(companyId: Long, partner: Xuid) {
        val response = post("/v1/session/join", """{"xuid":"${partner.text}","gamertag":"Associe"}""")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        MariaDbSupport.update("UPDATE company_members SET company_id = ?, role = 'ASSOCIE' WHERE xuid = ?", companyId, partner.text)
    }

    /** Remet l'horloge de production à maintenant puis la laisse courir [away] heures : de quoi gagner du Donar. */
    private fun earnSomething(companyId: Long, away: Double = 1.0) {
        MariaDbSupport.update(
            "UPDATE companies SET last_collect_at = ? WHERE id = ?",
            java.time.LocalDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC),
            companyId,
        )
        clock.advance(hours(away))
    }

    // --- lectures en base

    private fun missionRows(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(CAST(xuid AS CHAR), '|', mission_id, '|', progress, '|', completed_at IS NOT NULL) FROM player_missions ORDER BY xuid, mission_id",
    )

    private fun lingotsOf(xuid: Xuid) = MariaDbSupport.long("SELECT lingots FROM player_balances WHERE xuid = ?", xuid.text)

    private fun ledger(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(CAST(xuid AS CHAR), '|', currency, '|', delta, '|', balance, '|', reason, '|', ref_id) FROM currency_ledger ORDER BY id",
    )

    /** Les événements dans l'ordre : `xuid|type|action`. */
    private fun events(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(IFNULL(CAST(xuid AS CHAR), '-'), '|', kind, '|', JSON_VALUE(payload, '\$.action')) FROM game_events ORDER BY id",
    )

    private fun missionEvents(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(JSON_VALUE(payload, '\$.missionId'), '|', JSON_VALUE(payload, '\$.rewardLingots')) FROM game_events " +
            "WHERE JSON_VALUE(payload, '\$.action') = 'mission' ORDER BY id",
    )

    private fun donar(companyId: Long): Double = MariaDbSupport.raw { connection ->
        connection.prepareStatement("SELECT donar FROM companies WHERE id = ?").use { statement ->
            statement.setLong(1, companyId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getDouble(1)
            }
        }
    }

    private fun version(companyId: Long) = MariaDbSupport.long("SELECT version FROM companies WHERE id = ?", companyId)

    // --- getMissions

    @Test
    @DisplayName("getMissions : un joueur qui vient de rejoindre est à la première mission, à zéro ; lire n'écrit aucune ligne")
    fun freshPlayerIsOnTheFirstMission() = withCompany {
        val state = stateOf(KR)

        assertEquals(first.id, state.current?.id)
        assertEquals(0, state.current?.progress)
        assertEquals(first.target, state.current?.target)
        assertEquals(reward, state.current?.rewardLingots)
        assertEquals(emptyList<String>(), state.completed)
        assertEquals(0L, MariaDbSupport.count("player_missions"))
    }

    @Test
    @DisplayName("getMissions : un XUID inconnu du service, mal formé ou trop long donne 400 REQUETE_INVALIDE")
    fun unknownOrMalformedPlayerIsRefused() = withCompany {
        for (xuid in listOf(OTHER.text, "abc", "-1", "123456789012345678901")) {
            val response = missions(xuid)
            assertEquals(HttpStatusCode.BadRequest, response.status, xuid)
            assertEquals(ErrorCodes.BAD_REQUEST, SiloooJson.decodeFromString(ErrorBody.serializer(), response.bodyAsText()).reason)
        }
    }

    // --- collect

    @Test
    @DisplayName("collect : un encaissement qui rapporte termine la mission : la ligne, le solde, le journal et l'événement sont écrits ensemble")
    fun collectCompletesTheMission() = withCompany { id ->
        earnSomething(id)

        assertEquals(HttpStatusCode.OK, collect(id).status)

        assertEquals(listOf("${KR.text}|${first.id}|${first.target}|1"), missionRows())
        assertEquals(reward, lingotsOf(KR))
        assertEquals(listOf("${KR.text}|LINGOTS|$reward|$reward|mission|${first.id}"), ledger())
        assertEquals(listOf("${KR.text}|ANNONCE|collect", "${KR.text}|ANNONCE|mission"), events())
        assertEquals(listOf("${first.id}|$reward"), missionEvents())

        val state = stateOf(KR)
        assertEquals(listOf(first.id), state.completed.take(1))
        assertTrue(state.current == null || state.current!!.id != first.id)
    }

    @Test
    @DisplayName("collect rejoué, puis encaissé de nouveau : une mission terminée ne verse rien de plus et ne publie rien de plus")
    fun completedMissionIsNeverReplayed() = withCompany { id ->
        earnSomething(id)
        val firstAnswer = collect(id, n = 1).bodyAsText()

        assertEquals(firstAnswer, collect(id, n = 1).bodyAsText(), "le même requestId rend la réponse gardée")
        earnSomething(id)
        assertEquals(HttpStatusCode.OK, collect(id, n = 2).status)

        assertEquals(1, ledger().count { it!!.endsWith("|mission|${first.id}") }, "un seul versement pour cette mission")
        assertEquals(1, missionEvents().count { it!!.startsWith("${first.id}|") }, "un seul événement pour cette mission")
        assertEquals(1, missionRows().count { it!!.contains("|${first.id}|") })
        assertTrue(lingotsOf(KR) >= reward)
    }

    @Test
    @DisplayName("collect : rien n'est dû, ou rien n'est gagné : aucune ligne de mission, aucun Lingot")
    fun noGainNoProgress() = withCompany { id ->
        MariaDbSupport.update("UPDATE company_factories SET state = 'EN_RUINE', level = 0 WHERE company_id = ?", id)
        earnSomething(id, away = 3.0)

        assertEquals(HttpStatusCode.OK, collect(id).status)

        assertEquals(emptyList<String?>(), missionRows())
        assertEquals(0L, lingotsOf(KR))
        assertEquals(emptyList<String?>(), ledger())
        assertEquals(listOf("${KR.text}|ANNONCE|collect"), events(), "le collect à zéro est écrit et annoncé comme avant")
    }

    @Test
    @DisplayName("collect : si la dernière écriture de la mission échoue, tout est annulé (Donar, version, mission, Lingots, journal, événements)")
    fun missionRollsBackWithTheCollect() = withCompany(FailingPublish()) { id ->
        earnSomething(id)
        val donarBefore = donar(id)

        val response = collect(id)

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(donarBefore, donar(id))
        assertEquals(0L, version(id))
        assertEquals(emptyList<String?>(), missionRows())
        assertEquals(0L, lingotsOf(KR))
        assertEquals(emptyList<String?>(), ledger())
        assertEquals(emptyList<String?>(), events())
    }

    @Test
    @DisplayName("Des collect simultanés ne terminent la mission qu'une fois : un seul versement, un seul événement, les perdants reçoivent CONFLIT_REESSAYER")
    fun parallelCollectsCompleteOnce() = withCompany { id ->
        earnSomething(id)
        val parallel = 4

        val responses = coroutineScope {
            val go = CompletableDeferred<Unit>()
            val jobs = (1..parallel).map { n -> async(Dispatchers.IO) { go.await(); collect(id, n = n) } }
            go.complete(Unit)
            jobs.awaitAll()
        }

        assertTrue(responses.any { it.status == HttpStatusCode.OK }, "au moins un encaissement passe")
        for (response in responses.filter { it.status != HttpStatusCode.OK }) {
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(RefusalCodes.CONFLICT_RETRY, SiloooJson.decodeFromString(RefusedBody.serializer(), response.bodyAsText()).reason)
        }
        assertEquals(1, missionRows().count { it!!.contains("|${first.id}|") })
        assertEquals("${KR.text}|${first.id}|${first.target}|1", missionRows().first { it!!.contains("|${first.id}|") })
        assertEquals(1, ledger().count { it!!.endsWith("|mission|${first.id}") })
        assertEquals(1, missionEvents().count { it!!.startsWith("${first.id}|") })
    }

    @Test
    @DisplayName("Deux associés : chacun a sa mission et reçoit sa récompense quand c'est lui qui encaisse")
    fun eachMemberHasHisOwnMission() = withCompany { id ->
        addPartner(id, OTHER)
        earnSomething(id)

        assertEquals(HttpStatusCode.OK, collect(id, n = 1, actor = OTHER).status)

        assertEquals(listOf("${OTHER.text}|${first.id}|${first.target}|1"), missionRows())
        assertEquals(reward, lingotsOf(OTHER))
        assertEquals(0L, lingotsOf(KR), "le PDG n'a rien reçu")
        assertEquals(listOf(first.id), stateOf(OTHER).completed)
        assertEquals(first.id, stateOf(KR).current?.id, "la mission du PDG n'a pas bougé")
        assertEquals(0, stateOf(KR).current?.progress)

        earnSomething(id)
        assertEquals(HttpStatusCode.OK, collect(id, n = 2, actor = KR).status)
        assertEquals(listOf(OTHER.text, KR.text).sorted(), missionRows().map { it!!.substringBefore('|') }.sorted())
        assertEquals(reward, lingotsOf(KR))
    }
}
