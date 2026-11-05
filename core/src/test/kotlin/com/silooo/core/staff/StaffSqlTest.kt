package com.silooo.core.staff

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
import com.silooo.core.api.Staff
import com.silooo.core.db.MariaDbSupport
import com.silooo.core.db.Xuid
import com.silooo.core.definitions.StaffRarity
import com.silooo.core.definitions.StaffRole
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
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le personnel sur la vraie MariaDB : `JdbcStaffStore` ligne à ligne, puis `getCandidates`, `hire`, `assignStaff` et
 * `fireStaff` par HTTP avec le vrai moteur, le vrai dépôt SQL et la vraie idempotence. Ce que les tests de route
 * (`StaffRoutesTest`) ne peuvent pas voir : ce qui arrive vraiment dans `staff`, `agency_candidates`, `companies` (`donar`,
 * `version`) et `game_events`, dans une seule transaction, et ce que donne une concurrence réelle. Ignoré sans Docker (voir
 * [MariaDbSupport]) : la CI les rejoue.
 *
 * Les chiffres attendus (prix, candidats) sortent du moteur, des `definitions/` et de `AgencyDraw`, jamais d'un littéral
 * recopié.
 */
class StaffSqlTest {
    private val database get() = MariaDbSupport.database
    private val definitions = TestDefinitions.loaded
    private val personnel = definitions.personnel
    private val engine = EconomyEngine(definitions)
    private val store = JdbcSessionStore()
    private val clock = MutableClock(START)
    private val presence = ServerPresence(clock = clock)
    private val draw = AgencyDraw(personnel)
    private val jdbc = JdbcStaffStore()
    private val key = "cle-de-test"

    private val starter = definitions.economy.prestige.courseStart.factoryId
    private val second = engine.factoryIds().first { it != starter }
    private val today: LocalDate get() = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

    /** Assez de Donar pour n'importe quelle embauche d'une entreprise neuve : cent fois la plus chère. */
    private val rich: Double
        get() = engine.hireCost(StaffRole.CHEF, StaffRarity.EPIQUE, engine.ratePerHour(engine.startingLevels(), engine.globalMultiplier(0.0, 0))) * 100

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    // --- outils de la couche SQL

    private fun candidate(slot: Int, role: StaffRole = StaffRole.SALARIE, rarity: StaffRarity = StaffRarity.COMMUN) = Candidate(
        slot = slot,
        name = "Candidat $slot",
        skinId = personnel.agency.appearances.getValue(role).first(),
        role = role,
        rarity = rarity,
    )

    private fun bonusOf(id: Long): String? = MariaDbSupport.string("SELECT CAST(bonus_percent AS CHAR) FROM staff WHERE id = ?", id)

    private fun factoryOf(id: Long): String? = MariaDbSupport.string("SELECT factory_id FROM staff WHERE id = ?", id)

    // --- JdbcStaffStore

    @Test
    @DisplayName("insert, find et loadStaff : le membre écrit est relu tel quel, par identifiant croissant, et seulement dans son entreprise")
    fun insertFindAndLoad() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val b = MariaDbSupport.insertCompany("B", 1)
        val ids = MariaDbSupport.raw { c ->
            listOf(
                jdbc.insert(c, a, candidate(1, StaffRole.SALARIE, StaffRarity.RARE), starter, 25.0),
                jdbc.insert(c, a, candidate(2, StaffRole.CHEF, StaffRarity.EPIQUE), null, 60.0),
                jdbc.insert(c, b, candidate(3), second, 10.0),
            )
        }

        assertTrue(ids[0] < ids[1] && ids[1] < ids[2], "des identifiants qui croissent : $ids")
        assertEquals("25.00", bonusOf(ids[0]))
        assertEquals("60.00", bonusOf(ids[1]))

        val first = MariaDbSupport.raw { jdbc.find(it, a, ids[0]) }!!
        assertEquals(ids[0], first.id)
        assertEquals(starter, first.factoryId)
        assertEquals("Candidat 1", first.name)
        assertEquals(personnel.agency.appearances.getValue(StaffRole.SALARIE).first(), first.skinId)
        assertEquals(StaffRole.SALARIE, first.role)
        assertEquals(StaffRarity.RARE, first.rarity)
        val chef = MariaDbSupport.raw { jdbc.find(it, a, ids[1]) }!!
        assertNull(chef.factoryId, "sans usine demandée, la colonne reste NULL")
        assertEquals(StaffRole.CHEF, chef.role)
        assertEquals(StaffRarity.EPIQUE, chef.rarity)

