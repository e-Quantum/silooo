package com.silooo.core.findings

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.ErrorBody
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.FindingBook
import com.silooo.core.api.Idempotency
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le Carnet de trouvailles sur la vraie MariaDB, par HTTP, avec le vrai moteur, le vrai dépôt SQL et la vraie idempotence :
 * ce que les tests de route ne voient pas, c'est-à-dire ce qui est vraiment écrit dans `player_findings`, `player_balances`,
 * `currency_ledger` et `game_events`, dans la même transaction que le `collect` qui les cause, et la clé primaire qui garde
 * une trouvaille de s'inscrire deux fois. Ignoré sans Docker (voir [MariaDbSupport]) : la CI les rejoue.
 *
 * Les ressources attendues et la prime sortent de `definitions/`, jamais d'un littéral recopié.
 */
class FindingSqlTest {
    private val database get() = MariaDbSupport.database
    private val definitions = TestDefinitions.loaded
    private val engine = EconomyEngine(definitions)
    private val store = JdbcSessionStore()
    private val clock = MutableClock(START)
    private val key = "cle-de-test"

    private val reward = definitions.economy.lingotsOutsidePrestige.finding
    private val starterId = definitions.economy.prestige.courseStart.factoryId
    private val starterProducts = definitions.factories.factories.single { it.id == starterId }.products

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    // --- le service et l'entreprise de départ

    private fun dependencies(findingStore: FindingStore): Dependencies = Dependencies(
        database = database,
        definitions = definitions,
        apiKey = key,
        economy = engine,
        idempotency = Idempotency(database),
        sessionStore = store,
        economyFigures = EngineFigures(engine, store, clock),
        companyBootstrap = DefinitionsBootstrap(engine, definitions),
        findingStore = findingStore,
    )

    /** Crée l'entreprise de [KR] par le vrai `join`, puis monte le service sur la base. */
    private fun withCompany(findingStore: FindingStore = JdbcFindingStore(), block: suspend ApplicationTestBuilder.(Long) -> Unit) {
        val joined = runBlocking {
            SessionService(database, store, EngineFigures(engine, store, clock), DefinitionsBootstrap(engine, definitions)).join(KR, "Kr")
        }
        testApplication {
            application { siloooModule(dependencies(findingStore)) }
            block(joined.companyId)
        }
    }

    /** Un dépôt qui écrit comme le vrai, sauf la publication d'événement : la dernière écriture de la trouvaille échoue. */
    private class FailingPublish(private val real: FindingStore = JdbcFindingStore()) : FindingStore by real {
        override fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long =
            throw IllegalStateException("publication impossible (test)")
    }

    // --- HTTP

    private fun requestId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    private suspend fun ApplicationTestBuilder.collect(companyId: Long, n: Int = 1, actor: Xuid = KR): HttpResponse =
        client.post("/v1/companies/$companyId/collect") {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody("""{"requestId":"${requestId(n)}","actor":"${actor.text}"}""")
        }

    private suspend fun ApplicationTestBuilder.findings(xuid: String): HttpResponse =
        client.get("/v1/players/$xuid/findings") { header(SERVER_KEY_HEADER, key) }

    private suspend fun ApplicationTestBuilder.bookOf(xuid: Xuid): FindingBook {
        val response = findings(xuid.text)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return SiloooJson.decodeFromString(FindingBook.serializer(), response.bodyAsText())
    }

    /** Remet l'horloge de production à maintenant puis la laisse courir [away] heures : de quoi gagner du Donar. */
    private fun earnSomething(companyId: Long, away: Double = 1.0) {
        MariaDbSupport.update(
            "UPDATE companies SET last_collect_at = ? WHERE id = ?",
            LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
            companyId,
        )
        clock.advance(hours(away))
    }

    // --- lectures en base

    private fun findingRows(): List<String?> =
        MariaDbSupport.strings("SELECT CONCAT(CAST(xuid AS CHAR), '|', finding_id) FROM player_findings ORDER BY xuid, finding_id")

    private fun lingotsOf(xuid: Xuid) = MariaDbSupport.long("SELECT lingots FROM player_balances WHERE xuid = ?", xuid.text)

    /** Le journal des primes de trouvaille : `xuid|monnaie|delta|raison|ressource` (le solde dépend des autres versements, il n'y est pas). */
    private fun findingLedger(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(CAST(xuid AS CHAR), '|', currency, '|', delta, '|', reason, '|', ref_id) FROM currency_ledger " +
            "WHERE reason = 'trouvaille' ORDER BY id",
    )

