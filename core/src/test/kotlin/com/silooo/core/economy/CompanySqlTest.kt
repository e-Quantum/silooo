package com.silooo.core.economy

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.CompanyState
import com.silooo.core.api.CompanyUpdated
import com.silooo.core.api.ErrorBody
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.Idempotency
import com.silooo.core.api.JoinResponse
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.RefusedBody
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.MariaDbSupport
import com.silooo.core.db.Xuid
import com.silooo.core.economy.EngineFixture.Companion.KR
import com.silooo.core.economy.EngineFixture.Companion.START
import com.silooo.core.economy.EngineFixture.Companion.hours
import com.silooo.core.events.GameEventKind
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
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
 * `getCompany`, `collect`, `buyLevels` et `restoreStep` sur la vraie MariaDB, par HTTP, avec le vrai moteur
 * d'économie, le vrai dépôt SQL et la vraie idempotence : ce que les tests de route (`CompanyRoutesTest`) ne
 * peuvent pas voir, c'est-à-dire ce qui arrive vraiment dans les tables. `companies` (`donar`, `donar_earned`,
 * `last_collect_at`, `version`), `company_factories` (`state`, `restore_step`, `level`) et `game_events`.
 * L'horloge est celle du test : on avance le temps sans attendre. Ignoré sans Docker (voir [MariaDbSupport]).
 *
 * Les chiffres attendus sortent du moteur et des `definitions/`, jamais d'un littéral recopié.
 */
class CompanySqlTest {
    private val database get() = MariaDbSupport.database
    private val definitions = TestDefinitions.loaded
    private val engine = EconomyEngine(definitions)
    private val offline = definitions.economy.offline
    private val start = definitions.economy.prestige.courseStart
    private val store = JdbcSessionStore()
    private val clock = MutableClock(START)
    private val key = "cle-de-test"

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    // --- le service et l'entreprise de départ

    private fun dependencies(companyStore: CompanyStore): Dependencies = Dependencies(
        database = database,
        definitions = definitions,
        apiKey = key,
        economy = engine,
        idempotency = Idempotency(database),
        sessionStore = store,
        economyFigures = EngineFigures(engine, store, clock),
        companyBootstrap = DefinitionsBootstrap(engine, definitions),
        companyStore = companyStore,
    )

    /** Crée l'entreprise de [KR] par le vrai `join`, puis monte le service sur la base. */
    private fun withCompany(companyStore: CompanyStore = JdbcCompanyStore(), block: suspend ApplicationTestBuilder.(JoinResponse) -> Unit) {
        val joined = runBlocking {
            SessionService(database, store, EngineFigures(engine, store, clock), DefinitionsBootstrap(engine, definitions)).join(KR, "Kr")
        }
        testApplication {
            application { siloooModule(dependencies(companyStore)) }
            block(joined)
        }
    }

    /** Un dépôt qui écrit comme le vrai, sauf la publication d'événement : la dernière écriture d'une action échoue. */
    private class FailingPublish(private val real: CompanyStore = JdbcCompanyStore()) : CompanyStore by real {
        override fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long =
            throw IllegalStateException("publication impossible (test)")
    }

    // --- HTTP

    private fun requestId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    private fun action(n: Int = 1, extra: String = ""): String = """{"requestId":"${requestId(n)}","actor":"${KR.text}"$extra}"""

