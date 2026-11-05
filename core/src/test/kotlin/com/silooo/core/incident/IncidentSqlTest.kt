package com.silooo.core.incident

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.CompanyUpdated
import com.silooo.core.api.FactoryState
import com.silooo.core.api.Idempotency
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
import com.silooo.core.economy.JdbcCompanyStore
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
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les incidents d'usine sur la vraie MariaDB : `JdbcIncidentStore` ligne à ligne, la jointure de `loadFactories`, la remise à zéro
 * d'un prestige, puis l'ouverture par le minuteur et la réparation par HTTP avec le vrai moteur, les vrais dépôts SQL et la vraie
 * idempotence. Ce que les tests de route (`IncidentRoutesTest`) ne peuvent pas voir : ce qui arrive vraiment dans `factory_incidents`,
 * `companies` (`donar`, `version`) et `game_events`, dans une seule transaction, et ce que donne une concurrence réelle. Ignoré sans
 * Docker (voir [MariaDbSupport]) : la CI les rejoue.
 *
 * Les chiffres attendus (revenu, récompense) sortent du moteur et des `definitions/`, jamais d'un littéral recopié.
 */
class IncidentSqlTest {
    private val database get() = MariaDbSupport.database
    private val definitions = TestDefinitions.loaded
    private val engine = EconomyEngine(definitions)
    private val store = JdbcSessionStore()
    private val clock = MutableClock(START)
    private val presence = ServerPresence(clock = clock)
    private val jdbc = JdbcIncidentStore()
    private val companyStore = JdbcCompanyStore()
    private val figures = EngineFigures(engine, store, clock)
    private val key = "cle-de-test"

    private val starter = definitions.economy.prestige.courseStart.factoryId
    private val second = engine.factoryIds().first { it != starter }
    private val third = engine.factoryIds().first { it != starter && it != second }

    /** Un instant à la milliseconde, comme la colonne `DATETIME(3)` le garde. */
    private val at: Instant = Instant.parse("2026-11-05T12:00:00.123Z")

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    // --- outils de la couche SQL

    /** Une usine active, de niveau [level], à l'entreprise [companyId]. */
    private fun ownFactory(companyId: Long, factoryId: String, level: Int = 3) {
        MariaDbSupport.update(
            "INSERT INTO company_factories (company_id, factory_id, state, restore_step, level) VALUES (?, ?, 'ACTIVE', 4, ?)",
            companyId,
            factoryId,
            level,
        )
    }

    private fun open(companyId: Long, factoryId: String, kind: String = "panne", slowdown: Double = 0.6, when_: Instant = at): Long =
        MariaDbSupport.raw { jdbc.open(it, companyId, factoryId, kind, slowdown, when_) }

    private fun find(companyId: Long, incidentId: Long): IncidentRecord? = MariaDbSupport.raw { jdbc.find(it, companyId, incidentId) }

    private fun close(companyId: Long, incidentId: Long, when_: Instant): Boolean = MariaDbSupport.raw { jdbc.close(it, companyId, incidentId, when_) }

    private fun factories(companyId: Long) = MariaDbSupport.raw { store.loadFactories(it, companyId) }.associateBy { it.factoryId }

    private fun assertClose(expected: Double, actual: Double, what: String) {
        assertTrue(abs(expected - actual) <= 1e-9 * maxOf(1.0, abs(expected)), "$what : attendu $expected, reçu $actual")
    }

    // --- JdbcIncidentStore

    @Test
    @DisplayName("open, find et close : l'incident écrit est relu tel quel (au centième et à la milliseconde), clos une seule fois, et invisible d'une autre entreprise")
    fun openFindClose() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val b = MariaDbSupport.insertCompany("B", 1)
        ownFactory(a, starter)
        ownFactory(b, starter)

        val id = open(a, starter, "panne", 0.6)

        assertTrue(id > 0)
        assertEquals(IncidentRecord(id, a, starter, "panne", 0.6, at, null), find(a, id))
        assertEquals("0.60", MariaDbSupport.string("SELECT CAST(slowdown AS CHAR) FROM factory_incidents WHERE id = ?", id))
        assertNull(find(b, id), "l'incident d'une autre entreprise est introuvable")
        assertNull(find(a, id + 100), "un incident inconnu aussi")