    private fun findingEvents(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(IFNULL(CAST(xuid AS CHAR), '-'), '|', kind, '|', JSON_VALUE(payload, '\$.resourceId'), '|', JSON_VALUE(payload, '\$.rewardLingots')) " +
            "FROM game_events WHERE JSON_VALUE(payload, '\$.action') = 'trouvaille' ORDER BY id",
    )

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

    // --- getFindings

    @Test
    @DisplayName("getFindings : un joueur qui vient de rejoindre a un Carnet vide ; lire n'écrit aucune ligne")
    fun freshPlayerHasAnEmptyBook() = withCompany {
        val book = bookOf(KR)

        assertTrue(book.found.isEmpty())
        assertEquals(definitions.resources.size, book.total)
        assertEquals(reward, book.rewardLingots)
        assertEquals(0L, MariaDbSupport.count("player_findings"))
    }

    @Test
    @DisplayName("getFindings : un XUID inconnu du service, mal formé ou trop long donne 400 REQUETE_INVALIDE")
    fun unknownOrMalformedPlayerIsRefused() = withCompany {
        for (xuid in listOf(OTHER.text, "abc", "-1", "123456789012345678901")) {
            val response = findings(xuid)
            assertEquals(HttpStatusCode.BadRequest, response.status, xuid)
            assertEquals(ErrorCodes.BAD_REQUEST, SiloooJson.decodeFromString(ErrorBody.serializer(), response.bodyAsText()).reason)
        }
    }

    // --- collect

    @Test
    @DisplayName("collect : un encaissement qui rapporte inscrit les produits des usines en marche : lignes, solde, journal et événements sont écrits ensemble")
    fun collectInscribesAndPays() = withCompany { id ->
        earnSomething(id)

        assertEquals(HttpStatusCode.OK, collect(id).status)

        assertEquals(starterProducts.sorted().map { "${KR.text}|$it" }, findingRows())
        assertEquals(starterProducts.map { "${KR.text}|LINGOTS|$reward|trouvaille|$it" }, findingLedger(), "une prime par ressource, qui en est la référence")
        assertEquals(starterProducts.map { "${KR.text}|ANNONCE|$it|$reward" }, findingEvents())
        assertTrue(lingotsOf(KR) >= reward * starterProducts.size, "les primes sont au solde (la mission de bienvenue s'y ajoute)")

        val book = bookOf(KR)
        assertEquals(starterProducts.sorted(), book.found.map { it.id })
        book.found.forEach { assertTrue(runCatching { Instant.parse(it.foundAt) }.isSuccess, "foundAt est un instant ISO : ${it.foundAt}") }
    }

    @Test
    @DisplayName("collect rejoué, puis encaissé de nouveau : une trouvaille déjà faite ne verse rien de plus et ne publie rien de plus")
    fun aFindingPaysOnce() = withCompany { id ->
        earnSomething(id)
        val firstAnswer = collect(id, n = 1).bodyAsText()

        assertEquals(firstAnswer, collect(id, n = 1).bodyAsText(), "le même requestId rend la réponse gardée")
        earnSomething(id)
        assertEquals(HttpStatusCode.OK, collect(id, n = 2).status)

        assertEquals(starterProducts.size, findingRows().size)
        assertEquals(starterProducts.size, findingLedger().size)
        assertEquals(starterProducts.size, findingEvents().size)
    }

    @Test
    @DisplayName("collect : rien n'est gagné (usine en ruine) : aucune trouvaille")
    fun noGainFindsNothing() = withCompany { id ->
        MariaDbSupport.update("UPDATE company_factories SET state = 'EN_RUINE', level = 0 WHERE company_id = ?", id)
        earnSomething(id, away = 3.0)

        assertEquals(HttpStatusCode.OK, collect(id).status)

        assertEquals(emptyList<String?>(), findingRows())
        assertEquals(emptyList<String?>(), findingLedger())
        assertEquals(emptyList<String?>(), findingEvents())
    }

