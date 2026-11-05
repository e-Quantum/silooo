package com.silooo.core.findings

import com.silooo.core.Dependencies
import com.silooo.core.api.ErrorBody
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.FactoryState
import com.silooo.core.api.FindingBook
import com.silooo.core.api.Idempotency
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.InMemoryApiRequestStore
import com.silooo.core.db.RecordingDatabase
import com.silooo.core.db.Xuid
import com.silooo.core.economy.EngineFixture
import com.silooo.core.economy.InMemoryCompanyStore
import com.silooo.core.events.GameEventKind
import com.silooo.core.mission.InMemoryMissionStore
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
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le Carnet de trouvailles avec des faux (pas de base) : `getFindings`, et l'inscription que fait `collect`. Le moteur est le
 * vrai, sur les `definitions/` du dépôt ; les ressources et la prime viennent de `usines.json` et `economie.json`, jamais d'un
 * littéral. Le SQL (clé primaire, courses entre deux encaissements) est dans `FindingSqlTest`, le contrat dans `ContractTest`.
 */
class FindingRoutesTest {
    private val key = "cle-de-test"
    private val kr = EngineFixture.KR
    private val other = EngineFixture.OTHER
    private val stranger = EngineFixture.STRANGER

    private class Harness(
        val fixture: EngineFixture,
        val findings: InMemoryFindingStore,
        val companies: InMemoryCompanyStore,
    ) {
        val definitions get() = fixture.definitions
        val starterId get() = fixture.starting.factoryId

        /** La prime d'une trouvaille, telle que `economie.json` la donne. */
        val reward: Long get() = definitions.economy.lingotsOutsidePrestige.finding

        /** Les produits d'une usine, dans l'ordre de usines.json. */
        fun productsOf(factoryId: String): List<String> = definitions.factories.factories.single { it.id == factoryId }.products

        /** Une entreprise du PDG [KR] avec [awayHours] heures de production en attente. */
        fun company(awayHours: Double = 2.0): CompanyRecord {
            val company = fixture.newCompany(EngineFixture.KR)
            fixture.clock.advance(EngineFixture.hours(awayHours))
            return company
        }

        /** Une usine qui n'est pas celle de départ, dont au moins un produit n'est produit par aucune usine de départ. */
        fun otherFactoryWithNewProducts(): String =
            fixture.otherFactories().first { id -> productsOf(id).any { it !in productsOf(starterId) } }
    }

