package com.silooo.core.city

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.CompanyState
import com.silooo.core.api.CompanyUpdated
import com.silooo.core.api.Idempotency
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.RefusedBody
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.Currency
import com.silooo.core.db.InsufficientBalance
import com.silooo.core.db.MariaDbSupport
import com.silooo.core.db.Xuid
import com.silooo.core.db.creditBalance
import com.silooo.core.economy.CompanyStore
import com.silooo.core.economy.DefinitionsBootstrap
import com.silooo.core.economy.EconomyEngine
import com.silooo.core.economy.EngineFigures
import com.silooo.core.economy.EngineFixture.Companion.KR
import com.silooo.core.economy.EngineFixture.Companion.OTHER
import com.silooo.core.economy.EngineFixture.Companion.START
import com.silooo.core.economy.EngineFixture.Companion.hours
import com.silooo.core.economy.JdbcCompanyStore
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
import java.sql.SQLException
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La ville sur la vraie MariaDB : `JdbcCityStore` ligne à ligne (zones, bâtiments, clé unique de V6, Lingots et journal), puis
 * `buyZone`, `placeBuilding` et `upgradeBuilding` par HTTP avec le vrai moteur, le vrai dépôt SQL et la vraie idempotence. Ce que les
 * tests de route (`CityRoutesTest`) ne peuvent pas voir : ce qui arrive vraiment dans `city_zones`, `city_buildings`,
 * `player_balances`, `currency_ledger`, `companies` (`mairie_level`, `version`) et `game_events`, dans une seule transaction, et ce
 * que donne une concurrence réelle. Ignoré sans Docker (voir [MariaDbSupport]) : la CI les rejoue.
 *
 * Les chiffres attendus (prix, niveaux, parcelles) sortent de [CityRules] et de `definitions/`, jamais d'un littéral recopié.
 */
class CitySqlTest {
    private val database get() = MariaDbSupport.database
    private val definitions = TestDefinitions.loaded
    private val rules = CityRules(definitions)
    private val engine = EconomyEngine(definitions)
    private val store = JdbcSessionStore()
    private val clock = MutableClock(START)
    private val presence = ServerPresence(clock = clock)
    private val jdbc = JdbcCityStore()
    private val key = "cle-de-test"

    private val starter = definitions.economy.prestige.courseStart.factoryId
    private val second = engine.factoryIds().first { it != starter }

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    // --- outils

    /** Le coin de la parcelle numéro [index] de la zone [zoneIndex], sur la grille des parcelles. */
    private fun plot(zoneIndex: Int, index: Int = 0): Pair<Int, Int> {
        val zone = rules.zone(zoneIndex)
        val zoneBlocks = definitions.city.zones.sizeChunks * definitions.city.grid.blocksPerChunk
        val perSide = zoneBlocks / rules.plotBlocks
        return (zone.column * zoneBlocks + (index % perSide) * rules.plotBlocks) to (zone.row * zoneBlocks + (index / perSide) * rules.plotBlocks)
    }

    private fun lingots(xuid: Xuid): Long = MariaDbSupport.long("SELECT lingots FROM player_balances WHERE xuid = ?", xuid.text)

    /** Donne [amount] Lingots à [xuid] par le vrai journal : la ligne de solde est créée si elle manque. */
    private fun grant(xuid: Xuid, amount: Long) {
        MariaDbSupport.raw { creditBalance(it, xuid, Currency.LINGOTS, amount, "test", "depart") }
    }

    private fun version(companyId: Long) = MariaDbSupport.long("SELECT version FROM companies WHERE id = ?", companyId)

    private fun mairieLevel(companyId: Long) = MariaDbSupport.long("SELECT mairie_level FROM companies WHERE id = ?", companyId)

