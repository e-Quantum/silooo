package com.silooo.core.staff

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
import com.silooo.core.api.Staff
import com.silooo.core.contract.OpenApi
import com.silooo.core.db.InMemoryApiRequestStore
import com.silooo.core.db.RecordingDatabase
import com.silooo.core.definitions.StaffRarity
import com.silooo.core.definitions.StaffRole
import com.silooo.core.economy.EngineFixture
import com.silooo.core.economy.EngineFixture.Companion.hours
import com.silooo.core.economy.InMemoryCompanyStore
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.StaffRecord
import com.silooo.core.siloooModule
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `getCandidates`, `hire`, `assignStaff` et `fireStaff` avec des faux (pas de base) : le moteur d'économie réel sur les
 * `definitions/` du dépôt, un faux dépôt du personnel, une horloge que le test avance, et une connexion qui note les
 * `UPDATE companies` du verrou optimiste. Les chiffres attendus sortent du moteur et de la liste du jour (`AgencyDraw`), jamais
 * d'un littéral. La connexion ne change pas le faux dépôt de l'entreprise : le Donar d'après se lit dans la réponse et dans
 * l'`UPDATE` noté. Le SQL est dans `StaffSqlTest`, le contrat dans `ContractTest`.
 */
class StaffRoutesTest {
    private val key = "cle-de-test"
    private val kr = EngineFixture.KR
    private val other = EngineFixture.OTHER
    private val stranger = EngineFixture.STRANGER

    private class Harness(
        val fixture: EngineFixture,
        val companies: InMemoryCompanyStore,
        val staff: InMemoryStaffStore,
    ) {
        val engine get() = fixture.engine
        val recording get() = fixture.recording
        val personnel get() = fixture.definitions.personnel
        val draw = AgencyDraw(fixture.definitions.personnel)

        val starterId get() = fixture.starting.factoryId
        val secondId get() = fixture.otherFactories()[0]
        val thirdId get() = fixture.otherFactories()[1]

        /** Le jour de l'agence, à l'horloge de maintenant. */
        val today: LocalDate get() = LocalDate.ofInstant(fixture.clock.instant(), ZoneOffset.UTC)

        private var nextSeedId = 100L

        /** Assez de Donar pour n'importe quelle embauche d'une entreprise neuve : cent fois la plus chère. */
        val rich: Double
            get() = engine.hireCost(StaffRole.CHEF, StaffRarity.EPIQUE, engine.ratePerHour(engine.startingLevels(), engine.globalMultiplier(0.0, 0))) * 100

        fun companyUpdates() = recording.statements.filter { it.sql.startsWith("UPDATE companies") }

        /** Une entreprise du PDG [EngineFixture.KR], avec [donar] en caisse et [awayHours] heures de production en attente. */
        fun company(donar: Double = rich, awayHours: Double = 0.0): CompanyRecord {
            val company = fixture.update(fixture.newCompany(EngineFixture.KR).copy(donar = donar))
            fixture.clock.advance(hours(awayHours))
            return company
        }

        /** Une usine de plus à l'entreprise, au niveau [level]. */
        fun own(companyId: Long, factoryId: String, level: Int = 1, state: FactoryState = FactoryState.ACTIVE) =
            fixture.putFactory(companyId, FactoryRecord(factoryId, state, 0, if (state == FactoryState.ACTIVE) level else 0, null))

        /** Le candidat de la place [slot] du jour, tel que le service le tire. */
        fun candidate(companyId: Long, slot: Int): Candidate = draw.candidates(companyId, today)[slot - 1]

        /** Le prix d'embauche de la place [slot], au revenu de maintenant. */
        fun cost(companyId: Long, slot: Int): Double {
            val candidate = candidate(companyId, slot)
            return engine.hireCost(candidate.role, candidate.rarity, fixture.figuresOf(companyId).ratePerHour)
        }

        /** Les places de la liste du jour qui proposent [role], dans l'ordre. */
        fun slotsOf(role: StaffRole): List<Int> = personnel.agency.rolesPerSlot.withIndex().filter { it.value == role }.map { it.index + 1 }

        /** Un membre posé directement dans le faux dépôt, sans passer par l'agence ni noter d'écriture. */
        fun member(companyId: Long, factoryId: String?, role: StaffRole = StaffRole.SALARIE, rarity: StaffRarity = StaffRarity.RARE): StaffRecord {
            nextSeedId += 1
            val record = StaffRecord(nextSeedId, factoryId, "Membre $nextSeedId", "ouvrier-casque", role, rarity)
            fixture.store.staff.getOrPut(companyId) { mutableListOf() } += record
            return record
        }

        fun membersOf(companyId: Long): List<StaffRecord> = fixture.store.loadStaff(fixture.connection, companyId)
    }