    private suspend fun ApplicationTestBuilder.get(path: String): HttpResponse = client.get(path) { header(SERVER_KEY_HEADER, key) }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String): HttpResponse =
        client.post(path) {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.collect(companyId: Long, n: Int = 1) = post("/v1/companies/$companyId/collect", action(n))

    private suspend fun ApplicationTestBuilder.buy(companyId: Long, factoryId: String, amount: String, n: Int = 1) =
        post("/v1/companies/$companyId/factories/$factoryId/levels", action(n, ""","amount":"$amount""""))

    private suspend fun ApplicationTestBuilder.restore(companyId: Long, factoryId: String, step: Int, n: Int = 1) =
        post("/v1/companies/$companyId/factories/$factoryId/restore", action(n, ""","step":$step"""))

    private suspend fun updated(response: HttpResponse): CompanyUpdated {
        assertEquals(HttpStatusCode.OK, response.status)
        return SiloooJson.decodeFromString(CompanyUpdated.serializer(), response.bodyAsText())
    }

    private suspend fun assertRefused(response: HttpResponse, reason: String): RefusedBody {
        assertEquals(HttpStatusCode.Conflict, response.status)
        val body = SiloooJson.decodeFromString(RefusedBody.serializer(), response.bodyAsText())
        assertEquals(reason, body.reason)
        return body
    }

    // --- lectures en base

    private fun double(sql: String, vararg params: Any?): Double = MariaDbSupport.raw { connection ->
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows ->
                check(rows.next()) { "aucune ligne pour $sql" }
                rows.getDouble(1)
            }
        }
    }

    private fun donar(companyId: Long) = double("SELECT donar FROM companies WHERE id = ?", companyId)

    private fun donarEarned(companyId: Long) = double("SELECT donar_earned FROM companies WHERE id = ?", companyId)

    private fun version(companyId: Long) = MariaDbSupport.long("SELECT version FROM companies WHERE id = ?", companyId)

    private fun lastCollectAt(companyId: Long): Instant = MariaDbSupport.raw { connection ->
        connection.prepareStatement("SELECT last_collect_at FROM companies WHERE id = ?").use { statement ->
            statement.setLong(1, companyId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getObject(1, LocalDateTime::class.java).toInstant(ZoneOffset.UTC)
            }
        }
    }

    /** Fixe le dernier calcul de production de l'entreprise à l'heure de l'horloge : le point de départ de l'attente. */
    private fun collectedNow(companyId: Long) {
        MariaDbSupport.update("UPDATE companies SET last_collect_at = ? WHERE id = ?", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), companyId)
    }

    private fun setDonar(companyId: Long, donar: Double) {
        MariaDbSupport.update("UPDATE companies SET donar = ? WHERE id = ?", donar, companyId)
    }

    private fun factoryRow(companyId: Long, factoryId: String): String? =
        MariaDbSupport.strings(
            "SELECT CONCAT(state, '|', restore_step, '|', level) FROM company_factories WHERE company_id = ? AND factory_id = ?",
            companyId,
            factoryId,
        ).singleOrNull()

    private fun level(companyId: Long, factoryId: String): Int =
        MariaDbSupport.long("SELECT level FROM company_factories WHERE company_id = ? AND factory_id = ?", companyId, factoryId).toInt()

    private fun eventCount() = MariaDbSupport.count("game_events")

    /** Le champ [name] du `payload` de chaque événement, dans l'ordre où ils ont été publiés. */
    private fun eventValues(name: String): List<String?> =
        MariaDbSupport.strings("SELECT JSON_VALUE(payload, '\$.$name') FROM game_events ORDER BY id")

    private fun startingRate() = engine.ratePerHour(engine.startingLevels(), engine.globalMultiplier(0.0, 0))

    /** Une usine qui n'est pas celle de départ : l'entreprise ne l'a pas tant qu'un test ne pose pas sa ligne. */
    private fun ruinedFactory(): String = engine.factoryIds().first { it != start.factoryId }

    private fun putRuin(companyId: Long, factoryId: String) {
        MariaDbSupport.update(
            "INSERT INTO company_factories (company_id, factory_id, state, restore_step, level) VALUES (?, ?, 'EN_RUINE', 0, 0)",
            companyId,
            factoryId,
        )
    }

    // --- getCompany

    @Test
    @DisplayName("getCompany : l'état de la base, le même que celui de join, et rien d'écrit ; une entreprise inconnue donne 400")
    fun getCompanyReadsWithoutWriting() = withCompany { joined ->
        val id = joined.companyId

        val response = get("/v1/companies/$id")

        assertEquals(HttpStatusCode.OK, response.status)
        val state = SiloooJson.decodeFromString(CompanyState.serializer(), response.bodyAsText())
        assertEquals(joined.state, state)
        assertEquals(0L, version(id), "une lecture n'écrit pas")
        assertEquals(0L, eventCount())

        val unknown = get("/v1/companies/${id + 1000}")
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertEquals(ErrorCodes.BAD_REQUEST, SiloooJson.decodeFromString(ErrorBody.serializer(), unknown.bodyAsText()).reason)
    }

    // --- collect

    @Test
    @DisplayName("collect : Donar, Donar gagnés, last_collect_at et version écrits ; un événement pour l'acteur ; rejoué, rien de plus")
    fun collectWritesEverything() = withCompany { joined ->
        val id = joined.companyId
        collectedNow(id)
        val away = offline.capHours / 2
        clock.advance(hours(away))
        val earned = engine.offlineEarnings(startingRate(), away, offline.capHours)
        assertTrue(earned > 0.0)

        val first = collect(id)
        assertEquals(HttpStatusCode.OK, first.status)
        val firstBody = first.bodyAsText()
        val collected = SiloooJson.decodeFromString(CollectResponse.serializer(), firstBody)
        assertGolden(earned, collected.collected, "collected")
        assertGolden(engine.offlineHours(away, offline.capHours), collected.offlineHours, "offlineHours")
        assertGolden(earned, collected.state.donar, "donar de la réponse")
        assertEquals(1, collected.state.version)

        assertGolden(earned, donar(id), "donar en base")
        assertGolden(earned, donarEarned(id), "donar gagnés en base")
        assertEquals(clock.instant(), lastCollectAt(id))
        assertEquals(1L, version(id))

        assertEquals(1L, eventCount())
        assertEquals(CompanyService.EVENT_KIND.name, MariaDbSupport.string("SELECT kind FROM game_events"))
        assertEquals(KR.text, MariaDbSupport.string("SELECT CAST(xuid AS CHAR) FROM game_events"))
        assertEquals(listOf(CompanyService.ACTION_COLLECT), eventValues("action"))
        assertEquals(listOf(id.toString()), eventValues("companyId"))
        assertGolden(earned, eventValues("collected").single()!!.toDouble(), "Donar encaissé dans l'événement")

        // Rejoué avec le même requestId : la même réponse, aucune écriture de plus.
        val replay = collect(id)
        assertEquals(firstBody, replay.bodyAsText())
        assertEquals(1L, version(id))
        assertEquals(1L, eventCount())

        // Un autre appel, sans temps écoulé : rien à encaisser, ni écriture ni événement.
        val nothing = SiloooJson.decodeFromString(CollectResponse.serializer(), collect(id, n = 2).bodyAsText())
        assertEquals(0.0, nothing.collected)
        assertEquals(0.0, nothing.offlineHours)
        assertGolden(earned, donar(id), "donar après l'appel à vide")
        assertEquals(1L, version(id))
        assertEquals(1L, eventCount())
        assertEquals(clock.instant(), lastCollectAt(id))
    }

    @Test
    @DisplayName("collect : au-delà du plafond de l'entreprise, le gain s'arrête au plafond et last_collect_at repart de maintenant")
    fun collectIsCappedByTheCompanyCap() = withCompany { joined ->
        val id = joined.companyId
        val cap = 2.5
        MariaDbSupport.update("UPDATE companies SET offline_cap_hours = ? WHERE id = ?", cap, id)
        collectedNow(id)
        clock.advance(hours(cap + offline.capHours))

        val collected = SiloooJson.decodeFromString(CollectResponse.serializer(), collect(id).bodyAsText())

        assertGolden(engine.offlineEarnings(startingRate(), cap + offline.capHours, cap), collected.collected, "gain au plafond")
        assertGolden(cap, collected.offlineHours, "heures retenues")
        assertGolden(collected.collected, donar(id), "donar en base")
        assertEquals(clock.instant(), lastCollectAt(id))
    }

    // --- buyLevels

    @Test
    @DisplayName("buyLevels : le niveau de l'usine et le Donar sont écrits dans la même version ; un événement avec le prix")
    fun buyLevelsWritesLevelAndDebits() = withCompany { joined ->
        val id = joined.companyId
        collectedNow(id)
        val cost = engine.levelsCost(start.factoryId, start.level, 10)
        setDonar(id, cost * 2.0)

        val result = updated(buy(id, start.factoryId, "10"))

        assertEquals(start.level + 10, result.state.factories.single().level)
        assertGolden(cost, result.state.donar, "donar de la réponse")
        assertEquals(start.level + 10, level(id, start.factoryId))
        assertEquals("ACTIVE|0|${start.level + 10}", factoryRow(id, start.factoryId))
        assertGolden(cost, donar(id), "donar en base")
        assertEquals(0.0, donarEarned(id))
        assertEquals(1L, version(id))
        assertEquals(1, result.state.version)
        assertEquals(clock.instant(), lastCollectAt(id))

        assertEquals(1L, eventCount())
        assertEquals(CompanyService.EVENT_KIND.name, MariaDbSupport.string("SELECT kind FROM game_events"))
        assertEquals(KR.text, MariaDbSupport.string("SELECT CAST(xuid AS CHAR) FROM game_events"))
        assertEquals(listOf(CompanyService.ACTION_BUY_LEVELS), eventValues("action"))
        assertEquals(listOf(start.factoryId), eventValues("factoryId"))
        assertEquals(listOf("10"), eventValues("levels"))
        assertEquals(listOf((start.level + 10).toString()), eventValues("level"))
        assertGolden(cost, eventValues("cost").single()!!.toDouble(), "prix dans l'événement")
    }

    @Test
    @DisplayName("buyLevels : la production en attente est versée avant le débit, dans la même écriture")
    fun buyLevelsSettlesProductionBeforeTheDebit() = withCompany { joined ->
        val id = joined.companyId
        collectedNow(id)
        val oneLevel = engine.nextLevelCost(start.factoryId, start.level)
        setDonar(id, oneLevel)
        val away = 2.0
        clock.advance(hours(away))
        val earned = engine.offlineEarnings(startingRate(), away, offline.capHours)
        assertTrue(earned > 0.0)

        updated(buy(id, start.factoryId, "1"))

        assertEquals(start.level + 1, level(id, start.factoryId))
        assertGolden(earned, donarEarned(id), "donar gagnés en base")
        // Le Donar d'avant (le prix d'un niveau) plus la production, moins le prix : il reste la production.
        assertGolden(oneLevel + earned, donar(id) + oneLevel, "donar en base")
        assertEquals(clock.instant(), lastCollectAt(id))
        assertEquals(1L, version(id))
    }

    @Test
    @DisplayName("buyLevels refusé faute de Donar : le refus dit ce qui manque, et rien n'est écrit")
    fun buyLevelsRefusedWritesNothing() = withCompany { joined ->
        val id = joined.companyId
        collectedNow(id)
        val before = lastCollectAt(id)

        val refused = assertRefused(buy(id, start.factoryId, "10"), RefusalCodes.NOT_ENOUGH_DONAR)

        assertGolden(engine.levelsCost(start.factoryId, start.level, 10), refused.missing!!, "ce qui manque")
        assertEquals("ACTIVE|0|${start.level}", factoryRow(id, start.factoryId))
        assertEquals(0.0, donar(id))
        assertEquals(0.0, donarEarned(id))
        assertEquals(0L, version(id))
        assertEquals(before, lastCollectAt(id))
        assertEquals(0L, eventCount())
    }

    @Test
    @DisplayName("buyLevels : si la dernière écriture de l'action échoue, tout est annulé (Donar, niveau, last_collect_at, version, événement)")
    fun buyLevelsRollsBackAsOneTransaction() = withCompany(FailingPublish()) { joined ->
        val id = joined.companyId
        collectedNow(id)
        val before = lastCollectAt(id)
        val oneLevel = engine.nextLevelCost(start.factoryId, start.level)
        setDonar(id, oneLevel)
        clock.advance(hours(1.0))

        val response = buy(id, start.factoryId, "1")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(oneLevel, donar(id))
        assertEquals(0.0, donarEarned(id))
        assertEquals("ACTIVE|0|${start.level}", factoryRow(id, start.factoryId))
        assertEquals(0L, version(id))
        assertEquals(before, lastCollectAt(id))
        assertEquals(0L, eventCount())
    }

    @Test
    @DisplayName("Des achats simultanés ne perdent aucune écriture : chaque succès monte un niveau et paie son prix, les autres reçoivent CONFLIT_REESSAYER")
    fun parallelPurchasesNeverLoseAnUpdate() = withCompany { joined ->
        val id = joined.companyId
        collectedNow(id)
        val parallel = 4
        val allFour = engine.levelsCost(start.factoryId, start.level, parallel)
        setDonar(id, allFour * 2.0)

        val responses = coroutineScope {
            val go = CompletableDeferred<Unit>()
            val jobs = (1..parallel).map { n -> async(Dispatchers.IO) { go.await(); buy(id, start.factoryId, "1", n = n) } }
            go.complete(Unit)
            jobs.awaitAll()
        }

        val succeeded = responses.count { it.status == HttpStatusCode.OK }
        val refusals = responses.filter { it.status != HttpStatusCode.OK }.map { response ->
            assertEquals(HttpStatusCode.Conflict, response.status)
            SiloooJson.decodeFromString(RefusedBody.serializer(), response.bodyAsText()).reason
        }
        assertEquals(parallel, succeeded + refusals.size)
        assertTrue(refusals.all { it == RefusalCodes.CONFLICT_RETRY }, "un perdant reçoit CONFLIT_REESSAYER : $refusals")
        assertTrue(succeeded >= 1, "au moins un achat passe")

        assertEquals(start.level + succeeded, level(id, start.factoryId))
        assertEquals(succeeded.toLong(), version(id), "une version par achat réussi")
        assertEquals(succeeded.toLong(), eventCount())
        assertGolden(allFour * 2.0 - engine.levelsCost(start.factoryId, start.level, succeeded), donar(id), "donar en base")
    }

    // --- restoreStep

    @Test
    @DisplayName("restoreStep : les quatre étapes dans l'ordre, EN_RUINE puis REMISE_EN_ETAT puis ACTIVE au niveau de départ, un événement par étape")
    fun restoreStepsAdvanceToActive() = withCompany { joined ->
        val id = joined.companyId
        val ruin = ruinedFactory()
        putRuin(id, ruin)
        collectedNow(id)
        assertEquals("EN_RUINE|0|0", factoryRow(id, ruin))

        // Dans le désordre : refusé, rien n'est écrit.
        assertRefused(restore(id, ruin, 2), RefusalCodes.FACTORY_LOCKED)
        assertEquals("EN_RUINE|0|0", factoryRow(id, ruin))
        assertEquals(0L, version(id))
        assertEquals(0L, eventCount())

        // L'étape 1, après une heure : la production en attente est versée avec elle.
        clock.advance(hours(1.0))
        val earned = engine.offlineEarnings(startingRate(), 1.0, offline.capHours)
        val first = updated(restore(id, ruin, 1, n = 2))
        assertEquals("REMISE_EN_ETAT|1|0", factoryRow(id, ruin))
        assertEquals(1L, version(id))
        assertGolden(earned, donarEarned(id), "production versée avec la première étape")
        assertEquals(clock.instant(), lastCollectAt(id))
        assertTrue(first.state.factories.any { it.factoryId == ruin })

        // Refaire une étape déjà faite : refusé.
        assertRefused(restore(id, ruin, 1, n = 3), RefusalCodes.ALREADY_OWNED)
        assertEquals("REMISE_EN_ETAT|1|0", factoryRow(id, ruin))
        assertEquals(1L, version(id))

        updated(restore(id, ruin, 2, n = 4))
        assertEquals("REMISE_EN_ETAT|2|0", factoryRow(id, ruin))
        updated(restore(id, ruin, 3, n = 5))
        assertEquals("REMISE_EN_ETAT|3|0", factoryRow(id, ruin))

        val last = updated(restore(id, ruin, 4, n = 6))
        assertEquals("ACTIVE|4|${engine.restoredLevel(0)}", factoryRow(id, ruin))
        assertEquals(engine.restoredLevel(0), last.state.factories.single { it.factoryId == ruin }.level)
        assertEquals(4L, version(id), "une version par étape acceptée")

        // Une usine ACTIVE ne se remet plus en état.
        assertRefused(restore(id, ruin, 4, n = 7), RefusalCodes.ALREADY_OWNED)
        assertEquals(4L, version(id))

        assertEquals(4L, eventCount())
        assertEquals(List(4) { CompanyService.EVENT_KIND.name }, MariaDbSupport.strings("SELECT kind FROM game_events ORDER BY id"))
        assertEquals(List(4) { CompanyService.ACTION_RESTORE_STEP }, eventValues("action"))
        assertEquals(listOf("1", "2", "3", "4"), eventValues("step"))
        assertEquals(listOf("REMISE_EN_ETAT", "REMISE_EN_ETAT", "REMISE_EN_ETAT", "ACTIVE"), eventValues("state"))
        assertEquals(List(4) { ruin }, eventValues("factoryId"))
    }
}
