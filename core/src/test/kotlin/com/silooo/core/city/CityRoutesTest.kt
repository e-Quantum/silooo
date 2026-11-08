package com.silooo.core.city

import com.silooo.core.Dependencies
import com.silooo.core.api.CompanyUpdated
import com.silooo.core.api.ErrorBody
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.FactoryState
import com.silooo.core.api.Idempotency
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.RefusedBody
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.contract.OpenApi
import com.silooo.core.db.InMemoryApiRequestStore
import com.silooo.core.db.RecordingDatabase
import com.silooo.core.db.Xuid
import com.silooo.core.economy.EngineFigures
import com.silooo.core.economy.EngineFixture
import com.silooo.core.economy.EngineFixture.Companion.hours
import com.silooo.core.economy.InMemoryCompanyStore
import com.silooo.core.events.GameEventKind
import com.silooo.core.mission.InMemoryMissionStore
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.Regime
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
import kotlin.math.abs
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `buyZone`, `placeBuilding` et `upgradeBuilding` avec des faux (pas de base) : le moteur d'économie réel sur les `definitions/` du
 * dépôt, un faux dépôt de la ville ([InMemoryCityStore]), une horloge que le test avance, et une connexion qui note les `UPDATE
 * companies` du verrou optimiste. Les chiffres attendus (prix, niveaux, zones, bonus) sortent de [CityRules] et de `definitions/`,
 * jamais d'un littéral. La connexion ne change pas le faux dépôt de l'entreprise : le Donar d'après se lit dans la réponse et dans
 * l'`UPDATE` noté. Le SQL est dans `CitySqlTest`, le contrat dans `ContractTest`.
 */
class CityRoutesTest {
    private val key = "cle-de-test"
    private val kr = EngineFixture.KR
    private val other = EngineFixture.OTHER
    private val stranger = EngineFixture.STRANGER

    private class Harness(
        val fixture: EngineFixture,
        val companies: InMemoryCompanyStore,
        val city: InMemoryCityStore,
    ) {
        val definitions get() = fixture.definitions
        val engine get() = fixture.engine
        val recording get() = fixture.recording
        val rules = CityRules(fixture.definitions)
        private val cityDefinitions get() = definitions.city

        fun companyUpdates() = recording.statements.filter { it.sql.startsWith("UPDATE companies") }

        fun lingots(xuid: Xuid): Long = fixture.store.balances.getValue(xuid).lingots

        /** Le coin de la parcelle numéro [index] de la zone [zoneIndex] : en ligne depuis le coin de la zone, sur la grille des parcelles. */
        fun plot(zoneIndex: Int, index: Int = 0): Pair<Int, Int> {
            val zone = rules.zone(zoneIndex)
            val zoneBlocks = cityDefinitions.zones.sizeChunks * cityDefinitions.grid.blocksPerChunk
            val perSide = zoneBlocks / rules.plotBlocks
            return (zone.column * zoneBlocks + (index % perSide) * rules.plotBlocks) to (zone.row * zoneBlocks + (index / perSide) * rules.plotBlocks)
        }

        /**
         * Une entreprise neuve du PDG [EngineFixture.KR] : [lingots] Lingots, [activeFactories] usines actives (celle de départ comprise),
         * [awayHours] heures de production en attente. Aucune zone, aucun bâtiment, mairie niveau 1.
         */
        fun fresh(lingots: Long, activeFactories: Int = 2, awayHours: Double = 0.0): CompanyRecord {
            val company = fixture.newCompany(EngineFixture.KR, lingots)
            fixture.otherFactories().take(activeFactories - 1).forEach { id ->
                fixture.putFactory(company.id, FactoryRecord(id, FactoryState.ACTIVE, 0, 5, null))
            }
            fixture.clock.advance(hours(awayHours))
            return company
        }

        /**
         * Une entreprise qui a déjà ses [zones], sa mairie au niveau [mairieLevel] posée sur son emplacement de départ, et [lingots]
         * Lingots : le point de départ de `placeBuilding` et `upgradeBuilding`.
         */
        fun settled(lingots: Long, zones: List<Int> = listOf(1), mairieLevel: Int = 1, awayHours: Double = 0.0): CompanyRecord {
            val company = fresh(lingots, awayHours = awayHours)
            city.seedZones(company.id, zones)
            val start = rules.startPlot
            city.seedBuilding(company.id, rules.townHall.id, mairieLevel, rules.zoneOfPlot(start.x, start.z)!!, start.x, start.z, start.rotation)
            return fixture.update(company.copy(mairieLevel = mairieLevel))
        }
    }

