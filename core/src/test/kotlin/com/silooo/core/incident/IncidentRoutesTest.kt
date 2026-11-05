package com.silooo.core.incident

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
import com.silooo.core.db.InMemoryApiRequestStore
import com.silooo.core.db.RecordingDatabase
import com.silooo.core.db.Xuid
import com.silooo.core.economy.EngineFixture
import com.silooo.core.economy.EngineFixture.Companion.hours
import com.silooo.core.economy.InMemoryCompanyStore
import com.silooo.core.events.GameEventKind
import com.silooo.core.server.ServerPresence
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.FactoryRecord
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `resolveIncident` avec des faux (pas de base) : le moteur d'économie réel sur les `definitions/` du dépôt, un faux dépôt des
 * incidents, une horloge que le test avance, une présence que le test remplit, et une connexion qui note les `UPDATE companies` du
 * verrou optimiste. Les chiffres attendus sortent du moteur, jamais d'un littéral. La connexion ne change pas le faux dépôt de
 * l'entreprise : le Donar d'après se lit dans la réponse et dans l'`UPDATE` noté. Le SQL est dans `IncidentSqlTest`, le contrat
 * dans `ContractTest`.
 */
class IncidentRoutesTest {
    private val key = "cle-de-test"
    private val kr = EngineFixture.KR
    private val other = EngineFixture.OTHER
    private val stranger = EngineFixture.STRANGER

    private class Harness(
        val fixture: EngineFixture,
        val companies: InMemoryCompanyStore,
        val incidents: InMemoryIncidentStore,
        val presence: ServerPresence,
    ) {
        val engine get() = fixture.engine
        val recording get() = fixture.recording
        val starterId get() = fixture.starting.factoryId
        val reward get() = fixture.definitions.incidents.reward

        fun companyUpdates() = recording.statements.filter { it.sql.startsWith("UPDATE companies") }

        /** Une entreprise du PDG [EngineFixture.KR], avec [donar] en caisse. */
        fun company(donar: Double = 100.0): CompanyRecord = fixture.update(fixture.newCompany(EngineFixture.KR).copy(donar = donar))

        /** Un incident déjà ouvert, comme le minuteur l'aurait ouvert à l'instant de l'horloge. */
        fun incident(companyId: Long, factoryId: String = starterId, kind: String = "panne", slowdown: Double = 0.6): Long =
            incidents.seedOpen(companyId, factoryId, kind, slowdown, fixture.clock.instant())

        /** Les membres connectés maintenant (le signal de vie du serveur, frais à l'horloge de maintenant). */
        fun online(vararg xuids: Xuid) = presence.record("serveur-1", xuids.map { it.text })

        fun own(companyId: Long, factoryId: String, level: Int = 5) =
            fixture.putFactory(companyId, FactoryRecord(factoryId, FactoryState.ACTIVE, 0, level, null))
    }