    private fun withApp(block: suspend ApplicationTestBuilder.(Harness) -> Unit) = testApplication {
        val fixture = EngineFixture()
        val database = RecordingDatabase(fixture.recording)
        val companies = InMemoryCompanyStore(fixture.store)
        val staff = InMemoryStaffStore(fixture.store)
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
                    economyFigures = fixture.figures,
                    companyBootstrap = fixture.bootstrap,
                    companyStore = companies,
                    staffStore = staff,
                ),
            )
        }
        block(Harness(fixture, companies, staff))
    }

    // --- outils

    private fun requestId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    private fun action(n: Int = 1, actor: String = kr.text, extra: String = ""): String =
        """{"requestId":"${requestId(n)}","actor":"$actor"$extra}"""

    private suspend fun ApplicationTestBuilder.get(path: String): HttpResponse = client.get(path) { header(SERVER_KEY_HEADER, key) }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String): HttpResponse =
        client.post(path) {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.candidates(companyId: Long) = get("/v1/companies/$companyId/agency")

    private suspend fun ApplicationTestBuilder.hire(companyId: Long, slot: Any, factoryId: String? = null, n: Int = 1, actor: String = kr.text) =
        post("/v1/companies/$companyId/agency/$slot/hire", action(n, actor, factoryId?.let { ""","factoryId":"$it"""" }.orEmpty()))

    private suspend fun ApplicationTestBuilder.assign(companyId: Long, staffId: Any, factoryId: String, n: Int = 1, actor: String = kr.text) =
        client.patch("/v1/companies/$companyId/staff/$staffId") {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(action(n, actor, ""","factoryId":"$factoryId""""))
        }

    private suspend fun ApplicationTestBuilder.fire(companyId: Long, staffId: Any, n: Int = 1, actor: String = kr.text) =
        client.delete("/v1/companies/$companyId/staff/$staffId?requestId=${requestId(n)}&actor=$actor") { header(SERVER_KEY_HEADER, key) }

    private suspend fun updated(response: HttpResponse): CompanyUpdated {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        return SiloooJson.decodeFromString(CompanyUpdated.serializer(), body)
    }

    private suspend fun staffList(response: HttpResponse): List<Staff> {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        return SiloooJson.decodeFromString(ListSerializer(Staff.serializer()), body)
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

    /** Rien n'a été écrit : ni entreprise, ni personnel, ni liste du jour, ni événement. */
    private fun assertNothingWritten(app: Harness) {
        assertEquals(0, app.companyUpdates().size, "aucun UPDATE companies")
        assertTrue(app.staff.writes.isEmpty(), "aucune écriture du personnel : ${app.staff.writes}")
        assertTrue(app.staff.agency.isEmpty(), "aucune place embauchée")
        assertEquals(0, app.companies.published.size, "aucun événement")
    }

    // --- getCandidates

    @Test
    @DisplayName("getCandidates : la liste du jour tirée pour l'entreprise, avec le prix d'embauche au revenu de maintenant, et rien d'écrit")
    fun listIsTheDraw() = withApp { app ->
        val company = app.company()
        val list = staffList(candidates(company.id))
        val drawn = app.draw.candidates(company.id, app.today)
        val revenue = app.fixture.figuresOf(company.id).ratePerHour

        assertEquals(drawn.size, list.size)
        for ((shown, proposed) in list.zip(drawn)) {
            assertEquals(proposed.slot, shown.slot)
            assertEquals(proposed.name, shown.name)
            assertEquals(proposed.skinId, shown.skinId)
            assertEquals(proposed.role, shown.role)
            assertEquals(proposed.rarity, shown.rarity)
            assertEquals(app.engine.staffBonusPercent(proposed.rarity), shown.bonusPercent)
            assertEquals(app.engine.staffSalaryPercent(proposed.role, proposed.rarity), shown.salaryPercent)
            assertEquals(app.engine.hireCost(proposed.role, proposed.rarity, revenue), shown.hireCost)
            assertEquals(null, shown.staffId, "un candidat libre n'a pas d'identifiant d'embauche")
            assertEquals(null, shown.factoryId)
        }
        assertEquals(0, app.recording.statements.size, "lire la liste n'écrit rien")
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("getCandidates : la même liste tout le jour (UTC) et à chaque lecture, une autre le lendemain")
    fun listIsStableForTheDay() = withApp { app ->
        val company = app.company()
        val first = candidates(company.id).bodyAsText()

        app.fixture.clock.advance(hours(5.0)) // 17 h
        assertEquals(first, candidates(company.id).bodyAsText())
        app.fixture.clock.advance(hours(6.9)) // 23 h 54
        assertEquals(first, candidates(company.id).bodyAsText())
        app.fixture.clock.advance(hours(0.2)) // 0 h 06 le lendemain
        assertNotEquals(first, candidates(company.id).bodyAsText(), "un nouveau jour, une nouvelle liste")
    }

    @Test
    @DisplayName("getCandidates : une entreprise inconnue ou un identifiant mal formé donnent 400")
    fun candidatesBadRequests() = withApp { app ->
        app.company()
        for (path in listOf("/v1/companies/0/agency", "/v1/companies/-1/agency", "/v1/companies/abc/agency", "/v1/companies/99/agency")) {
            assertError(get(path), path)
        }
    }

    @Test
    @DisplayName("Pas de relance payante : le contrat n'a que getCandidates et hire côté agence, et aucune autre route ne tire une liste")
    fun noPaidReroll() = withApp { app ->
        val agencyOperations = OpenApi.load().operations.filter { "/agency" in it.path }.map { it.id }.toSet()
        assertEquals(setOf("getCandidates", "hire"), agencyOperations)

        val company = app.company()
        val before = candidates(company.id).bodyAsText()
        for (path in listOf("/agency", "/agency/reroll", "/agency/refresh", "/agency/draw")) {
            val response = post("/v1/companies/${company.id}$path", action())
            assertTrue(response.status.value in setOf(404, 405, 501), "$path doit rester sans effet : ${response.status}")
        }
        assertEquals(before, candidates(company.id).bodyAsText(), "la liste n'a pas bougé")
        assertNothingWritten(app)
    }

    // --- hire

    @Test
    @DisplayName("hire : le prix est débité du Donar, le membre est écrit tel que proposé, la place est notée, l'événement ANNONCE part")
    fun hireDebitsAndRecords() = withApp { app ->
        val company = app.company()
        val proposed = app.candidate(company.id, 2)
        val cost = app.cost(company.id, 2)
        assertTrue(cost > 0.0)

        val result = updated(hire(company.id, 2))

        assertClose(company.donar - cost, result.state.donar, "donar après l'embauche")
        val member = result.state.staff.single()
        assertEquals(proposed.name, member.name)
        assertEquals(proposed.skinId, member.skinId)
        assertEquals(proposed.role, member.role)
        assertEquals(proposed.rarity, member.rarity)
        assertEquals(null, member.factoryId, "sans usine demandée, le membre n'en a pas")
        assertEquals(0.0, member.salaryPerHour, "sans usine il ne coûte rien")

        val update = app.companyUpdates().single()
        assertClose(company.donar - cost, update.parameters[1] as Double, "UPDATE donar")
        val insert = app.staff.writes.single() as InMemoryStaffStore.Write.Inserted
        assertEquals(member.staffId, insert.staffId)
        assertEquals(company.id, insert.companyId)
        assertEquals(proposed, insert.candidate)
        assertEquals(app.engine.staffBonusPercent(proposed.rarity), insert.bonusPercent)
        val row = app.staff.agency.single()
        assertEquals(app.today, row.day)
        assertEquals(2, row.candidate.slot)
        assertEquals(member.staffId, row.staffId)

        val event = app.companies.published.single()
        assertEquals(GameEventKind.ANNONCE, event.kind)
        assertEquals(kr, event.xuid)
        assertEquals("hire", event.payload.getValue("action").jsonPrimitive.content)
        assertEquals(company.id.toString(), event.payload.getValue("companyId").jsonPrimitive.content)
        assertEquals(member.staffId.toString(), event.payload.getValue("staffId").jsonPrimitive.content)
        assertEquals("2", event.payload.getValue("slot").jsonPrimitive.content)
        assertEquals(proposed.role.name, event.payload.getValue("role").jsonPrimitive.content)
        assertEquals(proposed.rarity.name, event.payload.getValue("rarity").jsonPrimitive.content)
        assertClose(cost, event.payload.getValue("cost").jsonPrimitive.content.toDouble(), "coût de l'événement")
    }

    @Test
    @DisplayName("hire avec une usine : le membre y travaille tout de suite, le revenu net de l'usine et de l'entreprise monte")
    fun hireIntoAFactory() = withApp { app ->
        val company = app.company()
        val before = app.fixture.figuresOf(company.id)

        val result = updated(hire(company.id, 1, factoryId = app.starterId))

        val member = result.state.staff.single()
        assertEquals(app.starterId, member.factoryId)
        val after = app.fixture.figuresOf(company.id)
        assertEquals(after.ratePerHour, result.state.ratePerHour, "l'état rendu est celui du moteur sur ce qui vient d'être écrit")
        assertTrue(result.state.ratePerHour > before.ratePerHour, "un membre qui travaille fait monter le revenu net")
        val factory = result.state.factories.single { it.factoryId == app.starterId }
        assertEquals(result.state.ratePerHour, factory.ratePerHour)
        assertTrue(member.salaryPerHour!! > 0.0, "il est payé")
        assertEquals(app.engine.staffSalaryPercent(member.role, member.rarity), member.salaryPercent)
    }

    @Test
    @DisplayName("hire : la production en attente est versée d'abord, au revenu plein, et paie l'embauche")
    fun hirePaysFromPendingProduction() = withApp { app ->
        val company = app.company(donar = 0.0, awayHours = 30.0)
        val earned = app.engine.onlineEarnings(app.fixture.figuresOf(company.id).ratePerHour, 30.0)
        val cost = app.cost(company.id, 1)
        assertTrue(cost > 0.0 && cost <= earned, "le test n'a de sens que si la production seule paie : $cost pour $earned")

        val result = updated(hire(company.id, 1))

        assertClose(earned - cost, result.state.donar, "donar : production versée, prix débité")
        assertClose(earned, result.state.donarEarned, "donarEarned : toute la production")
        val update = app.companyUpdates().single()
        assertEquals(app.fixture.clock.instant().toEpochMilli(), (update.parameters[3] as java.time.LocalDateTime).toInstant(ZoneOffset.UTC).toEpochMilli(), "last_collect_at repart de maintenant")
    }

    @Test
    @DisplayName("hire : 409 PAS_ASSEZ_DE_DONAR avec la somme qui manque, rien d'écrit ; juste assez de Donar, et c'est payé")
    fun hireNeedsEnoughDonar() = withApp { app ->
        val company = app.company(donar = 0.0)
        val cost = app.cost(company.id, 1)
        app.fixture.update(app.fixture.store.loadCompany(app.fixture.connection, company.id).copy(donar = cost / 2))

        val refusal = assertRefused(hire(company.id, 1, n = 1), RefusalCodes.NOT_ENOUGH_DONAR)
        assertClose(cost / 2, refusal.missing!!, "somme qui manque")
        assertNothingWritten(app)

        app.fixture.update(app.fixture.store.loadCompany(app.fixture.connection, company.id).copy(donar = cost))
        val paid = updated(hire(company.id, 1, n = 2))
        assertEquals(0.0, paid.state.donar, "le prix exact vide la caisse")
    }

    @Test
    @DisplayName("hire : un candidat déjà embauché aujourd'hui donne 409 DEJA_POSSEDE, et une seule embauche est écrite")
    fun hireTwiceIsRefused() = withApp { app ->
        val company = app.company()
        updated(hire(company.id, 3, n = 1))

        assertRefused(hire(company.id, 3, n = 2), RefusalCodes.ALREADY_OWNED)

        assertEquals(1, app.staff.writes.size)
        assertEquals(1, app.staff.agency.size)
        assertEquals(1, app.companyUpdates().size)
        assertEquals(1, app.companies.published.size)
    }

    @Test
    @DisplayName("hire : une usine qui n'est pas à l'entreprise, ou en ruine, donne 409 USINE_VERROUILLEE, rien d'écrit")
    fun hireIntoALockedFactory() = withApp { app ->
        val company = app.company()
        assertRefused(hire(company.id, 1, factoryId = app.secondId, n = 1), RefusalCodes.FACTORY_LOCKED)

        app.own(company.id, app.secondId, state = FactoryState.EN_RUINE)
        assertRefused(hire(company.id, 1, factoryId = app.secondId, n = 2), RefusalCodes.FACTORY_LOCKED)
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("hire : une usine n'a que ses postes de salarié, 409 EMPLACEMENT_OCCUPE au-delà ; le chef a son propre poste")
    fun hireFillsThePosts() = withApp { app ->
        val company = app.company()
        app.own(company.id, app.starterId, level = 1)
        val posts = app.engine.staffPosts(StaffRole.SALARIE, 1)
        val employees = app.slotsOf(StaffRole.SALARIE)
        assertTrue(posts < employees.size, "le test veut plus de places de salarié ($employees) que de postes ($posts)")

        employees.take(posts).forEachIndexed { index, slot ->
            assertEquals(HttpStatusCode.OK, hire(company.id, slot, factoryId = app.starterId, n = index + 1).status)
        }
        val refused = employees[posts]
        assertRefused(hire(company.id, refused, factoryId = app.starterId, n = 50), RefusalCodes.SPOT_TAKEN)
        assertEquals(posts, app.staff.writes.size, "le refus n'écrit rien")

        assertEquals(HttpStatusCode.OK, hire(company.id, refused, n = 51).status, "sans usine, pas de poste à remplir")
        val chef = app.slotsOf(StaffRole.CHEF).first()
        assertEquals(HttpStatusCode.OK, hire(company.id, chef, factoryId = app.starterId, n = 52).status, "le chef n'occupe pas un poste de salarié")
    }

    @Test
    @DisplayName("hire : un seul chef par usine, même d'un jour à l'autre ; une autre usine peut avoir le sien")
    fun oneLeaderPerFactory() = withApp { app ->
        val company = app.company()
        app.own(company.id, app.secondId, level = 1)
        val chef = app.slotsOf(StaffRole.CHEF).first()
        assertEquals(HttpStatusCode.OK, hire(company.id, chef, factoryId = app.starterId, n = 1).status)

        app.fixture.clock.advance(hours(24.0)) // le lendemain, un autre chef est proposé
        assertRefused(hire(company.id, chef, factoryId = app.starterId, n = 2), RefusalCodes.SPOT_TAKEN)
        assertEquals(HttpStatusCode.OK, hire(company.id, chef, factoryId = app.secondId, n = 3).status)
    }

    @Test
    @DisplayName("hire : un associé peut embaucher (l'événement porte son XUID) ; un étranger, une place hors 1 à 5, une usine inconnue donnent 400")
    fun hireBadRequestsAndPartners() = withApp { app ->
        val company = app.company()
        app.fixture.addMember(company.id, other, lingots = 0)

        for (slot in listOf("0", "6", "-1", "abc")) assertError(hire(company.id, slot, n = 10), "place $slot")
        assertError(hire(company.id, 1, actor = stranger.text, n = 11), "étranger")
        assertError(hire(company.id, 1, factoryId = "usine-inconnue", n = 12), "usine inconnue")
        assertError(hire(99, 1, n = 13), "entreprise inconnue")
        assertError(post("/v1/companies/${company.id}/agency/1/hire", """{"requestId":"abc","actor":"${kr.text}"}"""), "requestId")
        assertNothingWritten(app)

        assertEquals(HttpStatusCode.OK, hire(company.id, 1, actor = other.text, n = 14).status)
        assertEquals(other, app.companies.published.single().xuid)
    }

    @Test
    @DisplayName("hire rejoué avec le même requestId : la même réponse, une seule embauche, un seul débit, un seul événement")
    fun hireIsIdempotent() = withApp { app ->
        val company = app.company()

        val first = hire(company.id, 1, factoryId = app.starterId, n = 7)
        val firstBody = first.bodyAsText()
        app.fixture.clock.advance(hours(2.0))
        val second = hire(company.id, 1, factoryId = app.starterId, n = 7)

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(firstBody, second.bodyAsText(), "le rejeu rend la réponse gardée, même si le temps a passé")
        assertEquals(1, app.staff.writes.size)
        assertEquals(1, app.companyUpdates().size)
        assertEquals(1, app.companies.published.size)
    }

    @Test
    @DisplayName("hire : version dépassée (un autre associé a écrit) : 409 CONFLIT_REESSAYER, rien d'écrit, et le même requestId passe au nouvel essai")
    fun hireOnVersionConflict() = withApp { app ->
        val company = app.company()

        app.recording.rowsTouched = 0
        assertRefused(hire(company.id, 1, n = 9), RefusalCodes.CONFLICT_RETRY)
        assertTrue(app.staff.writes.isEmpty(), "le verrou est pris avant le membre : un conflit n'en écrit aucun")
        assertTrue(app.staff.agency.isEmpty())
        assertEquals(0, app.companies.published.size)
        assertTrue(app.membersOf(company.id).isEmpty())

        app.recording.rowsTouched = 1
        assertEquals(HttpStatusCode.OK, hire(company.id, 1, n = 9).status, "le conflit n'est pas gardé : le nouvel essai passe")
        assertEquals(1, app.staff.writes.size)
    }

    @Test
    @DisplayName("Une place embauchée reste dans la liste avec son staffId et son usine, sans prix ; les autres places gardent leur candidat")
    fun hiredSlotStaysInTheList() = withApp { app ->
        val company = app.company()
        val before = staffList(candidates(company.id))

        val member = updated(hire(company.id, 2, factoryId = app.starterId)).state.staff.single()
        val after = staffList(candidates(company.id))

        val hired = after.single { it.slot == 2 }
        assertEquals(member.staffId, hired.staffId)
        assertEquals(app.starterId, hired.factoryId)
        assertEquals(null, hired.hireCost, "un candidat embauché n'a plus de prix")
        assertEquals(before.single { it.slot == 2 }.name, hired.name, "le candidat est celui qui a été proposé")
        for (slot in after.map { it.slot }.filter { it != 2 }) {
            val was = before.single { it.slot == slot }
            val now = after.single { it.slot == slot }
            assertEquals(was.name, now.name, "place $slot")
            assertEquals(was.rarity, now.rarity, "place $slot")
            assertEquals(null, now.staffId)
        }
        // Le prix des autres suit le revenu de maintenant, qui a monté avec le nouveau membre.
        val revenue = app.fixture.figuresOf(company.id).ratePerHour
        for (candidate in after.filter { it.slot != 2 }) {
            assertEquals(app.engine.hireCost(candidate.role, candidate.rarity, revenue), candidate.hireCost, "place ${candidate.slot}")
        }
    }

    // --- assignStaff

    @Test
    @DisplayName("assignStaff : le membre change d'usine sans frais, le revenu des deux usines suit, l'événement ANNONCE part")
    fun assignMovesTheMember() = withApp { app ->
        val company = app.company()
        app.own(company.id, app.secondId, level = 5)
        val worker = app.member(company.id, app.starterId)
        val before = app.fixture.figuresOf(company.id)

        val result = updated(assign(company.id, worker.id, app.secondId))

        assertEquals(app.secondId, result.state.staff.single().factoryId)
        assertEquals(app.secondId, app.membersOf(company.id).single().factoryId)
        assertEquals(listOf(InMemoryStaffStore.Write.Assigned(company.id, worker.id, app.secondId)), app.staff.writes)
        assertClose(company.donar, result.state.donar, "l'affectation est gratuite")
        val after = app.fixture.figuresOf(company.id)
        assertEquals(after.ratePerHour, result.state.ratePerHour)
        assertTrue(after.forFactory(app.starterId).ratePerHour < before.forFactory(app.starterId).ratePerHour, "l'usine quittée perd l'équipe")
        assertTrue(after.forFactory(app.secondId).ratePerHour > before.forFactory(app.secondId).ratePerHour, "l'usine rejointe la gagne")

        assertEquals(1, app.companyUpdates().size)
        val event = app.companies.published.single()
        assertEquals(GameEventKind.ANNONCE, event.kind)
        assertEquals(kr, event.xuid)
        assertEquals("assignStaff", event.payload.getValue("action").jsonPrimitive.content)
        assertEquals(worker.id.toString(), event.payload.getValue("staffId").jsonPrimitive.content)
        assertEquals(app.secondId, event.payload.getValue("factoryId").jsonPrimitive.content)
    }

    @Test
    @DisplayName("assignStaff : un membre sans usine part travailler dans une usine active")
    fun assignAnUnassignedMember() = withApp { app ->
        val company = app.company()
        val worker = app.member(company.id, null)

        val result = updated(assign(company.id, worker.id, app.starterId))

        assertEquals(app.starterId, result.state.staff.single().factoryId)
        assertTrue(result.state.ratePerHour > app.engine.ratePerHour(app.engine.startingLevels(), result.state.multiplier))
    }

    @Test
    @DisplayName("assignStaff : l'affecter là où il est déjà ne change rien : ni écriture, ni événement, la même réponse d'état")
    fun assignToTheSameFactoryIsANoOp() = withApp { app ->
        val company = app.company()
        val worker = app.member(company.id, app.starterId)

        val result = updated(assign(company.id, worker.id, app.starterId))

        assertEquals(app.starterId, result.state.staff.single().factoryId)
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("assignStaff : 409 USINE_VERROUILLEE (pas à l'entreprise, en ruine) et EMPLACEMENT_OCCUPE (postes pleins), rien d'écrit")
    fun assignRefusals() = withApp { app ->
        val company = app.company()
        app.own(company.id, app.starterId, level = 1)
        val employee = app.member(company.id, null, StaffRole.SALARIE)
        val chef = app.member(company.id, null, StaffRole.CHEF)
        app.member(company.id, app.starterId, StaffRole.SALARIE) // le seul poste de salarié du niveau 1

        assertRefused(assign(company.id, employee.id, app.thirdId, n = 1), RefusalCodes.FACTORY_LOCKED)
        app.own(company.id, app.secondId, state = FactoryState.EN_RUINE)
        assertRefused(assign(company.id, employee.id, app.secondId, n = 2), RefusalCodes.FACTORY_LOCKED)
        assertRefused(assign(company.id, employee.id, app.starterId, n = 3), RefusalCodes.SPOT_TAKEN)
        assertNothingWritten(app)

        assertEquals(HttpStatusCode.OK, assign(company.id, chef.id, app.starterId, n = 4).status, "le chef a son poste, un salarié l'occupe sans le gêner")
    }

    @Test
    @DisplayName("assignStaff : un membre inconnu, d'une autre entreprise, une usine inconnue ou un étranger donnent 400")
    fun assignBadRequests() = withApp { app ->
        val company = app.company()
        val worker = app.member(company.id, null)
        val outsider = app.fixture.newCompany(other)
        val foreign = app.member(outsider.id, null)

        assertError(assign(company.id, 9999, app.starterId, n = 1), "membre inconnu")
        assertError(assign(company.id, foreign.id, app.starterId, n = 2), "membre d'une autre entreprise")
        assertError(assign(company.id, worker.id, "usine-inconnue", n = 3), "usine inconnue")
        assertError(assign(company.id, worker.id, app.starterId, n = 4, actor = stranger.text), "étranger")
        assertError(assign(company.id, worker.id, app.starterId, n = 5, actor = other.text), "membre d'une autre entreprise comme acteur")
        for (id in listOf("0", "-1", "abc")) assertError(assign(company.id, id, app.starterId, n = 6), "staffId $id")
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("assignStaff : rejoué à l'identique avec le même requestId ; version dépassée : 409 CONFLIT_REESSAYER, le membre reste où il est")
    fun assignIdempotentAndConflict() = withApp { app ->
        val company = app.company()
        app.own(company.id, app.secondId, level = 5)
        val worker = app.member(company.id, app.starterId)

        app.recording.rowsTouched = 0
        assertRefused(assign(company.id, worker.id, app.secondId, n = 3), RefusalCodes.CONFLICT_RETRY)
        assertEquals(app.starterId, app.membersOf(company.id).single().factoryId)
        assertTrue(app.staff.writes.isEmpty())

        app.recording.rowsTouched = 1
        val first = assign(company.id, worker.id, app.secondId, n = 3)
        val firstBody = first.bodyAsText()
        val second = assign(company.id, worker.id, app.secondId, n = 3)
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(firstBody, second.bodyAsText())
        assertEquals(1, app.staff.writes.size)
        assertEquals(1, app.companies.published.size)
    }

    // --- fireStaff

    @Test
    @DisplayName("fireStaff : le PDG renvoie un membre, sans remboursement : il disparaît, le revenu revient, l'événement ANNONCE part")
    fun fireRemovesTheMember() = withApp { app ->
        val company = app.company()
        val hired = updated(hire(company.id, 1, factoryId = app.starterId, n = 1)).state.staff.single()
        val staffId = hired.staffId!!
        val withCrew = app.fixture.figuresOf(company.id).ratePerHour

        val result = updated(fire(company.id, staffId, n = 2))

        assertTrue(result.state.staff.isEmpty())
        assertTrue(app.membersOf(company.id).isEmpty())
        assertEquals(app.engine.ratePerHour(app.engine.startingLevels(), result.state.multiplier), result.state.ratePerHour, "sans équipe, le revenu d'avant")
        assertTrue(withCrew > result.state.ratePerHour)
        assertClose(company.donar, result.state.donar, "pas de remboursement : le Donar est celui d'avant (le prix payé à l'embauche reste payé)")
        assertEquals(InMemoryStaffStore.Write.Deleted(company.id, staffId), app.staff.writes.last())
        assertTrue(app.staff.agency.isEmpty(), "la place du jour est libérée")

        val event = app.companies.published.last()
        assertEquals(GameEventKind.ANNONCE, event.kind)
        assertEquals(kr, event.xuid)
        assertEquals("fireStaff", event.payload.getValue("action").jsonPrimitive.content)
        assertEquals(staffId.toString(), event.payload.getValue("staffId").jsonPrimitive.content)
        assertEquals(hired.role.name, event.payload.getValue("role").jsonPrimitive.content)
        assertEquals(hired.rarity.name, event.payload.getValue("rarity").jsonPrimitive.content)
    }

    @Test
    @DisplayName("fireStaff : le candidat renvoyé redevient embauchable aujourd'hui, au prix du moment")
    fun firedCandidateCanBeHiredAgain() = withApp { app ->
        val company = app.company()
        val staffId = updated(hire(company.id, 1, n = 1)).state.staff.single().staffId!!
        assertRefused(hire(company.id, 1, n = 2), RefusalCodes.ALREADY_OWNED)

        updated(fire(company.id, staffId, n = 3))
        val free = staffList(candidates(company.id)).single { it.slot == 1 }
        assertEquals(null, free.staffId)
        assertEquals(app.cost(company.id, 1), free.hireCost)

        val again = updated(hire(company.id, 1, n = 4))
        assertTrue(again.state.staff.single().staffId!! > staffId, "un nouvel identifiant, jamais réutilisé")
    }

    @Test
    @DisplayName("fireStaff : un associé reçoit 403 PAS_LE_PDG et rien n'est écrit ; un étranger, un membre inconnu ou d'une autre entreprise : 400")
    fun fireRefusals() = withApp { app ->
        val company = app.company()
        app.fixture.addMember(company.id, other, lingots = 0)
        val worker = app.member(company.id, app.starterId)
        val outsider = app.fixture.newCompany(stranger)
        val foreign = app.member(outsider.id, null)

        assertRefused(fire(company.id, worker.id, n = 1, actor = other.text), RefusalCodes.NOT_THE_CEO, HttpStatusCode.Forbidden)
        assertNothingWritten(app)
        assertEquals(1, app.membersOf(company.id).size)

        assertError(fire(company.id, worker.id, n = 2, actor = EngineFixture.STRANGER.text), "étranger : il est PDG de son entreprise, pas de celle-ci")
        assertError(fire(company.id, 9999, n = 3), "membre inconnu")
        assertError(fire(company.id, foreign.id, n = 4), "membre d'une autre entreprise")
        assertError(fire(99, worker.id, n = 5), "entreprise inconnue")
        for (id in listOf("0", "-1", "abc")) assertError(fire(company.id, id, n = 6), "staffId $id")
        assertError(client.delete("/v1/companies/${company.id}/staff/${worker.id}") { header(SERVER_KEY_HEADER, key) }, "sans requestId ni actor")
        assertEquals(1, app.membersOf(company.id).size)
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("fireStaff rejoué avec le même requestId : la même réponse, un seul renvoi ; version dépassée : 409 CONFLIT_REESSAYER, le membre reste")
    fun fireIdempotentAndConflict() = withApp { app ->
        val company = app.company()
        val worker = app.member(company.id, app.starterId)

        app.recording.rowsTouched = 0
        assertRefused(fire(company.id, worker.id, n = 5), RefusalCodes.CONFLICT_RETRY)
        assertEquals(1, app.membersOf(company.id).size, "un conflit ne renvoie personne")
        assertEquals(0, app.companies.published.size)

        app.recording.rowsTouched = 1
        val first = fire(company.id, worker.id, n = 5)
        val firstBody = first.bodyAsText()
        val second = fire(company.id, worker.id, n = 5)
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(firstBody, second.bodyAsText(), "le rejeu rend la réponse gardée, même si le membre n'existe plus")
        assertEquals(1, app.staff.writes.size)
        assertEquals(1, app.companies.published.size)
        assertFalse(app.membersOf(company.id).any { it.id == worker.id })
    }
}
