package com.silooo.core.economy

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.api.CompanyState
import com.silooo.core.api.CompanyUpdated
import com.silooo.core.api.ErrorBody
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.FactoryState
import com.silooo.core.api.GameEvent
import com.silooo.core.api.Idempotency
import com.silooo.core.api.Island
import com.silooo.core.api.PrestigePreview
import com.silooo.core.api.PrestigeShare
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.RefusedBody
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.InMemoryApiRequestStore
import com.silooo.core.db.RecordingDatabase
import com.silooo.core.db.Xuid
import com.silooo.core.events.GameEventKind
import com.silooo.core.findings.InMemoryFindingStore
import com.silooo.core.mission.InMemoryMissionStore
import com.silooo.core.mission.MissionRow
import com.silooo.core.server.ServerPresence
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.FactoryRecord
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `getCompany`, `collect`, `buyLevels` et `restoreStep` avec des faux (pas de base) : le moteur d'économie réel sur les
 * `definitions/` du dépôt, un faux dépôt en mémoire, une horloge que le test avance, et une connexion qui note les
 * `UPDATE companies` que le verrou optimiste écrit. Les chiffres attendus sortent du moteur, jamais d'un littéral.
 * Le SQL et les transactions sont dans `CompanySqlTest`, le contrat dans `ContractTest`.
 */
class CompanyRoutesTest {
    private val key = "cle-de-test"
    private val kr = EngineFixture.KR
    private val other = EngineFixture.OTHER
    private val stranger = EngineFixture.STRANGER

    private class Harness(
        val fixture: EngineFixture,
        val database: RecordingDatabase,
        val companies: InMemoryCompanyStore,
        /** Les missions des joueurs, en mémoire : un encaissement qui rapporte avance la mission en cours. */
        val missions: InMemoryMissionStore,
        /** La présence que `join` interroge, sur l'horloge du test : un signal de vie se périme quand le test avance le temps. */
        val presence: ServerPresence,
        /** Le Carnet des joueurs, en mémoire : un encaissement qui rapporte y inscrit les ressources des usines en marche et verse leur prime. */
        val findings: InMemoryFindingStore,
    ) {
        val engine get() = fixture.engine
        val recording get() = fixture.recording

        /** L'usine de départ de la course (`prestige.departDeCourse`) et son niveau de départ. */
        val starterId get() = fixture.starting.factoryId
        val starterLevel get() = fixture.starting.level

        /** Une usine qui n'est pas celle de départ : elle n'est pas dans l'entreprise tant qu'un test ne l'y met pas. */
        val secondId get() = fixture.otherFactories().first()

        /** Les `UPDATE companies` écrits jusqu'ici. */
        fun companyUpdates() = recording.statements.filter { it.sql.startsWith("UPDATE companies") }

        /** Une entreprise du PDG [KR], avec [donar] en caisse et [awayHours] heures de production en attente. */
        fun company(donar: Double = 0.0, awayHours: Double = 0.0): CompanyRecord {
            val company = fixture.update(fixture.newCompany(EngineFixture.KR).copy(donar = donar))
            fixture.clock.advance(EngineFixture.hours(awayHours))
            return company
        }

        /**
         * La production que `collect`, `buyLevels` et `restoreStep` doivent verser après [hours] heures : le revenu du moteur,
         * plein, sans plafond (l'acteur est connecté).
         */
        fun production(companyId: Long, hours: Double): Double =
            engine.onlineEarnings(fixture.figuresOf(companyId).ratePerHour, hours)

        fun factory(companyId: Long, factoryId: String): FactoryRecord =
            fixture.store.loadFactories(fixture.connection, companyId).single { it.factoryId == factoryId }

        /**
         * Une entreprise du PDG [KR] à la course [course], avec [earned] Donar gagnés dans la course (le seuil par
         * défaut), [awayHours] heures de production en attente et [ownerLingots] Lingots au PDG.
         */
        fun prestigeCompany(
            course: Int = 1,
            earned: Double = engine.prestigeThreshold(course),
            awayHours: Double = 0.0,
            ownerLingots: Long = 0L,
        ): CompanyRecord {
            val base = fixture.newCompany(EngineFixture.KR, ownerLingots)
            val company = fixture.update(
                base.copy(courseNumber = course, era = engine.eraAt(course), stars = engine.starsAt(course), donar = earned, donarEarned = earned),
            )
            fixture.clock.advance(EngineFixture.hours(awayHours))
            return company
        }
    }