    private fun withApp(block: suspend ApplicationTestBuilder.(Harness) -> Unit) = testApplication {
        val fixture = EngineFixture()
        val database = RecordingDatabase(fixture.recording)
        val findings = InMemoryFindingStore(fixture.store)
        val companies = InMemoryCompanyStore(fixture.store)
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
                    missionStore = InMemoryMissionStore(fixture.store),
                    findingStore = findings,
                ),
            )
        }
        block(Harness(fixture, findings, companies))
    }

    // --- outils

    private fun requestId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    private suspend fun ApplicationTestBuilder.get(path: String): HttpResponse = client.get(path) { header(SERVER_KEY_HEADER, key) }

    private suspend fun ApplicationTestBuilder.collect(companyId: Long, n: Int = 1, actor: Xuid = kr): HttpResponse =
        client.post("/v1/companies/$companyId/collect") {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody("""{"requestId":"${requestId(n)}","actor":"${actor.text}"}""")
        }

    private suspend fun ApplicationTestBuilder.book(xuid: Xuid): FindingBook {
        val response = get("/v1/players/${xuid.text}/findings")
        assertEquals(HttpStatusCode.OK, response.status)
        return SiloooJson.decodeFromString(FindingBook.serializer(), response.bodyAsText())
    }

    private suspend fun assertError(response: HttpResponse) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCodes.BAD_REQUEST, SiloooJson.decodeFromString(ErrorBody.serializer(), response.bodyAsText()).reason)
    }

    // --- getFindings

    @Test
    @DisplayName("getFindings : un joueur inscrit sans trouvaille a un Carnet vide, le total des ressources du jeu et la prime de economie.json ; rien n'est écrit")
    fun emptyBook() = withApp { app ->
        app.company()

        val book = book(kr)

        assertTrue(book.found.isEmpty())
        assertEquals(app.definitions.resources.size, book.total)
        assertEquals(app.reward, book.rewardLingots)
        assertTrue(app.findings.rows.isEmpty())
        assertEquals(0, app.fixture.recording.statements.size)
    }

    @Test
    @DisplayName("getFindings : un XUID mal formé, ou d'un joueur que le service ne connaît pas, donne 400 REQUETE_INVALIDE")
    fun badPlayer() = withApp { app ->
        app.company()

        assertError(get("/v1/players/pas-un-xuid/findings"))
        assertError(get("/v1/players/${stranger.text}/findings"))
    }

    // --- l'inscription par collect

    @Test
    @DisplayName("collect : le premier encaissement qui rapporte inscrit au Carnet les produits des usines en marche, verse la prime de chacun et publie un ANNONCE trouvaille")
    fun collectInscribesTheRunningFactoriesProducts() = withApp { app ->
        val company = app.company()
        val expected = app.productsOf(app.starterId)
        assertTrue(expected.isNotEmpty() && app.reward > 0, "le test n'a de sens que si l'usine de départ produit et que la prime vaut quelque chose")

        assertEquals(HttpStatusCode.OK, collect(company.id).status)

        val book = book(kr)
        assertEquals(expected.sorted(), book.found.map { it.id }, "les produits de l'usine de départ, une fois chacun, par identifiant")
        book.found.forEach { assertEquals(EngineFixture.START.toString(), it.foundAt) }
        assertEquals(
            expected.map { InMemoryFindingStore.Credit(kr, app.reward, "trouvaille", it) },
            app.findings.credits,
            "une prime par trouvaille, avec la ressource en référence",
        )
        val events = app.findings.published
        assertEquals(expected, events.map { it.payload.getValue("resourceId").jsonPrimitive.content })
        events.forEach {
            assertEquals(GameEventKind.ANNONCE, it.kind)
            assertEquals(kr, it.xuid)
            assertEquals("trouvaille", it.payload.getValue("action").jsonPrimitive.content)
            assertEquals(app.reward.toString(), it.payload.getValue("rewardLingots").jsonPrimitive.content)
        }
    }

    @Test
    @DisplayName("collect : un second encaissement ne réinscrit rien et ne repaye pas")
    fun secondCollectPaysNothingMore() = withApp { app ->
        val company = app.company()
        collect(company.id, n = 1)
        val credits = app.findings.credits.size
        val events = app.findings.published.size
        val lingots = app.fixture.store.balances.getValue(kr).lingots
        app.fixture.clock.advance(EngineFixture.hours(2.0))

        assertEquals(HttpStatusCode.OK, collect(company.id, n = 2).status)

        assertEquals(credits, app.findings.credits.size)
        assertEquals(events, app.findings.published.size)
        assertEquals(lingots, app.fixture.store.balances.getValue(kr).lingots)
        assertEquals(credits, book(kr).found.size)
    }

    @Test
    @DisplayName("collect rejoué avec le même requestId : le Carnet n'est pas payé deux fois")
    fun replayDoesNotPayTwice() = withApp { app ->
        val company = app.company()

        collect(company.id, n = 4)
        val credits = app.findings.credits.size
        collect(company.id, n = 4)

        assertEquals(credits, app.findings.credits.size)
        assertEquals(credits, app.findings.published.size)
    }

    @Test
    @DisplayName("collect : rien n'est dû, rien n'est trouvé : sans production, le Carnet reste vide")
    fun nothingEarnedFindsNothing() = withApp { app ->
        val company = app.company(awayHours = 0.0)

        assertEquals(HttpStatusCode.OK, collect(company.id).status)

        assertTrue(app.findings.rows.isEmpty())
        assertTrue(app.findings.credits.isEmpty())
        assertTrue(app.findings.published.isEmpty())
    }

    @Test
    @DisplayName("collect : une usine en ruine ne trouve rien ; une fois remise en marche, ses produits nouveaux entrent au Carnet, seuls les nouveaux sont payés")
    fun onlyRunningFactoriesFind() = withApp { app ->
        val company = app.company()
        val second = app.otherFactoryWithNewProducts()
        app.fixture.putFactory(company.id, FactoryRecord(second, FactoryState.EN_RUINE, 0, 0, null))
        val starting = app.productsOf(app.starterId)

        collect(company.id, n = 1)
        assertEquals(starting.sorted(), book(kr).found.map { it.id }, "l'usine en ruine n'a rien produit")

        app.fixture.putFactory(company.id, FactoryRecord(second, FactoryState.ACTIVE, 0, 1, null))
        app.fixture.clock.advance(EngineFixture.hours(2.0))
        val before = app.findings.credits.size
        collect(company.id, n = 2)

        val expectedNew = app.productsOf(second).filter { it !in starting }
        assertTrue(expectedNew.isNotEmpty())
        assertEquals((starting + expectedNew).toSet(), book(kr).found.map { it.id }.toSet())
        assertEquals(expectedNew, app.findings.credits.drop(before).map { it.refId }, "seules les ressources nouvelles sont payées")
    }

    @Test
    @DisplayName("collect : le Carnet est celui de qui encaisse ; un associé qui encaisse inscrit dans le sien, et son Carnet ne dépend pas de celui du PDG")
    fun everyPlayerHasTheirOwnBook() = withApp { app ->
        val company = app.company()
        app.fixture.addMember(company.id, other, lingots = 0)
        collect(company.id, n = 1, actor = kr)
        assertTrue(book(other).found.isEmpty(), "ce que le PDG a trouvé n'est pas à son associé")
        app.fixture.clock.advance(EngineFixture.hours(2.0))

        collect(company.id, n = 2, actor = other)

        val expected = app.productsOf(app.starterId)
        assertEquals(expected.sorted(), book(other).found.map { it.id })
        assertEquals(expected.sorted(), book(kr).found.map { it.id })
        assertEquals(expected.size * 2, app.findings.credits.size, "chacun touche sa prime pour ses propres trouvailles")
        assertEquals(expected.size, app.findings.credits.count { it.xuid == other })
    }
}