    private fun withApp(block: suspend ApplicationTestBuilder.(Harness) -> Unit) = testApplication {
        val fixture = EngineFixture()
        val database = RecordingDatabase(fixture.recording)
        val companies = InMemoryCompanyStore(fixture.store)
        val city = InMemoryCityStore(fixture.store)
        val figures = EngineFigures(fixture.engine, fixture.store, fixture.clock, CityFigures(CityRules(fixture.definitions), fixture.store, city))
        application {
            siloooModule(
                Dependencies(
                    database = database,
                    definitions = fixture.definitions,
                    apiKey = key,
                    economy = fixture.engine,
                    idempotency = Idempotency(database, InMemoryApiRequestStore(EngineFixture.START)),
                    sessionStore = fixture.store,
                    clock = fixture.clock,
                    economyFigures = figures,
                    companyBootstrap = fixture.bootstrap,
                    companyStore = companies,
                    cityStore = city,
                    missionStore = InMemoryMissionStore(fixture.store),
                ),
            )
        }
        block(Harness(fixture, companies, city))
    }

    // --- outils

    private fun requestId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    private fun action(n: Int = 1, actor: String = kr.text, extra: String = ""): String =
        """{"requestId":"${requestId(n)}","actor":"$actor"$extra}"""

    private suspend fun ApplicationTestBuilder.post(path: String, body: String): HttpResponse =
        client.post(path) {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.buyZone(companyId: Any, zoneIndex: Any, n: Int = 1, actor: String = kr.text) =
        post("/v1/companies/$companyId/city/zones/$zoneIndex", action(n, actor))

    private suspend fun ApplicationTestBuilder.place(
        companyId: Any,
        type: String,
        x: Any,
        z: Any,
        rotation: Any = 0,
        n: Int = 1,
        actor: String = kr.text,
    ) = post(
        "/v1/companies/$companyId/city/buildings",
        action(n, actor, ""","buildingType":"$type","x":$x,"z":$z,"rotation":$rotation"""),
    )

    private suspend fun ApplicationTestBuilder.upgrade(companyId: Any, buildingId: Any, n: Int = 1, actor: String = kr.text) =
        post("/v1/companies/$companyId/city/buildings/$buildingId", action(n, actor))

    private suspend fun updated(response: HttpResponse): CompanyUpdated {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        return SiloooJson.decodeFromString(CompanyUpdated.serializer(), body)
    }

    private suspend fun assertError(response: HttpResponse, what: String = "") {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.BadRequest, response.status, "$what : $body")
        assertEquals(ErrorCodes.BAD_REQUEST, SiloooJson.decodeFromString(ErrorBody.serializer(), body).reason, what)
    }

    private suspend fun assertRefused(response: HttpResponse, reason: String, status: HttpStatusCode = HttpStatusCode.Conflict): RefusedBody {
        val body = response.bodyAsText()
        assertEquals(status, response.status, body)
        val refused = SiloooJson.decodeFromString(RefusedBody.serializer(), body)
        assertEquals(reason, refused.reason)
        return refused
    }

    /** Égal à un arrondi de flottant près : les deux côtés viennent des mêmes formules du moteur. */
    private fun assertClose(expected: Double, actual: Double, what: String = "valeur") {
        assertTrue(abs(expected - actual) <= 1e-9 * maxOf(1.0, abs(expected)), "$what : attendu $expected, reçu $actual")
    }

    /** Rien n'a été écrit : ni entreprise, ni zone, ni bâtiment, ni dépense de Lingots, ni événement. */
    private fun assertNothingWritten(app: Harness) {
        assertEquals(0, app.companyUpdates().size, "aucun UPDATE companies")
        assertTrue(app.city.writes.isEmpty(), "aucune écriture de la ville : ${app.city.writes}")
        assertTrue(app.city.spent.isEmpty(), "aucune dépense de Lingots : ${app.city.spent}")
        assertEquals(0, app.companies.published.size, "aucun événement")
    }

    private fun payload(app: Harness, key: String): String = app.companies.published.single().payload.getValue(key).jsonPrimitive.content

    // --- le contrat

    @Test
    @DisplayName("Le contrat n'a que trois opérations pour la ville, et aucune ne vend, ne tire au sort ni n'échange")
    fun onlyThreeCityOperations() {
        val operations = OpenApi.load().operations.filter { "/city" in it.path }.map { it.id }.toSet()
        assertEquals(setOf("buyZone", "placeBuilding", "upgradeBuilding"), operations)
    }

    // --- buyZone