    private fun withApp(block: suspend ApplicationTestBuilder.(Harness) -> Unit) = testApplication {
        val fixture = EngineFixture()
        val database = RecordingDatabase(fixture.recording)
        val companies = InMemoryCompanyStore(fixture.store)
        val missions = InMemoryMissionStore(fixture.store)
        val presence = ServerPresence(clock = fixture.clock)
        val findings = InMemoryFindingStore(fixture.store)
        application {
            siloooModule(
                Dependencies(
                    database = database,
                    definitions = fixture.definitions,
                    apiKey = key,
                    economy = fixture.engine,
                    idempotency = Idempotency(database, InMemoryApiRequestStore(EngineFixture.START)),
                    sessionStore = fixture.store,
                    economyFigures = fixture.figures,
                    companyBootstrap = fixture.bootstrap,
                    companyStore = companies,
                    missionStore = missions,
                    findingStore = findings,
                    serverPresence = presence,
                ),
            )
        }
        block(Harness(fixture, database, companies, missions, presence, findings))
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

    private suspend fun ApplicationTestBuilder.collect(companyId: Long, n: Int = 1, actor: String = kr.text) =
        post("/v1/companies/$companyId/collect", action(n, actor))

    private suspend fun ApplicationTestBuilder.buy(companyId: Long, factoryId: String, amount: String, n: Int = 1, actor: String = kr.text) =
        post("/v1/companies/$companyId/factories/$factoryId/levels", action(n, actor, ""","amount":"$amount""""))

    private suspend fun ApplicationTestBuilder.restore(companyId: Long, factoryId: String, step: Int, n: Int = 1, actor: String = kr.text) =
        post("/v1/companies/$companyId/factories/$factoryId/restore", action(n, actor, ""","step":$step"""))

    private suspend fun ApplicationTestBuilder.prestige(companyId: Long, n: Int = 1, actor: String = kr.text) =
        post("/v1/companies/$companyId/prestige", action(n, actor))

    private suspend fun ApplicationTestBuilder.preview(companyId: Long) = get("/v1/companies/$companyId/prestige/preview")

    private suspend fun previewOf(response: HttpResponse): PrestigePreview {
        assertEquals(HttpStatusCode.OK, response.status)
        return SiloooJson.decodeFromString(PrestigePreview.serializer(), response.bodyAsText())
    }

    private suspend fun updated(response: HttpResponse): CompanyUpdated {
        assertEquals(HttpStatusCode.OK, response.status)
        return SiloooJson.decodeFromString(CompanyUpdated.serializer(), response.bodyAsText())
    }

    private suspend fun assertError(response: HttpResponse, reason: String = ErrorCodes.BAD_REQUEST) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(reason, SiloooJson.decodeFromString(ErrorBody.serializer(), response.bodyAsText()).reason)
    }

    private suspend fun assertRefused(response: HttpResponse, reason: String): RefusedBody {
        assertEquals(HttpStatusCode.Conflict, response.status)
        val body = SiloooJson.decodeFromString(RefusedBody.serializer(), response.bodyAsText())
        assertEquals(reason, body.reason)
        return body
    }

    /** Égal à un arrondi de flottant près : les deux côtés viennent des mêmes formules du moteur. */
    private fun assertClose(expected: Double, actual: Double, what: String = "valeur") {
        assertTrue(abs(expected - actual) <= 1e-9 * maxOf(1.0, abs(expected)), "$what : attendu $expected, reçu $actual")
    }

    private fun utc(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)

    // --- getCompany

    @Test
    @DisplayName("getCompany : l'état de l'entreprise, avec les chiffres du moteur, et rien d'écrit")
    fun getCompanyReturnsTheState() = withApp { app ->
        val company = app.company(donar = 12.5)
        val response = get("/v1/companies/${company.id}")

        assertEquals(HttpStatusCode.OK, response.status)
        val state = SiloooJson.decodeFromString(CompanyState.serializer(), response.bodyAsText())
        val figures = app.fixture.figuresOf(company.id)
        assertEquals(company.id, state.companyId)
        assertEquals(company.name, state.name)
        assertEquals(12.5, state.donar)
        assertEquals(Island(company.island.x, company.island.z), state.island, "l'île vient de la case d'archipel de l'entreprise")
        assertEquals(company.version, state.version)
        assertEquals(figures.ratePerHour, state.ratePerHour)
        assertEquals(figures.multiplier, state.multiplier)
        assertEquals(figures.threshold, state.threshold)
        val starter = state.factories.single()
        assertEquals(app.starterId, starter.factoryId)
        assertEquals(FactoryState.ACTIVE, starter.state)
        assertEquals(app.starterLevel, starter.level)
        assertEquals(app.engine.nextLevelCost(app.starterId, app.starterLevel), starter.nextCost)
        assertEquals(app.engine.nextMilestone(app.starterLevel), starter.nextMilestone)
        assertEquals(0, app.recording.statements.size, "getCompany ne lit que : aucune écriture")
        assertEquals(0, app.companies.published.size)
    }

    @Test
    @DisplayName("getCompany : une entreprise inconnue, ou un identifiant qui n'en est pas un, donne 400 REQUETE_INVALIDE")
    fun getCompanyRefusesWhatIsNotACompany() = withApp { app ->
        val company = app.company()
        assertError(get("/v1/companies/${company.id + 1}"))
        assertError(get("/v1/companies/0"))
        assertError(get("/v1/companies/-3"))
        assertError(get("/v1/companies/abc"))
    }

    // --- collect

    @Test
    @DisplayName("collect : verse la production des heures écoulées, remet last_collect_at, écrit une seule fois et publie l'événement")
    fun collectPaysTheProduction() = withApp { app ->
        val company = app.company(donar = 10.0, awayHours = 3.0)
        val expected = app.production(company.id, 3.0)
        assertTrue(expected > 0.0, "le test n'a de sens que si l'entreprise produit")

        val response = collect(company.id)

        assertEquals(HttpStatusCode.OK, response.status)
        val body = SiloooJson.parseToJsonElement(response.bodyAsText()).let { it as JsonObject }
        assertClose(expected, body.getValue("collected").jsonPrimitive.content.toDouble(), "collected")
        assertEquals(3.0, body.getValue("offlineHours").jsonPrimitive.content.toDouble())
        val state = SiloooJson.decodeFromJsonElement(CompanyState.serializer(), body.getValue("state"))
        assertClose(10.0 + expected, state.donar, "donar de l'état")
        assertClose(expected, state.donarEarned, "donarEarned de l'état")
        assertEquals(company.version + 1, state.version)

        val write = app.companyUpdates().single()
        assertEquals("UPDATE companies SET donar = ?, donar_earned = ?, last_collect_at = ?, version = version + 1 WHERE id = ? AND version = ?", write.sql)
        assertClose(10.0 + expected, write.parameters.getValue(1) as Double, "donar écrit")
        assertClose(expected, write.parameters.getValue(2) as Double, "donar_earned écrit")
        assertEquals(utc(app.fixture.clock.now), write.parameters.getValue(3), "last_collect_at passe à maintenant")
        assertEquals(company.id, write.parameters.getValue(4))
        assertEquals(company.version, write.parameters.getValue(5), "le verrou optimiste porte la version lue")

        val event = app.companies.published.single()
        assertEquals(GameEventKind.ANNONCE, event.kind)
        assertEquals(kr, event.xuid)
        assertEquals("collect", event.payload.getValue("action").jsonPrimitive.content)
        assertEquals(company.id.toString(), event.payload.getValue("companyId").jsonPrimitive.content)
        assertClose(expected, event.payload.getValue("collected").jsonPrimitive.content.toDouble(), "collected de l'événement")
    }

    @Test
    @DisplayName("collect : en ligne, toutes les heures écoulées sont payées au revenu plein, même au-delà du plafond hors ligne ; offlineHours les compte toutes")
    fun collectHasNoCapWhenOnline() = withApp { app ->
        val company = app.company()
        val away = company.offlineCapHours + 100.0
        app.fixture.clock.advance(EngineFixture.hours(away))
        val rate = app.fixture.figuresOf(company.id).ratePerHour

        val body = SiloooJson.parseToJsonElement(collect(company.id).bodyAsText()) as JsonObject

        val collected = body.getValue("collected").jsonPrimitive.content.toDouble()
        assertClose(away, body.getValue("offlineHours").jsonPrimitive.content.toDouble(), "offlineHours : toutes les heures comptées")
        assertClose(app.engine.onlineEarnings(rate, away), collected, "collected au revenu plein")
        assertTrue(collected > app.engine.offlineEarnings(rate, away, company.offlineCapHours), "plus que ne donnerait le régime hors ligne")
    }

    @Test
    @DisplayName("collect : sous le plafond hors ligne, le revenu plein est celui du compteur de l'add-on : revenu x heures, sans efficacité")
    fun collectPaysWhatTheCounterShowed() = withApp { app ->
        val company = app.company()
        val away = company.offlineCapHours / 2
        app.fixture.clock.advance(EngineFixture.hours(away))
        val rate = app.fixture.figuresOf(company.id).ratePerHour

        val body = SiloooJson.parseToJsonElement(collect(company.id).bodyAsText()) as JsonObject

        assertClose(rate * away, body.getValue("collected").jsonPrimitive.content.toDouble(), "collected = revenu x heures")
    }

    @Test
    @DisplayName("collect : rien n'est dû quand l'horloge n'a pas avancé : 200 à zéro, aucune écriture, aucun événement")
    fun collectWithNothingDue() = withApp { app ->
        val company = app.company(donar = 7.0)

        val body = SiloooJson.parseToJsonElement(collect(company.id).bodyAsText()) as JsonObject

        assertEquals(0.0, body.getValue("collected").jsonPrimitive.content.toDouble())
        assertEquals(0.0, body.getValue("offlineHours").jsonPrimitive.content.toDouble())
        assertEquals(0, app.companyUpdates().size)
        assertEquals(0, app.companies.published.size)
        assertEquals(company.version, SiloooJson.decodeFromJsonElement(CompanyState.serializer(), body.getValue("state")).version)
    }

    @Test
    @DisplayName("collect : un acteur qui n'est pas de l'entreprise, ou une entreprise inconnue, donne 400 et rien n'est écrit")
    fun collectRefusesOutsiders() = withApp { app ->
        val company = app.company(awayHours = 2.0)
        app.fixture.newCompany(other)

        assertError(collect(company.id, n = 1, actor = stranger.text))
        assertError(collect(company.id, n = 2, actor = other.text))
        assertError(collect(company.id + 50, n = 3))
        assertEquals(0, app.companyUpdates().size)
        assertEquals(0, app.companies.published.size)
    }

    @Test
    @DisplayName("collect : un corps hors contrat donne 400 (requestId pas un UUID, acteur pas un XUID, corps vide)")
    fun collectRefusesBadBodies() = withApp { app ->
        val company = app.company(awayHours = 2.0)
        val path = "/v1/companies/${company.id}/collect"

        assertError(post(path, """{"requestId":"abc","actor":"${kr.text}"}"""))
        assertError(post(path, """{"requestId":"${requestId(1)}","actor":"pas-un-xuid"}"""))
        assertError(post(path, "{}"))
        assertError(post(path, "pas du json"))
        assertError(post("/v1/companies/abc/collect", action()))
        assertEquals(0, app.companyUpdates().size)
    }

    @Test
    @DisplayName("collect rejoué avec le même requestId : la même réponse, une seule écriture, un seul événement")
    fun collectIsIdempotent() = withApp { app ->
        val company = app.company(awayHours = 2.0)

        val first = collect(company.id, n = 7)
        val firstBody = first.bodyAsText()
        app.fixture.clock.advance(EngineFixture.hours(5.0))
        val second = collect(company.id, n = 7)

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(firstBody, second.bodyAsText(), "le rejeu rend la réponse gardée, même si le temps a passé")
        assertEquals(1, app.companyUpdates().size)
        assertEquals(1, app.companies.published.size)
    }

    @Test
    @DisplayName("collect : version dépassée (un autre associé a écrit) : 409 CONFLIT_REESSAYER, le même requestId peut être rejoué ensuite")
    fun collectOnVersionConflict() = withApp { app ->
        val company = app.company(awayHours = 2.0)

        app.recording.rowsTouched = 0
        assertRefused(collect(company.id, n = 9), RefusalCodes.CONFLICT_RETRY)
        assertEquals(0, app.companies.published.size, "un conflit ne publie rien")

        app.recording.rowsTouched = 1
        assertEquals(HttpStatusCode.OK, collect(company.id, n = 9).status, "le conflit n'est pas gardé : le nouvel essai passe")
        assertEquals(1, app.companies.published.size)
    }

    // --- collect et la mission de bienvenue

    @Test
    @DisplayName("collect : un encaissement qui rapporte termine la mission de bienvenue : Lingots du catalogue versés, événement publié, une seule fois")
    fun collectFinishesTheWelcomeMission() = withApp { app ->
        val first = app.fixture.definitions.missions.missions.first()
        val reward = app.fixture.definitions.missionRewards.getValue(first.id)
        val company = app.company(awayHours = 2.0)
        assertTrue(app.production(company.id, 2.0) > 0.0, "le test n'a de sens que si l'entreprise produit")

        assertEquals(HttpStatusCode.OK, collect(company.id, n = 1).status)

        assertEquals(MissionRow(first.target, completed = true), app.missions.rows.getValue(kr to first.id))
        assertEquals(listOf(InMemoryMissionStore.Credit(kr, reward, "mission", first.id)), app.missions.credits)
        // Le solde du PDG est la récompense de la mission plus les primes du Carnet que le même encaissement a inscrit.
        val findingsReward = app.findings.credits.sumOf { it.amount }
        assertTrue(findingsReward > 0, "l'encaissement a inscrit les ressources de l'usine de départ")
        assertEquals(reward + findingsReward, app.fixture.store.balances.getValue(kr).lingots)
        val event = app.missions.published.single()
        assertEquals(GameEventKind.ANNONCE, event.kind)
        assertEquals(kr, event.xuid)
        assertEquals("mission", event.payload.getValue("action").jsonPrimitive.content)
        assertEquals(first.id, event.payload.getValue("missionId").jsonPrimitive.content)
        assertEquals(reward.toString(), event.payload.getValue("rewardLingots").jsonPrimitive.content)
        assertEquals(1, app.companies.published.size, "l'événement du collect lui-même reste seul de son espèce")

        // Un second encaissement qui rapporte : la mission est faite, elle ne se rejoue pas.
        app.fixture.clock.advance(EngineFixture.hours(2.0))
        assertEquals(HttpStatusCode.OK, collect(company.id, n = 2).status)
        assertEquals(1, app.missions.credits.size)
        assertEquals(1, app.missions.published.size)
        assertEquals(reward + findingsReward, app.fixture.store.balances.getValue(kr).lingots, "ni la mission ni le Carnet ne repayent")
    }

    @Test
    @DisplayName("collect rejoué avec le même requestId : la mission n'est comptée ni versée deux fois")
    fun collectReplayDoesNotCountTwice() = withApp { app ->
        val company = app.company(awayHours = 2.0)

        collect(company.id, n = 5)
        app.fixture.clock.advance(EngineFixture.hours(5.0))
        collect(company.id, n = 5)

        assertEquals(1, app.missions.credits.size)
        assertEquals(1, app.missions.published.size)
    }

    @Test
    @DisplayName("collect : rien n'est dû, ou rien n'est gagné (aucune usine ne produit) : la mission n'avance pas")
    fun collectWithoutGainDoesNotAdvanceTheMission() = withApp { app ->
        val nothingDue = app.company()
        assertEquals(HttpStatusCode.OK, collect(nothingDue.id).status)
        assertTrue(app.missions.rows.isEmpty(), "l'horloge n'a pas avancé : aucune ligne de mission")

        val idle = app.company(awayHours = 3.0)
        app.fixture.putFactory(idle.id, FactoryRecord(app.starterId, FactoryState.EN_RUINE, 0, 0, null))
        assertEquals(HttpStatusCode.OK, collect(idle.id, n = 2).status)
        assertEquals(1, app.companies.published.size, "l'encaissement à zéro est écrit et annoncé comme avant")
        assertTrue(app.missions.rows.isEmpty(), "rien n'a été gagné : la mission n'avance pas")
        assertTrue(app.missions.credits.isEmpty())
        assertTrue(app.missions.published.isEmpty())
    }

    @Test
    @DisplayName("collect : version dépassée : 409 CONFLIT_REESSAYER, la mission ne bouge pas, et le nouvel essai la termine")
    fun collectConflictLeavesTheMissionAlone() = withApp { app ->
        val company = app.company(awayHours = 2.0)

        app.recording.rowsTouched = 0
        assertRefused(collect(company.id, n = 9), RefusalCodes.CONFLICT_RETRY)
        assertTrue(app.missions.rows.isEmpty())
        assertTrue(app.missions.credits.isEmpty())
        assertTrue(app.missions.published.isEmpty())

        app.recording.rowsTouched = 1
        assertEquals(HttpStatusCode.OK, collect(company.id, n = 9).status)
        assertEquals(1, app.missions.credits.size)
    }

    @Test
    @DisplayName("collect : un associé qui encaisse avance SA mission, pas celle du PDG")
    fun missionsAreKeptPerPlayer() = withApp { app ->
        val company = app.company(awayHours = 2.0)
        app.fixture.addMember(company.id, other, lingots = 0)

        assertEquals(HttpStatusCode.OK, collect(company.id, n = 1, actor = other.text).status)

        val first = app.fixture.definitions.missions.missions.first().id
        assertEquals(setOf(other to first), app.missions.rows.keys)
        assertEquals(other, app.missions.credits.single().xuid)
        assertEquals(0L, app.fixture.store.balances.getValue(kr).lingots, "le PDG n'a rien reçu")
    }

    // --- buyLevels

    @Test
    @DisplayName("buyLevels 1 : verse d'abord la production, débite le prix du moteur, monte l'usine, publie l'événement")
    fun buyOneLevel() = withApp { app ->
        val cost = app.engine.levelsCost(app.starterId, app.starterLevel, 1)
        val company = app.company(donar = cost, awayHours = 2.0)
        val earned = app.production(company.id, 2.0)

        val result = updated(buy(company.id, app.starterId, "1"))

        val starter = result.state.factories.single()
        assertEquals(app.starterLevel + 1, starter.level)
        assertClose(cost + earned - cost, result.state.donar, "donar après achat")
        assertClose(earned, result.state.donarEarned, "donarEarned compte la production, pas l'achat")
        assertEquals(company.version + 1, result.state.version)
        assertEquals(app.engine.nextLevelCost(app.starterId, app.starterLevel + 1), starter.nextCost)
        assertEquals(app.fixture.figuresOf(company.id).ratePerHour, result.state.ratePerHour, "le revenu rendu est celui du nouveau niveau")
        assertEquals(app.fixture.store.loadBalances(app.fixture.connection, kr), result.balances)

        val write = app.companyUpdates().single()
        assertClose(cost + earned - cost, write.parameters.getValue(1) as Double, "donar écrit")
        assertEquals(utc(app.fixture.clock.now), write.parameters.getValue(3), "last_collect_at : la production est versée au niveau d'avant")
        assertEquals(InMemoryCompanyStore.SavedFactory(company.id, app.starterId, FactoryState.ACTIVE, 0, app.starterLevel + 1), app.companies.saved.single())

        val event = app.companies.published.single()
        assertEquals(GameEventKind.ANNONCE, event.kind)
        assertEquals(kr, event.xuid)
        assertEquals("buyLevels", event.payload.getValue("action").jsonPrimitive.content)
        assertEquals(app.starterId, event.payload.getValue("factoryId").jsonPrimitive.content)
        assertEquals("1", event.payload.getValue("levels").jsonPrimitive.content)
    }

    @Test
    @DisplayName("buyLevels 10 et palier : le lot entier au prix du moteur ; palier va jusqu'au prochain doublement")
    fun buyBatches() = withApp { app ->
        val tenCost = app.engine.levelsCost(app.starterId, app.starterLevel, 10)
        val company = app.company(donar = tenCost)

        val ten = updated(buy(company.id, app.starterId, "10", n = 1))
        assertEquals(app.starterLevel + 10, ten.state.factories.single().level)
        assertClose(0.0, ten.state.donar, "tout le Donar est parti dans le lot")

        val toMilestone = app.engine.nextMilestone(app.starterLevel + 10) - (app.starterLevel + 10)
        val milestoneCost = app.engine.levelsCost(app.starterId, app.starterLevel + 10, toMilestone)
        app.fixture.update(app.fixture.store.loadCompany(app.fixture.connection, company.id).copy(donar = milestoneCost))
        val milestone = updated(buy(company.id, app.starterId, "palier", n = 2))
        assertEquals(app.engine.nextMilestone(app.starterLevel + 10), milestone.state.factories.single().level)
    }

    @Test
    @DisplayName("buyLevels max : achète exactement ce que le solde paie, jusqu'au dernier niveau payable")
    fun buyMax() = withApp { app ->
        val threeLevels = app.engine.levelsCost(app.starterId, app.starterLevel, 3)
        val company = app.company(donar = threeLevels)

        val result = updated(buy(company.id, app.starterId, "max"))

        assertEquals(app.starterLevel + 3, result.state.factories.single().level)
        assertClose(0.0, result.state.donar, "le solde paie exactement trois niveaux")
        assertEquals("3", app.companies.published.single().payload.getValue("levels").jsonPrimitive.content)

        // Un cheveu sous le prix de trois niveaux : le solde n'en paie que deux, et le reste reste en caisse.
        val almost = Math.nextDown(app.engine.levelsCost(app.starterId, app.starterLevel + 3, 3))
        app.fixture.update(app.fixture.store.loadCompany(app.fixture.connection, company.id).copy(donar = almost))
        val second = updated(buy(company.id, app.starterId, "max", n = 2))
        assertEquals(app.starterLevel + 3 + 2, second.state.factories.single().level)
        assertTrue(second.state.donar in 0.0..almost, "il reste le Donar que deux niveaux ne dépensent pas")
    }

    @Test
    @DisplayName("buyLevels : Donar manquant sur 1, 10 ou palier : 409 PAS_ASSEZ_DE_DONAR avec la somme qui manque, rien d'écrit")
    fun buyRefusedWhenBroke() = withApp { app ->
        val nine = app.engine.levelsCost(app.starterId, app.starterLevel, 9)
        val company = app.company(donar = nine)

        val refused = assertRefused(buy(company.id, app.starterId, "10"), RefusalCodes.NOT_ENOUGH_DONAR)

        // « 10 » est tout ou rien : neuf niveaux payables ne font pas dix.
        assertClose(app.engine.levelsCost(app.starterId, app.starterLevel, 10) - nine, refused.missing!!, "missing")
        assertEquals(0, app.companyUpdates().size)
        assertEquals(0, app.companies.saved.size)
        assertEquals(0, app.companies.published.size)
        assertEquals(app.starterLevel, app.factory(company.id, app.starterId).level)
    }

    @Test
    @DisplayName("buyLevels max : refuse seulement quand le solde ne paie pas même un niveau, et dit ce qui manque pour ce niveau")
    fun buyMaxRefusedOnlyWhenNothingIsAffordable() = withApp { app ->
        val one = app.engine.levelsCost(app.starterId, app.starterLevel, 1)
        val company = app.company(donar = one / 4.0)

        val refused = assertRefused(buy(company.id, app.starterId, "max"), RefusalCodes.NOT_ENOUGH_DONAR)

        assertClose(one - one / 4.0, refused.missing!!, "missing")
        assertEquals(0, app.companyUpdates().size)
    }

    @Test
    @DisplayName("buyLevels : la production en attente compte dans le solde de l'achat")
    fun buyCountsPendingProduction() = withApp { app ->
        val one = app.engine.levelsCost(app.starterId, app.starterLevel, 1)
        val company = app.company(donar = 0.0, awayHours = 5.0)
        val earned = app.production(company.id, 5.0)
        assertTrue(earned >= one, "le test suppose que 5 heures de production paient un niveau")

        assertEquals(HttpStatusCode.OK, buy(company.id, app.starterId, "1").status)
    }

    @Test
    @DisplayName("buyLevels : une usine en ruine, en remise en état, ou que l'entreprise n'a pas : 409 USINE_VERROUILLEE")
    fun buyOnFactoryThatDoesNotProduce() = withApp { app ->
        val company = app.company(donar = Double.MAX_VALUE / 4)
        val ruin = app.secondId
        app.fixture.putFactory(company.id, FactoryRecord(ruin, FactoryState.EN_RUINE, 0, 0, null))
        assertRefused(buy(company.id, ruin, "1", n = 1), RefusalCodes.FACTORY_LOCKED)

        app.fixture.putFactory(company.id, FactoryRecord(ruin, FactoryState.REMISE_EN_ETAT, 2, 0, null))
        assertRefused(buy(company.id, ruin, "1", n = 2), RefusalCodes.FACTORY_LOCKED)

        val absent = app.fixture.otherFactories()[1]
        assertRefused(buy(company.id, absent, "1", n = 3), RefusalCodes.FACTORY_LOCKED)
        assertEquals(0, app.companyUpdates().size)
    }

    @Test
    @DisplayName("buyLevels : une usine absente de usines.json, un amount hors contrat ou un acteur étranger donnent 400")
    fun buyBadRequests() = withApp { app ->
        val company = app.company(donar = 1e9)
        assertError(buy(company.id, "usine-qui-n-existe-pas", "1", n = 1))
        assertError(buy(company.id, app.starterId, "2", n = 2))
        assertError(buy(company.id, app.starterId, "", n = 3))
        assertError(post("/v1/companies/${company.id}/factories/${app.starterId}/levels", action(4)))
        assertError(buy(company.id, app.starterId, "1", n = 5, actor = stranger.text))
        assertEquals(0, app.companyUpdates().size)
    }

    @Test
    @DisplayName("buyLevels rejoué : même réponse, un seul achat ; un refus se rejoue aussi, même quand l'entreprise est devenue riche")
    fun buyIsIdempotent() = withApp { app ->
        val one = app.engine.levelsCost(app.starterId, app.starterLevel, 1)
        val company = app.company(donar = one)

        val first = buy(company.id, app.starterId, "1", n = 1)
        val firstBody = first.bodyAsText()
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(firstBody, buy(company.id, app.starterId, "1", n = 1).bodyAsText())
        assertEquals(1, app.companyUpdates().size)
        assertEquals(1, app.companies.saved.size)
        assertEquals(1, app.companies.published.size)

        // Le niveau suivant coûte plus que le solde : cet achat est refusé, et le refus est gardé sous son requestId.
        val refusedBody = assertRefused(buy(company.id, app.starterId, "1", n = 2), RefusalCodes.NOT_ENOUGH_DONAR)
        app.fixture.update(app.fixture.store.loadCompany(app.fixture.connection, company.id).copy(donar = 1e30))
        assertRefused(buy(company.id, app.starterId, "1", n = 2), RefusalCodes.NOT_ENOUGH_DONAR).also {
            assertEquals(refusedBody.missing, it.missing)
        }
        assertEquals(HttpStatusCode.OK, buy(company.id, app.starterId, "1", n = 3).status, "un requestId neuf voit le nouveau solde")
    }

    @Test
    @DisplayName("buyLevels : version dépassée : 409 CONFLIT_REESSAYER, l'usine garde son niveau, l'événement n'est pas publié")
    fun buyOnVersionConflict() = withApp { app ->
        val one = app.engine.levelsCost(app.starterId, app.starterLevel, 1)
        val company = app.company(donar = one)

        app.recording.rowsTouched = 0
        assertRefused(buy(company.id, app.starterId, "1", n = 4), RefusalCodes.CONFLICT_RETRY)

        assertEquals(app.starterLevel, app.factory(company.id, app.starterId).level)
        assertEquals(0, app.companies.saved.size, "la version est vérifiée avant l'écriture de l'usine")
        assertEquals(0, app.companies.published.size)
    }

    @Test
    @DisplayName("buyLevels : la production en attente est versée au revenu plein, même au-delà du plafond hors ligne")
    fun buyPaysPendingProductionAtFullRate() = withApp { app ->
        val cost = app.engine.levelsCost(app.starterId, app.starterLevel, 1)
        val company = app.company(donar = cost)
        val away = company.offlineCapHours + 40.0
        app.fixture.clock.advance(EngineFixture.hours(away))
        val earned = app.engine.onlineEarnings(app.fixture.figuresOf(company.id).ratePerHour, away)

        val result = updated(buy(company.id, app.starterId, "1"))

        assertClose(earned, result.state.donarEarned, "donarEarned : toute la production, au revenu plein")
        assertClose(earned, result.state.donar, "donar : production versée, prix débité")
    }

    // --- restoreStep

    private fun Harness.ruin(companyId: Long, state: FactoryState = FactoryState.EN_RUINE, step: Int = 0): String {
        fixture.putFactory(companyId, FactoryRecord(secondId, state, step, 0, null))
        return secondId
    }

    @Test
    @DisplayName("restoreStep 1 sur une usine en ruine : elle passe REMISE_EN_ETAT à l'étape 1, l'événement est publié")
    fun restoreFirstStep() = withApp { app ->
        val company = app.company(donar = 5.0)
        val factoryId = app.ruin(company.id)

        val result = updated(restore(company.id, factoryId, 1))

        val factory = result.state.factories.single { it.factoryId == factoryId }
        assertEquals(FactoryState.REMISE_EN_ETAT, factory.state)
        assertEquals(1, factory.restoreStep)
        assertEquals(0, factory.level, "tant que la remise en état n'est pas finie, le niveau ne bouge pas")
        assertEquals(0.0, factory.ratePerHour)
        assertEquals(company.version + 1, result.state.version)
        assertClose(5.0, result.state.donar, "aucune étape ne coûte de Donar")

        assertEquals(InMemoryCompanyStore.SavedFactory(company.id, factoryId, FactoryState.REMISE_EN_ETAT, 1, 0), app.companies.saved.single())
        val event = app.companies.published.single()
        assertEquals(GameEventKind.ANNONCE, event.kind)
        assertEquals(kr, event.xuid)
        assertEquals("restoreStep", event.payload.getValue("action").jsonPrimitive.content)
        assertEquals("1", event.payload.getValue("step").jsonPrimitive.content)
        assertEquals(FactoryState.REMISE_EN_ETAT.name, event.payload.getValue("state").jsonPrimitive.content)
    }

    @Test
    @DisplayName("restoreStep 1 à 4 dans l'ordre : la quatrième étape rend l'usine ACTIVE au niveau de départ des définitions, et elle produit")
    fun restoreAllFourSteps() = withApp { app ->
        val company = app.company()
        val factoryId = app.ruin(company.id)

        for (step in 1..3) {
            val factory = updated(restore(company.id, factoryId, step, n = step)).state.factories.single { it.factoryId == factoryId }
            assertEquals(FactoryState.REMISE_EN_ETAT, factory.state, "étape $step")
            assertEquals(step, factory.restoreStep)
        }
        val last = updated(restore(company.id, factoryId, 4, n = 4))

        val factory = last.state.factories.single { it.factoryId == factoryId }
        assertEquals(FactoryState.ACTIVE, factory.state)
        assertEquals(4, factory.restoreStep)
        assertEquals(app.engine.restoredLevel(0), factory.level)
        assertTrue(factory.level > 0, "une usine ACTIVE doit produire")
        assertClose(last.state.multiplier * app.engine.factoryRate(factoryId, factory.level), factory.ratePerHour, "revenu de l'usine remise en état")
        assertEquals(4, app.companies.published.size)
    }

    @Test
    @DisplayName("restoreStep : une étape trop loin donne USINE_VERROUILLEE, une étape déjà faite ou une usine ACTIVE donne DEJA_POSSEDE")
    fun restoreOutOfOrder() = withApp { app ->
        val company = app.company()
        val factoryId = app.ruin(company.id)

        assertRefused(restore(company.id, factoryId, 2, n = 1), RefusalCodes.FACTORY_LOCKED)
        assertRefused(restore(company.id, factoryId, 4, n = 2), RefusalCodes.FACTORY_LOCKED)
        assertEquals(HttpStatusCode.OK, restore(company.id, factoryId, 1, n = 3).status)
        assertRefused(restore(company.id, factoryId, 1, n = 4), RefusalCodes.ALREADY_OWNED)
        assertRefused(restore(company.id, factoryId, 3, n = 5), RefusalCodes.FACTORY_LOCKED)
        assertEquals(HttpStatusCode.OK, restore(company.id, factoryId, 2, n = 6).status)
        assertRefused(restore(company.id, factoryId, 1, n = 7), RefusalCodes.ALREADY_OWNED)
        assertRefused(restore(company.id, factoryId, 2, n = 8), RefusalCodes.ALREADY_OWNED)

        // L'usine de départ est ACTIVE depuis la création : il n'y a rien à remettre en état.
        assertRefused(restore(company.id, app.starterId, 1, n = 9), RefusalCodes.ALREADY_OWNED)
        assertRefused(restore(company.id, app.starterId, 4, n = 10), RefusalCodes.ALREADY_OWNED)

        assertEquals(2, app.companies.saved.size, "seules les deux étapes valides ont écrit")
        assertEquals(2, app.companies.published.size)
        assertEquals(2, app.companyUpdates().size)
    }

    @Test
    @DisplayName("restoreStep : une usine que l'entreprise n'a pas donne USINE_VERROUILLEE ; une étape hors de 1..4 ou une usine inconnue, 400")
    fun restoreBadRequests() = withApp { app ->
        val company = app.company()

        assertRefused(restore(company.id, app.secondId, 1, n = 1), RefusalCodes.FACTORY_LOCKED)
        assertError(restore(company.id, app.secondId, 0, n = 2))
        assertError(restore(company.id, app.secondId, 5, n = 3))
        assertError(restore(company.id, "usine-qui-n-existe-pas", 1, n = 4))
        assertError(post("/v1/companies/${company.id}/factories/${app.secondId}/restore", action(5, extra = ""","step":"un"""")))
        assertError(restore(company.id, app.secondId, 1, n = 6, actor = stranger.text))
        assertEquals(0, app.companyUpdates().size)
    }

    @Test
    @DisplayName("restoreStep rejoué : même réponse, une seule étape avancée ; la production en attente est versée avec l'étape")
    fun restoreIsIdempotentAndSettlesProduction() = withApp { app ->
        val company = app.company(donar = 3.0, awayHours = 4.0)
        val earned = app.production(company.id, 4.0)
        val factoryId = app.ruin(company.id)

        val first = restore(company.id, factoryId, 1, n = 1)
        val firstBody = first.bodyAsText()
        assertEquals(firstBody, restore(company.id, factoryId, 1, n = 1).bodyAsText())

        assertEquals(1, app.companies.saved.size)
        assertEquals(1, app.companyUpdates().size)
        val write = app.companyUpdates().single()
        assertClose(3.0 + earned, write.parameters.getValue(1) as Double, "donar écrit : la production en attente est versée")
        assertEquals(utc(app.fixture.clock.now), write.parameters.getValue(3))
    }

    @Test
    @DisplayName("restoreStep : version dépassée : 409 CONFLIT_REESSAYER, l'usine n'avance pas")
    fun restoreOnVersionConflict() = withApp { app ->
        val company = app.company()
        val factoryId = app.ruin(company.id)

        app.recording.rowsTouched = 0
        assertRefused(restore(company.id, factoryId, 1), RefusalCodes.CONFLICT_RETRY)

        assertEquals(FactoryState.EN_RUINE, app.factory(company.id, factoryId).state)
        assertEquals(0, app.companies.saved.size)
        assertEquals(0, app.companies.published.size)
    }

    // --- restoreStep : le courant qui manque (événement sansCourant, lu par le bot Discord)

    private val electricity get() = TestDefinitions.loaded.incidents.electricity

    /** Les usines qui dépendent de l'électricité, dans l'ordre d'usines.json. */
    private fun Harness.electricIds(): List<String> = fixture.definitions.factories.factories.filter { it.needsElectricity }.map { it.id }

    /** Une entreprise de l'époque [era] (par défaut celle où l'électricité devient obligatoire), dont le PDG s'appelle « Kr ». */
    private fun Harness.modernCompany(era: Int = electricity.fromEra): CompanyRecord {
        fixture.store.enterPlayer(fixture.connection, kr, "Kr")
        return fixture.update(company().copy(era = era, courseNumber = era))
    }

    private fun Harness.putRuin(companyId: Long, factoryId: String) =
        fixture.putFactory(companyId, FactoryRecord(factoryId, FactoryState.EN_RUINE, 0, 0, null))

    private fun Harness.noPowerEvents() =
        companies.published.filter { it.kind == GameEventKind.ANNONCE && it.payload["action"]?.jsonPrimitive?.content == "sansCourant" }

    /** Les quatre étapes de remise en état de [factoryId] ; [firstRequest] numérote les requêtes pour qu'aucune ne soit un rejeu. */
    private suspend fun ApplicationTestBuilder.restoreAll(companyId: Long, factoryId: String, firstRequest: Int): CompanyUpdated {
        var last: CompanyUpdated? = null
        for (step in 1..4) last = updated(restore(companyId, factoryId, step, n = firstRequest + step))
        return checkNotNull(last)
    }

    @Test
    @DisplayName("restoreStep : l'usine électrique qui finit sa remise en état sans Centrale s'arrête, et un événement sansCourant est publié à celui qui agit")
    fun noPowerIsAnnouncedWhenTheLastStepLeavesNoPower() = withApp { app ->
        val electric = app.electricIds().first()
        val company = app.modernCompany()
        app.putRuin(company.id, electric)

        for (step in 1..3) updated(restore(company.id, electric, step, n = step))
        assertEquals(0, app.noPowerEvents().size, "tant que la remise en état n'est pas finie, l'usine ne manque de rien")
        val last = updated(restore(company.id, electric, 4, n = 4))

        assertTrue(last.state.factories.single { it.factoryId == electric }.stoppedNoPower)
        val event = app.noPowerEvents().single()
        assertEquals(kr, event.xuid, "adressé à celui qui agit")
        assertEquals(electric, event.payload.getValue("factoryId").jsonPrimitive.content)
        assertEquals(app.engine.factory(electric).name, event.payload.getValue("factoryName").jsonPrimitive.content)
        assertEquals("Kr", event.payload.getValue("playerName").jsonPrimitive.content)
        assertEquals(company.id.toString(), event.payload.getValue("companyId").jsonPrimitive.content)
        assertEquals(5, app.companies.published.size, "les quatre étapes, et l'alerte une fois")
    }

    @Test
    @DisplayName("restoreStep : avec une Centrale qui tourne, la fin de la remise en état d'une usine électrique ne publie aucune alerte")
    fun noPowerIsNotAnnouncedWithAPowerPlant() = withApp { app ->
        val electric = app.electricIds().first()
        val company = app.modernCompany()
        app.fixture.putFactory(company.id, FactoryRecord(electricity.powerPlantId, FactoryState.ACTIVE, 0, 1, null))
        app.putRuin(company.id, electric)

        val last = restoreAll(company.id, electric, 0)

        assertFalse(last.state.factories.single { it.factoryId == electric }.stoppedNoPower)
        assertEquals(0, app.noPowerEvents().size)
    }

    @Test
    @DisplayName("restoreStep : avant l'époque de l'électricité, aucune usine ne s'arrête, donc aucune alerte")
    fun noPowerIsNotAnnouncedBeforeTheElectricityEra() = withApp { app ->
        val electric = app.electricIds().first()
        assertTrue(electricity.fromEra > 1, "le test suppose une époque avant celle de l'électricité")
        val company = app.modernCompany(era = electricity.fromEra - 1)
        app.putRuin(company.id, electric)

        val last = restoreAll(company.id, electric, 0)

        assertFalse(last.state.factories.single { it.factoryId == electric }.stoppedNoPower)
        assertEquals(0, app.noPowerEvents().size)
    }

    @Test
    @DisplayName("restoreStep : l'alerte part une fois par passage à l'état sans courant ; une deuxième usine électrique remise en état pendant l'attente n'en publie pas")
    fun noPowerIsAnnouncedOncePerPassage() = withApp { app ->
        val (first, second) = app.electricIds().take(2)
        val company = app.modernCompany()
        app.putRuin(company.id, first)
        app.putRuin(company.id, second)

        restoreAll(company.id, first, 0)
        assertEquals(1, app.noPowerEvents().size)
        val last = restoreAll(company.id, second, 10)

        assertTrue(last.state.factories.single { it.factoryId == second }.stoppedNoPower)
        assertEquals(1, app.noPowerEvents().size, "l'entreprise manquait déjà de courant")
        assertEquals(first, app.noPowerEvents().single().payload.getValue("factoryId").jsonPrimitive.content)
    }

    @Test
    @DisplayName("restoreStep : remettre en état la Centrale ou une usine qui n'a pas besoin d'électricité ne publie aucune alerte")
    fun noPowerIsNotAnnouncedForTheOthers() = withApp { app ->
        val plain = app.fixture.definitions.factories.factories
            .first { !it.needsElectricity && it.id != app.starterId && it.id != electricity.powerPlantId }.id
        val company = app.modernCompany()
        app.putRuin(company.id, electricity.powerPlantId)
        app.putRuin(company.id, plain)

        restoreAll(company.id, electricity.powerPlantId, 0)
        restoreAll(company.id, plain, 10)

        assertEquals(0, app.noPowerEvents().size)
    }

    // --- prestigePreview

    @Test
    @DisplayName("prestigePreview : seuil non atteint : allowed faux, Lingots et parts à zéro, l'époque suivante dite, rien d'écrit")
    fun previewBelowTheThreshold() = withApp { app ->
        val company = app.prestigeCompany(earned = app.engine.prestigeThreshold(1) / 2)

        val preview = previewOf(preview(company.id))

        assertFalse(preview.allowed)
        assertEquals(0L, preview.lingotsTotal)
        assertEquals(0L, preview.lingotsIfDoubled)
        assertEquals(listOf(PrestigeShare(kr.text, 0L)), preview.shares)
        assertEquals("EPOQUE_${app.engine.eraAt(2)}", preview.next)
        assertEquals(0, app.recording.statements.size, "prestigePreview ne lit que : aucune écriture")
        assertEquals(0, app.companies.published.size)
    }

    @Test
    @DisplayName("prestigePreview : au-dessus du seuil : les Lingots du moteur, le rendement décroissant, la part de chacun")
    fun previewAboveTheThreshold() = withApp { app ->
        val earned = app.engine.prestigeThreshold(1) * 8
        val company = app.prestigeCompany(earned = earned)
        app.fixture.addMember(company.id, other, lingots = 0)
        app.companies.sharePercents[kr] = 70
        app.companies.sharePercents[other] = 30
        val total = app.engine.prestigeLingots(1, earned)
        assertTrue(total > 0, "le test n'a de sens que si le prestige rapporte")

        val preview = previewOf(preview(company.id))

        assertTrue(preview.allowed)
        assertEquals(total, preview.lingotsTotal)
        assertEquals(app.engine.prestigeLingots(1, earned * 2), preview.lingotsIfDoubled)
        assertTrue(preview.lingotsIfDoubled > preview.lingotsTotal, "attendre plus rapporte plus")
        assertTrue(preview.lingotsIfDoubled < 2 * preview.lingotsTotal, "mais moins que le double")
        assertEquals(listOf(kr.text, other.text), preview.shares.map { it.xuid })
        assertEquals(total, preview.shares.sumOf { it.lingots })
        assertEquals(total * 30 / 100, preview.shares.last().lingots)
        assertEquals(0, app.recording.statements.size)
    }

    @Test
    @DisplayName("prestigePreview : la production en attente compte dans les Donar gagnés, comme au prestige")
    fun previewCountsThePendingProduction() = withApp { app ->
        val company = app.prestigeCompany(earned = Math.nextDown(app.engine.prestigeThreshold(1)))
        assertFalse(previewOf(preview(company.id)).allowed, "juste sous le seuil")

        app.fixture.clock.advance(EngineFixture.hours(1.0))

        assertTrue(previewOf(preview(company.id)).allowed, "une heure de production passe le seuil")
        assertEquals(0, app.recording.statements.size, "l'aperçu ne verse rien")
    }

    @Test
    @DisplayName("prestigePreview : next est l'époque suivante, puis ETOILE dès la dernière époque")
    fun previewNextFollowsTheEras() = withApp { app ->
        val lastEra = app.fixture.definitions.factories.factories.maxOf { it.era }
        for (course in 1..(lastEra + 2)) {
            val company = app.prestigeCompany(course = course)
            val expected = if (course >= lastEra) "ETOILE" else "EPOQUE_${course + 1}"
            assertEquals(expected, previewOf(preview(company.id)).next, "prestige de la course $course")
        }
    }

    @Test
    @DisplayName("prestigePreview : une entreprise inconnue, ou un identifiant qui n'en est pas un, donne 400 REQUETE_INVALIDE")
    fun previewRefusesWhatIsNotACompany() = withApp { app ->
        val company = app.prestigeCompany()
        assertError(preview(company.id + 1))
        assertError(get("/v1/companies/0/prestige/preview"))
        assertError(get("/v1/companies/abc/prestige/preview"))
    }

    // --- prestige

    @Test
    @DisplayName("prestige : le PDG au seuil lance la course suivante : Donar à zéro, usines au départ, Lingots crédités, historique et événement écrits")
    fun prestigeStartsTheNextCourse() = withApp { app ->
        val threshold = app.engine.prestigeThreshold(1)
        val earned = threshold * 8
        val company = app.prestigeCompany(earned = earned, ownerLingots = 4)
        val second = app.secondId
        app.fixture.putFactory(company.id, FactoryRecord(app.starterId, FactoryState.ACTIVE, 0, app.starterLevel + 40, openIncidentId = null))
        app.fixture.putFactory(company.id, FactoryRecord(second, FactoryState.ACTIVE, 0, 12, openIncidentId = 5L))
        val total = app.engine.prestigeLingots(1, earned)
        assertTrue(total > 0, "le test n'a de sens que si le prestige rapporte")

        val result = updated(prestige(company.id))

        val state = result.state
        assertEquals(2, state.courseNumber)
        assertEquals(app.engine.eraAt(2), state.era)
        assertEquals(app.engine.starsAt(2), state.stars)
        assertEquals(0.0, state.donar)
        assertEquals(0.0, state.donarEarned)
        assertEquals(company.version + 1, state.version)
        assertEquals(app.engine.prestigeThreshold(2), state.threshold, "le seuil est celui de la course suivante")
        val starter = state.factories.single { it.factoryId == app.starterId }
        assertEquals(FactoryState.ACTIVE, starter.state)
        assertEquals(app.starterLevel, starter.level, "l'usine de départ repart au niveau de départ")
        val sold = state.factories.single { it.factoryId == second }
        assertEquals(FactoryState.EN_RUINE, sold.state)
        assertEquals(0, sold.level)
        assertEquals(0, sold.restoreStep)
        assertEquals(null, sold.incidentId, "l'incident ouvert est clos")
        assertEquals(4 + total, result.balances.lingots, "les Lingots s'ajoutent à ceux que le PDG détenait")
        assertEquals(
            app.engine.globalMultiplier((4 + total).toDouble(), state.stars),
            state.multiplier,
            "le multiplicateur rendu compte les Lingots que le prestige vient de verser",
        )

        val write = app.companyUpdates().single()
        assertEquals(
            "UPDATE companies SET donar = ?, donar_earned = ?, course_number = ?, era = ?, stars = ?, course_started_at = ?, " +
                "last_collect_at = ?, version = version + 1 WHERE id = ? AND version = ?",
            write.sql,
        )
        assertEquals(listOf<Any?>(0.0, 0.0, 2, app.engine.eraAt(2), app.engine.starsAt(2)), (1..5).map { write.parameters.getValue(it) })
        assertEquals(utc(app.fixture.clock.now), write.parameters.getValue(6), "course_started_at passe à maintenant")
        assertEquals(utc(app.fixture.clock.now), write.parameters.getValue(7), "last_collect_at passe à maintenant")
        assertEquals(company.id, write.parameters.getValue(8))
        assertEquals(company.version, write.parameters.getValue(9), "le verrou optimiste porte la version lue")

        assertEquals(listOf(company.id to app.engine.startingLevels()), app.companies.restarts)
        assertEquals(listOf(InMemoryCompanyStore.Credit(kr, total, "prestige", "entreprise-${company.id}-course-1")), app.companies.credits)
        assertEquals(
            listOf(PrestigeRecord(company.id, 1, company.era, state.stars, threshold, earned, total, BigDecimal("0.00"), kr)),
            app.companies.prestiges,
        )
        assertEquals(2, app.companies.published.size, "l'annonce du PDG, et celle de tout le serveur")
        val event = app.companies.published.single { it.kind == GameEventKind.ANNONCE }
        assertEquals(GameEventKind.ANNONCE, event.kind)
        assertEquals(kr, event.xuid)
        assertEquals("prestige", event.payload.getValue("action").jsonPrimitive.content)
        assertEquals(company.id.toString(), event.payload.getValue("companyId").jsonPrimitive.content)
        assertEquals("1", event.payload.getValue("course").jsonPrimitive.content)
        assertEquals(state.era.toString(), event.payload.getValue("era").jsonPrimitive.content)
        assertEquals(total.toString(), event.payload.getValue("lingotsTotal").jsonPrimitive.content)
        assertEquals(total.toString(), event.payload.getValue("lingots").jsonPrimitive.content)
    }

    @Test
    @DisplayName("prestige : le seuil lui-même suffit ; un Donar de moins et le prestige est refusé")
    fun prestigeAtTheThresholdExactly() = withApp { app ->
        val threshold = app.engine.prestigeThreshold(1)
        val short = app.prestigeCompany(earned = Math.nextDown(threshold))
        assertRefused(prestige(short.id, n = 1), RefusalCodes.THRESHOLD_NOT_REACHED)

        val exact = app.prestigeCompany(earned = threshold)
        val result = updated(prestige(exact.id, n = 2))

        assertEquals(2, result.state.courseNumber)
        assertEquals(app.engine.prestigeLingots(1, threshold), result.balances.lingots)
    }

    @Test
    @DisplayName("prestige : seuil non atteint : 409 SEUIL_NON_ATTEINT avec les Donar qui manquent, et rien n'est écrit")
    fun prestigeBelowTheThreshold() = withApp { app ->
        val threshold = app.engine.prestigeThreshold(1)
        val company = app.prestigeCompany(earned = threshold / 4)

        val body = assertRefused(prestige(company.id), RefusalCodes.THRESHOLD_NOT_REACHED)

        assertClose(threshold - threshold / 4, checkNotNull(body.missing), "Donar qui manquent")
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("prestige : la production en attente compte pour le seuil et pour les Lingots, et la durée de la course en est tirée")
    fun prestigeSettlesThePendingProduction() = withApp { app ->
        val start = Math.nextDown(app.engine.prestigeThreshold(1))
        val company = app.prestigeCompany(earned = start, awayHours = 12.5)
        val production = app.production(company.id, 12.5)
        assertTrue(production > 0.0)

        val result = updated(prestige(company.id))

        assertEquals(2, result.state.courseNumber)
        val record = app.companies.prestiges.single()
        assertClose(start + production, record.donarEarned, "Donar gagnés de la course, production comprise")
        assertEquals(app.engine.prestigeLingots(1, record.donarEarned), record.lingotsTotal)
        assertEquals(BigDecimal("12.50"), record.durationHours)
        assertEquals(utc(app.fixture.clock.now), app.companyUpdates().single().parameters.getValue(7))
    }

    @Test
    @DisplayName("prestige : les Lingots se partagent selon les parts, le reste va au PDG, chaque membre est crédité et prévenu")
    fun prestigeSharesTheLingots() = withApp { app ->
        val earned = app.engine.prestigeThreshold(1) * 3
        val company = app.prestigeCompany(earned = earned, ownerLingots = 2)
        app.fixture.addMember(company.id, other, lingots = 6)
        app.companies.sharePercents[kr] = 70
        app.companies.sharePercents[other] = 30
        val total = app.engine.prestigeLingots(1, earned)
        val partnerShare = total * 30 / 100

        val result = updated(prestige(company.id))

        val refId = "entreprise-${company.id}-course-1"
        assertEquals(
            listOf(
                InMemoryCompanyStore.Credit(kr, total - partnerShare, "prestige", refId),
                InMemoryCompanyStore.Credit(other, partnerShare, "prestige", refId),
            ),
            app.companies.credits,
        )
        assertEquals(total, app.companies.credits.sumOf { it.amount }, "rien de créé, rien de perdu")
        assertEquals(2 + total - partnerShare, result.balances.lingots, "les soldes rendus sont ceux de l'acteur")
        assertEquals(6 + partnerShare, app.fixture.store.balances.getValue(other).lingots)
        assertEquals(total, app.companies.prestiges.single().lingotsTotal, "l'historique garde le total avant partage")
        assertEquals(
            app.engine.globalMultiplier(app.engine.averageHeldLingots(listOf(2 + total - partnerShare, 6 + partnerShare)), result.state.stars),
            result.state.multiplier,
        )
        val announcements = app.companies.published.filter { it.kind == GameEventKind.ANNONCE }
        assertEquals(listOf(kr, other), announcements.map { it.xuid })
        assertEquals(
            listOf(total - partnerShare, partnerShare),
            announcements.map { it.payload.getValue("lingots").jsonPrimitive.content.toLong() },
        )
        assertTrue(announcements.all { it.payload.getValue("lingotsTotal").jsonPrimitive.content.toLong() == total })
        val everyone = app.companies.published.single { it.kind == GameEventKind.PRESTIGE_ENTREPRISE }
        assertEquals(null, everyone.xuid, "pour tout le serveur")
        assertEquals(total.toString(), everyone.payload.getValue("lingots").jsonPrimitive.content, "le total avant partage, pas la part du PDG")
    }

    @Test
    @DisplayName("prestige : publie PRESTIGE_ENTREPRISE pour tout le serveur (nom, Lingots du total, course finie), sans base")
    fun prestigePublishesTheServerWideEvent() = withApp { app ->
        val earned = app.engine.prestigeThreshold(2) * 3
        val company = app.prestigeCompany(course = 2, earned = earned)
        val total = app.engine.prestigeLingots(2, earned)
        assertTrue(total > 0, "le test n'a de sens que si le prestige rapporte")

        updated(prestige(company.id))

        val published = app.companies.published.single { it.kind == GameEventKind.PRESTIGE_ENTREPRISE }
        assertEquals(null, published.xuid, "un événement pour tout le serveur n'a pas d'adresse")
        assertEquals(company.id.toString(), published.payload.getValue("companyId").jsonPrimitive.content)
        assertEquals(app.fixture.store.loadCompany(app.fixture.connection, company.id).name, published.payload.getValue("companyName").jsonPrimitive.content)
        assertEquals(total.toString(), published.payload.getValue("lingots").jsonPrimitive.content)
        assertEquals("2", published.payload.getValue("prestigeCount").jsonPrimitive.content, "la course qui vient de finir")
    }

    @Test
    @DisplayName("prestige : un prestige refusé ne publie aucun PRESTIGE_ENTREPRISE")
    fun aRefusedPrestigePublishesNothingForTheServer() = withApp { app ->
        val company = app.prestigeCompany(earned = app.engine.prestigeThreshold(1) / 4)

        assertRefused(prestige(company.id), RefusalCodes.THRESHOLD_NOT_REACHED)

        assertEquals(0, app.companies.published.count { it.kind == GameEventKind.PRESTIGE_ENTREPRISE })
    }

    @Test
    @DisplayName("prestige : après la dernière époque, le prestige donne une étoile et l'époque ne bouge plus")
    fun prestigeGivesAStar() = withApp { app ->
        val lastEra = app.fixture.definitions.factories.factories.maxOf { it.era }
        val company = app.prestigeCompany(course = lastEra)

        val state = updated(prestige(company.id)).state

        assertEquals(lastEra + 1, state.courseNumber)
        assertEquals(lastEra, state.era)
        assertEquals(1, state.stars)
        assertEquals(1, app.companies.prestiges.single().starsAfter)
    }

    @Test
    @DisplayName("prestige : un associé reçoit 403 PAS_LE_PDG, avant même le seuil ; un étranger, 400 ; rien n'est écrit")
    fun prestigeIsForTheCeoOnly() = withApp { app ->
        val company = app.prestigeCompany(earned = 0.0)
        app.fixture.addMember(company.id, other, lingots = 0)

        val response = prestige(company.id, n = 1, actor = other.text)

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(RefusalCodes.NOT_THE_CEO, SiloooJson.decodeFromString(RefusedBody.serializer(), response.bodyAsText()).reason)
        assertError(prestige(company.id, n = 2, actor = stranger.text))
        assertError(prestige(company.id + 50, n = 3))
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("prestige : un corps hors contrat donne 400 (requestId pas un UUID, acteur pas un XUID, corps vide, identifiant d'entreprise)")
    fun prestigeRefusesBadBodies() = withApp { app ->
        val company = app.prestigeCompany()
        val path = "/v1/companies/${company.id}/prestige"

        assertError(post(path, """{"requestId":"abc","actor":"${kr.text}"}"""))
        assertError(post(path, """{"requestId":"${requestId(1)}","actor":"pas-un-xuid"}"""))
        assertError(post(path, "{}"))
        assertError(post(path, "pas du json"))
        assertError(post("/v1/companies/abc/prestige", action()))
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("prestige rejoué avec le même requestId : la même réponse, un seul versement de Lingots ; un refus se rejoue aussi")
    fun prestigeIsIdempotent() = withApp { app ->
        val company = app.prestigeCompany(earned = app.engine.prestigeThreshold(1) * 2)
        app.fixture.addMember(company.id, other, lingots = 0)

        val first = prestige(company.id, n = 7)
        val firstBody = first.bodyAsText()
        val second = prestige(company.id, n = 7)

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(firstBody, second.bodyAsText())
        assertEquals(1, app.companyUpdates().size)
        assertEquals(1, app.companies.credits.size)
        assertEquals(1, app.companies.prestiges.size)
        assertEquals(3, app.companies.published.size, "un événement par membre et un pour tout le serveur, une seule fois")

        assertEquals(HttpStatusCode.Forbidden, prestige(company.id, n = 8, actor = other.text).status)
        assertEquals(HttpStatusCode.Forbidden, prestige(company.id, n = 8, actor = other.text).status)
        assertEquals(1, app.companyUpdates().size)
    }

    @Test
    @DisplayName("prestige : version dépassée : 409 CONFLIT_REESSAYER, aucun Lingot versé, le même requestId peut être rejoué ensuite")
    fun prestigeOnVersionConflict() = withApp { app ->
        val company = app.prestigeCompany(earned = app.engine.prestigeThreshold(1) * 2)

        app.recording.rowsTouched = 0
        assertRefused(prestige(company.id, n = 9), RefusalCodes.CONFLICT_RETRY)
        assertEquals(0, app.companies.credits.size)
        assertEquals(0, app.companies.prestiges.size)
        assertEquals(0, app.companies.restarts.size)
        assertEquals(0, app.companies.published.size)

        app.recording.rowsTouched = 1
        assertEquals(HttpStatusCode.OK, prestige(company.id, n = 9).status, "le conflit n'est pas gardé : le nouvel essai passe")
        assertEquals(1, app.companies.prestiges.size)
    }

    private fun assertNothingWritten(app: Harness) {
        assertEquals(0, app.companyUpdates().size)
        assertEquals(0, app.companies.credits.size)
        assertEquals(0, app.companies.prestiges.size)
        assertEquals(0, app.companies.restarts.size)
        assertEquals(0, app.companies.published.size)
    }

    // --- join et leave : le régime de la période

    private suspend fun ApplicationTestBuilder.join(xuid: Xuid) =
        post("/v1/session/join", """{"xuid":"${xuid.text}","gamertag":"Joueur"}""")

    private suspend fun ApplicationTestBuilder.leave(xuid: Xuid) =
        post("/v1/session/leave", """{"xuid":"${xuid.text}"}""")

    @Test
    @DisplayName("deux associés : A joue, B arrive : la période de A est payée au revenu plein, sans plafond, et la date repart de maintenant")
    fun partnerArrivingWhileTheOtherPlaysPaysFullRate() = withApp { app ->
        val company = app.company()
        app.fixture.addMember(company.id, other, lingots = 0)
        val away = company.offlineCapHours + 20.0
        app.fixture.clock.advance(EngineFixture.hours(away))
        app.presence.record("serveur-1", listOf(kr.text, other.text))
        val rate = app.fixture.figuresOf(company.id).ratePerHour

        val body = SiloooJson.parseToJsonElement(join(other).bodyAsText()) as JsonObject

        assertEquals(0.0, body.getValue("offlineCollected").jsonPrimitive.content.toDouble(), "le gain en ligne n'est pas un gain hors ligne")
        val write = app.companyUpdates().single()
        assertClose(app.engine.onlineEarnings(rate, away), (write.parameters.getValue(1) as Double) - company.donar, "Donar versé : revenu plein")
        assertTrue(app.engine.onlineEarnings(rate, away) > app.engine.offlineEarnings(rate, away, company.offlineCapHours))
        assertEquals(utc(app.fixture.clock.now), write.parameters.getValue(3), "last_collect_at repart de maintenant")
        assertEquals(company.version, write.parameters.getValue(5))
    }

    @Test
    @DisplayName("deux associés : B arrive et personne d'autre n'est connecté (il est seul dans le signal de vie) : la période est hors ligne, plafond et efficacité")
    fun partnerArrivingAloneIsPaidOffline() = withApp { app ->
        val company = app.company()
        app.fixture.addMember(company.id, other, lingots = 0)
        val away = company.offlineCapHours + 20.0
        app.fixture.clock.advance(EngineFixture.hours(away))
        app.presence.record("serveur-1", listOf(other.text))
        val rate = app.fixture.figuresOf(company.id).ratePerHour
        val expected = app.engine.offlineEarnings(rate, away, company.offlineCapHours)

        val body = SiloooJson.parseToJsonElement(join(other).bodyAsText()) as JsonObject

        assertClose(expected, body.getValue("offlineCollected").jsonPrimitive.content.toDouble(), "offlineCollected")
        val write = app.companyUpdates().single()
        assertClose(expected, (write.parameters.getValue(1) as Double) - company.donar, "Donar versé : plafond et efficacité")
        assertEquals(utc(app.fixture.clock.now), write.parameters.getValue(3))
    }

    @Test
    @DisplayName("deux associés : le signal de vie qui nommait A est périmé quand B arrive : la période est hors ligne")
    fun staleHeartbeatDoesNotCountAsOnline() = withApp { app ->
        val company = app.company()
        app.fixture.addMember(company.id, other, lingots = 0)
        app.presence.record("serveur-1", listOf(kr.text))
        val away = company.offlineCapHours + 20.0
        app.fixture.clock.advance(EngineFixture.hours(away))
        val rate = app.fixture.figuresOf(company.id).ratePerHour

        val body = SiloooJson.parseToJsonElement(join(other).bodyAsText()) as JsonObject

        assertClose(
            app.engine.offlineEarnings(rate, away, company.offlineCapHours),
            body.getValue("offlineCollected").jsonPrimitive.content.toDouble(),
            "offlineCollected",
        )
    }

    @Test
    @DisplayName("join remet last_collect_at à maintenant même quand il n'y a rien à payer (plafond nul) : la version monte, le Donar reste")
    fun joinResetsTheDateEvenWithNothingToPay() = withApp { app ->
        val company = app.fixture.update(app.company(donar = 6.0).copy(offlineCapHours = 0.0))
        app.fixture.clock.advance(EngineFixture.hours(5.0))

        val body = SiloooJson.parseToJsonElement(join(kr).bodyAsText()) as JsonObject

        assertEquals(0.0, body.getValue("offlineCollected").jsonPrimitive.content.toDouble())
        val write = app.companyUpdates().single()
        assertEquals(6.0, write.parameters.getValue(1), "donar inchangé")
        assertEquals(company.donarEarned, write.parameters.getValue(2), "donar_earned inchangé")
        assertEquals(utc(app.fixture.clock.now), write.parameters.getValue(3), "last_collect_at repart de maintenant")
        assertEquals(company.version, write.parameters.getValue(5), "le verrou porte la version lue : elle monte d'un cran")
    }

    @Test
    @DisplayName("join ne remet rien quand l'horloge n'a pas avancé : aucune écriture, pas de coup de version pour rien")
    fun joinWritesNothingWhenNoTimeElapsed() = withApp { app ->
        app.company(donar = 6.0)

        assertEquals(HttpStatusCode.OK, join(kr).status)

        assertEquals(0, app.companyUpdates().size)
    }

    @Test
    @DisplayName("leave verse la production au revenu plein, même au-delà du plafond, et remet last_collect_at à maintenant dans la même écriture")
    fun leavePaysFullRateAndResetsTheDate() = withApp { app ->
        val company = app.company(donar = 4.0)
        assertEquals(HttpStatusCode.OK, join(kr).status)
        val away = company.offlineCapHours + 30.0
        app.fixture.clock.advance(EngineFixture.hours(away))
        val expected = app.engine.onlineEarnings(app.fixture.figuresOf(company.id).ratePerHour, away)

        assertEquals(HttpStatusCode.NoContent, leave(kr).status)

        val write = app.companyUpdates().single()
        assertClose(4.0 + expected, write.parameters.getValue(1) as Double, "donar écrit")
        assertClose(expected, write.parameters.getValue(2) as Double, "donar_earned écrit")
        assertEquals(utc(app.fixture.clock.now), write.parameters.getValue(3), "last_collect_at repart de maintenant")
        assertEquals(company.id, write.parameters.getValue(4))
        assertEquals(company.version, write.parameters.getValue(5))
    }

    @Test
    @DisplayName("leave d'un joueur jamais vu : 204, aucune écriture")
    fun leaveOfAnUnknownPlayerWritesNothing() = withApp { app ->
        val company = app.company()
        app.fixture.clock.advance(EngineFixture.hours(3.0))

        assertEquals(HttpStatusCode.NoContent, leave(stranger).status)

        assertEquals(0, app.companyUpdates().size)
        assertEquals(company.version, app.fixture.store.loadCompany(app.fixture.connection, company.id).version)
    }
}