    @Test
    @DisplayName("collect : si la dernière écriture de la trouvaille échoue, tout est annulé (Donar, version, trouvailles, Lingots, journal, événements)")
    fun findingsRollBackWithTheCollect() = withCompany(FailingPublish()) { id ->
        earnSomething(id)
        val donarBefore = donar(id)

        val response = collect(id)

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(donarBefore, donar(id))
        assertEquals(0L, version(id))
        assertEquals(emptyList<String?>(), findingRows())
        assertEquals(emptyList<String?>(), findingLedger())
        assertEquals(0L, lingotsOf(KR))
        assertEquals(0L, MariaDbSupport.count("game_events"))
    }

    @Test
    @DisplayName("Chaque joueur a son Carnet : un associé qui encaisse inscrit dans le sien, ce que le PDG a trouvé n'est pas à lui")
    fun eachMemberHasHisOwnBook() = withCompany { id ->
        val response = client.post("/v1/session/join") {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody("""{"xuid":"${OTHER.text}","gamertag":"Associe"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        MariaDbSupport.update("UPDATE company_members SET company_id = ?, role = 'ASSOCIE' WHERE xuid = ?", id, OTHER.text)
        earnSomething(id)
        assertEquals(HttpStatusCode.OK, collect(id, n = 1, actor = KR).status)
        assertTrue(bookOf(OTHER).found.isEmpty())

        earnSomething(id)
        assertEquals(HttpStatusCode.OK, collect(id, n = 2, actor = OTHER).status)

        assertEquals(starterProducts.sorted(), bookOf(OTHER).found.map { it.id })
        assertEquals(starterProducts.sorted(), bookOf(KR).found.map { it.id })
        assertEquals(starterProducts.size * 2, findingRows().size)
        assertEquals(starterProducts.size * 2, findingLedger().size)
    }

    // --- la clé primaire

    @Test
    @DisplayName("add : une trouvaille ne s'inscrit qu'une fois ; l'écriture qui perd le dit (false) et ne change rien")
    fun addSaysWhoWon() {
        MariaDbSupport.insertPlayer(KR.text)
        val findings = JdbcFindingStore()
        val resource = starterProducts.first()

        assertTrue(runBlocking { database.transaction { findings.add(it, KR, resource) } })
        assertFalse(runBlocking { database.transaction { findings.add(it, KR, resource) } })

        assertEquals(listOf("${KR.text}|$resource"), findingRows())
    }

    @Test
    @DisplayName("add : des inscriptions simultanées de la même trouvaille ne la comptent qu'une fois, une seule écriture gagne")
    fun parallelAddsHaveOneWinner() {
        MariaDbSupport.insertPlayer(KR.text)
        val findings = JdbcFindingStore()
        val resource = starterProducts.first()

        val wins = runBlocking {
            coroutineScope {
                val go = CompletableDeferred<Unit>()
                val jobs = (1..4).map { async(Dispatchers.IO) { go.await(); database.transaction { findings.add(it, KR, resource) } } }
                go.complete(Unit)
                jobs.awaitAll()
            }
        }

        assertEquals(1, wins.count { it }, "une seule écriture gagne")
        assertEquals(listOf("${KR.text}|$resource"), findingRows())
    }

    @Test
    @DisplayName("playerExists : vrai pour un joueur inscrit, faux pour les autres ; load rend le Carnet du joueur seul, du plus ancien au plus récent")
    fun playerExistsAndLoad() {
        MariaDbSupport.insertPlayer(KR.text)
        MariaDbSupport.insertPlayer(OTHER.text)
        val findings = JdbcFindingStore()
        val early = LocalDateTime.of(2026, 11, 5, 10, 0, 0)
        val late = LocalDateTime.of(2026, 11, 5, 11, 0, 0)
        for ((xuid, resource, at) in listOf(
            Triple(KR, "b-ressource", early),
            Triple(KR, "a-ressource", late),
            Triple(KR, "c-ressource", early),
            Triple(OTHER, "d-ressource", early),
        )) {
            MariaDbSupport.update("INSERT INTO player_findings (xuid, finding_id, found_at) VALUES (?, ?, ?)", xuid.text, resource, at)
        }

        assertTrue(runBlocking { database.transaction { findings.playerExists(it, KR) } })
        assertFalse(runBlocking { database.transaction { findings.playerExists(it, Xuid.parse("2535428000000099")!!) } })
        val book = runBlocking { database.transaction { findings.load(it, KR) } }
        assertEquals(listOf("b-ressource", "c-ressource", "a-ressource"), book.map { it.resourceId }, "le plus ancien d'abord, à égalité par identifiant")
        assertEquals(early.toInstant(ZoneOffset.UTC), book.first().foundAt)
    }
}