        assertNull(MariaDbSupport.raw { jdbc.find(it, a, ids[2]) }, "le membre d'une autre entreprise est introuvable")
        assertNull(MariaDbSupport.raw { jdbc.find(it, a, 987654L) })
        assertEquals(listOf(ids[0], ids[1]), MariaDbSupport.raw { store.loadStaff(it, a) }.map { it.id })
        assertEquals(listOf(ids[2]), MariaDbSupport.raw { store.loadStaff(it, b) }.map { it.id })
    }

    @Test
    @DisplayName("Une ligne DIRECTEUR (rôle de la colonne, pas encore du jeu) n'est ni trouvée ni comptée dans le personnel")
    fun directeurRowsAreLeftOut() {
        val a = MariaDbSupport.insertCompany("A", 0)
        MariaDbSupport.update(
            "INSERT INTO staff (company_id, factory_id, name, skin_id, role, rarity, bonus_percent) VALUES (?, NULL, 'Directeur', 'costume', 'DIRECTEUR', 'RARE', 0)",
            a,
        )
        val directeur = MariaDbSupport.long("SELECT id FROM staff WHERE company_id = ?", a)

        assertNull(MariaDbSupport.raw { jdbc.find(it, a, directeur) })
        assertEquals(emptyList<Long>(), MariaDbSupport.raw { store.loadStaff(it, a) }.map { it.id })
    }

    @Test
    @DisplayName("recordHire et hiredSlots : les places embauchées du jour, par place croissante, telles qu'elles ont été proposées")
    fun hiredSlotsRoundTrip() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val b = MariaDbSupport.insertCompany("B", 1)
        val day = LocalDate.of(2026, 11, 5)
        val third = candidate(3, StaffRole.CHEF, StaffRarity.RARE)
        val first = candidate(1, StaffRole.SALARIE, StaffRarity.EPIQUE)
        MariaDbSupport.raw { c ->
            val firstId = jdbc.insert(c, a, first, null, 60.0)
            val thirdId = jdbc.insert(c, a, third, null, 25.0)
            jdbc.recordHire(c, a, day, third, 25.0, thirdId)
            jdbc.recordHire(c, a, day, first, 60.0, firstId)
            jdbc.recordHire(c, a, day.plusDays(1), candidate(2), 10.0, firstId)
            jdbc.recordHire(c, b, day, candidate(4), 10.0, jdbc.insert(c, b, candidate(4), null, 10.0))
            // Une ligne sans embauche (la liste n'est pas gardée, mais le schéma le permet) n'est pas une place prise.
            c.prepareStatement(
                "INSERT INTO agency_candidates (company_id, day, slot, name, skin_id, role, rarity, bonus_percent) VALUES (?, ?, 5, 'Libre', 'x', 'SALARIE', 'COMMUN', 0)",
            ).use {
                it.setLong(1, a)
                it.setObject(2, day)
                it.executeUpdate()
            }
        }

        val slots = MariaDbSupport.raw { jdbc.hiredSlots(it, a, day) }
        assertEquals(listOf(1, 3), slots.map { it.candidate.slot })
        assertEquals(first, slots[0].candidate)
        assertEquals(third, slots[1].candidate)
        assertEquals(listOf(2), MariaDbSupport.raw { jdbc.hiredSlots(it, a, day.plusDays(1)) }.map { it.candidate.slot }, "un autre jour, une autre liste")
        assertEquals(listOf(4), MariaDbSupport.raw { jdbc.hiredSlots(it, b, day) }.map { it.candidate.slot })
        assertEquals(emptyList<HiredSlot>(), MariaDbSupport.raw { jdbc.hiredSlots(it, a, day.minusDays(1)) })
    }

    @Test
    @DisplayName("recordHire : une place du jour ne s'embauche pas deux fois (clé primaire), même en dehors du service")
    fun aSlotIsHiredOncePerDay() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val day = LocalDate.of(2026, 11, 5)
        MariaDbSupport.raw { c ->
            val id = jdbc.insert(c, a, candidate(1), null, 10.0)
            jdbc.recordHire(c, a, day, candidate(1), 10.0, id)
            val again = jdbc.insert(c, a, candidate(1), null, 10.0)
            assertThrows(SQLException::class.java) { jdbc.recordHire(c, a, day, candidate(1), 10.0, again) }
        }
        assertEquals(1L, MariaDbSupport.long("SELECT COUNT(*) FROM agency_candidates WHERE company_id = ?", a))
    }

    @Test
    @DisplayName("assign : change l'usine du membre de son entreprise seulement ; un membre absent est une erreur de programmation, rien n'est touché")
    fun assignOnlyTouchesTheCompanysMember() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val b = MariaDbSupport.insertCompany("B", 1)
        val mine = MariaDbSupport.raw { jdbc.insert(it, a, candidate(1), null, 10.0) }
        val theirs = MariaDbSupport.raw { jdbc.insert(it, b, candidate(2), starter, 10.0) }

        MariaDbSupport.raw { jdbc.assign(it, a, mine, second) }
        assertEquals(second, factoryOf(mine))

        assertThrows(IllegalStateException::class.java) { MariaDbSupport.raw { jdbc.assign(it, a, theirs, second) } }
        assertEquals(starter, factoryOf(theirs), "le membre d'une autre entreprise n'a pas bougé")
        assertThrows(IllegalStateException::class.java) { MariaDbSupport.raw { jdbc.assign(it, a, 987654L, second) } }
    }

    @Test
    @DisplayName("delete : la ligne du membre et toutes ses places notées disparaissent ; les autres membres et entreprises ne bougent pas")
    fun deleteFreesTheMemberAndItsSlots() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val b = MariaDbSupport.insertCompany("B", 1)
        val day = LocalDate.of(2026, 11, 5)
        val ids = MariaDbSupport.raw { c ->
            val gone = jdbc.insert(c, a, candidate(1), null, 10.0)
            val kept = jdbc.insert(c, a, candidate(2), null, 10.0)
            val other = jdbc.insert(c, b, candidate(1), null, 10.0)
            jdbc.recordHire(c, a, day, candidate(1), 10.0, gone)
            jdbc.recordHire(c, a, day.plusDays(1), candidate(3), 10.0, gone)
            jdbc.recordHire(c, a, day, candidate(2), 10.0, kept)
            jdbc.recordHire(c, b, day, candidate(1), 10.0, other)
            Triple(gone, kept, other)
        }

        MariaDbSupport.raw { jdbc.delete(it, a, ids.first) }

        assertEquals(listOf(ids.second), MariaDbSupport.raw { store.loadStaff(it, a) }.map { it.id })
        assertEquals(listOf(ids.third), MariaDbSupport.raw { store.loadStaff(it, b) }.map { it.id })
        assertEquals(listOf(2), MariaDbSupport.raw { jdbc.hiredSlots(it, a, day) }.map { it.candidate.slot })
        assertEquals(emptyList<HiredSlot>(), MariaDbSupport.raw { jdbc.hiredSlots(it, a, day.plusDays(1)) })
        assertEquals(listOf(1), MariaDbSupport.raw { jdbc.hiredSlots(it, b, day) }.map { it.candidate.slot })
    }

    @Test
    @DisplayName("delete : un membre absent ou d'une autre entreprise est une erreur de programmation, et la place de l'autre entreprise reste")
    fun deleteOfAnAbsentMemberFails() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val b = MariaDbSupport.insertCompany("B", 1)
        val day = LocalDate.of(2026, 11, 5)
        val theirs = MariaDbSupport.raw { c ->
            jdbc.insert(c, b, candidate(1), null, 10.0).also { jdbc.recordHire(c, b, day, candidate(1), 10.0, it) }
        }

        assertThrows(IllegalStateException::class.java) { MariaDbSupport.raw { jdbc.delete(it, a, theirs) } }
        assertThrows(IllegalStateException::class.java) { MariaDbSupport.raw { jdbc.delete(it, a, 987654L) } }

        assertEquals(1, MariaDbSupport.raw { store.loadStaff(it, b) }.size)
        assertEquals(1, MariaDbSupport.raw { jdbc.hiredSlots(it, b, day) }.size)
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
        economyFigures = EngineFigures(engine, store, clock),
        companyBootstrap = DefinitionsBootstrap(engine, definitions),
        companyStore = companyStore,
        staffStore = jdbc,
        serverPresence = presence,
    )

    /**
     * Crée l'entreprise de [KR] par le vrai `join`, lui donne [rich] Donar et remet son dernier calcul de production à l'heure
     * de l'horloge du test (`join` le pose à l'heure de la base : sans cela, de la production en attente fausserait les débits).
     */
    private fun startCompany(): Long {
        val joined = runBlocking {
            SessionService(database, store, EngineFigures(engine, store, clock), DefinitionsBootstrap(engine, definitions)).join(KR, "Kr")
        }
        MariaDbSupport.update(
            "UPDATE companies SET donar = ?, last_collect_at = ? WHERE id = ?",
            rich,
            LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
            joined.companyId,
        )
        return joined.companyId
    }

    /** Monte le service sur la base, avec l'entreprise de [KR] déjà créée par [startCompany]. */
    private fun withCompany(companyStore: CompanyStore = JdbcCompanyStore(), block: suspend ApplicationTestBuilder.(Long) -> Unit) {
        val id = startCompany()
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

    private suspend fun ApplicationTestBuilder.get(path: String): HttpResponse = client.get(path) { header(SERVER_KEY_HEADER, key) }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String): HttpResponse =
        client.post(path) {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.hire(companyId: Long, slot: Int, factoryId: String? = null, n: Int = 1, actor: Xuid = KR) =
        post("/v1/companies/$companyId/agency/$slot/hire", action(n, actor, factoryId?.let { ""","factoryId":"$it"""" }.orEmpty()))

    private suspend fun ApplicationTestBuilder.assign(companyId: Long, staffId: Long, factoryId: String, n: Int = 1, actor: Xuid = KR) =
        client.patch("/v1/companies/$companyId/staff/$staffId") {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(action(n, actor, ""","factoryId":"$factoryId""""))
        }

    private suspend fun ApplicationTestBuilder.fire(companyId: Long, staffId: Long, n: Int = 1, actor: Xuid = KR) =
        client.delete("/v1/companies/$companyId/staff/$staffId?requestId=${requestId(n)}&actor=${actor.text}") { header(SERVER_KEY_HEADER, key) }

    private suspend fun updated(response: HttpResponse): CompanyUpdated {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        return SiloooJson.decodeFromString(CompanyUpdated.serializer(), body)
    }

    private suspend fun ApplicationTestBuilder.stateOf(companyId: Long): CompanyState {
        val response = get("/v1/companies/$companyId")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return SiloooJson.decodeFromString(CompanyState.serializer(), response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.agencyList(companyId: Long): List<Staff> {
        val response = get("/v1/companies/$companyId/agency")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return SiloooJson.decodeFromString(ListSerializer(Staff.serializer()), response.bodyAsText())
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

    /** Une deuxième usine active, de niveau 1, à l'entreprise : de quoi y affecter un membre. */
    private fun ownSecondFactory(companyId: Long) {
        MariaDbSupport.update(
            "INSERT INTO company_factories (company_id, factory_id, state, restore_step, level) VALUES (?, ?, 'ACTIVE', 4, 1)",
            companyId,
            second,
        )
    }

    // --- lectures en base

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

    /** Les membres de l'entreprise : `id|usine|nom|apparence|rôle|rareté|bonus`. */
    private fun staffRows(companyId: Long): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(id, '|', IFNULL(factory_id, '-'), '|', name, '|', skin_id, '|', role, '|', rarity, '|', bonus_percent) FROM staff WHERE company_id = ? ORDER BY id",
        companyId,
    )

    /** Les places notées : `jour|place|nom|apparence|rôle|rareté|identifiant`. */
    private fun agencyRows(companyId: Long): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(day, '|', slot, '|', name, '|', skin_id, '|', role, '|', rarity, '|', hired_staff_id) FROM agency_candidates WHERE company_id = ? ORDER BY day, slot",
        companyId,
    )

    /** Les événements dans l'ordre : `xuid|type|action`. */
    private fun events(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(IFNULL(CAST(xuid AS CHAR), '-'), '|', kind, '|', JSON_VALUE(payload, '\$.action')) FROM game_events ORDER BY id",
    )

    private fun staffEvents(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(JSON_VALUE(payload, '\$.action'), '|', JSON_VALUE(payload, '\$.staffId')) FROM game_events " +
            "WHERE JSON_VALUE(payload, '\$.action') IN ('hire', 'assignStaff', 'fireStaff') ORDER BY id",
    )

    private fun bonus(rarity: StaffRarity): String = String.format(Locale.ROOT, "%.2f", engine.staffBonusPercent(rarity))

    private fun assertClose(expected: Double, actual: Double, what: String) {
        assertTrue(abs(expected - actual) <= 1e-9 * maxOf(1.0, abs(expected)), "$what : attendu $expected, reçu $actual")
    }

    private fun slotOf(role: StaffRole, nth: Int = 0): Int = personnel.agency.rolesPerSlot.withIndex().filter { it.value == role }[nth].index + 1

    // --- getCandidates

    @Test
    @DisplayName("getCandidates : la liste du jour tirée pour l'entreprise ; lire n'écrit aucune ligne")
    fun candidatesAreTheDraw() = withCompany { id ->
        val list = agencyList(id)

        val drawn = draw.candidates(id, today)
        assertEquals(drawn.map { it.name }, list.map { it.name })
        assertEquals(drawn.map { it.rarity }, list.map { it.rarity })
        assertEquals(drawn.map { it.role }, list.map { it.role })
        assertTrue(list.all { it.staffId == null && it.hireCost != null })
        assertEquals(0L, MariaDbSupport.count("staff"))
        assertEquals(0L, MariaDbSupport.count("agency_candidates"))
        assertEquals(emptyList<String?>(), events())
    }

    // --- hire

    @Test
    @DisplayName("hire : le prix est débité, la version monte d'un, le membre, la place du jour et l'événement ANNONCE sont écrits ensemble")
    fun hireWritesEverythingTogether() = withCompany { id ->
        val slot = slotOf(StaffRole.SALARIE, 1)
        val proposed = draw.candidates(id, today)[slot - 1]
        val before = stateOf(id)
        val cost = engine.hireCost(proposed.role, proposed.rarity, before.ratePerHour)
        val donarBefore = donar(id)
        val versionBefore = version(id)

        val result = updated(hire(id, slot, factoryId = starter))

        val member = result.state.staff.single()
        assertClose(donarBefore - cost, donar(id), "donar en base")
        assertClose(donarBefore - cost, result.state.donar, "donar rendu")
        assertEquals(versionBefore + 1, version(id))
        assertEquals(
            listOf("${member.staffId}|$starter|${proposed.name}|${proposed.skinId}|${proposed.role}|${proposed.rarity}|${bonus(proposed.rarity)}"),
            staffRows(id),
        )
        assertEquals(
            listOf("${today}|$slot|${proposed.name}|${proposed.skinId}|${proposed.role}|${proposed.rarity}|${member.staffId}"),
            agencyRows(id),
        )
        assertEquals(listOf("${KR.text}|ANNONCE|hire"), events())
        assertEquals(listOf("hire|${member.staffId}"), staffEvents())
    }

    @Test
    @DisplayName("hire : le membre est lu en base par getCompany et getCandidates, l'usine rapporte plus, la place montre son staffId")
    fun hiredMemberShowsEverywhere() = withCompany { id ->
        val slot = slotOf(StaffRole.SALARIE)
        val before = stateOf(id)

        val member = updated(hire(id, slot, factoryId = starter)).state.staff.single()

        val after = stateOf(id)
        assertEquals(member.staffId, after.staff.single().staffId)
        assertEquals(starter, after.staff.single().factoryId)
        assertTrue(after.ratePerHour > before.ratePerHour, "le revenu net monte avec un membre qui travaille")
        val shown = agencyList(id).single { it.slot == slot }
        assertEquals(member.staffId, shown.staffId)
        assertEquals(starter, shown.factoryId)
        assertNull(shown.hireCost)
    }

    @Test
    @DisplayName("hire : les refus (DEJA_POSSEDE, PAS_ASSEZ_DE_DONAR, USINE_VERROUILLEE) n'écrivent rien en base")
    fun refusalsWriteNothing() = withCompany { id ->
        val slot = slotOf(StaffRole.SALARIE)
        updated(hire(id, slot, n = 1))
        val donarAfter = donar(id)
        val versionAfter = version(id)
        val rowsAfter = staffRows(id)

        assertEquals(RefusalCodes.ALREADY_OWNED, refusalOf(hire(id, slot, n = 2)))
        assertEquals(RefusalCodes.FACTORY_LOCKED, refusalOf(hire(id, slotOf(StaffRole.SALARIE, 1), factoryId = second, n = 3)))
        MariaDbSupport.update("UPDATE companies SET donar = 0 WHERE id = ?", id)
        assertEquals(RefusalCodes.NOT_ENOUGH_DONAR, refusalOf(hire(id, slotOf(StaffRole.SALARIE, 1), n = 4)))

        assertEquals(rowsAfter, staffRows(id))
        assertEquals(1, agencyRows(id).size)
        assertEquals(versionAfter, version(id))
        assertEquals(0.0, donar(id))
        assertTrue(donarAfter > 0.0)
        assertEquals(listOf("${KR.text}|ANNONCE|hire"), events())
    }

    @Test
    @DisplayName("hire : si la publication de l'événement échoue, tout est annulé (Donar, version, membre, place du jour)")
    fun hireRollsBackAsOne() = withCompany(FailingPublish()) { id ->
        val donarBefore = donar(id)

        val response = hire(id, slotOf(StaffRole.SALARIE), factoryId = starter)

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(donarBefore, donar(id))
        assertEquals(0L, version(id))
        assertEquals(emptyList<String?>(), staffRows(id))
        assertEquals(emptyList<String?>(), agencyRows(id))
        assertEquals(emptyList<String?>(), events())
    }

    @Test
    @DisplayName("hire rejoué avec le même requestId : la même réponse, une seule ligne, un seul débit")
    fun hireReplayWritesOnce() = withCompany { id ->
        val slot = slotOf(StaffRole.SALARIE)

        val first = hire(id, slot, n = 5).bodyAsText()
        val donarAfter = donar(id)
        clock.advance(hours(1.0))
        val second = hire(id, slot, n = 5)

        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(first, second.bodyAsText())
        assertEquals(1, staffRows(id).size)
        assertEquals(donarAfter, donar(id))
        assertEquals(1, events().size)
    }

    @Test
    @DisplayName("Des embauches simultanées de la même place : une seule passe, un seul débit, un seul membre, les autres sont refusées")
    fun parallelHiresOfTheSameSlot() = withCompany { id ->
        val slot = slotOf(StaffRole.SALARIE)
        val proposed = draw.candidates(id, today)[slot - 1]
        val cost = engine.hireCost(proposed.role, proposed.rarity, stateOf(id).ratePerHour)
        val donarBefore = donar(id)

        val responses = coroutineScope {
            val go = CompletableDeferred<Unit>()
            val jobs = (1..4).map { n -> async(Dispatchers.IO) { go.await(); hire(id, slot, n = n) } }
            go.complete(Unit)
            jobs.awaitAll()
        }

        assertEquals(1, responses.count { it.status == HttpStatusCode.OK }, "exactement une embauche passe")
        for (response in responses.filter { it.status != HttpStatusCode.OK }) {
            assertEquals(HttpStatusCode.Conflict, response.status)
            val reason = SiloooJson.decodeFromString(RefusedBody.serializer(), response.bodyAsText()).reason
            assertTrue(reason == RefusalCodes.CONFLICT_RETRY || reason == RefusalCodes.ALREADY_OWNED, "refus inattendu : $reason")
        }
        assertEquals(1, staffRows(id).size)
        assertEquals(1, agencyRows(id).size)
        assertClose(donarBefore - cost, donar(id), "un seul débit")
        assertEquals(1L, version(id))
        assertEquals(1, staffEvents().size)
    }

    @Test
    @DisplayName("Des embauches simultanées de places différentes : chaque embauche réussie est écrite en entier, chaque perdante laisse CONFLIT_REESSAYER")
    fun parallelHiresOfDifferentSlots() = withCompany { id ->
        val rate = stateOf(id).ratePerHour
        val drawn = draw.candidates(id, today)
        val donarBefore = donar(id)

        val results = coroutineScope {
            val go = CompletableDeferred<Unit>()
            val jobs = drawn.map { candidate -> async(Dispatchers.IO) { go.await(); candidate to hire(id, candidate.slot, n = 10 + candidate.slot) } }
            go.complete(Unit)
            jobs.awaitAll()
        }

        val won = results.filter { it.second.status == HttpStatusCode.OK }.map { it.first }
        assertTrue(won.isNotEmpty(), "au moins une embauche passe")
        for ((_, response) in results.filter { it.second.status != HttpStatusCode.OK }) {
            assertEquals(RefusalCodes.CONFLICT_RETRY, refusalOf(response))
        }
        assertEquals(won.size, staffRows(id).size)
        assertEquals(won.map { it.slot }.sorted(), agencyRows(id).map { it!!.split('|')[1].toInt() })
        assertEquals(won.size.toLong(), version(id))
        assertClose(donarBefore - won.sumOf { engine.hireCost(it.role, it.rarity, rate) }, donar(id), "somme des prix des embauches passées")
        assertEquals(won.size, staffEvents().size)
    }

    // --- assignStaff et fireStaff

    @Test
    @DisplayName("assignStaff : la ligne change d'usine, sans frais, la version monte, l'événement part ; l'affecter là où il est déjà n'écrit rien")
    fun assignMovesTheRow() = withCompany { id ->
        ownSecondFactory(id)
        val member = updated(hire(id, slotOf(StaffRole.SALARIE), factoryId = starter, n = 1)).state.staff.single()
        val donarBefore = donar(id)
        val versionBefore = version(id)

        val result = updated(assign(id, member.staffId!!, second, n = 2))

        assertEquals(second, result.state.staff.single().factoryId)
        assertEquals(second, factoryOf(member.staffId))
        assertEquals(donarBefore, donar(id), "l'affectation est gratuite")
        assertEquals(versionBefore + 1, version(id))
        assertEquals(listOf("hire|${member.staffId}", "assignStaff|${member.staffId}"), staffEvents())

        updated(assign(id, member.staffId, second, n = 3))
        assertEquals(versionBefore + 1, version(id), "un no-op n'écrit pas")
        assertEquals(2, staffEvents().size)
    }

    @Test
    @DisplayName("fireStaff : le PDG renvoie un membre sans remboursement ; sa ligne et sa place du jour disparaissent, la place redevient embauchable")
    fun fireDeletesTheRowAndFreesTheSlot() = withCompany { id ->
        val slot = slotOf(StaffRole.SALARIE)
        val hired = updated(hire(id, slot, factoryId = starter, n = 1)).state.staff.single()
        val donarBefore = donar(id)
        val versionBefore = version(id)

        val result = updated(fire(id, hired.staffId!!, n = 2))

        assertTrue(result.state.staff.isEmpty())
        assertEquals(emptyList<String?>(), staffRows(id))
        assertEquals(emptyList<String?>(), agencyRows(id))
        assertEquals(donarBefore, donar(id), "pas de remboursement")
        assertEquals(versionBefore + 1, version(id))
        assertEquals(listOf("hire|${hired.staffId}", "fireStaff|${hired.staffId}"), staffEvents())

        val again = updated(hire(id, slot, n = 3)).state.staff.single()
        assertTrue(again.staffId!! > hired.staffId, "un nouvel identifiant")
        assertEquals(1, agencyRows(id).size)
        assertEquals(1, staffRows(id).size)
    }

    @Test
    @DisplayName("Un associé embauche et affecte, mais ne renvoie pas : 403 PAS_LE_PDG et le membre reste en base")
    fun partnersCannotFire() = withCompany { id ->
        addPartner(id, OTHER)
        ownSecondFactory(id)
        val member = updated(hire(id, slotOf(StaffRole.SALARIE), factoryId = starter, n = 1, actor = OTHER)).state.staff.single()
        updated(assign(id, member.staffId!!, second, n = 2, actor = OTHER))

        val response = fire(id, member.staffId, n = 3, actor = OTHER)

        assertEquals(RefusalCodes.NOT_THE_CEO, refusalOf(response, HttpStatusCode.Forbidden))
        assertEquals(1, staffRows(id).size)
        assertEquals(second, factoryOf(member.staffId))
        assertEquals(listOf("hire|${member.staffId}", "assignStaff|${member.staffId}"), staffEvents())
    }

    @Test
    @DisplayName("fireStaff : si la publication de l'événement échoue, tout est annulé : le membre et sa place du jour restent")
    fun fireRollsBackAsOne() {
        val id = startCompany()
        val candidate = draw.candidates(id, today)[0]
        val staffId = MariaDbSupport.raw { c ->
            jdbc.insert(c, id, candidate, starter, 10.0).also { jdbc.recordHire(c, id, today, candidate, 10.0, it) }
        }
        val versionBefore = version(id)

        testApplication {
            application { siloooModule(dependencies(FailingPublish())) }
            assertEquals(HttpStatusCode.InternalServerError, fire(id, staffId).status)
        }

        assertEquals(1, staffRows(id).size)
        assertEquals(1, agencyRows(id).size)
        assertEquals(versionBefore, version(id))
        assertEquals(emptyList<String?>(), events())
    }
}
