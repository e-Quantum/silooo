package com.silooo.core.orders

import com.silooo.core.Dependencies
import com.silooo.core.api.Balances
import com.silooo.core.api.ErrorBody
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.Idempotency
import com.silooo.core.api.OrderDelivered
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.RefusedBody
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.api.WorldOrder
import com.silooo.core.db.InMemoryApiRequestStore
import com.silooo.core.db.RecordingDatabase
import com.silooo.core.db.Xuid
import com.silooo.core.economy.EngineFixture
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.CompanyRecord
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
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `todayOrder` et `deliverOrder` avec des faux (pas de base) : le moteur d'économie réel sur les `definitions/` du dépôt, un
 * faux dépôt en mémoire, une horloge que le test avance et une connexion qui note les `UPDATE companies`. Les chiffres attendus
 * (objectif, prime, prix d'une unité, revenu) sortent de `definitions/` et du moteur, jamais d'un littéral. Le SQL et les
 * verrous sont dans `OrderSqlTest`, le contrat dans `ContractTest`.
 */
class OrderRoutesTest {
    private val key = "cle-de-test"
    private val kr = EngineFixture.KR
    private val other = EngineFixture.OTHER
    private val stranger = EngineFixture.STRANGER

    private class Harness(
        val fixture: EngineFixture,
        val database: RecordingDatabase,
        val orders: InMemoryOrderStore,
    ) {
        val definitions get() = fixture.definitions
        val recording get() = fixture.recording

        /** L'objectif de la commande du jour, en unités entières, tel que `economie.json` le donne. */
        val target: Long get() = definitions.economy.dailyOrder.targetUnits

        val reward: Long get() = definitions.economy.lingotsOutsidePrestige.dailyOrder

        /** Le jour de jeu du test : la date UTC de l'horloge. */
        val today: LocalDate get() = LocalDate.ofInstant(fixture.clock.instant(), ZoneOffset.UTC)

        /** Les `UPDATE companies` écrits jusqu'ici. */
        fun companyUpdates() = recording.statements.filter { it.sql.startsWith("UPDATE companies") }

        /**
         * Une entreprise du PDG [owner] avec [donar] en caisse. Sans [donar], de quoi payer dix fois la commande entière au
         * revenu de l'entreprise : la caisse ne limite pas le test, et les écarts restent bien plus grands que l'arrondi.
         */
        fun company(owner: Xuid = EngineFixture.KR, donar: Double? = null): CompanyRecord {
            val created = fixture.newCompany(owner)
            return fixture.update(created.copy(donar = donar ?: (10 * costOf(created.id, target))))
        }

        /**
         * Ce que la base ferait de l'écriture de l'entreprise : le faux dépôt ne rejoue pas les `UPDATE`, le test reporte donc
         * l'état rendu par la livraison (Donar, version) dans l'entreprise que la livraison suivante va lire.
         */
        fun applyWrite(result: OrderDelivered) {
            val company = fixture.store.loadCompany(fixture.connection, result.state.companyId)
            fixture.update(
                company.copy(
                    donar = result.state.donar,
                    donarEarned = result.state.donarEarned,
                    lastCollectAt = fixture.clock.instant(),
                    version = result.state.version,
                ),
            )
        }

        fun lingotsOf(xuid: Xuid): Long = fixture.store.balances.getValue(xuid).lingots

        /** Ce que coûtent [units] unités à l'entreprise : le prix de `economie.json` au revenu de l'entreprise. */
        fun costOf(companyId: Long, units: Long): Double =
            units * definitions.economy.dailyOrder.minutesOfIncomePerUnit / 60.0 * fixture.figuresOf(companyId).ratePerHour

        /** La ressource que le plan donne pour [day] : la `(jour mod nombre de ressources)`-ième. */
        fun resourceOf(day: LocalDate): String =
            definitions.resources[Math.floorMod(day.toEpochDay(), definitions.resources.size.toLong()).toInt()]
    }

    private fun withApp(block: suspend ApplicationTestBuilder.(Harness) -> Unit) = testApplication {
        val fixture = EngineFixture()
        val database = RecordingDatabase(fixture.recording)
        val orders = InMemoryOrderStore(fixture.store)
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
                    orderStore = orders,
                    ordersClock = fixture.clock,
                ),
            )
        }
        block(Harness(fixture, database, orders))
    }

    // --- outils

    private fun requestId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    private fun body(n: Int, actor: String, quantity: String): String =
        """{"requestId":"${requestId(n)}","actor":"$actor","quantity":$quantity}"""

    private suspend fun ApplicationTestBuilder.get(path: String): HttpResponse = client.get(path) { header(SERVER_KEY_HEADER, key) }

    private suspend fun ApplicationTestBuilder.rawPost(body: String): HttpResponse =
        client.post("/v1/orders/today/deliveries") {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.deliver(quantity: Long, n: Int = 1, actor: Xuid = kr): HttpResponse =
        rawPost(body(n, actor.text, quantity.toString()))

    private suspend fun ApplicationTestBuilder.today(viewer: Xuid? = null): HttpResponse =
        get(if (viewer == null) "/v1/orders/today" else "/v1/orders/today?xuid=${viewer.text}")

    private suspend fun orderOf(response: HttpResponse): WorldOrder {
        assertEquals(HttpStatusCode.OK, response.status)
        return SiloooJson.decodeFromString(WorldOrder.serializer(), response.bodyAsText())
    }

    private suspend fun delivered(response: HttpResponse): OrderDelivered {
        assertEquals(HttpStatusCode.OK, response.status, "corps : " + response.bodyAsText())
        return SiloooJson.decodeFromString(OrderDelivered.serializer(), response.bodyAsText())
    }

    private suspend fun assertError(response: HttpResponse, reason: String = ErrorCodes.BAD_REQUEST) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(reason, SiloooJson.decodeFromString(ErrorBody.serializer(), response.bodyAsText()).reason)
    }

    private suspend fun assertRefused(response: HttpResponse, reason: String): RefusedBody {
        assertEquals(HttpStatusCode.Conflict, response.status)
        val refused = SiloooJson.decodeFromString(RefusedBody.serializer(), response.bodyAsText())
        assertEquals(reason, refused.reason)
        return refused
    }

    /** Égal à un arrondi de flottant près : les deux côtés viennent des mêmes formules. */
    private fun assertClose(expected: Double, actual: Double, what: String = "valeur") {
        assertTrue(abs(expected - actual) <= 1e-9 * maxOf(1.0, abs(expected)), "$what : attendu $expected, reçu $actual")
    }

    // --- todayOrder

    @Test
    @DisplayName("todayOrder : la commande du jour vient du plan (ressource, objectif, prime), à zéro tant que personne n'a livré, et rien n'est écrit")
    fun todayOrderComesFromThePlan() = withApp { app ->
        val order = orderOf(today())

        assertEquals(app.today.toString(), order.day)
        assertEquals(app.resourceOf(app.today), order.resourceId)
        assertEquals(app.target.toDouble(), order.target)
        assertEquals(0.0, order.delivered)
        assertEquals(app.reward, order.rewardLingots)
        assertEquals(false, order.completed)
        assertEquals(0.0, order.mine)
        assertTrue(app.orders.orders.isEmpty(), "lire ne crée pas la commande")
        assertEquals(0, app.recording.statements.size)
        assertEquals(0, app.orders.published.size)
    }

    @Test
    @DisplayName("todayOrder : la même pour tout le monde au même jour, et la ressource suit l'ordre des ressources du jeu d'un jour à l'autre")
    fun todayOrderIsTheSameForEveryoneAndFollowsTheDays() = withApp { app ->
        app.company(kr)
        app.company(other)
        val first = today().bodyAsText()
        assertEquals(first, today().bodyAsText(), "deux lectures le même jour donnent la même commande")
        assertEquals(first, today(kr).bodyAsText(), "avec ou sans xuid, la commande est la même tant que personne n'a livré")
        assertEquals(first, today(other).bodyAsText())

        val resources = app.definitions.resources
        val seen = mutableListOf<String>()
        repeat(resources.size) {
            seen += orderOf(today()).resourceId
            app.fixture.clock.advance(EngineFixture.hours(24.0))
        }
        assertEquals(resources.toSet(), seen.toSet(), "toutes les ressources y passent, une par jour")
        assertEquals(resources.size, seen.distinct().size)
        assertEquals(seen.first(), orderOf(today()).resourceId, "après un tour complet, on revient à la première")
    }

    @Test
    @DisplayName("todayOrder : un xuid mal formé donne 400 REQUETE_INVALIDE ; un xuid bien formé sans livraison donne mine à zéro")
    fun todayOrderChecksTheXuid() = withApp { app ->
        assertError(get("/v1/orders/today?xuid=pas-un-xuid"))
        assertError(get("/v1/orders/today?xuid="))
        assertEquals(0.0, orderOf(today(stranger)).mine)
        assertTrue(app.orders.orders.isEmpty())
    }

    // --- deliverOrder

    @Test
    @DisplayName("deliverOrder : retire du Donar de l'entreprise au prix de economie.json, écrit une fois, compte la part du joueur et publie l'événement")
    fun deliverTakesTheCostAndCountsThePart() = withApp { app ->
        val company = app.company()
        val units = 10L
        val cost = app.costOf(company.id, units)
        assertTrue(cost > 0.0 && cost < company.donar, "le test n'a de sens que si la livraison coûte et se paie")

        val result = delivered(deliver(units))

        assertClose(company.donar - cost, result.state.donar, "Donar de l'état rendu")
        assertEquals(company.version + 1, result.state.version)
        assertEquals(units.toDouble(), result.order.delivered)
        assertEquals(units.toDouble(), result.order.mine)
        assertEquals(false, result.order.completed)
        assertEquals(app.resourceOf(app.today), result.order.resourceId)
        assertEquals(app.fixture.store.balances.getValue(kr), result.balances)

        val write = app.companyUpdates().single()
        assertEquals("UPDATE companies SET donar = ?, donar_earned = ?, last_collect_at = ?, version = version + 1 WHERE id = ? AND version = ?", write.sql)
        assertClose(company.donar - cost, write.parameters.getValue(1) as Double, "donar écrit")
        assertEquals(company.id, write.parameters.getValue(4))
        assertEquals(company.version, write.parameters.getValue(5), "le verrou optimiste porte la version lue")

        assertEquals(units.toDouble(), app.orders.contributions.getValue(app.orders.orders.getValue(app.today).id to kr))
        val event = app.orders.published.single()
        assertEquals(GameEventKind.ANNONCE, event.kind)
        assertEquals(kr, event.xuid)
        assertEquals("deliverOrder", event.payload.getValue("action").jsonPrimitive.content)
        assertEquals(app.today.toString(), event.payload.getValue("day").jsonPrimitive.content)
        assertEquals(app.resourceOf(app.today), event.payload.getValue("resourceId").jsonPrimitive.content)
        assertEquals(units.toString(), event.payload.getValue("quantity").jsonPrimitive.content)
        assertTrue(app.orders.credits.isEmpty(), "la prime n'est versée qu'à la fin de la commande")
    }

    @Test
    @DisplayName("deliverOrder : la production en attente est versée dans la même écriture que le prix, et compte pour payer")
    fun deliverPaysThePendingProductionInTheSameWrite() = withApp { app ->
        val company = app.company(donar = 0.0)
        app.fixture.clock.advance(EngineFixture.hours(3.0))
        val pending = app.fixture.engine.onlineEarnings(app.fixture.figuresOf(company.id).ratePerHour, 3.0)
        val units = 5L
        val cost = app.costOf(company.id, units)
        assertTrue(pending > cost && cost > 0.0, "le test n'a de sens que si la production en attente paie la livraison")

        val result = delivered(deliver(units))

        assertClose(pending - cost, result.state.donar, "Donar : production versée, prix retiré")
        assertClose(pending, result.state.donarEarned, "la production versée compte dans donarEarned")
        val write = app.companyUpdates().single()
        assertClose(pending - cost, write.parameters.getValue(1) as Double, "donar écrit")
        assertClose(pending, write.parameters.getValue(2) as Double, "donar_earned écrit")
    }

    @Test
    @DisplayName("deliverOrder : le même joueur livre plusieurs fois, ses parts s'additionnent et chaque livraison lit l'entreprise déjà débitée")
    fun deliverTwiceAddsUp() = withApp { app ->
        val company = app.company()
        val one = app.costOf(company.id, 7)
        val two = app.costOf(company.id, 4)

        val firstResult = delivered(deliver(7, n = 1))
        app.applyWrite(firstResult)
        val result = delivered(deliver(4, n = 2))

        assertEquals(11.0, result.order.delivered)
        assertEquals(11.0, result.order.mine)
        assertClose(company.donar - one - two, result.state.donar, "Donar après deux livraisons : la seconde part de l'entreprise déjà débitée")
        assertEquals(company.version + 2, result.state.version)
        assertEquals(2, app.companyUpdates().size)
        assertEquals(2, app.orders.published.size)
        assertEquals(11.0, orderOf(today(kr)).mine)
        assertEquals(0.0, orderOf(today(other)).mine, "la part d'un autre joueur reste à lui")
    }

    @Test
    @DisplayName("deliverOrder : une livraison qui dépasse le reste à livrer est ramenée à ce reste, ne coûte que ce reste, et termine la commande")
    fun deliverIsClampedToTheRemainder() = withApp { app ->
        val company = app.company()
        val firstResult = delivered(deliver(app.target - 3, n = 1))
        app.applyWrite(firstResult)
        val before = firstResult.state.donar
        val cost = app.costOf(company.id, 3)

        val result = delivered(deliver(app.target * 10, n = 2))

        assertEquals(app.target.toDouble(), result.order.delivered, "la commande ne dépasse pas l'objectif")
        assertEquals(true, result.order.completed)
        assertEquals(app.target.toDouble(), result.order.mine)
        assertClose(before - cost, result.state.donar, "seules les 3 unités restantes se paient")
        assertEquals(3L.toString(), app.orders.published.last { it.kind == GameEventKind.ANNONCE }.payload.getValue("quantity").jsonPrimitive.content)
    }

    @Test
    @DisplayName("deliverOrder : à la fin, chaque joueur qui a livré touche la prime une fois et reçoit COMMANDE_TERMINEE ; celui du dernier lot ne prend rien de plus")
    fun completionPaysEveryContributorOnce() = withApp { app ->
        val kr1 = app.company(kr)
        val other1 = app.company(other)
        val half = app.target / 2

        delivered(deliver(half, n = 1, actor = kr))
        assertTrue(app.orders.credits.isEmpty())
        val last = delivered(deliver(app.target, n = 2, actor = other))

        assertEquals(true, last.order.completed)
        assertEquals((app.target - half).toDouble(), last.order.mine)
        val day = app.today.toString()
        assertEquals(
            listOf(
                InMemoryOrderStore.Credit(kr, app.reward, "commande", "commande-$day"),
                InMemoryOrderStore.Credit(other, app.reward, "commande", "commande-$day"),
            ),
            app.orders.credits.sortedBy { it.xuid.number },
        )
        assertEquals(app.reward, app.lingotsOf(kr))
        assertEquals(app.reward, app.lingotsOf(other))
        assertEquals(app.reward, last.balances.lingots, "les soldes rendus sont ceux d'après la prime")

        val finished = app.orders.published.filter { it.kind == GameEventKind.COMMANDE_TERMINEE }
        assertEquals(setOf(kr, other), finished.map { it.xuid }.toSet())
        assertEquals(2, finished.size, "un événement par joueur, pas plus")
        finished.forEach {
            assertEquals(day, it.payload.getValue("day").jsonPrimitive.content)
            assertEquals(app.resourceOf(app.today), it.payload.getValue("resourceId").jsonPrimitive.content)
            assertEquals(app.reward.toString(), it.payload.getValue("rewardLingots").jsonPrimitive.content)
        }
        val writes = app.companyUpdates().associate { it.parameters.getValue(4) as Long to it.parameters.getValue(1) as Double }
        assertEquals(setOf(kr1.id, other1.id), writes.keys, "une écriture par entreprise")
        assertTrue(writes.getValue(kr1.id) < kr1.donar && writes.getValue(other1.id) < other1.donar, "chacun a payé sa part en Donar")
        assertEquals(2, app.orders.rewarded.size)
    }

    @Test
    @DisplayName("deliverOrder : sur une commande terminée, rien n'est pris ni écrit ni payé : 200 avec la commande finie et l'entreprise telle quelle")
    fun finishedOrderTakesNothing() = withApp { app ->
        app.company()
        val first = delivered(deliver(app.target, n = 1))
        app.applyWrite(first)
        val writes = app.companyUpdates().size
        val events = app.orders.published.size
        val credits = app.orders.credits.size

        val result = delivered(deliver(1, n = 2))

        assertEquals(true, result.order.completed)
        assertEquals(app.target.toDouble(), result.order.delivered, "la commande reste à l'objectif")
        assertEquals(app.target.toDouble(), result.order.mine)
        assertClose(first.state.donar, result.state.donar, "le Donar de l'entreprise n'a pas bougé")
        assertEquals(first.state.version, result.state.version)
        assertEquals(writes, app.companyUpdates().size, "aucune écriture d'entreprise : rien n'est débité")
        assertEquals(events, app.orders.published.size)
        assertEquals(credits, app.orders.credits.size)
        assertEquals(1, credits, "la prime n'a été versée qu'une fois")
        assertEquals(app.target.toDouble(), orderOf(today(kr)).mine)
    }

    @Test
    @DisplayName("deliverOrder : un jour plus tard, la commande est une autre, à zéro, et se livre à nouveau")
    fun nextDayIsANewOrder() = withApp { app ->
        app.company()
        delivered(deliver(app.target, n = 1))
        val yesterday = app.today
        app.fixture.clock.advance(EngineFixture.hours(24.0))

        val fresh = orderOf(today(kr))
        assertEquals(app.today.toString(), fresh.day)
        assertNotEquals(yesterday.toString(), fresh.day)
        assertEquals(0.0, fresh.delivered)
        assertEquals(0.0, fresh.mine)
        assertEquals(false, fresh.completed)

        val result = delivered(deliver(2, n = 2))
        assertEquals(2.0, result.order.delivered)
        assertEquals(2, app.orders.orders.size, "une ligne par jour")
    }

    @Test
    @DisplayName("deliverOrder : pas assez de Donar donne 409 PAS_ASSEZ_DE_DONAR avec la somme qui manque, et rien n'est écrit ni compté")
    fun notEnoughDonarIsRefused() = withApp { app ->
        val company = app.company(donar = 0.0)
        val units = 20L
        val cost = app.costOf(company.id, units)
        assertTrue(cost > 0.0)

        val refused = assertRefused(deliver(units), RefusalCodes.NOT_ENOUGH_DONAR)

        assertClose(cost, refused.missing!!, "missing : tout le prix, la caisse est vide")
        assertEquals(0, app.companyUpdates().size)
        assertTrue(app.orders.contributions.isEmpty())
        assertEquals(0, app.orders.published.size)
        assertEquals(0.0, orderOf(today(kr)).delivered)
    }

    @Test
    @DisplayName("deliverOrder : le manque est ce que la caisse ne couvre pas : prix moins Donar déjà là")
    fun missingIsThePriceMinusWhatIsThere() = withApp { app ->
        val company = app.company()
        val units = 20L
        val cost = app.costOf(company.id, units)
        val have = cost / 4
        app.fixture.update(app.fixture.store.loadCompany(app.fixture.connection, company.id).copy(donar = have))

        val refused = assertRefused(deliver(units), RefusalCodes.NOT_ENOUGH_DONAR)

        assertClose(cost - have, refused.missing!!, "missing")
    }

    @Test
    @DisplayName("deliverOrder rejoué avec le même requestId : la même réponse, une seule livraison, une seule écriture")
    fun deliverIsIdempotent() = withApp { app ->
        val company = app.company()

        val first = deliver(10, n = 7)
        val firstBody = first.bodyAsText()
        val second = deliver(10, n = 7)

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(firstBody, second.bodyAsText())
        assertEquals(1, app.companyUpdates().size)
        assertEquals(1, app.orders.published.size)
        assertEquals(10.0, orderOf(today(kr)).delivered, "livré une fois")
        assertClose(company.donar - app.costOf(company.id, 10), SiloooJson.decodeFromString(OrderDelivered.serializer(), firstBody).state.donar, "débité une fois")
    }

    @Test
    @DisplayName("deliverOrder : un refus est gardé comme les autres réponses, le rejeu rend le même refus")
    fun refusalIsReplayed() = withApp { app ->
        app.company(donar = 0.0)

        val first = assertRefused(deliver(5, n = 3), RefusalCodes.NOT_ENOUGH_DONAR)
        val second = assertRefused(deliver(5, n = 3), RefusalCodes.NOT_ENOUGH_DONAR)

        assertEquals(first, second)
    }

    @Test
    @DisplayName("deliverOrder : version d'entreprise dépassée : 409 CONFLIT_REESSAYER sans livraison ni événement ; le même requestId passe ensuite")
    fun deliverOnVersionConflict() = withApp { app ->
        app.company()

        app.recording.rowsTouched = 0
        assertRefused(deliver(5, n = 9), RefusalCodes.CONFLICT_RETRY)
        assertTrue(app.orders.contributions.isEmpty(), "rien n'est compté")
        assertEquals(0, app.orders.published.size)

        app.recording.rowsTouched = 1
        assertEquals(HttpStatusCode.OK, deliver(5, n = 9).status, "le conflit n'est pas gardé : le nouvel essai passe")
        assertEquals(5.0, orderOf(today(kr)).delivered)
    }

    @Test
    @DisplayName("deliverOrder : une quantité sous 1, absente ou décimale donne 400 et rien n'est écrit")
    fun badQuantityIsRefused() = withApp { app ->
        app.company()

        assertError(rawPost(body(1, kr.text, "0")))
        assertError(rawPost(body(2, kr.text, "-4")))
        assertError(rawPost(body(3, kr.text, "1.5")))
        assertError(rawPost("""{"requestId":"${requestId(5)}","actor":"${kr.text}"}"""))
        assertEquals(0, app.companyUpdates().size)
        assertTrue(app.orders.contributions.isEmpty())
        assertEquals(0, app.orders.published.size)
    }

    @Test
    @DisplayName("deliverOrder : un corps hors contrat (requestId, acteur, JSON) ou un acteur sans entreprise donne 400 et rien n'est écrit")
    fun badBodiesAndOutsidersAreRefused() = withApp { app ->
        app.company()

        assertError(rawPost("""{"requestId":"abc","actor":"${kr.text}","quantity":1}"""))
        assertError(rawPost("""{"requestId":"${requestId(1)}","actor":"pas-un-xuid","quantity":1}"""))
        assertError(rawPost("{}"))
        assertError(rawPost("pas du json"))
        assertError(deliver(1, n = 2, actor = stranger))
        assertEquals(0, app.companyUpdates().size)
        assertTrue(app.orders.contributions.isEmpty())
        assertEquals(0, app.orders.published.size)
    }

    @Test
    @DisplayName("deliverOrder : les soldes rendus sont ceux de celui qui livre, et le nombre de Lingots d'un autre joueur n'y entre pas")
    fun balancesAreTheDeliverers() = withApp { app ->
        app.company(kr)
        app.company(other)
        app.fixture.store.balances[other] = Balances(500, 0, 0, 0)

        val result = delivered(deliver(1, actor = kr))

        assertEquals(app.fixture.store.balances.getValue(kr), result.balances)
        assertEquals(0L, result.balances.lingots)
        assertEquals(500L, app.lingotsOf(other))
    }
}
