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
import com.silooo.core.api.PrestigePreview
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.RefusedBody
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.MariaDbSupport
import com.silooo.core.db.Xuid
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

    /** La présence que `join` interroge, sur l'horloge du test : le test dit qui est connecté et quand. */
    private val presence = ServerPresence(clock = clock)
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
        serverPresence = presence,
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
        val earned = engine.onlineEarnings(startingRate(), away)
        assertTrue(earned > 0.0)

        val first = collect(id)
        assertEquals(HttpStatusCode.OK, first.status)
        val firstBody = first.bodyAsText()
        val collected = SiloooJson.decodeFromString(CollectResponse.serializer(), firstBody)
        assertGolden(earned, collected.collected, "collected")
        assertGolden(away, collected.offlineHours, "offlineHours : toutes les heures, en ligne")
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
    @DisplayName("collect : en ligne, le plafond de l'entreprise ne coupe rien : tout le temps écoulé est payé au revenu plein et last_collect_at repart de maintenant")
    fun collectHasNoCapOnline() = withCompany { joined ->
        val id = joined.companyId
        val cap = 2.5
        MariaDbSupport.update("UPDATE companies SET offline_cap_hours = ? WHERE id = ?", cap, id)
        collectedNow(id)
        val away = cap + offline.capHours
        clock.advance(hours(away))

        val collected = SiloooJson.decodeFromString(CollectResponse.serializer(), collect(id).bodyAsText())

        assertGolden(engine.onlineEarnings(startingRate(), away), collected.collected, "gain au revenu plein")
        assertTrue(collected.collected > engine.offlineEarnings(startingRate(), away, cap), "plus que ne donnerait le régime hors ligne")
        assertGolden(away, collected.offlineHours, "heures comptées : toutes")
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
        val earned = engine.onlineEarnings(startingRate(), away)
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
        val earned = engine.onlineEarnings(startingRate(), 1.0)
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

    // --- join et leave : le régime de la période

    private suspend fun ApplicationTestBuilder.join(xuid: Xuid, gamertag: String = "Kr"): JoinResponse {
        val response = post("/v1/session/join", """{"xuid":"${xuid.text}","gamertag":"$gamertag"}""")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return SiloooJson.decodeFromString(JoinResponse.serializer(), response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.leave(xuid: Xuid) = post("/v1/session/leave", """{"xuid":"${xuid.text}"}""")

    /**
     * [partner] devient associé de l'entreprise [companyId]. Son premier `join` lui crée une entreprise à lui ; on le
     * rattache ensuite à celle-ci, comme le fera l'invitation d'un associé.
     */
    private suspend fun ApplicationTestBuilder.addPartner(companyId: Long, partner: Xuid) {
        join(partner, "Associe")
        MariaDbSupport.update("UPDATE company_members SET company_id = ?, role = 'ASSOCIE' WHERE xuid = ?", companyId, partner.text)
    }

    @Test
    @DisplayName("deux associés : A joue, B arrive : la période de A est payée au revenu plein (au-delà du plafond aussi), puis une heure plus tard le collect ne compte qu'elle")
    fun partnerArrivingWhileTheOtherPlaysPaysFullRate() = withCompany { joined ->
        val id = joined.companyId
        addPartner(id, OTHER)
        collectedNow(id)
        val away = offline.capHours + 3.0
        clock.advance(hours(away))
        presence.record("serveur-1", listOf(KR.text, OTHER.text))
        val earned = engine.onlineEarnings(startingRate(), away)
        assertTrue(earned > engine.offlineEarnings(startingRate(), away, offline.capHours))

        val back = join(OTHER, "Associe")

        assertEquals(0.0, back.offlineCollected, "le gain en ligne n'est pas du hors ligne")
        assertGolden(earned, donar(id), "donar en base")
        assertGolden(earned, donarEarned(id), "donar gagnés en base")
        assertEquals(clock.instant(), lastCollectAt(id))
        assertEquals(1L, version(id))

        clock.advance(hours(1.0))
        val collected = SiloooJson.decodeFromString(CollectResponse.serializer(), collect(id).bodyAsText())
        assertGolden(engine.onlineEarnings(startingRate(), 1.0), collected.collected, "une heure de plus, au revenu plein")
        assertGolden(1.0, collected.offlineHours, "heures comptées")
        assertGolden(earned + collected.collected, donar(id), "donar en base après le collect")
    }

    @Test
    @DisplayName("deux associés : B arrive et personne d'autre n'est connecté : la période est hors ligne, plafond et efficacité, et offlineCollected la rapporte")
    fun partnerArrivingWhenNobodyElseIsOnlinePaysOffline() = withCompany { joined ->
        val id = joined.companyId
        addPartner(id, OTHER)
        collectedNow(id)
        val away = offline.capHours + 3.0
        clock.advance(hours(away))
        presence.record("serveur-1", listOf(OTHER.text))
        val earned = engine.offlineEarnings(startingRate(), away, offline.capHours)
        assertTrue(earned > 0.0)

        val back = join(OTHER, "Associe")

        assertGolden(earned, back.offlineCollected, "offlineCollected")
        assertGolden(earned, donar(id), "donar en base")
        assertEquals(clock.instant(), lastCollectAt(id))
        assertEquals(1L, version(id))
    }

    @Test
    @DisplayName("join remet last_collect_at à maintenant même quand il n'y a rien à payer : le collect qui suit ne compte pas ces heures au revenu plein")
    fun joinResetsTheDateEvenWithNothingToPay() = withCompany { joined ->
        val id = joined.companyId
        MariaDbSupport.update("UPDATE companies SET offline_cap_hours = 0 WHERE id = ?", id)
        collectedNow(id)
        clock.advance(hours(5.0))

        val back = join(KR)

        assertEquals(0.0, back.offlineCollected)
        assertEquals(0.0, donar(id))
        assertEquals(clock.instant(), lastCollectAt(id))
        assertEquals(1L, version(id), "le coup de version est accepté")

        val nothing = SiloooJson.decodeFromString(CollectResponse.serializer(), collect(id).bodyAsText())
        assertEquals(0.0, nothing.collected)
        assertEquals(0.0, nothing.offlineHours)
        assertEquals(0.0, donar(id))
    }

    @Test
    @DisplayName("leave verse la production au revenu plein et remet last_collect_at à maintenant ; le retour compte ensuite l'absence au régime hors ligne, depuis le départ")
    fun leavePaysOnlineAndTheReturnIsOffline() = withCompany { joined ->
        val id = joined.companyId
        collectedNow(id)
        val away = offline.capHours + 5.0
        clock.advance(hours(away))
        val played = engine.onlineEarnings(startingRate(), away)
        assertTrue(played > engine.offlineEarnings(startingRate(), away, offline.capHours))

        assertEquals(HttpStatusCode.NoContent, leave(KR).status)

        assertGolden(played, donar(id), "donar en base après le départ")
        assertGolden(played, donarEarned(id), "donar gagnés en base après le départ")
        assertEquals(clock.instant(), lastCollectAt(id))
        assertEquals(1L, version(id))

        // Un second départ sans temps écoulé ne paie rien et ne touche pas la version.
        assertEquals(HttpStatusCode.NoContent, leave(KR).status)
        assertEquals(1L, version(id))

        // Deux heures d'absence, puis le retour : hors ligne, depuis le départ.
        val absence = 2.0
        clock.advance(hours(absence))
        val absent = engine.offlineEarnings(startingRate(), absence, offline.capHours)
        val back = join(KR)

        assertGolden(absent, back.offlineCollected, "offlineCollected")
        assertGolden(played + absent, donar(id), "donar en base après le retour")
        assertEquals(clock.instant(), lastCollectAt(id))
        assertEquals(2L, version(id))
    }

    // --- prestigePreview et prestige

    private suspend fun ApplicationTestBuilder.prestige(companyId: Long, n: Int = 1, actor: Xuid = KR) =
        post("/v1/companies/$companyId/prestige", """{"requestId":"${requestId(n)}","actor":"${actor.text}"}""")

    private suspend fun ApplicationTestBuilder.previewOf(companyId: Long): PrestigePreview {
        val response = get("/v1/companies/$companyId/prestige/preview")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return SiloooJson.decodeFromString(PrestigePreview.serializer(), response.bodyAsText())
    }

    private fun setDonarEarned(companyId: Long, earned: Double) {
        MariaDbSupport.update("UPDATE companies SET donar_earned = ? WHERE id = ?", earned, companyId)
    }

    private fun courseNumber(companyId: Long) = MariaDbSupport.long("SELECT course_number FROM companies WHERE id = ?", companyId).toInt()

    private fun lingotsOf(xuid: Xuid) = MariaDbSupport.long("SELECT lingots FROM player_balances WHERE xuid = ?", xuid.text)

    private fun prestigeLedger(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(CAST(xuid AS CHAR), '|', delta, '|', balance, '|', ref_id) FROM currency_ledger WHERE reason = 'prestige' ORDER BY id",
    )

    /** Une usine active à [level] avec un incident ouvert : ce que le prestige doit remettre à zéro. */
    private fun putBrokenFactory(companyId: Long, factoryId: String, level: Int) {
        MariaDbSupport.update(
            "INSERT INTO company_factories (company_id, factory_id, state, restore_step, level) VALUES (?, ?, 'ACTIVE', 4, ?)",
            companyId,
            factoryId,
            level,
        )
        MariaDbSupport.update(
            "INSERT INTO factory_incidents (company_id, factory_id, kind, slowdown) VALUES (?, ?, 'panne', 0.50)",
            companyId,
            factoryId,
        )
    }

    private fun openIncidents(companyId: Long) =
        MariaDbSupport.long("SELECT COUNT(*) FROM factory_incidents WHERE company_id = ? AND resolved_at IS NULL", companyId)

    /** Aucune écriture du prestige : ni course, ni Donar, ni usine, ni Lingots, ni historique, ni événement. */
    private fun assertNoPrestigeWritten(companyId: Long, earned: Double, brokenId: String) {
        assertEquals(1, courseNumber(companyId))
        assertEquals(0L, version(companyId))
        assertGolden(earned, donarEarned(companyId), "donar gagnés intacts")
        assertEquals("ACTIVE|0|${start.level}", factoryRow(companyId, start.factoryId))
        assertEquals("ACTIVE|4|12", factoryRow(companyId, brokenId))
        assertEquals(1L, openIncidents(companyId))
        assertEquals(0L, MariaDbSupport.count("prestige_history"))
        assertEquals(emptyList<String?>(), prestigeLedger())
        assertEquals(0L, eventCount())
    }

    @Test
    @DisplayName("prestige : la course suivante est écrite en base : entreprise, usines, incidents, Lingots et journal des deux associés, historique, événements ; rejoué, rien de plus")
    fun prestigeWritesTheNextCourse() = withCompany { joined ->
        val id = joined.companyId
        addPartner(id, OTHER)
        MariaDbSupport.update("UPDATE company_members SET share_percent = 70 WHERE xuid = ?", KR.text)
        MariaDbSupport.update("UPDATE company_members SET share_percent = 30 WHERE xuid = ?", OTHER.text)
        val broken = ruinedFactory()
        putBrokenFactory(id, broken, level = 12)
        MariaDbSupport.update("UPDATE company_factories SET level = ? WHERE company_id = ? AND factory_id = ?", start.level + 40, id, start.factoryId)
        val earned = engine.prestigeThreshold(1) * 8
        setDonarEarned(id, earned)
        setDonar(id, 1234.0)
        collectedNow(id)
        val total = engine.prestigeLingots(1, earned)
        assertTrue(total > 0, "le test n'a de sens que si le prestige rapporte")
        val partnerShare = total * 30 / 100

        // L'aperçu lit seulement : mêmes chiffres que le prestige qui suit, rien d'écrit.
        val preview = previewOf(id)
        assertTrue(preview.allowed)
        assertEquals(total, preview.lingotsTotal)
        assertEquals(listOf(KR.text, OTHER.text).sorted(), preview.shares.map { it.xuid }.sorted())
        assertEquals(total, preview.shares.sumOf { it.lingots })
        assertEquals(0L, version(id))
        assertEquals(emptyList<String?>(), prestigeLedger())
        assertEquals(0L, eventCount())

        val response = prestige(id)
        val body = response.bodyAsText()
        val result = SiloooJson.decodeFromString(CompanyUpdated.serializer(), body)
        assertEquals(HttpStatusCode.OK, response.status, body)

        // companies : la course 2, Donar et Donar gagnés à zéro, les dates à maintenant, une version de plus.
        assertEquals(2, courseNumber(id))
        assertEquals(engine.eraAt(2), MariaDbSupport.long("SELECT era FROM companies WHERE id = ?", id).toInt())
        assertEquals(engine.starsAt(2), MariaDbSupport.long("SELECT stars FROM companies WHERE id = ?", id).toInt())
        assertEquals(0.0, donar(id))
        assertEquals(0.0, donarEarned(id))
        assertEquals(1L, version(id))
        assertEquals(clock.instant(), lastCollectAt(id))
        assertEquals(
            1L,
            MariaDbSupport.long("SELECT COUNT(*) FROM companies WHERE id = ? AND course_started_at = ?", id, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)),
            "course_started_at passe à maintenant",
        )
        assertEquals(2, result.state.courseNumber)
        assertEquals(1L, result.state.version)

        // company_factories : l'usine de départ au niveau de départ, l'autre en ruine au niveau zéro, aucun incident ouvert.
        assertEquals("ACTIVE|0|${start.level}", factoryRow(id, start.factoryId))
        assertEquals("EN_RUINE|0|0", factoryRow(id, broken))
        assertEquals(0L, openIncidents(id))
        assertEquals(1L, MariaDbSupport.count("factory_incidents"), "la ligne de l'incident reste : elle est close, pas effacée")

        // player_balances et currency_ledger : une ligne par associé, le reste de la division va au PDG.
        assertEquals(total - partnerShare, lingotsOf(KR))
        assertEquals(partnerShare, lingotsOf(OTHER))
        val refId = "entreprise-$id-course-1"
        assertEquals(
            listOf("${KR.text}|${total - partnerShare}|${total - partnerShare}|$refId", "${OTHER.text}|$partnerShare|$partnerShare|$refId"),
            prestigeLedger(),
        )

        // prestige_history : la course 1 qui vient de finir, avec le total avant partage.
        assertEquals(1L, MariaDbSupport.count("prestige_history"))
        assertEquals(1, MariaDbSupport.long("SELECT course_number FROM prestige_history WHERE company_id = ?", id).toInt())
        assertEquals(total, MariaDbSupport.long("SELECT lingots_total FROM prestige_history WHERE company_id = ?", id))
        assertEquals(engine.starsAt(2), MariaDbSupport.long("SELECT stars_after FROM prestige_history WHERE company_id = ?", id).toInt())
        assertGolden(engine.prestigeThreshold(1), double("SELECT threshold FROM prestige_history WHERE company_id = ?", id), "seuil de la course")
        assertGolden(earned, double("SELECT donar_earned FROM prestige_history WHERE company_id = ?", id), "Donar gagnés de la course")
        assertEquals(KR.text, MariaDbSupport.string("SELECT CAST(decided_by AS CHAR) FROM prestige_history WHERE company_id = ?", id))

        // game_events : un ANNONCE par associé, avec la part de chacun.
        assertEquals(2L, eventCount())
        assertEquals(List(2) { GameEventKind.ANNONCE.name }, MariaDbSupport.strings("SELECT kind FROM game_events ORDER BY id"))
        assertEquals(List(2) { "prestige" }, eventValues("action"))
        assertEquals(listOf(total - partnerShare, partnerShare).map { it.toString() }, eventValues("lingots"))
        assertEquals(List(2) { total.toString() }, eventValues("lingotsTotal"))
        assertEquals(List(2) { id.toString() }, eventValues("companyId"))

        // Rejoué avec le même requestId : la même réponse, aucun Lingot de plus.
        val replay = prestige(id)
        assertEquals(body, replay.bodyAsText())
        assertEquals(1L, version(id))
        assertEquals(2, prestigeLedger().size)
        assertEquals(total - partnerShare, lingotsOf(KR))
        assertEquals(1L, MariaDbSupport.count("prestige_history"))
        assertEquals(2L, eventCount())

        // Un autre requestId tout de suite : la course 2 vient de commencer, le seuil n'est plus atteint.
        assertRefused(prestige(id, n = 2), RefusalCodes.THRESHOLD_NOT_REACHED)
        assertEquals(1L, version(id))
        assertEquals(1L, MariaDbSupport.count("prestige_history"))
    }

    @Test
    @DisplayName("prestige : seuil non atteint, 409 SEUIL_NON_ATTEINT ; associé, 403 PAS_LE_PDG ; rien n'est écrit, et l'aperçu dit allowed faux")
    fun prestigeRefusalsWriteNothing() = withCompany { joined ->
        val id = joined.companyId
        addPartner(id, OTHER)
        val broken = ruinedFactory()
        putBrokenFactory(id, broken, level = 12)
        val earned = Math.nextDown(engine.prestigeThreshold(1))
        setDonarEarned(id, earned)
        collectedNow(id)

        val short = assertRefused(prestige(id), RefusalCodes.THRESHOLD_NOT_REACHED)
        assertTrue(checkNotNull(short.missing) > 0.0, "le manque est dit")
        assertNoPrestigeWritten(id, earned, broken)

        val partner = prestige(id, n = 2, actor = OTHER)
        assertEquals(HttpStatusCode.Forbidden, partner.status)
        assertEquals(RefusalCodes.NOT_THE_CEO, SiloooJson.decodeFromString(RefusedBody.serializer(), partner.bodyAsText()).reason)
        assertNoPrestigeWritten(id, earned, broken)

        val preview = previewOf(id)
        assertEquals(false, preview.allowed)
        assertEquals(0L, preview.lingotsTotal)
        assertNoPrestigeWritten(id, earned, broken)
    }

    @Test
    @DisplayName("prestige : si la dernière écriture échoue, tout est annulé (course, Donar, usines, incidents, Lingots, journal, historique, événements)")
    fun prestigeRollsBackAsOneTransaction() = withCompany(FailingPublish()) { joined ->
        val id = joined.companyId
        val broken = ruinedFactory()
        putBrokenFactory(id, broken, level = 12)
        val earned = engine.prestigeThreshold(1) * 8
        setDonarEarned(id, earned)
        setDonar(id, 99.0)
        collectedNow(id)
        val before = lastCollectAt(id)
        val lingotsBefore = lingotsOf(KR)

        val response = prestige(id)

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertNoPrestigeWritten(id, earned, broken)
        assertEquals(99.0, donar(id))
        assertEquals(before, lastCollectAt(id))
        assertEquals(lingotsBefore, lingotsOf(KR), "aucun Lingot n'est resté versé")
    }

    @Test
    @DisplayName("des prestiges simultanés ne versent les Lingots qu'une fois : un seul passe, les autres reçoivent CONFLIT_REESSAYER ou SEUIL_NON_ATTEINT")
    fun parallelPrestigesPayOnce() = withCompany { joined ->
        val id = joined.companyId
        val earned = engine.prestigeThreshold(1) * 8
        setDonarEarned(id, earned)
        collectedNow(id)
        val total = engine.prestigeLingots(1, earned)
        val parallel = 4

        val responses = coroutineScope {
            val go = CompletableDeferred<Unit>()
            val jobs = (1..parallel).map { n -> async(Dispatchers.IO) { go.await(); prestige(id, n = n) } }
            go.complete(Unit)
            jobs.awaitAll()
        }

        assertEquals(1, responses.count { it.status == HttpStatusCode.OK }, "un seul prestige passe")
        val refusals = responses.filter { it.status != HttpStatusCode.OK }.map { response ->
            assertEquals(HttpStatusCode.Conflict, response.status)
            SiloooJson.decodeFromString(RefusedBody.serializer(), response.bodyAsText()).reason
        }
        assertTrue(
            refusals.all { it == RefusalCodes.CONFLICT_RETRY || it == RefusalCodes.THRESHOLD_NOT_REACHED },
            "les perdants reçoivent CONFLIT_REESSAYER ou SEUIL_NON_ATTEINT : $refusals",
        )
        assertEquals(2, courseNumber(id))
        assertEquals(1L, version(id))
        assertEquals(total, lingotsOf(KR), "les Lingots ne sont versés qu'une fois")
        assertEquals(1, prestigeLedger().size)
        assertEquals(1L, MariaDbSupport.count("prestige_history"))
        assertEquals(1L, eventCount())
    }
}