    @Test
    @DisplayName("buyZone : la première zone se paie en Lingots, pose la mairie niveau 1 sur son emplacement de départ, verse la production d'abord")
    fun buyFirstZone() = withApp { app ->
        val price = app.rules.zonePrice(1)
        val company = app.fresh(lingots = price + 7, awayHours = 3.0)
        val expectedEarned = app.fixture.figures
            .pendingProduction(app.fixture.connection, company, app.fixture.store.loadFactories(app.fixture.connection, company.id), Regime.ONLINE).earned
        assertTrue(expectedEarned > 0.0)

        val result = updated(buyZone(company.id, 1))

        assertEquals(listOf(1), result.state.zonesOwned)
        assertEquals(7L, result.balances.lingots)
        assertEquals(7L, app.lingots(kr))
        assertClose(company.donar + expectedEarned, result.state.donar, "la production d'avant l'achat est versée")
        val update = app.companyUpdates().single()
        assertClose(result.state.donar, update.parameters[1] as Double, "UPDATE donar")

        val city = result.state.city!!
        assertEquals(1, city.mairieLevel)
        val townHall = city.buildings.single()
        assertEquals(app.rules.townHall.id, townHall.buildingType)
        assertEquals(1, townHall.level)
        assertEquals(app.rules.startPlot.x, townHall.x)
        assertEquals(app.rules.startPlot.z, townHall.z)
        assertEquals(app.rules.startPlot.rotation, townHall.rotation)
        assertEquals(1, townHall.zoneIndex)
        assertEquals(2, city.nextZone!!.zoneIndex)
        assertEquals(app.rules.zonePrice(2), city.nextZone.priceLingots)

        assertEquals(listOf(InMemoryCityStore.Spent(kr, price, CityService.REASON_ZONE, "${company.id}/1")), app.city.spent)
        val bought = app.city.writes.filterIsInstance<InMemoryCityStore.Write.ZoneBought>().single()
        assertEquals(InMemoryCityStore.Write.ZoneBought(company.id, 1, kr, price), bought)
        val placed = app.city.writes.filterIsInstance<InMemoryCityStore.Write.BuildingPlaced>().single()
        assertEquals(app.rules.townHall.id, placed.record.buildingType)
        assertEquals(2, app.city.writes.size)

        val event = app.companies.published.single()
        assertEquals(GameEventKind.ANNONCE, event.kind)
        assertEquals(kr, event.xuid)
        assertEquals("buyZone", payload(app, "action"))
        assertEquals(company.id.toString(), payload(app, "companyId"))
        assertEquals("1", payload(app, "zoneIndex"))
        assertEquals(price.toString(), payload(app, "lingots"))
    }

    @Test
    @DisplayName("buyZone : une zone suivante ne pose pas de mairie et ne demande pas d'usines actives, mais le niveau de mairie de ville.json")
    fun buyNextZone() = withApp { app ->
        val needed = app.rules.zone(2).townHallLevelRequired
        val price = app.rules.zonePrice(2)
        val company = app.settled(lingots = price, zones = listOf(1), mairieLevel = needed)

        val result = updated(buyZone(company.id, 2))

        assertEquals(listOf(1, 2), result.state.zonesOwned)
        assertEquals(0L, result.balances.lingots)
        assertEquals(1, app.city.writes.size, "la zone seule : la mairie est déjà là")
        assertEquals(InMemoryCityStore.Write.ZoneBought(company.id, 2, kr, price), app.city.writes.single())
        assertEquals(1, result.state.city!!.buildings.size)
        assertEquals(CityService.ACTION_BUY_ZONE, payload(app, "action"))
    }

    @Test
    @DisplayName("buyZone : pas assez d'usines actives pour la première, une zone hors d'ordre, ou une mairie trop basse : 409 ZONE_VERROUILLEE, rien d'écrit")
    fun buyZoneLocked() = withApp { app ->
        val needed = app.rules.activeFactoriesRequired(1)
        assertTrue(needed >= 2, "la règle de la première zone demande plusieurs usines")
        val poor = app.fresh(lingots = 10_000, activeFactories = needed - 1)
        app.fixture.putFactory(poor.id, FactoryRecord(app.fixture.otherFactories()[5], FactoryState.EN_RUINE, 0, 0, null))
        assertRefused(buyZone(poor.id, 1, n = 1), RefusalCodes.ZONE_LOCKED)
        assertNothingWritten(app)

        assertRefused(buyZone(poor.id, 3, n = 2), RefusalCodes.ZONE_LOCKED)
        assertNothingWritten(app)
        assertEquals(10_000L, app.lingots(kr))
    }

    @Test
    @DisplayName("buyZone : une zone qui n'est pas la suivante, ou une mairie trop basse pour elle : 409 ZONE_VERROUILLEE, rien d'écrit")
    fun buyZoneOutOfOrderOrTownHallTooLow() = withApp { app ->
        assertTrue(app.rules.zone(2).townHallLevelRequired > 1, "la zone 2 demande une mairie plus haute que le niveau 1")
        val company = app.settled(lingots = 10_000, zones = listOf(1), mairieLevel = 1)

        assertRefused(buyZone(company.id, 2, n = 1), RefusalCodes.ZONE_LOCKED)
        assertRefused(buyZone(company.id, 3, n = 2), RefusalCodes.ZONE_LOCKED)
        assertNothingWritten(app)
        assertEquals(10_000L, app.lingots(kr))
    }