    /** Les zones de l'entreprise : `zone|acheteur|Lingots payés`. */
    private fun zoneRows(companyId: Long): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(zone_index, '|', bought_by, '|', lingots_paid) FROM city_zones WHERE company_id = ? ORDER BY zone_index",
        companyId,
    )

    /** Les bâtiments de l'entreprise : `id|type|niveau|zone|x|z|rotation`. */
    private fun buildingRows(companyId: Long): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(id, '|', building_type, '|', level, '|', zone_index, '|', x, '|', z, '|', rotation) FROM city_buildings WHERE company_id = ? ORDER BY id",
        companyId,
    )

    /** Les dépenses de la ville dans le journal des monnaies : `xuid|variation|solde après|motif|référence`. */
    private fun ledgerRows(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(xuid, '|', delta, '|', balance, '|', reason, '|', IFNULL(ref_id, '-')) FROM currency_ledger " +
            "WHERE reason IN ('zone', 'batiment', 'amelioration') ORDER BY id",
    )

    /** Les événements de la ville : `xuid|type|action`. */
    private fun events(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(IFNULL(CAST(xuid AS CHAR), '-'), '|', kind, '|', JSON_VALUE(payload, '\$.action')) FROM game_events " +
            "WHERE JSON_VALUE(payload, '\$.action') IN ('buyZone', 'placeBuilding', 'upgradeBuilding') ORDER BY id",
    )

    // --- JdbcCityStore

    @Test
    @DisplayName("insertBuilding, findBuilding, loadBuildings et setBuildingLevel : le bâtiment écrit est relu tel quel, par identifiant croissant, et seulement dans son entreprise")
    fun buildingsRoundTrip() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val b = MariaDbSupport.insertCompany("B", 1)
        val (x0, z0) = plot(1, 0)
        val (x1, z1) = plot(1, 1)
        val ids = MariaDbSupport.raw { c ->
            listOf(
                jdbc.insertBuilding(c, a, "maison", 1, 1, x0, z0, 90),
                jdbc.insertBuilding(c, a, "route", 1, 1, x1, z1, 0),
                jdbc.insertBuilding(c, b, "maison", 1, 1, x0, z0, 270),
            )
        }

        assertTrue(ids[0] < ids[1] && ids[1] < ids[2], "des identifiants qui croissent : $ids")
        val house = MariaDbSupport.raw { jdbc.findBuilding(it, a, ids[0]) }!!
        assertEquals(CityBuildingRecord(ids[0], "maison", 1, 1, x0, z0, 90), house)
        assertNull(MariaDbSupport.raw { jdbc.findBuilding(it, a, ids[2]) }, "le bâtiment d'une autre entreprise est introuvable")
        assertNull(MariaDbSupport.raw { jdbc.findBuilding(it, a, 987654L) })
        assertEquals(listOf(ids[0], ids[1]), MariaDbSupport.raw { jdbc.loadBuildings(it, a) }.map { it.id })
        assertEquals(listOf(ids[2]), MariaDbSupport.raw { jdbc.loadBuildings(it, b) }.map { it.id })

        MariaDbSupport.raw { jdbc.setBuildingLevel(it, a, ids[0], 2) }
        assertEquals(2, MariaDbSupport.raw { jdbc.findBuilding(it, a, ids[0]) }!!.level)
        assertEquals(1, MariaDbSupport.raw { jdbc.findBuilding(it, b, ids[2]) }!!.level, "l'autre entreprise n'a pas bougé")
    }

    @Test
    @DisplayName("setBuildingLevel : un bâtiment absent ou d'une autre entreprise est une erreur de programmation, rien n'est touché")
    fun setLevelOnlyTouchesTheCompanysBuilding() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val b = MariaDbSupport.insertCompany("B", 1)
        val (x, z) = plot(1, 0)
        val theirs = MariaDbSupport.raw { jdbc.insertBuilding(it, b, "maison", 1, 1, x, z, 0) }

        assertThrows(IllegalStateException::class.java) { MariaDbSupport.raw { jdbc.setBuildingLevel(it, a, theirs, 2) } }
        assertThrows(IllegalStateException::class.java) { MariaDbSupport.raw { jdbc.setBuildingLevel(it, a, 987654L, 2) } }
        assertEquals(1, MariaDbSupport.raw { jdbc.findBuilding(it, b, theirs) }!!.level)
    }

    @Test
    @DisplayName("V6 : une parcelle n'a qu'un bâtiment par entreprise, même d'un autre type ; une autre parcelle ou une autre entreprise le permet")
    fun aPlotHoldsOneBuilding() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val b = MariaDbSupport.insertCompany("B", 1)
        val (x, z) = plot(1, 0)
        val (nextX, nextZ) = plot(1, 1)
        MariaDbSupport.raw { jdbc.insertBuilding(it, a, "maison", 1, 1, x, z, 0) }

        assertThrows(SQLException::class.java) { MariaDbSupport.raw { jdbc.insertBuilding(it, a, "route", 1, 1, x, z, 0) } }
        assertThrows(SQLException::class.java) { MariaDbSupport.raw { jdbc.insertBuilding(it, a, "maison", 2, 1, x, z, 90) } }
        MariaDbSupport.raw { jdbc.insertBuilding(it, a, "maison", 1, 1, nextX, nextZ, 0) }
        MariaDbSupport.raw { jdbc.insertBuilding(it, b, "maison", 1, 1, x, z, 0) }

        assertEquals(2L, MariaDbSupport.long("SELECT COUNT(*) FROM city_buildings WHERE company_id = ?", a))
        assertEquals(1L, MariaDbSupport.long("SELECT COUNT(*) FROM city_buildings WHERE company_id = ?", b))
    }

    @Test
    @DisplayName("insertZone et loadZones : les zones de l'entreprise sont relues par numéro croissant, avec l'acheteur et le prix payé ; une zone ne s'achète qu'une fois")
    fun zonesRoundTrip() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val b = MariaDbSupport.insertCompany("B", 1)
        MariaDbSupport.raw { c ->
            jdbc.insertZone(c, a, 2, OTHER, 10)
            jdbc.insertZone(c, a, 1, KR, 5)
            jdbc.insertZone(c, b, 1, KR, 5)
        }

        assertEquals(listOf(1, 2), MariaDbSupport.raw { store.loadZones(it, a) })
        assertEquals(listOf(1), MariaDbSupport.raw { store.loadZones(it, b) })
        assertEquals(listOf("1|${KR.text}|5", "2|${OTHER.text}|10"), zoneRows(a))
        assertThrows(SQLException::class.java) { MariaDbSupport.raw { jdbc.insertZone(it, a, 1, OTHER, 5) } }
        assertEquals(2, zoneRows(a).size)
    }

    @Test
    @DisplayName("spendLingots : retire les Lingots du solde du joueur et écrit une ligne de journal (motif, référence) ; un solde trop juste lève InsufficientBalance sans rien écrire")
    fun spendLingotsWritesTheLedger() {
        MariaDbSupport.insertPlayer(KR.text)
        grant(KR, 100)

        val after = MariaDbSupport.raw { jdbc.spendLingots(it, KR, 30, "zone", "12/3") }

        assertEquals(70L, after)
        assertEquals(70L, lingots(KR))
        assertEquals(listOf("${KR.text}|-30|70|zone|12/3"), ledgerRows())

        val failure = assertThrows(InsufficientBalance::class.java) { MariaDbSupport.raw { jdbc.spendLingots(it, KR, 71, "batiment", "12/4") } }
        assertEquals(1L, failure.missing)
        assertEquals(70L, lingots(KR))
        assertEquals(1, ledgerRows().size)
    }

    // --- le service et l'entreprise de départ

    private fun dependencies(companyStore: CompanyStore): Dependencies = Dependencies(
        database = database,
        definitions = definitions,
        apiKey = key,
        economy = engine,
        idempotency = Idempotency(database),
        sessionStore = store,
        clock = clock,
        economyFigures = EngineFigures(engine, store, clock, CityFigures(rules, store, jdbc)),
        companyBootstrap = DefinitionsBootstrap(engine, definitions),
        companyStore = companyStore,
        cityStore = jdbc,
        serverPresence = presence,
    )

    /**
     * Crée l'entreprise de [KR] par le vrai `join`, lui donne [lingots] Lingots, une deuxième usine active (la première zone demande
     * plusieurs usines actives) et remet son dernier calcul de production à l'heure de l'horloge du test (`join` le pose à l'heure de
     * la base : sans cela, de la production en attente fausserait les comptes).
     */
    private fun startCompany(lingots: Long): Long {
        val joined = runBlocking {
            SessionService(database, store, EngineFigures(engine, store, clock), DefinitionsBootstrap(engine, definitions)).join(KR, "Kr")
        }
        MariaDbSupport.update(
            "UPDATE companies SET last_collect_at = ? WHERE id = ?",
            LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
            joined.companyId,
        )
        MariaDbSupport.update(
            "INSERT INTO company_factories (company_id, factory_id, state, restore_step, level) VALUES (?, ?, 'ACTIVE', 4, 1)",
            joined.companyId,
            second,
        )
        grant(KR, lingots)
        return joined.companyId
    }

    /** Monte le service sur la base, avec l'entreprise de [KR] déjà créée par [startCompany]. */
    private fun withCompany(lingots: Long = 1_000_000, companyStore: CompanyStore = JdbcCompanyStore(), block: suspend ApplicationTestBuilder.(Long) -> Unit) {
        val id = startCompany(lingots)
        testApplication {
            application { siloooModule(dependencies(companyStore)) }
            block(id)
        }
    }

    /** Un dépôt qui écrit comme le vrai, sauf la publication d'événement : la dernière écriture d'une action échoue. */
    private class FailingPublish(private val real: CompanyStore = JdbcCompanyStore()) : CompanyStore by real {
        override fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long =
            throw IllegalStateException("publication impossible (test)")
    }

    // --- HTTP

    private fun requestId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    private fun action(n: Int = 1, actor: Xuid = KR, extra: String = ""): String = """{"requestId":"${requestId(n)}","actor":"${actor.text}"$extra}"""

    private suspend fun ApplicationTestBuilder.post(path: String, body: String): HttpResponse =
        client.post(path) {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.buyZone(companyId: Long, zoneIndex: Int, n: Int = 1, actor: Xuid = KR) =
        post("/v1/companies/$companyId/city/zones/$zoneIndex", action(n, actor))

    private suspend fun ApplicationTestBuilder.place(companyId: Long, type: String, x: Int, z: Int, rotation: Int = 0, n: Int = 1, actor: Xuid = KR) =
        post("/v1/companies/$companyId/city/buildings", action(n, actor, ""","buildingType":"$type","x":$x,"z":$z,"rotation":$rotation"""))

    private suspend fun ApplicationTestBuilder.upgrade(companyId: Long, buildingId: Long, n: Int = 1, actor: Xuid = KR) =
        post("/v1/companies/$companyId/city/buildings/$buildingId", action(n, actor))

    private suspend fun updated(response: HttpResponse): CompanyUpdated {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        return SiloooJson.decodeFromString(CompanyUpdated.serializer(), body)
    }

    private suspend fun ApplicationTestBuilder.stateOf(companyId: Long): CompanyState {
        val response = client.get("/v1/companies/$companyId") { header(SERVER_KEY_HEADER, key) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return SiloooJson.decodeFromString(CompanyState.serializer(), response.bodyAsText())
    }

    private suspend fun refusalOf(response: HttpResponse, status: HttpStatusCode = HttpStatusCode.Conflict): String {
        assertEquals(status, response.status, response.bodyAsText())
        return SiloooJson.decodeFromString(RefusedBody.serializer(), response.bodyAsText()).reason
    }

    /** Un associé de l'entreprise [companyId] : son premier `join` lui crée une entreprise, qu'on remplace par celle-ci. */
    private suspend fun ApplicationTestBuilder.addPartner(companyId: Long, partner: Xuid) {
        val response = post("/v1/session/join", """{"xuid":"${partner.text}","gamertag":"Associe"}""")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        MariaDbSupport.update("UPDATE company_members SET company_id = ?, role = 'ASSOCIE' WHERE xuid = ?", companyId, partner.text)
    }

    // --- buyZone

    @Test
    @DisplayName("buyZone : la première zone, la mairie niveau 1 sur son emplacement de départ, le retrait de Lingots, l'événement ANNONCE et la version sont écrits ensemble")
    fun buyFirstZoneWritesEverythingTogether() = withCompany { id ->
        val price = rules.zonePrice(1)
        val start = rules.startPlot
        val lingotsBefore = lingots(KR)

        val result = updated(buyZone(id, 1))

        assertEquals(lingotsBefore - price, lingots(KR))
        assertEquals(lingotsBefore - price, result.balances.lingots)
        assertEquals(listOf("1|${KR.text}|$price"), zoneRows(id))
        val hall = buildingRows(id).single()!!.split('|')
        assertEquals(listOf(rules.townHall.id, "1", "1", "${start.x}", "${start.z}", "${start.rotation}"), hall.drop(1))
        assertEquals(listOf("${KR.text}|-$price|${lingotsBefore - price}|zone|$id/1"), ledgerRows())
        assertEquals(listOf("${KR.text}|ANNONCE|buyZone"), events())
        assertEquals(1L, version(id), "une seule écriture de l'entreprise")
        assertEquals(1L, mairieLevel(id))
        assertEquals(listOf(1), result.state.zonesOwned)
        assertEquals(1, result.state.city!!.buildings.size)
    }

    @Test
    @DisplayName("buyZone : les refus (PAS_ASSEZ_DE_LINGOTS, ZONE_VERROUILLEE, DEJA_POSSEDE) n'écrivent rien en base")
    fun buyZoneRefusalsWriteNothing() = withCompany(lingots = 1) { id ->
        assertEquals(RefusalCodes.NOT_ENOUGH_LINGOTS, refusalOf(buyZone(id, 1, n = 1)))
        assertEquals(RefusalCodes.ZONE_LOCKED, refusalOf(buyZone(id, 2, n = 2)))
        grant(KR, rules.zonePrice(1))
        updated(buyZone(id, 1, n = 3))
        val rows = buildingRows(id)
        val ledger = ledgerRows()
        val versionAfter = version(id)

        assertEquals(RefusalCodes.ALREADY_OWNED, refusalOf(buyZone(id, 1, n = 4)))
        assertEquals(RefusalCodes.ZONE_LOCKED, refusalOf(buyZone(id, 2, n = 5)), "la mairie du niveau 1 ne suffit pas")
        assertEquals(listOf("1|${KR.text}|${rules.zonePrice(1)}"), zoneRows(id))
        assertEquals(rows, buildingRows(id))
        assertEquals(ledger, ledgerRows())
        assertEquals(versionAfter, version(id))
        assertEquals(1, events().size)
    }

    @Test
    @DisplayName("buyZone : si la publication de l'événement échoue, tout est annulé (Lingots, journal, zone, mairie, version)")
    fun buyZoneRollsBackAsOne() = withCompany(companyStore = FailingPublish()) { id ->
        val lingotsBefore = lingots(KR)

        val response = buyZone(id, 1)

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(lingotsBefore, lingots(KR))
        assertEquals(emptyList<String?>(), ledgerRows())
        assertEquals(emptyList<String?>(), zoneRows(id))
        assertEquals(emptyList<String?>(), buildingRows(id))
        assertEquals(0L, version(id))
        assertEquals(emptyList<String?>(), events())
    }

    @Test
    @DisplayName("buyZone rejoué avec le même requestId : la même réponse, une seule zone, un seul retrait de Lingots")
    fun buyZoneReplayWritesOnce() = withCompany { id ->
        val first = buyZone(id, 1, n = 5).bodyAsText()
        val lingotsAfter = lingots(KR)
        clock.advance(hours(1.0))

        val second = buyZone(id, 1, n = 5)

        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(first, second.bodyAsText())
        assertEquals(1, zoneRows(id).size)
        assertEquals(1, ledgerRows().size)
        assertEquals(lingotsAfter, lingots(KR))
        assertEquals(1, events().size)
    }

    @Test
    @DisplayName("Un associé achète une zone avec SES Lingots : le solde du PDG ne bouge pas, la zone porte le XUID de l'associé")
    fun associatePaysFromHisOwnBalance() = withCompany { id ->
        addPartner(id, OTHER)
        val price = rules.zonePrice(1)
        grant(OTHER, price + 3)
        val krBefore = lingots(KR)

        val result = updated(buyZone(id, 1, actor = OTHER))

        assertEquals(3L, lingots(OTHER))
        assertEquals(3L, result.balances.lingots)
        assertEquals(krBefore, lingots(KR))
        assertEquals(listOf("1|${OTHER.text}|$price"), zoneRows(id))
        assertEquals(listOf("${OTHER.text}|-$price|3|zone|$id/1"), ledgerRows())
        assertEquals(listOf("${OTHER.text}|ANNONCE|buyZone"), events())
    }

    // --- placeBuilding

    @Test
    @DisplayName("placeBuilding : le bâtiment, le retrait de Lingots avec son identifiant en référence, l'événement et la version sont écrits ensemble ; la ville le montre")
    fun placeWritesEverythingTogether() = withCompany { id ->
        updated(buyZone(id, 1, n = 1))
        val house = rules.building("maison")!!
        val (x, z) = plot(1, 0)
        val lingotsBefore = lingots(KR)
        val versionBefore = version(id)

        val result = updated(place(id, "maison", x, z, rotation = 180, n = 2))

        val placed = buildingRows(id).map { it!!.split('|') }.single { it[1] == "maison" }
        assertEquals(listOf("maison", "1", "1", "$x", "$z", "180"), placed.drop(1))
        assertEquals(lingotsBefore - house.level(1).priceLingots, lingots(KR))
        assertEquals("${KR.text}|-${house.level(1).priceLingots}|${lingotsBefore - house.level(1).priceLingots}|batiment|$id/${placed[0]}", ledgerRows().last())
        assertEquals(listOf("${KR.text}|ANNONCE|buyZone", "${KR.text}|ANNONCE|placeBuilding"), events())
        assertEquals(versionBefore + 1, version(id))

        val shown = stateOf(id).city!!
        assertEquals(placed[0].toLong(), shown.buildings.single { it.buildingType == "maison" }.buildingId)
        assertEquals(Math.floor(house.level(1).bonus!!).toInt(), shown.bonuses.housingPlaces)
        assertEquals(shown, result.state.city)
    }

    @Test
    @DisplayName("placeBuilding : une parcelle prise, une zone pas achetée ou un solde trop juste n'écrivent rien en base")
    fun placeRefusalsWriteNothing() = withCompany { id ->
        updated(buyZone(id, 1, n = 1))
        val start = rules.startPlot
        val (x, z) = plot(1, 0)
        val (otherZoneX, otherZoneZ) = plot(2, 0)
        val rows = buildingRows(id)
        val ledger = ledgerRows()
        val versionBefore = version(id)

        assertEquals(RefusalCodes.SPOT_TAKEN, refusalOf(place(id, "maison", start.x, start.z, n = 2)))
        assertEquals(RefusalCodes.ZONE_LOCKED, refusalOf(place(id, "maison", otherZoneX, otherZoneZ, n = 3)))
        MariaDbSupport.update("UPDATE player_balances SET lingots = 0 WHERE xuid = ?", KR.text)
        assertEquals(RefusalCodes.NOT_ENOUGH_LINGOTS, refusalOf(place(id, "maison", x, z, n = 4)))

        assertEquals(rows, buildingRows(id))
        assertEquals(ledger, ledgerRows())
        assertEquals(versionBefore, version(id))
        assertEquals(1, events().size)
    }

    @Test
    @DisplayName("Des poses simultanées sur la même parcelle : une seule passe, un seul retrait, un seul bâtiment, les autres sont refusées")
    fun parallelPlacesOnTheSamePlot() = withCompany { id ->
        updated(buyZone(id, 1, n = 1))
        val (x, z) = plot(1, 0)
        val lingotsBefore = lingots(KR)
        val price = rules.building("maison")!!.level(1).priceLingots

        val responses = coroutineScope {
            val go = CompletableDeferred<Unit>()
            val jobs = (1..4).map { n -> async(Dispatchers.IO) { go.await(); place(id, "maison", x, z, n = 10 + n) } }
            go.complete(Unit)
            jobs.awaitAll()
        }

        assertEquals(1, responses.count { it.status == HttpStatusCode.OK }, "exactement une pose passe")
        for (response in responses.filter { it.status != HttpStatusCode.OK }) {
            val reason = refusalOf(response)
            assertTrue(reason == RefusalCodes.CONFLICT_RETRY || reason == RefusalCodes.SPOT_TAKEN, "refus inattendu : $reason")
        }
        assertEquals(1, buildingRows(id).count { it!!.contains("|maison|") })
        assertEquals(lingotsBefore - price, lingots(KR), "un seul retrait")
        assertEquals(2, ledgerRows().size)
    }

    // --- upgradeBuilding

    @Test
    @DisplayName("upgradeBuilding : le bâtiment passe au niveau suivant, le retrait de Lingots porte le niveau en référence, l'événement part")
    fun upgradeWritesEverythingTogether() = withCompany { id ->
        updated(buyZone(id, 1, n = 1))
        val house = rules.building("maison")!!
        val (x, z) = plot(1, 0)
        MariaDbSupport.update("UPDATE companies SET mairie_level = ? WHERE id = ?", house.level(2).townHallLevelRequired, id)
        val houseId = updated(place(id, "maison", x, z, n = 2)).state.city!!.buildings.single { it.buildingType == "maison" }.buildingId
        val lingotsBefore = lingots(KR)
        val versionBefore = version(id)

        val result = updated(upgrade(id, houseId, n = 3))

        assertEquals(2, result.state.city!!.buildings.single { it.buildingId == houseId }.level)
        assertEquals(2, MariaDbSupport.raw { jdbc.findBuilding(it, id, houseId) }!!.level)
        assertEquals(lingotsBefore - house.level(2).priceLingots, lingots(KR))
        assertEquals("${KR.text}|-${house.level(2).priceLingots}|${lingotsBefore - house.level(2).priceLingots}|amelioration|$id/$houseId/2", ledgerRows().last())
        assertEquals("${KR.text}|ANNONCE|upgradeBuilding", events().last())
        assertEquals(versionBefore + 1, version(id))
        assertEquals(house.level(2).townHallLevelRequired.toLong(), mairieLevel(id), "une maison ne touche pas au niveau de la mairie")
    }

    @Test
    @DisplayName("upgradeBuilding sur la mairie : companies.mairie_level monte dans la même écriture que la version, la ligne du bâtiment suit, la zone suivante s'ouvre")
    fun upgradeTownHallWritesTheCompanyRow() = withCompany { id ->
        updated(buyZone(id, 1, n = 1))
        val hallId = MariaDbSupport.long("SELECT id FROM city_buildings WHERE company_id = ?", id)
        val needed = rules.zone(2).townHallLevelRequired
        assertTrue(needed >= 2)
        val versionBefore = version(id)

        for (level in 2..needed) {
            val result = updated(upgrade(id, hallId, n = 10 + level))
            assertEquals(level, result.state.city!!.mairieLevel)
            assertEquals(level.toLong(), mairieLevel(id))
            assertEquals(level.toString(), buildingRows(id).single()!!.split('|')[2])
        }
        assertEquals(versionBefore + (needed - 1), version(id), "une écriture de l'entreprise par niveau")
        assertEquals(needed, stateOf(id).city!!.mairieLevel)

        updated(buyZone(id, 2, n = 2))
        assertEquals(listOf(1, 2), MariaDbSupport.raw { store.loadZones(it, id) })
    }

    @Test
    @DisplayName("upgradeBuilding : les refus (DEJA_POSSEDE au dernier niveau, ZONE_VERROUILLEE, PAS_ASSEZ_DE_LINGOTS) n'écrivent rien en base")
    fun upgradeRefusalsWriteNothing() = withCompany { id ->
        updated(buyZone(id, 1, n = 1))
        val house = rules.building("maison")!!
        val (x, z) = plot(1, 0)
        val houseId = updated(place(id, "maison", x, z, n = 2)).state.city!!.buildings.single { it.buildingType == "maison" }.buildingId
        val rows = buildingRows(id)
        val ledger = ledgerRows()
        val versionBefore = version(id)

        assertEquals(RefusalCodes.ZONE_LOCKED, refusalOf(upgrade(id, houseId, n = 3)), "la mairie du niveau 1 ne permet pas le niveau 2 de la maison")
        MariaDbSupport.update("UPDATE companies SET mairie_level = 16 WHERE id = ?", id)
        MariaDbSupport.update("UPDATE player_balances SET lingots = 0 WHERE xuid = ?", KR.text)
        assertEquals(RefusalCodes.NOT_ENOUGH_LINGOTS, refusalOf(upgrade(id, houseId, n = 4)))
        MariaDbSupport.update("UPDATE city_buildings SET level = ? WHERE id = ?", house.maxLevel(), houseId)
        assertEquals(RefusalCodes.ALREADY_OWNED, refusalOf(upgrade(id, houseId, n = 5)))

        assertEquals(house.maxLevel().toString(), buildingRows(id).first { it!!.startsWith("$houseId|") }!!.split('|')[2])
        assertEquals(rows.size, buildingRows(id).size)
        assertEquals(ledger, ledgerRows())
        assertEquals(versionBefore, version(id), "aucun refus n'écrit l'entreprise")
        assertEquals(0L, lingots(KR))
    }

    @Test
    @DisplayName("upgradeBuilding sur la mairie : si la publication de l'événement échoue, tout est annulé (Lingots, journal, niveau de la mairie, version)")
    fun upgradeRollsBackAsOne() = withCompany(companyStore = FailingPublish()) { id ->
        val start = rules.startPlot
        MariaDbSupport.update("INSERT INTO city_zones (company_id, zone_index, bought_by, lingots_paid) VALUES (?, 1, ?, 0)", id, KR.text)
        MariaDbSupport.update(
            "INSERT INTO city_buildings (company_id, building_type, level, zone_index, x, z, rotation) VALUES (?, ?, 1, 1, ?, ?, ?)",
            id,
            rules.townHall.id,
            start.x,
            start.z,
            start.rotation,
        )
        val hallId = MariaDbSupport.long("SELECT id FROM city_buildings WHERE company_id = ?", id)
        val lingotsBefore = lingots(KR)

        val response = upgrade(id, hallId)

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(lingotsBefore, lingots(KR))
        assertEquals(1L, mairieLevel(id))
        assertEquals("1", buildingRows(id).single()!!.split('|')[2])
        assertEquals(emptyList<String?>(), ledgerRows())
        assertEquals(0L, version(id))
    }
}