    private fun withApp(block: suspend ApplicationTestBuilder.(Harness) -> Unit) = testApplication {
        val fixture = EngineFixture()
        val database = RecordingDatabase(fixture.recording)
        val companies = InMemoryCompanyStore(fixture.store)
        val incidents = InMemoryIncidentStore(fixture.store)
        val presence = ServerPresence(clock = fixture.clock)
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
                    incidentStore = incidents,
                    serverPresence = presence,
                ),
            )
        }
        block(Harness(fixture, companies, incidents, presence))
    }

    // --- outils

    private fun requestId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    private fun action(n: Int = 1, actor: String = kr.text): String = """{"requestId":"${requestId(n)}","actor":"$actor"}"""

    private suspend fun ApplicationTestBuilder.resolve(companyId: Any, incidentId: Any, n: Int = 1, actor: String = kr.text, body: String = action(n, actor)): HttpResponse =
        client.post("/v1/companies/$companyId/incidents/$incidentId/resolve") {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

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

    /** Égal à un arrondi de flottant près : les deux côtés viennent des mêmes formules du moteur. */
    private fun assertClose(expected: Double, actual: Double, what: String = "valeur") {
        assertTrue(abs(expected - actual) <= 1e-9 * maxOf(1.0, abs(expected)), "$what : attendu $expected, reçu $actual")
    }

    /** Rien n'a été écrit : ni entreprise, ni événement, et l'incident [incidentId] est toujours ouvert. */
    private fun assertNothingWritten(app: Harness, incidentId: Long? = null) {
        assertEquals(0, app.companyUpdates().size, "aucun UPDATE companies")
        assertEquals(0, app.companies.published.size, "aucun événement")
        if (incidentId != null) assertNull(app.incidents.rows.single { it.id == incidentId }.resolvedAt, "l'incident est resté ouvert")
    }

    // --- la réparation

    @Test
    @DisplayName("resolveIncident : l'incident est clos, l'usine reprend son rythme, l'entreprise reçoit la production ralentie puis la récompense, en une seule écriture")
    fun resolveRepairsAndPays() = withApp { app ->
        val company = app.company(donar = 100.0)
        val incidentId = app.incident(company.id, slowdown = 0.6)
        app.fixture.clock.advance(hours(2.0))
        val slowed = app.fixture.figuresOf(company.id)
        val nominal = slowed.forFactory(app.starterId).nominalRatePerHour
        val production = app.engine.onlineEarnings(slowed.ratePerHour, 2.0)
        val reward = app.engine.incidentReward(nominal)
        assertTrue(reward > 0.0, "une réparation rapporte quelque chose")
        assertClose(nominal * app.reward.revenueMinutes / 60.0, reward, "la récompense, en minutes de revenu")
        assertClose(slowed.ratePerHour, nominal * 0.6, "le revenu affiché est ralenti")

        val result = updated(resolve(company.id, incidentId))

        assertClose(100.0 + production + reward, result.state.donar, "donar après la réparation")
        assertClose(company.donarEarned + production + reward, result.state.donarEarned, "la récompense compte dans le Donar gagné")
        val factory = result.state.factories.single { it.factoryId == app.starterId }
        assertNull(factory.incidentId)
        assertNull(factory.incidentKind)
        assertClose(nominal, factory.ratePerHour, "l'usine reprend son rythme")
        assertClose(nominal, result.state.ratePerHour, "et l'entreprise aussi")
        assertEquals(company.version + 1, result.state.version)

        val update = app.companyUpdates().single()
        assertClose(100.0 + production + reward, update.parameters[1] as Double, "UPDATE donar")
        assertClose(company.donarEarned + production + reward, update.parameters[2] as Double, "UPDATE donar_earned")
        val closed = app.incidents.rows.single()
        assertEquals(app.fixture.clock.instant(), closed.resolvedAt)
        assertEquals(company.id, closed.companyId)
    }

    @Test
    @DisplayName("resolveIncident : l'événement INCIDENT « resolved » part vers chaque membre connecté et vers celui qui répare, une fois chacun")
    fun resolvePublishesToOnlineMembersAndTheActor() = withApp { app ->
        val company = app.company()
        app.fixture.addMember(company.id, other, 0)
        val incidentId = app.incident(company.id, kind = "courroie", slowdown = 0.7)
        app.fixture.clock.advance(hours(1.0))
        val nominal = app.fixture.figuresOf(company.id).forFactory(app.starterId).nominalRatePerHour
        // Le PDG est connecté, l'associé répare (il l'est aussi : il est dans la liste une seule fois), un inconnu connecté n'est pas membre.
        app.online(kr, other, stranger)

        updated(resolve(company.id, incidentId, actor = other.text))

        val events = app.companies.published
        assertEquals(setOf(kr, other), events.map { it.xuid }.toSet())
        assertEquals(2, events.size, "un événement par destinataire")
        for (event in events) {
            assertEquals(GameEventKind.INCIDENT, event.kind)
            assertEquals("resolved", event.payload.getValue("action").jsonPrimitive.content)
            assertEquals(company.id.toString(), event.payload.getValue("companyId").jsonPrimitive.content)
            assertEquals(incidentId.toString(), event.payload.getValue("incidentId").jsonPrimitive.content)
            assertEquals(app.starterId, event.payload.getValue("factoryId").jsonPrimitive.content)
            assertEquals("courroie", event.payload.getValue("kind").jsonPrimitive.content)
            assertEquals(other.text, event.payload.getValue("resolvedBy").jsonPrimitive.content)
            assertClose(app.engine.incidentReward(nominal), event.payload.getValue("reward").jsonPrimitive.content.toDouble(), "récompense de l'événement")
        }
    }

    @Test
    @DisplayName("resolveIncident : si personne n'est connecté, l'événement part quand même vers celui qui répare")
    fun resolvePublishesToTheActorWhenNobodyIsOnline() = withApp { app ->
        val company = app.company()
        val incidentId = app.incident(company.id)

        updated(resolve(company.id, incidentId))

        assertEquals(listOf(kr), app.companies.published.map { it.xuid })
    }

    @Test
    @DisplayName("resolveIncident : un associé peut réparer, pas seulement le PDG ; le PDG connecté reçoit l'événement")
    fun anAssociateMayRepair() = withApp { app ->
        val company = app.company()
        app.fixture.addMember(company.id, other, 0)
        val incidentId = app.incident(company.id)
        app.online(kr)

        val result = updated(resolve(company.id, incidentId, actor = other.text))

        assertNull(result.state.factories.single { it.factoryId == app.starterId }.incidentId)
        assertNotNull(app.incidents.rows.single().resolvedAt)
        assertEquals(setOf(kr, other), app.companies.published.map { it.xuid }.toSet())
    }

    @Test
    @DisplayName("resolveIncident : rejoué avec le même requestId, la réponse est la même et rien n'est écrit deux fois")
    fun resolveIsIdempotent() = withApp { app ->
        val company = app.company()
        val incidentId = app.incident(company.id)
        app.fixture.clock.advance(hours(1.0))

        val first = resolve(company.id, incidentId, n = 4)
        val firstBody = first.bodyAsText()
        app.fixture.clock.advance(hours(1.0))
        val second = resolve(company.id, incidentId, n = 4)

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(firstBody, second.bodyAsText())
        assertEquals(1, app.companyUpdates().size)
        assertEquals(1, app.companies.published.size)
    }

    @Test
    @DisplayName("resolveIncident : un incident déjà clos rend 200 sans rien changer : ni Donar, ni récompense, ni événement")
    fun alreadyClosedIsANoOp() = withApp { app ->
        val company = app.company(donar = 100.0)
        val incidentId = app.incident(company.id)
        app.incidents.close(app.fixture.connection, company.id, incidentId, app.fixture.clock.instant())
        val closedAt = app.incidents.rows.single().resolvedAt
        app.fixture.clock.advance(hours(3.0))

        val result = updated(resolve(company.id, incidentId, n = 5))

        assertEquals(100.0, result.state.donar, "la réponse est l'état d'aujourd'hui, la production en attente n'est pas versée")
        assertNull(result.state.factories.single { it.factoryId == app.starterId }.incidentId)
        assertEquals(0, app.companyUpdates().size)
        assertEquals(0, app.companies.published.size)
        assertEquals(closedAt, app.incidents.rows.single().resolvedAt, "la date de réparation ne bouge pas")
    }

    @Test
    @DisplayName("resolveIncident : réparer deux fois avec deux requestId ne paie qu'une fois, et l'associé qui arrive trop tard ne touche rien")
    fun theRewardIsPaidOnce() = withApp { app ->
        val company = app.company()
        app.fixture.addMember(company.id, other, 0)
        val incidentId = app.incident(company.id)
        app.fixture.clock.advance(hours(1.0))

        updated(resolve(company.id, incidentId, n = 1, actor = kr.text))
        val late = updated(resolve(company.id, incidentId, n = 2, actor = other.text))

        assertEquals(1, app.companyUpdates().size, "une seule écriture de l'entreprise")
        assertEquals(1, app.companies.published.filter { it.xuid == kr }.size)
        assertEquals(0, app.companies.published.count { it.xuid == other }, "l'associé en retard ne reçoit pas un second événement")
        assertNull(late.state.factories.single { it.factoryId == app.starterId }.incidentId)
    }

    @Test
    @DisplayName("resolveIncident : une usine arrêtée faute de courant ne rapporte aucune récompense, mais l'incident se clôt")
    fun aStoppedFactoryHasNoReward() = withApp { app ->
        val era = app.fixture.definitions.incidents.electricity.fromEra
        val electric = app.fixture.definitions.factories.factories.first { it.needsElectricity }.id
        val company = app.fixture.update(app.company(donar = 50.0).copy(era = era, courseNumber = era))
        app.own(company.id, electric)
        val incidentId = app.incident(company.id, factoryId = electric)
        app.fixture.clock.advance(hours(1.0))
        assertTrue(app.fixture.figuresOf(company.id).forFactory(electric).stoppedNoPower)

        val result = updated(resolve(company.id, incidentId))

        val factory = result.state.factories.single { it.factoryId == electric }
        assertNull(factory.incidentId)
        assertTrue(factory.stoppedNoPower, "la réparation ne rallume pas le courant")
        assertNotNull(app.incidents.rows.single().resolvedAt)
        assertEquals(0.0, app.companies.published.single().payload.getValue("reward").jsonPrimitive.content.toDouble())
    }

    @Test
    @DisplayName("resolveIncident : version dépassée (un autre associé a écrit) : 409 CONFLIT_REESSAYER, l'incident reste ouvert, et le même requestId passe au nouvel essai")
    fun resolveOnVersionConflict() = withApp { app ->
        val company = app.company()
        val incidentId = app.incident(company.id)

        app.recording.rowsTouched = 0
        val conflict = resolve(company.id, incidentId, n = 9)
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals(RefusalCodes.CONFLICT_RETRY, SiloooJson.decodeFromString(RefusedBody.serializer(), conflict.bodyAsText()).reason)
        // Le verrou est pris avant tout : la tentative d'UPDATE n'a touché aucune ligne, et ni l'incident ni les événements ne bougent.
        assertEquals(0, app.companies.published.size)
        assertNull(app.incidents.rows.single().resolvedAt, "l'incident est resté ouvert")

        app.recording.rowsTouched = 1
        assertEquals(HttpStatusCode.OK, resolve(company.id, incidentId, n = 9).status, "le conflit n'est pas gardé : le nouvel essai passe")
        assertNotNull(app.incidents.rows.single().resolvedAt)
    }

    // --- les demandes refusées

    @Test
    @DisplayName("resolveIncident : un acteur qui n'est pas de l'entreprise, ou qui n'est de aucune : 400, rien d'écrit")
    fun aNonMemberCannotRepair() = withApp { app ->
        val company = app.company()
        val incidentId = app.incident(company.id)
        val rival = app.fixture.newCompany(other)

        assertError(resolve(company.id, incidentId, actor = stranger.text), "un inconnu")
        assertError(resolve(company.id, incidentId, n = 2, actor = other.text), "le PDG d'une autre entreprise")
        assertError(resolve(rival.id, incidentId, n = 3), "le PDG, dans l'entreprise d'un autre")
        assertNothingWritten(app, incidentId)
    }

    @Test
    @DisplayName("resolveIncident : un incident inconnu, ou qui est d'une autre entreprise : 400, et l'incident de l'autre reste ouvert")
    fun anUnknownOrForeignIncidentIsRejected() = withApp { app ->
        val company = app.company()
        val rival = app.fixture.newCompany(other)
        val foreign = app.incident(rival.id)

        assertError(resolve(company.id, 999), "un incident qui n'existe pas")
        assertError(resolve(company.id, foreign, n = 2), "l'incident de l'entreprise voisine")
        assertNothingWritten(app, foreign)
    }

    @Test
    @DisplayName("resolveIncident : des identifiants mal formés ou une entreprise inconnue donnent 400")
    fun badIdentifiers() = withApp { app ->
        val company = app.company()
        val incidentId = app.incident(company.id)

        for ((companyPath, incidentPath) in listOf("0" to "$incidentId", "-1" to "$incidentId", "abc" to "$incidentId", "99" to "$incidentId", "${company.id}" to "0", "${company.id}" to "-3", "${company.id}" to "x")) {
            assertError(resolve(companyPath, incidentPath), "entreprise $companyPath, incident $incidentPath")
        }
        assertNothingWritten(app, incidentId)
    }

    @Test
    @DisplayName("resolveIncident : un corps sans requestId, sans acteur, ou qui n'est pas du JSON : 400")
    fun badBodies() = withApp { app ->
        val company = app.company()
        val incidentId = app.incident(company.id)

        assertError(resolve(company.id, incidentId, body = """{"actor":"${kr.text}"}"""), "sans requestId")
        assertError(resolve(company.id, incidentId, body = """{"requestId":"${requestId(1)}"}"""), "sans acteur")
        assertError(resolve(company.id, incidentId, body = """{"requestId":"pas-un-uuid","actor":"${kr.text}"}"""), "requestId qui n'est pas un UUID")
        assertError(resolve(company.id, incidentId, body = "pas du json"), "pas du JSON")
        assertNothingWritten(app, incidentId)
    }

    @Test
    @DisplayName("resolveIncident : sans la clé du serveur, 401 et rien d'écrit")
    fun theKeyIsRequired() = withApp { app ->
        val company = app.company()
        val incidentId = app.incident(company.id)

        val response = client.post("/v1/companies/${company.id}/incidents/$incidentId/resolve") {
            contentType(ContentType.Application.Json)
            setBody(action())
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertNothingWritten(app, incidentId)
    }
}