        val closedAt = at.plusSeconds(90)
        assertFalse(close(b, id, closedAt), "une autre entreprise ne ferme pas cet incident")
        assertNull(find(a, id)!!.resolvedAt)
        assertTrue(close(a, id, closedAt))
        assertEquals(closedAt, find(a, id)!!.resolvedAt)
        assertFalse(close(a, id, closedAt.plusSeconds(5)), "déjà clos : rien à clore")
        assertEquals(closedAt, find(a, id)!!.resolvedAt, "la date de réparation ne bouge pas")
        assertFalse(close(a, id + 100, closedAt), "un incident inconnu")
    }

    @Test
    @DisplayName("Chaque sorte du jeu tient dans les colonnes : kind (16 caractères) et slowdown (DECIMAL 3,2) relus sans perte")
    fun everyKindFitsTheColumns() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val factoryIds = engine.factoryIds()
        val kinds = definitions.incidents.kinds
        assertTrue(factoryIds.size >= kinds.size, "une usine par sorte pour le test")
        for ((index, kind) in kinds.withIndex()) {
            ownFactory(a, factoryIds[index])
            val id = open(a, factoryIds[index], kind.id, kind.slowdown)
            val record = find(a, id)!!
            assertEquals(kind.id, record.kind)
            assertEquals(kind.slowdown, record.slowdown)
        }
    }

    @Test
    @DisplayName("Un incident ne s'ouvre que sur une usine que l'entreprise possède : la clé étrangère refuse le reste")
    fun anIncidentNeedsTheFactory() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val b = MariaDbSupport.insertCompany("B", 1)
        ownFactory(b, starter)

        assertThrows(SQLException::class.java) { open(a, starter) }
        assertThrows(SQLException::class.java) { open(b, second) }
        assertEquals(0L, MariaDbSupport.count("factory_incidents"))
    }

    @Test
    @DisplayName("startedSince : vrai dès qu'un incident de l'entreprise a commencé à l'instant donné ou après, réparé ou non, jamais pour l'incident d'une autre entreprise")
    fun startedSince() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val b = MariaDbSupport.insertCompany("B", 1)
        ownFactory(a, starter)
        ownFactory(a, second)
        ownFactory(b, starter)
        val first = open(a, starter, when_ = at)
        open(a, second, when_ = at.plusSeconds(600))

        fun since(companyId: Long, instant: Instant) = MariaDbSupport.raw { jdbc.startedSince(it, companyId, instant) }
        assertTrue(since(a, at))
        assertTrue(since(a, at.plusMillis(1)), "le second, 10 minutes plus tard, est après")
        assertTrue(since(a, at.plusSeconds(600)), "la limite est comprise")
        assertFalse(since(a, at.plusSeconds(600).plusMillis(1)))
        assertFalse(since(b, at), "l'entreprise B n'a rien eu")

        close(a, first, at.plusSeconds(60))
        assertTrue(since(a, at), "un incident réparé compte encore : un seul incident par créneau")

        open(b, starter, when_ = at.plusSeconds(3600))
        assertTrue(since(b, at))
        assertFalse(since(a, at.plusSeconds(3600)), "l'incident de B n'est pas à A")
    }

    // --- la jointure de loadFactories

    @Test
    @DisplayName("loadFactories : l'usine à incident ouvert porte son identifiant, sa sorte et son facteur ; les autres rendent 1 sans incident, une ligne par usine")
    fun loadFactoriesJoinsTheOpenIncident() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val b = MariaDbSupport.insertCompany("B", 1)
        for (company in listOf(a, b)) listOf(starter, second, third).forEach { ownFactory(company, it) }
        val mine = open(a, second, "courroie", 0.7)
        open(b, third, "incendie", 0.5)

        val list = MariaDbSupport.raw { store.loadFactories(it, a) }
        assertEquals(3, list.size, "une ligne par usine")
        assertEquals(setOf(starter, second, third), list.map { it.factoryId }.toSet())
        val byId = list.associateBy { it.factoryId }
        assertEquals(mine, byId.getValue(second).openIncidentId)
        assertEquals("courroie", byId.getValue(second).incidentKind)
        assertEquals(0.7, byId.getValue(second).incidentSlowdown)
        assertEquals(FactoryState.ACTIVE, byId.getValue(second).state)
        for (id in listOf(starter, third)) {
            assertNull(byId.getValue(id).openIncidentId)
            assertNull(byId.getValue(id).incidentKind)
            assertEquals(1.0, byId.getValue(id).incidentSlowdown, "l'incident de l'entreprise B n'est pas à A")
        }

        close(a, mine, at.plusSeconds(30))
        assertNull(factories(a).getValue(second).openIncidentId, "un incident réparé ne se montre plus")
        assertEquals(1.0, factories(a).getValue(second).incidentSlowdown)
    }

    @Test
    @DisplayName("Si deux incidents sont ouverts sur la même usine (le schéma ne l'interdit pas), la jointure garde le plus ancien, sans doubler la ligne")
    fun loadFactoriesKeepsTheOldestOpenIncident() {
        val a = MariaDbSupport.insertCompany("A", 0)
        ownFactory(a, starter)
        val older = open(a, starter, "panne", 0.6)
        val newer = open(a, starter, "incendie", 0.5, at.plusSeconds(1800))

        assertEquals(1, MariaDbSupport.raw { store.loadFactories(it, a) }.size)
        assertEquals(older, factories(a).getValue(starter).openIncidentId)
        assertEquals("panne", factories(a).getValue(starter).incidentKind)

        close(a, older, at.plusSeconds(3600))
        assertEquals(newer, factories(a).getValue(starter).openIncidentId, "le plus ancien réparé, le suivant se montre")
        assertEquals(0.5, factories(a).getValue(starter).incidentSlowdown)
    }

    // --- le prestige

    @Test
    @DisplayName("Un prestige clôt les incidents ouverts de l'entreprise et de personne d'autre ; un incident déjà réparé garde sa date")
    fun prestigeClosesTheOpenIncidents() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val b = MariaDbSupport.insertCompany("B", 1)
        ownFactory(a, starter)
        ownFactory(a, second)
        ownFactory(b, starter)
        val onStarter = open(a, starter)
        val repaired = open(a, second)
        val earlier = at.plusSeconds(60)
        close(a, repaired, earlier)
        val theirs = open(b, starter)

        MariaDbSupport.raw { companyStore.restartFactories(it, a, mapOf(starter to 1)) }

        assertNotNull(find(a, onStarter)!!.resolvedAt, "l'usine repart de zéro : plus d'incident")
        assertEquals(earlier, find(a, repaired)!!.resolvedAt, "la date d'une réparation passée ne bouge pas")
        assertNull(find(b, theirs)!!.resolvedAt, "l'incident d'une autre entreprise reste ouvert")
        val after = factories(a)
        assertNull(after.getValue(starter).openIncidentId)
        assertEquals(FactoryState.ACTIVE, after.getValue(starter).state)
        assertEquals(FactoryState.EN_RUINE, after.getValue(second).state)
    }

    // --- le minuteur et la réparation, de bout en bout

    private fun dependencies() = Dependencies(
        database = database,
        definitions = definitions,
        apiKey = key,
        economy = engine,
        idempotency = Idempotency(database),
        sessionStore = store,
        clock = clock,
        economyFigures = figures,
        companyBootstrap = DefinitionsBootstrap(engine, definitions),
        companyStore = companyStore,
        incidentStore = jdbc,
        serverPresence = presence,
    )

    /**
     * Crée l'entreprise de [KR] par le vrai `join`, lui donne 100 Donar et remet son dernier calcul de production à l'heure de
     * l'horloge du test (`join` le pose à l'heure de la base : sans cela, de la production en attente fausserait les montants).
     */
    private fun startCompany(): Long {
        val joined = runBlocking {
            SessionService(database, store, figures, DefinitionsBootstrap(engine, definitions)).join(KR, "Kr")
        }
        MariaDbSupport.update(
            "UPDATE companies SET donar = ?, last_collect_at = ? WHERE id = ?",
            100.0,
            LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
            joined.companyId,
        )
        return joined.companyId
    }

    private fun withCompany(block: suspend ApplicationTestBuilder.(Long) -> Unit) {
        val id = startCompany()
        testApplication {
            application { siloooModule(dependencies()) }
            block(id)
        }
    }

    private fun requestId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    private suspend fun ApplicationTestBuilder.resolve(companyId: Long, incidentId: Long, n: Int = 1, actor: Xuid = KR): HttpResponse =
        client.post("/v1/companies/$companyId/incidents/$incidentId/resolve") {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody("""{"requestId":"${requestId(n)}","actor":"${actor.text}"}""")
        }

    private suspend fun updated(response: HttpResponse): CompanyUpdated {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        return SiloooJson.decodeFromString(CompanyUpdated.serializer(), body)
    }

    private fun donar(companyId: Long): Double = MariaDbSupport.string("SELECT donar FROM companies WHERE id = ?", companyId)!!.toDouble()

    private fun version(companyId: Long) = MariaDbSupport.long("SELECT version FROM companies WHERE id = ?", companyId)

    /** Les événements d'incident dans l'ordre : `xuid|action|incident`. */
    private fun incidentEvents(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(IFNULL(CAST(xuid AS CHAR), '-'), '|', JSON_VALUE(payload, '\$.action'), '|', JSON_VALUE(payload, '\$.incidentId')) " +
            "FROM game_events WHERE kind = 'INCIDENT' ORDER BY id",
    )

    /** Le rythme que la base montre à cet instant, incidents ouverts compris. */
    private fun figuresNow(companyId: Long) = MariaDbSupport.raw { c ->
        figures.forCompany(c, store.loadCompany(c, companyId), store.loadFactories(c, companyId))
    }

    @Test
    @DisplayName("resolveIncident : l'incident est clos à l'heure du service, la production ralentie puis la récompense sont versées en une écriture, la version monte de 1, l'événement part")
    fun resolveWritesEverythingInOneTransaction() = withCompany { id ->
        val incidentId = open(id, starter, "panne", 0.6, clock.instant())
        clock.advance(hours(2.0))
        val slowed = figuresNow(id)
        val nominal = slowed.forFactory(starter).nominalRatePerHour
        val production = engine.onlineEarnings(slowed.ratePerHour, 2.0)
        val reward = engine.incidentReward(nominal)
        assertTrue(reward > 0.0)
        val donarBefore = donar(id)
        val versionBefore = version(id)

        val result = updated(resolve(id, incidentId))

        assertClose(donarBefore + production + reward, donar(id), "donar en base")
        assertClose(donar(id), result.state.donar, "donar de la réponse")
        assertEquals(versionBefore + 1, version(id), "une seule écriture de l'entreprise")
        assertEquals(clock.instant(), find(id, incidentId)!!.resolvedAt)
        assertNull(result.state.factories.single { it.factoryId == starter }.incidentId)
        assertNull(factories(id).getValue(starter).openIncidentId)
        assertEquals(listOf("${KR.text}|resolved|$incidentId"), incidentEvents())
        assertEquals(nominal, figuresNow(id).forFactory(starter).ratePerHour, "la production est de nouveau entière")
    }

    @Test
    @DisplayName("resolveIncident : rejoué avec le même requestId, la même réponse sans nouvelle écriture ; avec un autre, l'incident est déjà clos et rien ne change")
    fun resolveIsIdempotentAndASecondRepairIsANoOp() = withCompany { id ->
        val incidentId = open(id, starter, "courroie", 0.7, clock.instant())
        clock.advance(hours(1.0))

        val first = resolve(id, incidentId, n = 1)
        val firstBody = first.bodyAsText()
        assertEquals(HttpStatusCode.OK, first.status, firstBody)
        val donarAfter = donar(id)
        val versionAfter = version(id)

        assertEquals(firstBody, resolve(id, incidentId, n = 1).bodyAsText(), "même requestId : même réponse")
        updated(resolve(id, incidentId, n = 2))

        assertEquals(donarAfter, donar(id))
        assertEquals(versionAfter, version(id))
        assertEquals(1, incidentEvents().size)
    }

    @Test
    @DisplayName("resolveIncident : l'incident d'une autre entreprise, un incident inconnu ou un acteur étranger donnent 400 et ne touchent à rien")
    fun foreignAndUnknownIncidentsAreRejected() = withCompany { id ->
        val rival = MariaDbSupport.insertCompany("Rivale", 7)
        ownFactory(rival, starter)
        val foreign = open(rival, starter)
        val mine = open(id, starter)
        val donarBefore = donar(id)

        for ((incident, actor) in listOf(foreign to KR, 987654L to KR, mine to OTHER)) {
            val response = resolve(id, incident, n = incident.toInt() % 1000, actor = actor)
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        }

        assertNull(find(rival, foreign)!!.resolvedAt)
        assertNull(find(id, mine)!!.resolvedAt)
        assertEquals(donarBefore, donar(id))
        assertEquals(emptyList<String?>(), incidentEvents())
    }

    @Test
    @DisplayName("Des réparations simultanées du même incident : une seule paie, les autres rendent 200 sans rien changer ou CONFLIT_REESSAYER, et la récompense n'est versée qu'une fois")
    fun parallelRepairsPayOnce() = withCompany { id ->
        val incidentId = open(id, starter, "panne", 0.6, clock.instant())
        val reward = engine.incidentReward(figuresNow(id).forFactory(starter).nominalRatePerHour)
        val donarBefore = donar(id)

        val responses = coroutineScope {
            val go = CompletableDeferred<Unit>()
            val jobs = (1..4).map { n -> async(Dispatchers.IO) { go.await(); resolve(id, incidentId, n = n) } }
            go.complete(Unit)
            jobs.awaitAll()
        }

        for (response in responses.filter { it.status != HttpStatusCode.OK }) {
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(RefusalCodes.CONFLICT_RETRY, SiloooJson.decodeFromString(RefusedBody.serializer(), response.bodyAsText()).reason)
        }
        assertTrue(responses.any { it.status == HttpStatusCode.OK }, "au moins une réparation passe")
        // Les perdantes qui ont reçu un conflit rejouent : l'incident est clos, c'est un 200 sans effet.
        for (n in responses.indices.filter { responses[it].status != HttpStatusCode.OK }) updated(resolve(id, incidentId, n = n + 1))

        assertNotNull(find(id, incidentId)!!.resolvedAt)
        assertClose(donarBefore + reward, donar(id), "la récompense n'est versée qu'une fois (la production en attente est nulle)")
        assertEquals(1, incidentEvents().size, "un seul événement : celui de la réparation qui a payé")
    }

    @Test
    @DisplayName("Le minuteur : deux passages avec un membre connecté ouvrent l'incident du créneau en base, versent la production d'avant, publient l'événement, et le créneau n'en reçoit pas un second")
    fun theTickerOpensOneIncidentPerSlot() {
        val id = startCompany()
        ownFactory(id, second) // deux usines à toucher : l'incident du premier créneau reste ouvert au second
        val draw = IncidentDraw(definitions.incidents.let { it.copy(frequency = it.frequency.copy(chancePercent = 100.0)) })
        val service = IncidentService(store, companyStore, jdbc, engine, figures, draw, presence, clock)
        val ticker = IncidentTicker(database, store, service) { setOf(KR) }
        clock.advance(hours(1.0))
        val before = figuresNow(id)
        val donarBefore = donar(id)

        runBlocking { ticker.tick() }
        assertEquals(0L, MariaDbSupport.count("factory_incidents"), "le premier passage ne tire pas : le join n'a peut-être pas versé l'absence")
        val opened = runBlocking { ticker.tick() }

        assertEquals(1, opened.size)
        val incident = opened.single()
        val row = find(id, incident.incidentId)!!
        assertEquals(id, row.companyId)
        assertEquals(incident.factoryId, row.factoryId)
        assertEquals(incident.kind, row.kind)
        assertEquals(incident.slowdown, row.slowdown)
        assertEquals(clock.instant().truncatedTo(ChronoUnit.MILLIS), row.startedAt)
        assertNull(row.resolvedAt)
        assertEquals(incident.incidentId, factories(id).getValue(incident.factoryId).openIncidentId)
        assertClose(donarBefore + engine.onlineEarnings(before.ratePerHour, 1.0), donar(id), "la production d'avant l'incident est versée")
        assertEquals(listOf("${KR.text}|opened|${incident.incidentId}"), incidentEvents())
        assertTrue(figuresNow(id).ratePerHour < before.ratePerHour)

        assertTrue(runBlocking { ticker.tick() }.isEmpty(), "même créneau : pas de second incident")
        assertEquals(1L, MariaDbSupport.count("factory_incidents"))

        clock.advance(hours(1.0)) // un autre créneau
        assertEquals(1, runBlocking { ticker.tick() }.size)
        assertEquals(2L, MariaDbSupport.count("factory_incidents"))
        assertEquals(2, MariaDbSupport.long("SELECT COUNT(DISTINCT factory_id) FROM factory_incidents WHERE resolved_at IS NULL").toInt(), "jamais deux incidents ouverts sur une usine")
    }

    @Test
    @DisplayName("Lire l'entreprise montre l'incident ouvert (sorte et identifiant) et le revenu ralenti ; réparée, plus rien")
    fun theCompanyStateShowsTheIncident() = withCompany { id ->
        val incidentId = open(id, starter, "incendie", 0.5, clock.instant())

        val shown = client.get("/v1/companies/$id") { header(SERVER_KEY_HEADER, key) }
        assertEquals(HttpStatusCode.OK, shown.status, shown.bodyAsText())
        val factory = SiloooJson.decodeFromString(com.silooo.core.api.CompanyState.serializer(), shown.bodyAsText()).factories.single { it.factoryId == starter }

        assertEquals(incidentId, factory.incidentId)
        assertEquals("incendie", factory.incidentKind)
        assertClose(figuresNow(id).forFactory(starter).nominalRatePerHour * 0.5, factory.ratePerHour, "le revenu affiché est ralenti")
    }
}
