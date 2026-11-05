package com.silooo.core.orders

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.Idempotency
import com.silooo.core.api.OrderDelivered
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.RefusedBody
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.api.WorldOrder
import com.silooo.core.db.MariaDbSupport
import com.silooo.core.db.Xuid
import com.silooo.core.economy.DefinitionsBootstrap
import com.silooo.core.economy.EconomyEngine
import com.silooo.core.economy.EngineFigures
import com.silooo.core.economy.EngineFixture.Companion.KR
import com.silooo.core.economy.EngineFixture.Companion.OTHER
import com.silooo.core.economy.EngineFixture.Companion.START
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
import java.time.LocalDate
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
 * La commande mondiale sur la vraie MariaDB, par HTTP, avec le vrai moteur, le vrai dépôt SQL et la vraie idempotence : ce
 * que les tests de route ne voient pas, c'est-à-dire ce qui est vraiment écrit dans `world_orders`,
 * `world_order_contributions`, `companies`, `player_balances`, `currency_ledger` et `game_events`, dans une seule
 * transaction, et ce que le verrou de la ligne de commande fait quand des joueurs livrent en même temps. Ignoré sans Docker
 * (voir [MariaDbSupport]) : la CI les rejoue.
 *
 * Les chiffres attendus (objectif, prime, ressource du jour) sortent de `definitions/`, jamais d'un littéral recopié.
 */
class OrderSqlTest {
    private val database get() = MariaDbSupport.database
    private val definitions = TestDefinitions.loaded
    private val engine = EconomyEngine(definitions)
    private val store = JdbcSessionStore()
    private val clock = MutableClock(START)
    private val key = "cle-de-test"

    private val target = definitions.economy.dailyOrder.targetUnits
    private val reward = definitions.economy.lingotsOutsidePrestige.dailyOrder
    private val day: LocalDate get() = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    private val resourceOfTheDay: String
        get() = definitions.resources[Math.floorMod(day.toEpochDay(), definitions.resources.size.toLong()).toInt()]

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    // --- le service, les entreprises

    private fun dependencies(orderStore: OrderStore): Dependencies = Dependencies(
        database = database,
        definitions = definitions,
        apiKey = key,
        economy = engine,
        idempotency = Idempotency(database),
        sessionStore = store,
        economyFigures = EngineFigures(engine, store, clock),
        companyBootstrap = DefinitionsBootstrap(engine, definitions),
        orderStore = orderStore,
        ordersClock = clock,
    )

    private fun join(xuid: Xuid): Long = runBlocking {
        SessionService(database, store, EngineFigures(engine, store, clock), DefinitionsBootstrap(engine, definitions)).join(xuid, "J${xuid.text}")
    }.companyId