    @Test
    @DisplayName("buyZone : une zone déjà à l'entreprise donne 409 DEJA_POSSEDE, rien d'écrit")
    fun buyZoneAlreadyOwned() = withApp { app ->
        val company = app.settled(lingots = 10_000, zones = listOf(1))

        assertRefused(buyZone(company.id, 1), RefusalCodes.ALREADY_OWNED)
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("buyZone : pas assez de Lingots donne 409 PAS_ASSEZ_DE_LINGOTS avec la somme qui manque, rien d'écrit, le Donar du PDG ne paie pas")
    fun buyZoneNotEnoughLingots() = withApp { app ->
        val price = app.rules.zonePrice(1)
        val company = app.fresh(lingots = price - 1, awayHours = 2.0)

        val refused = assertRefused(buyZone(company.id, 1), RefusalCodes.NOT_ENOUGH_LINGOTS)
        assertEquals(1.0, refused.missing)
        assertNothingWritten(app)
        assertEquals(price - 1, app.lingots(kr))
    }

    @Test
    @DisplayName("buyZone : un numéro hors de 1 à 16, une entreprise inconnue, un étranger à l'entreprise donnent 400, rien d'écrit")
    fun buyZoneBadRequests() = withApp { app ->
        val company = app.fresh(lingots = 10_000)

        for (zone in listOf("0", "-1", "17", "abc", "1.5")) assertError(buyZone(company.id, zone, n = 1), "zone $zone")
        for (id in listOf("0", "-1", "abc", "9999")) assertError(buyZone(id, 1, n = 2), "entreprise $id")
        assertError(buyZone(company.id, 1, n = 3, actor = stranger.text), "étranger")
        assertNothingWritten(app)
        assertEquals(10_000L, app.lingots(kr))
    }

    @Test
    @DisplayName("Un associé achète avec SES Lingots : ceux du PDG ne comptent pas, son XUID est dans l'événement")
    fun associatePaysWithHisOwnLingots() = withApp { app ->
        val price = app.rules.zonePrice(1)
        val company = app.fresh(lingots = 1_000_000)
        app.fixture.addMember(company.id, other, lingots = price - 1)

        val refused = assertRefused(buyZone(company.id, 1, n = 1, actor = other.text), RefusalCodes.NOT_ENOUGH_LINGOTS)
        assertEquals(1.0, refused.missing, "ce qui manque à l'associé, pas au PDG")
        assertNothingWritten(app)

        app.fixture.addMember(company.id, other, lingots = price + 4)
        val result = updated(buyZone(company.id, 1, n = 2, actor = other.text))
        assertEquals(4L, result.balances.lingots, "les Lingots de l'associé")
        assertEquals(4L, app.lingots(other))
        assertEquals(1_000_000L, app.lingots(kr), "le PDG ne paie rien")
        assertEquals(other, app.city.spent.single().xuid)
        assertEquals(other, app.companies.published.single().xuid)
    }

    @Test
    @DisplayName("buyZone rejoué avec le même requestId : la même réponse, un seul achat, un seul débit, un seul événement")
    fun buyZoneIsIdempotent() = withApp { app ->
        val price = app.rules.zonePrice(1)
        val company = app.fresh(lingots = price * 3)

        val first = buyZone(company.id, 1, n = 7)
        val firstBody = first.bodyAsText()
        app.fixture.clock.advance(hours(2.0))
        val second = buyZone(company.id, 1, n = 7)

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(firstBody, second.bodyAsText(), "le rejeu rend la réponse gardée, même si le temps a passé")
        assertEquals(1, app.city.spent.size)
        assertEquals(price * 2, app.lingots(kr))
        assertEquals(1, app.companyUpdates().size)
        assertEquals(1, app.companies.published.size)
        assertEquals(2, app.city.writes.size)
    }

    @Test
    @DisplayName("buyZone : version dépassée (un autre associé a écrit) : 409 CONFLIT_REESSAYER, aucun Lingot retiré, et le même requestId passe au nouvel essai")
    fun buyZoneOnVersionConflict() = withApp { app ->
        val price = app.rules.zonePrice(1)
        val company = app.fresh(lingots = price)

        app.recording.rowsTouched = 0
        assertRefused(buyZone(company.id, 1, n = 9), RefusalCodes.CONFLICT_RETRY)
        assertTrue(app.city.writes.isEmpty(), "le verrou est pris avant la ville : un conflit n'écrit rien")
        assertTrue(app.city.spent.isEmpty(), "ni retrait de Lingots")
        assertEquals(price, app.lingots(kr))
        assertEquals(0, app.companies.published.size)

        app.recording.rowsTouched = 1
        assertEquals(HttpStatusCode.OK, buyZone(company.id, 1, n = 9).status, "le conflit n'est pas gardé : le nouvel essai passe")
        assertEquals(0L, app.lingots(kr))
    }

    // --- placeBuilding

    @Test
    @DisplayName("placeBuilding : le bâtiment est écrit au niveau 1 sur sa parcelle, payé en Lingots, la production est versée, l'événement ANNONCE part")
    fun placeBuildingWrites() = withApp { app ->
        val house = app.rules.building("maison")!!
        val price = house.level(1).priceLingots
        val company = app.settled(lingots = price + 7, awayHours = 2.0)
        val (x, z) = app.plot(1, 0)
        val expectedEarned = app.fixture.figures
            .pendingProduction(app.fixture.connection, company, app.fixture.store.loadFactories(app.fixture.connection, company.id), Regime.ONLINE).earned

        val result = updated(place(company.id, "maison", x, z, rotation = 90))

        assertEquals(7L, result.balances.lingots)
        assertClose(company.donar + expectedEarned, result.state.donar, "la production d'avant la pose est versée")
        assertClose(result.state.donar, app.companyUpdates().single().parameters[1] as Double, "UPDATE donar")
        val building = result.state.city!!.buildings.single { it.buildingType == "maison" }
        assertEquals(1, building.level)
        assertEquals(house.maxLevel(), building.maxLevel)
        assertEquals(1, building.zoneIndex)
        assertEquals(x, building.x)
        assertEquals(z, building.z)
        assertEquals(90, building.rotation)
        assertEquals(house.level(1).bonus, building.bonus)
        assertEquals(house.level(2).priceLingots, building.nextLevel!!.priceLingots)
        assertEquals(Math.floor(house.level(1).bonus!!).toInt(), result.state.city.bonuses.housingPlaces)

        val spent = app.city.spent.single()
        assertEquals(kr, spent.xuid)
        assertEquals(price, spent.amount)
        assertEquals(CityService.REASON_BUILDING, spent.reason)
        assertEquals("${company.id}/${building.buildingId}", spent.refId)
        val placed = app.city.writes.filterIsInstance<InMemoryCityStore.Write.BuildingPlaced>().single()
        assertEquals(CityBuildingRecord(building.buildingId, "maison", 1, 1, x, z, 90), placed.record)

        val event = app.companies.published.single()
        assertEquals(GameEventKind.ANNONCE, event.kind)
        assertEquals(kr, event.xuid)
        assertEquals("placeBuilding", payload(app, "action"))
        assertEquals(building.buildingId.toString(), payload(app, "buildingId"))
        assertEquals("maison", payload(app, "buildingType"))
        assertEquals("1", payload(app, "zoneIndex"))
        assertEquals(x.toString(), payload(app, "x"))
        assertEquals(z.toString(), payload(app, "z"))
        assertEquals("90", payload(app, "rotation"))
        assertEquals(price.toString(), payload(app, "lingots"))
    }

    @Test
    @DisplayName("placeBuilding : un commerce monte le multiplicateur de l'entreprise du pourcentage de ville.json")
    fun placeCommerceRaisesTheMultiplier() = withApp { app ->
        val shop = app.rules.building("commerce")!!
        val company = app.settled(lingots = shop.level(1).priceLingots, mairieLevel = shop.level(1).townHallLevelRequired)
        val (x, z) = app.plot(1, 0)

        val result = updated(place(company.id, "commerce", x, z))

        val pct = shop.level(1).bonus!!
        assertEquals(pct, result.state.city!!.bonuses.donarProductionPercent)
        val plain = app.fixture.figuresOf(company.id)
        assertEquals(app.engine.withCityBonus(plain.multiplier, pct), result.state.multiplier)
    }

    @Test
    @DisplayName("placeBuilding : une parcelle prise donne 409 EMPLACEMENT_OCCUPE (la mairie de départ, ou un bâtiment posé avant), rien d'écrit")
    fun placeOnTakenPlot() = withApp { app ->
        val company = app.settled(lingots = 10_000)
        val start = app.rules.startPlot
        assertRefused(place(company.id, "maison", start.x, start.z, n = 1), RefusalCodes.SPOT_TAKEN)
        assertNothingWritten(app)

        val (x, z) = app.plot(1, 0)
        assertEquals(HttpStatusCode.OK, place(company.id, "route", x, z, n = 2).status)
        val spentBefore = app.city.spent.size
        assertRefused(place(company.id, "maison", x, z, n = 3), RefusalCodes.SPOT_TAKEN)
        assertEquals(spentBefore, app.city.spent.size, "le refus ne retire rien")
        assertEquals(1, app.city.buildings.getValue(company.id).count { it.x == x && it.z == z })
    }

    @Test
    @DisplayName("placeBuilding : une parcelle dans une zone que l'entreprise n'a pas achetée donne 409 ZONE_VERROUILLEE, rien d'écrit")
    fun placeInAZoneNotOwned() = withApp { app ->
        val company = app.settled(lingots = 10_000, zones = listOf(1))
        val (x, z) = app.plot(2, 0)

        assertRefused(place(company.id, "maison", x, z), RefusalCodes.ZONE_LOCKED)
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("placeBuilding : un bâtiment que la mairie ne permet pas encore donne 409 ZONE_VERROUILLEE ; une mairie assez haute le pose")
    fun placeNeedsTheTownHall() = withApp { app ->
        val block = app.rules.building("immeuble")!!
        val needed = block.level(1).townHallLevelRequired
        assertTrue(needed > 1)
        val company = app.settled(lingots = 10_000, mairieLevel = needed - 1)
        val (x, z) = app.plot(1, 0)

        assertRefused(place(company.id, "immeuble", x, z, n = 1), RefusalCodes.ZONE_LOCKED)
        assertNothingWritten(app)

        app.fixture.update(app.fixture.store.loadCompany(app.fixture.connection, company.id).copy(mairieLevel = needed))
        assertEquals(HttpStatusCode.OK, place(company.id, "immeuble", x, z, n = 2).status)
        assertEquals(10_000L - block.level(1).priceLingots, app.lingots(kr))
    }

    @Test
    @DisplayName("placeBuilding : autant d'exemplaires que le catalogue le permet, le suivant donne 409 DEJA_POSSEDE, rien d'écrit")
    fun placeBeyondTheMaximum() = withApp { app ->
        val house = app.rules.building("maison")!!
        assertTrue(house.maxCount in 1..30)
        val company = app.settled(lingots = 10_000, mairieLevel = house.level(1).townHallLevelRequired)
        repeat(house.maxCount) { i ->
            val (x, z) = app.plot(1, i)
            app.city.seedBuilding(company.id, "maison", 1, 1, x, z)
        }
        val (x, z) = app.plot(1, house.maxCount)

        assertRefused(place(company.id, "maison", x, z), RefusalCodes.ALREADY_OWNED)
        assertNothingWritten(app)
        assertEquals(HttpStatusCode.OK, place(company.id, "route", x, z, n = 2).status, "un autre type reste posable")
    }

    @Test
    @DisplayName("placeBuilding : pas assez de Lingots donne 409 PAS_ASSEZ_DE_LINGOTS avec la somme qui manque, rien d'écrit")
    fun placeNotEnoughLingots() = withApp { app ->
        val price = app.rules.building("maison")!!.level(1).priceLingots
        val company = app.settled(lingots = price - 2)
        val (x, z) = app.plot(1, 0)

        val refused = assertRefused(place(company.id, "maison", x, z), RefusalCodes.NOT_ENOUGH_LINGOTS)
        assertEquals(2.0, refused.missing)
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("placeBuilding : un type inconnu, la mairie, une parcelle hors de l'île ou mal alignée, une rotation hors 0/90/180/270, un étranger donnent 400")
    fun placeBadRequests() = withApp { app ->
        val company = app.settled(lingots = 10_000)
        val (x, z) = app.plot(1, 0)
        val island = app.definitions.city.zones.columns * app.definitions.city.zones.sizeChunks * app.definitions.city.grid.blocksPerChunk
        val step = app.rules.plotBlocks

        assertError(place(company.id, "palais", x, z, n = 1), "type inconnu")
        assertError(place(company.id, app.rules.townHall.id, x, z, n = 2), "la mairie ne se pose pas")
        assertError(place(company.id, "maison", island, z, n = 3), "x hors de l'île")
        assertError(place(company.id, "maison", x, island, n = 4), "z hors de l'île")
        assertError(place(company.id, "maison", -step, z, n = 5), "x négatif")
        assertError(place(company.id, "maison", x + 1, z, n = 6), "x mal aligné")
        assertError(place(company.id, "maison", x, z + 1, n = 7), "z mal aligné")
        assertError(place(company.id, "maison", x, z, rotation = 45, n = 8), "rotation 45")
        assertError(place(company.id, "maison", x, z, rotation = 360, n = 9), "rotation 360")
        assertError(place(company.id, "maison", x, z, rotation = -90, n = 10), "rotation négative")
        assertError(place(company.id, "maison", x.toString() + ".5", z, n = 11), "x décimal")
        assertError(place(company.id, "", x, z, n = 12), "type vide")
        assertError(place(company.id, "a".repeat(33), x, z, n = 13), "type trop long")
        assertError(place(company.id, "maison", x, z, n = 14, actor = stranger.text), "étranger")
        assertError(place(9999, "maison", x, z, n = 15), "entreprise inconnue")
        assertError(place("abc", "maison", x, z, n = 16), "entreprise mal formée")
        assertNothingWritten(app)
        assertEquals(10_000L, app.lingots(kr))
    }

    @Test
    @DisplayName("placeBuilding rejoué avec le même requestId : la même réponse, un seul bâtiment, un seul débit, un seul événement")
    fun placeIsIdempotent() = withApp { app ->
        val company = app.settled(lingots = 10_000)
        val (x, z) = app.plot(1, 0)

        val first = place(company.id, "maison", x, z, n = 5)
        val firstBody = first.bodyAsText()
        app.fixture.clock.advance(hours(2.0))
        val second = place(company.id, "maison", x, z, n = 5)

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(firstBody, second.bodyAsText())
        assertEquals(1, app.city.spent.size)
        assertEquals(1, app.city.buildings.getValue(company.id).count { it.buildingType == "maison" })
        assertEquals(1, app.companyUpdates().size)
        assertEquals(1, app.companies.published.size)
    }

    // --- upgradeBuilding

    @Test
    @DisplayName("upgradeBuilding : un bâtiment passe au niveau suivant au prix de ce niveau, et le bonus monte")
    fun upgradeBuilding() = withApp { app ->
        val house = app.rules.building("maison")!!
        val next = house.level(2)
        val company = app.settled(lingots = next.priceLingots + 3, mairieLevel = next.townHallLevelRequired, awayHours = 1.0)
        val (x, z) = app.plot(1, 0)
        val id = app.city.seedBuilding(company.id, "maison", 1, 1, x, z)

        val result = updated(upgrade(company.id, id))

        assertEquals(3L, result.balances.lingots)
        val building = result.state.city!!.buildings.single { it.buildingId == id }
        assertEquals(2, building.level)
        assertEquals(next.bonus, building.bonus)
        assertEquals(house.level(3).priceLingots, building.nextLevel!!.priceLingots)
        assertEquals(result.state.city.mairieLevel, next.townHallLevelRequired, "la mairie ne bouge pas")
        assertEquals(InMemoryCityStore.Spent(kr, next.priceLingots, CityService.REASON_UPGRADE, "${company.id}/$id/2"), app.city.spent.single())
        assertEquals(InMemoryCityStore.Write.BuildingLeveled(company.id, id, 2), app.city.writes.single())
        assertTrue("mairie_level" !in app.companyUpdates().single().sql, "seule la mairie écrit son niveau dans companies")

        assertEquals("upgradeBuilding", payload(app, "action"))
        assertEquals(id.toString(), payload(app, "buildingId"))
        assertEquals("maison", payload(app, "buildingType"))
        assertEquals("2", payload(app, "level"))
        assertEquals(next.priceLingots.toString(), payload(app, "lingots"))
    }

    @Test
    @DisplayName("upgradeBuilding sur la mairie : le niveau s'écrit dans companies.mairie_level, dans le même UPDATE que la production, et la réponse le montre")
    fun upgradeTownHall() = withApp { app ->
        val hall = app.rules.townHall
        val next = hall.level(2)
        val company = app.settled(lingots = next.priceLingots + 5, mairieLevel = 1, awayHours = 2.0)
        val hallId = app.city.loadBuildings(app.fixture.connection, company.id).single().id
        assertTrue(next.townHallLevelRequired <= 1, "monter la mairie du niveau 1 ne demande pas une mairie plus haute")

        val result = updated(upgrade(company.id, hallId))

        assertEquals(2, result.state.city!!.mairieLevel)
        assertEquals(2, result.state.city.buildings.single().level)
        assertEquals(5L, result.balances.lingots)
        val update = app.companyUpdates().single()
        assertTrue("mairie_level = ?" in update.sql, update.sql)
        val position = update.sql.substringBefore("mairie_level").count { it == '?' } + 1
        assertEquals(2, update.parameters[position], "le nouveau niveau est le paramètre $position de l'UPDATE : ${update.parameters}")
        assertEquals(2, app.fixture.store.loadCompany(app.fixture.connection, company.id).mairieLevel)
        assertEquals(next.priceLingots, app.city.spent.single().amount)
    }

    @Test
    @DisplayName("Monter la mairie ouvre la zone suivante : acheter la zone 2 est refusé, puis accepté une fois la mairie au niveau que ville.json demande")
    fun townHallUnlocksTheNextZone() = withApp { app ->
        val needed = app.rules.zone(2).townHallLevelRequired
        assertTrue(needed in 2..4, "ce test monte la mairie niveau par niveau : $needed")
        val lingots = (1L..needed).sumOf { app.rules.townHall.level(it.toInt()).priceLingots } + app.rules.zonePrice(2)
        val company = app.settled(lingots = lingots, zones = listOf(1), mairieLevel = 1)
        val hallId = app.city.loadBuildings(app.fixture.connection, company.id).single().id

        assertRefused(buyZone(company.id, 2, n = 1), RefusalCodes.ZONE_LOCKED)
        for (level in 2..needed) {
            assertEquals(level, updated(upgrade(company.id, hallId, n = 10 + level)).state.city!!.mairieLevel)
        }
        val result = updated(buyZone(company.id, 2, n = 2))
        assertEquals(listOf(1, 2), result.state.zonesOwned)
    }

    @Test
    @DisplayName("upgradeBuilding : un bâtiment au dernier niveau donne 409 DEJA_POSSEDE, rien d'écrit")
    fun upgradeAtTheLastLevel() = withApp { app ->
        val house = app.rules.building("maison")!!
        val company = app.settled(lingots = 10_000, mairieLevel = 16)
        val (x, z) = app.plot(1, 0)
        val id = app.city.seedBuilding(company.id, "maison", house.maxLevel(), 1, x, z)

        assertRefused(upgrade(company.id, id), RefusalCodes.ALREADY_OWNED)
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("upgradeBuilding : une mairie trop basse pour le niveau suivant donne 409 ZONE_VERROUILLEE, rien d'écrit")
    fun upgradeNeedsTheTownHall() = withApp { app ->
        val house = app.rules.building("maison")!!
        val needed = house.level(3).townHallLevelRequired
        assertTrue(needed > 1)
        val company = app.settled(lingots = 10_000, mairieLevel = needed - 1)
        val (x, z) = app.plot(1, 0)
        val id = app.city.seedBuilding(company.id, "maison", 2, 1, x, z)

        assertRefused(upgrade(company.id, id), RefusalCodes.ZONE_LOCKED)
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("upgradeBuilding : pas assez de Lingots donne 409 PAS_ASSEZ_DE_LINGOTS avec la somme qui manque, rien d'écrit")
    fun upgradeNotEnoughLingots() = withApp { app ->
        val next = app.rules.building("maison")!!.level(2)
        val company = app.settled(lingots = next.priceLingots - 4, mairieLevel = next.townHallLevelRequired)
        val (x, z) = app.plot(1, 0)
        val id = app.city.seedBuilding(company.id, "maison", 1, 1, x, z)

        val refused = assertRefused(upgrade(company.id, id), RefusalCodes.NOT_ENOUGH_LINGOTS)
        assertEquals(4.0, refused.missing)
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("upgradeBuilding : un bâtiment inconnu ou d'une autre entreprise, un identifiant mal formé, un étranger donnent 400")
    fun upgradeBadRequests() = withApp { app ->
        val company = app.settled(lingots = 10_000)
        val outsider = app.fixture.newCompany(stranger, 10_000)
        val foreign = app.city.seedBuilding(outsider.id, "maison", 1, 1, 128, 128)
        val (x, z) = app.plot(1, 0)
        val mine = app.city.seedBuilding(company.id, "maison", 1, 1, x, z)

        assertError(upgrade(company.id, 9999, n = 1), "bâtiment inconnu")
        assertError(upgrade(company.id, foreign, n = 2), "bâtiment d'une autre entreprise")
        for (id in listOf("0", "-1", "abc")) assertError(upgrade(company.id, id, n = 3), "buildingId $id")
        assertError(upgrade(company.id, mine, n = 4, actor = stranger.text), "étranger")
        assertError(upgrade(9999, mine, n = 5), "entreprise inconnue")
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("upgradeBuilding : version dépassée : 409 CONFLIT_REESSAYER, aucun Lingot retiré, le niveau ne bouge pas")
    fun upgradeOnVersionConflict() = withApp { app ->
        val next = app.rules.building("maison")!!.level(2)
        val company = app.settled(lingots = next.priceLingots, mairieLevel = next.townHallLevelRequired)
        val (x, z) = app.plot(1, 0)
        val id = app.city.seedBuilding(company.id, "maison", 1, 1, x, z)

        app.recording.rowsTouched = 0
        assertRefused(upgrade(company.id, id, n = 4), RefusalCodes.CONFLICT_RETRY)
        assertTrue(app.city.spent.isEmpty())
        assertTrue(app.city.writes.isEmpty())
        assertEquals(1, app.city.findBuilding(app.fixture.connection, company.id, id)!!.level)

        app.recording.rowsTouched = 1
        assertEquals(HttpStatusCode.OK, upgrade(company.id, id, n = 4).status)
        assertEquals(2, app.city.findBuilding(app.fixture.connection, company.id, id)!!.level)
    }

    @Test
    @DisplayName("La ville ne donne aucun Lingot : après n'importe quelle action, les Lingots de personne n'ont monté")
    fun nothingGivesLingots() = withApp { app ->
        val company = app.settled(lingots = 10_000, mairieLevel = app.rules.building("maison")!!.level(2).townHallLevelRequired)
        app.fixture.addMember(company.id, other, lingots = 50)
        val (x, z) = app.plot(1, 0)
        val before = app.fixture.store.balances.toMap()

        val placed = updated(place(company.id, "maison", x, z, n = 1))
        val id = placed.state.city!!.buildings.single { it.buildingType == "maison" }.buildingId
        updated(upgrade(company.id, id, n = 2))

        for ((xuid, balances) in app.fixture.store.balances) {
            assertTrue(balances.lingots <= before.getValue(xuid).lingots, "Lingots de $xuid")
            assertEquals(before.getValue(xuid).gemmes, balances.gemmes)
            assertEquals(before.getValue(xuid).jetons, balances.jetons)
            assertEquals(before.getValue(xuid).cles, balances.cles)
        }
        assertEquals(50L, app.lingots(other))
    }
}