    /** Crée l'entreprise de [KR] et celle de [OTHER] par le vrai `join`, de quoi payer toute la commande, puis monte le service sur la base. */
    private fun withCompanies(orderStore: OrderStore = JdbcOrderStore(), block: suspend ApplicationTestBuilder.(Long, Long) -> Unit) {
        val kr = join(KR)
        val other = join(OTHER)
        // La production repart de l'horloge du test, pas de celle de la base : rien n'est en attente, seul le prix de la livraison bouge le Donar.
        MariaDbSupport.update("UPDATE companies SET donar = ?, last_collect_at = ?", RICH, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
        testApplication {
            application { siloooModule(dependencies(orderStore)) }
            block(kr, other)
        }
    }

    /** Un dépôt qui écrit comme le vrai, sauf la publication d'événement : la dernière écriture de la livraison échoue. */
    private class FailingPublish(private val real: OrderStore = JdbcOrderStore()) : OrderStore by real {
        override fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long =
            throw IllegalStateException("publication impossible (test)")
    }

    /** Un dépôt qui écrit comme le vrai, sauf l'annonce de fin de commande : tout le reste (prime, solde, journal) est déjà écrit quand elle échoue. */
    private class FailingOnCompletion(private val real: OrderStore = JdbcOrderStore()) : OrderStore by real {
        override fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long {
            if (kind == GameEventKind.COMMANDE_TERMINEE) throw IllegalStateException("annonce de fin impossible (test)")
            return real.publish(connection, kind, xuid, payload)
        }
    }

    // --- HTTP

    private fun requestId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    private suspend fun ApplicationTestBuilder.deliver(quantity: Long, n: Int = 1, actor: Xuid = KR): HttpResponse =
        client.post("/v1/orders/today/deliveries") {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody("""{"requestId":"${requestId(n)}","actor":"${actor.text}","quantity":$quantity}""")
        }

    private suspend fun ApplicationTestBuilder.today(viewer: Xuid? = null): WorldOrder {
        val response = client.get(if (viewer == null) "/v1/orders/today" else "/v1/orders/today?xuid=${viewer.text}") { header(SERVER_KEY_HEADER, key) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return SiloooJson.decodeFromString(WorldOrder.serializer(), response.bodyAsText())
    }

    private suspend fun delivered(response: HttpResponse): OrderDelivered {
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return SiloooJson.decodeFromString(OrderDelivered.serializer(), response.bodyAsText())
    }

    private suspend fun reasonOf(response: HttpResponse): String {
        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        return SiloooJson.decodeFromString(RefusedBody.serializer(), response.bodyAsText()).reason
    }

    // --- lectures en base

    private fun orderRows(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(day, '|', resource_id, '|', reward_lingots, '|', completed_at IS NOT NULL) FROM world_orders ORDER BY day",
    )

    private fun deliveredInDb(): Double = doubles("SELECT delivered FROM world_orders").single()

    /** Les parts livrées : XUID puis quantité, dans l'ordre des XUID. */
    private fun parts(): List<Pair<String, Double>> = MariaDbSupport.raw { connection ->
        connection.prepareStatement("SELECT CAST(xuid AS CHAR), quantity FROM world_order_contributions ORDER BY xuid").use { statement ->
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getDouble(2)) } }
        }
    }

    private fun rewardedParts() = MariaDbSupport.long("SELECT COUNT(*) FROM world_order_contributions WHERE rewarded_at IS NOT NULL")

    private fun lingotsOf(xuid: Xuid) = MariaDbSupport.long("SELECT lingots FROM player_balances WHERE xuid = ?", xuid.text)

    private fun ledger(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(CAST(xuid AS CHAR), '|', currency, '|', delta, '|', balance, '|', reason, '|', ref_id) FROM currency_ledger ORDER BY id",
    )

    /** Les événements dans l'ordre : `xuid|type|action`, l'action vide pour un type qui n'en porte pas. */
    private fun events(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(IFNULL(CAST(xuid AS CHAR), '-'), '|', kind, '|', IFNULL(JSON_VALUE(payload, '\$.action'), '')) FROM game_events ORDER BY id",
    )

    private fun doubles(sql: String, vararg params: Any?): List<Double> = MariaDbSupport.raw { connection ->
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getDouble(1)) } }
        }
    }

    /** La somme des `quantity` annoncées par les événements de livraison. */
    private fun deliveredByEvents(): Double = doubles(
        "SELECT JSON_VALUE(payload, '\$.quantity') FROM game_events WHERE JSON_VALUE(payload, '\$.action') = 'deliverOrder'",
    ).sum()

    private fun donar(companyId: Long): Double = doubles("SELECT donar FROM companies WHERE id = ?", companyId).single()

    private fun version(companyId: Long) = MariaDbSupport.long("SELECT version FROM companies WHERE id = ?", companyId)

    // --- la livraison

    @Test
    @DisplayName("deliverOrder : la commande du jour, la part du joueur, le Donar, la version et l'événement sont écrits ensemble ; lire n'écrit rien")
    fun deliveryIsWrittenTogether() = withCompanies { kr, _ ->
        assertEquals(0L, MariaDbSupport.count("world_orders"))
        val before = today(KR)
        assertEquals(0L, MariaDbSupport.count("world_orders"), "lire la commande ne l'écrit pas")
        assertEquals(0.0, before.delivered)
        val donarBefore = donar(kr)
        val versionBefore = version(kr)

        val result = delivered(deliver(10))

        assertEquals(listOf("$day|$resourceOfTheDay|$reward|0"), orderRows())
        assertEquals(10.0, deliveredInDb())
        assertEquals(listOf(KR.text to 10.0), parts())
        assertEquals(donar(kr), result.state.donar, "la réponse montre ce qui est écrit")
        assertTrue(donar(kr) < donarBefore, "une livraison coûte du Donar")
        assertEquals(versionBefore + 1, version(kr))
        assertEquals(listOf("${KR.text}|ANNONCE|deliverOrder"), events())
        assertEquals(emptyList<String?>(), ledger(), "la prime n'est versée qu'à la fin")

        val after = today(KR)
        assertEquals(10.0, after.delivered)
        assertEquals(10.0, after.mine)
        assertEquals(0.0, today(OTHER).mine)
        assertEquals(0.0, today().mine)
    }

    @Test
    @DisplayName("deliverOrder : à la fin, chaque joueur qui a livré reçoit sa prime une fois (solde, journal, rewarded_at, événement) ; la suite ne prend ni n'écrit rien")
    fun completionPaysEveryContributorOnce() = withCompanies { kr, _ ->
        val firstPart = target * 6 / 10

        delivered(deliver(firstPart, n = 1, actor = KR))
        assertEquals(0L, rewardedParts())
        val last = delivered(deliver(target, n = 2, actor = OTHER))

        assertTrue(last.order.completed)
        assertEquals(listOf("$day|$resourceOfTheDay|$reward|1"), orderRows())
        assertEquals(target.toDouble(), deliveredInDb(), "la livraison qui dépasse est ramenée au reste")
        assertEquals(listOf(KR.text to firstPart.toDouble(), OTHER.text to (target - firstPart).toDouble()), parts())
        assertEquals(2L, rewardedParts())
        assertEquals(reward, lingotsOf(KR))
        assertEquals(reward, lingotsOf(OTHER))
        assertEquals(
            listOf("${KR.text}|LINGOTS|$reward|$reward|commande|commande-$day", "${OTHER.text}|LINGOTS|$reward|$reward|commande|commande-$day"),
            ledger().sortedBy { it!!.substringBefore('|').toLong() },
        )
        assertEquals(
            listOf(
                "${KR.text}|ANNONCE|deliverOrder",
                "${OTHER.text}|ANNONCE|deliverOrder",
                "${KR.text}|COMMANDE_TERMINEE|",
                "${OTHER.text}|COMMANDE_TERMINEE|",
            ),
            events(),
        )

        // Une commande terminée ne reprend rien, n'écrit rien et ne repaye pas : 200 avec la commande finie.
        val donarBefore = donar(kr)
        val versionBefore = version(kr)
        val extra = delivered(deliver(1, n = 3, actor = KR))
        assertTrue(extra.order.completed)
        assertEquals(target.toDouble(), extra.order.delivered)
        assertEquals(donarBefore, donar(kr))
        assertEquals(versionBefore, version(kr))
        assertEquals(2, ledger().size)
        assertEquals(4, events().size)
        assertEquals(target.toDouble(), deliveredInDb())
    }

    @Test
    @DisplayName("deliverOrder rejoué avec le même requestId : la même réponse, une seule livraison")
    fun replayDeliversOnce() = withCompanies { kr, _ ->
        val first = deliver(10, n = 1).bodyAsText()
        val donarAfterFirst = donar(kr)

        assertEquals(first, deliver(10, n = 1).bodyAsText())

        assertEquals(10.0, deliveredInDb())
        assertEquals(donarAfterFirst, donar(kr))
        assertEquals(1, events().size)
    }

    @Test
    @DisplayName("deliverOrder : pas assez de Donar donne PAS_ASSEZ_DE_DONAR et rien n'est écrit (ni la commande, ni la part, ni l'événement)")
    fun poorCompanyWritesNothing() = withCompanies { kr, _ ->
        MariaDbSupport.update("UPDATE companies SET donar = 0 WHERE id = ?", kr)

        assertEquals(RefusalCodes.NOT_ENOUGH_DONAR, reasonOf(deliver(10)))

        assertEquals(0.0, donar(kr))
        assertEquals(0L, version(kr))
        assertEquals(0L, MariaDbSupport.count("world_order_contributions"))
        assertEquals(emptyList<String?>(), events())
        assertEquals(0.0, today(KR).delivered)
    }

    @Test
    @DisplayName("deliverOrder : si la dernière écriture échoue, tout est annulé (commande, part, Donar, version, événements)")
    fun deliveryRollsBackAsOne() = withCompanies(FailingPublish()) { kr, _ ->
        val donarBefore = donar(kr)

        assertEquals(HttpStatusCode.InternalServerError, deliver(10).status)

        assertEquals(donarBefore, donar(kr))
        assertEquals(0L, version(kr))
        assertEquals(emptyList<String?>(), orderRows())
        assertEquals(0L, MariaDbSupport.count("world_order_contributions"))
        assertEquals(emptyList<String?>(), ledger())
        assertEquals(emptyList<String?>(), events())
    }

    @Test
    @DisplayName("deliverOrder : si l'annonce de fin échoue, la prime, le solde, le journal et la livraison qui a terminé la commande sont annulés ensemble")
    fun completionRollsBackAsOne() = withCompanies(FailingOnCompletion()) { _, other ->
        val firstPart = target / 2
        delivered(deliver(firstPart, n = 1, actor = KR))
        val donarBefore = donar(other)

        assertEquals(HttpStatusCode.InternalServerError, deliver(target, n = 2, actor = OTHER).status)

        assertEquals(firstPart.toDouble(), deliveredInDb(), "la livraison de OTHER est annulée")
        assertEquals(listOf("$day|$resourceOfTheDay|$reward|0"), orderRows(), "la commande n'est pas terminée")
        assertEquals(listOf(KR.text to firstPart.toDouble()), parts())
        assertEquals(0L, rewardedParts())
        assertEquals(donarBefore, donar(other))
        assertEquals(0L, version(other))
        assertEquals(emptyList<String?>(), ledger())
        assertEquals(listOf("${KR.text}|ANNONCE|deliverOrder"), events(), "seule la livraison de KR est annoncée")
        assertEquals(0L, lingotsOf(KR))
    }

    // --- les livraisons simultanées

    @Test
    @DisplayName("Des livraisons simultanées de deux joueurs : jamais plus que l'objectif, les parts et les événements font la commande, une prime par joueur payé, les perdants reçoivent CONFLIT_REESSAYER")
    fun parallelDeliveriesKeepTheOrderConsistent() = withCompanies { _, _ ->
        val each = target * 6 / 10

        val responses = coroutineScope {
            val go = CompletableDeferred<Unit>()
            val jobs = listOf(KR, OTHER, KR, OTHER).mapIndexed { index, actor ->
                async(Dispatchers.IO) {
                    go.await()
                    deliver(each, n = index + 1, actor = actor)
                }
            }
            go.complete(Unit)
            jobs.awaitAll()
        }

        assertTrue(responses.any { it.status == HttpStatusCode.OK }, "au moins une livraison passe")
        for (response in responses.filter { it.status != HttpStatusCode.OK }) {
            assertEquals(RefusalCodes.CONFLICT_RETRY, reasonOf(response), "un perdant reçoit CONFLIT_REESSAYER")
        }

        val written = deliveredInDb()
        assertTrue(written > 0.0 && written <= target, "la commande ne dépasse jamais l'objectif : $written")
        assertEquals(written, parts().sumOf { it.second }, "les parts font exactement la commande")
        assertEquals(written, deliveredByEvents(), "les événements de livraison disent exactement ce qui a été livré")

        val completed = orderRows().single()!!.endsWith("|1")
        assertEquals(completed, written == target.toDouble())
        val paid = if (completed) parts().size else 0
        assertEquals(paid.toLong(), rewardedParts())
        assertEquals(paid, ledger().count { it!!.contains("|commande|commande-$day") }, "une prime par joueur payé")
        assertEquals(paid, events().count { it!!.contains("|COMMANDE_TERMINEE|") })
    }

    @Test
    @DisplayName("lockOrCreate : deux demandes pour le même jour ne font qu'une ligne, et rendent la même commande")
    fun theDayHasOneRow() {
        val orders = JdbcOrderStore()

        val first = runBlocking { database.transaction { orders.lockOrCreate(it, day, resourceOfTheDay, target.toDouble(), reward) } }
        val second = runBlocking { database.transaction { orders.lockOrCreate(it, day, "autre", 1.0, 0) } }

        assertEquals(first, second, "la commande écrite la première reste celle du jour")
        assertEquals(1L, MariaDbSupport.count("world_orders"))
    }

    private companion object {
        /** Assez de Donar pour livrer toute la commande à n'importe quel revenu de départ. */
        const val RICH = 1.0e12
    }
}
