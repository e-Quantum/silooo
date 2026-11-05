package com.silooo.core.shop

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.Balances
import com.silooo.core.api.Idempotency
import com.silooo.core.api.JoinResponse
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.NeverUsedApiRequestStore
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.db.RecordingConnection
import com.silooo.core.db.Xuid
import com.silooo.core.economy.EngineFixture
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.InMemorySessionStore
import com.silooo.core.session.TestBootstrap
import com.silooo.core.session.TestEconomy
import com.silooo.core.shop.InMemoryShopStore.Movement
import com.silooo.core.shop.InMemoryShopStore.Published
import com.silooo.core.siloooModule
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.sql.Connection
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le relevé des livraisons de Gemmes ([DeliveryRelief]) avec des faux (pas de base) : à qui revient une livraison, ce qui reste en
 * attente, le versement unique, le rattrapage à `join` et la tâche de fond du module. Que le versement tienne en une seule
 * transaction (la ligne marquée, les Gemmes, l'événement : tout ou rien) ne se joue pas ici, le faux n'annule rien : c'est dans
 * `ShopSqlTest`.
 */
class DeliveryReliefTest {
    private val kr = EngineFixture.KR
    private val friend = EngineFixture.OTHER
    private val stranger = EngineFixture.STRANGER
    private val noon = EngineFixture.START

    private class Harness {
        val clock = MutableClock(EngineFixture.START)
        val sessions = InMemorySessionStore(EngineFixture.START)
        val shop = InMemoryShopStore(sessions)
        val database = PassThroughDatabase()
        val relief = DeliveryRelief(database, shop, clock)

        /** Inscrit [xuid] avec [gemmes] Gemmes. */
        fun player(xuid: Xuid, gamertag: String, gemmes: Long = 0L) {
            sessions.enterPlayer(RecordingConnection().connection, xuid, gamertag)
            sessions.balances[xuid] = Balances(0, gemmes, 0, 0)
        }

        fun poll(): Int = runBlocking { relief.poll() }

        fun gemmes(xuid: Xuid): Long = shop.gemmes(xuid)
    }

    private fun harness(block: Harness.() -> Unit) = Harness().block()

    // --- le relevé périodique

    @Test
    @DisplayName("Une livraison au XUID d'un joueur connu : ses Gemmes montent, la ligne est marquée, un mouvement `livraison` et un événement GEMMES_RECUES")
    fun deliveryByXuid() = harness {
        player(kr, "Kr", gemmes = 10)
        shop.seedDelivery("commande-1", "Pseudo saisi autrement", gemmes = 50, xuid = kr)

        assertEquals(1, poll())

        assertEquals(60L, gemmes(kr))
        assertEquals(noon, shop.deliveries.getValue("commande-1").deliveredAt)
        assertEquals(listOf(Movement(kr, 50, "livraison", "commande-1")), shop.movements)
        assertEquals(
            listOf(
                Published(
                    GameEventKind.GEMMES_RECUES,
                    kr,
                    buildJsonObject {
                        put("orderId", "commande-1")
                        put("gemmes", 50L)
                        put("gemmesTotal", 60L)
                    },
                ),
            ),
            shop.published,
        )
    }

    @Test
    @DisplayName("Une livraison sans XUID : le joueur est trouvé par son pseudo, sans tenir compte de la casse, et la ligne note son XUID")
    fun deliveryByGamertag() = harness {
        player(kr, "Kr", gemmes = 0)
        shop.seedDelivery("commande-1", "kR", gemmes = 275)

        assertEquals(1, poll())

        assertEquals(275L, gemmes(kr))
        assertEquals(kr, shop.deliveries.getValue("commande-1").xuid)
    }

    @Test
    @DisplayName("Relevée deux fois, une livraison n'est versée qu'une fois")
    fun polledTwiceIsCreditedOnce() = harness {
        player(kr, "Kr", gemmes = 10)
        shop.seedDelivery("commande-1", "Kr", gemmes = 50)

        assertEquals(1, poll())
        assertEquals(0, poll())
        assertEquals(0, poll())

        assertEquals(60L, gemmes(kr))
        assertEquals(1, shop.movements.size)
        assertEquals(1, shop.published.size)
    }

    @Test
    @DisplayName("Deux passages qui lisent la même livraison en même temps : le second ne touche aucune ligne et ne verse rien")
    fun twoPassesReadingTheSameRow() = harness {
        player(kr, "Kr", gemmes = 10)
        shop.seedDelivery("commande-1", "Kr", gemmes = 50)
        val stale = shop.pendingDeliveries(RecordingConnection().connection, 10)
        assertEquals(1, stale.size)
        val slow = DeliveryRelief(database, StaleList(shop, stale), clock)

        assertEquals(1, poll())
        assertEquals(0, runBlocking { slow.poll() }, "la ligne était déjà versée : le UPDATE ... WHERE delivered_at IS NULL ne touche rien")

        assertEquals(60L, gemmes(kr))
        assertEquals(1, shop.movements.size)
        assertEquals(1, shop.published.size)
    }

    @Test
    @DisplayName("Un joueur inconnu : la livraison reste en attente, intacte, et rien n'est versé ni publié")
    fun unknownPlayerStaysPending() = harness {
        player(kr, "Kr", gemmes = 10)
        shop.seedDelivery("commande-1", "Fantôme", gemmes = 50)

        assertEquals(0, poll())
        assertEquals(0, poll())

        val row = shop.deliveries.getValue("commande-1")
        assertNull(row.deliveredAt)
        assertNull(row.xuid)
        assertEquals(10L, gemmes(kr))
        assertTrue(shop.movements.isEmpty())
        assertTrue(shop.published.isEmpty())
    }

    @Test
    @DisplayName("Une livraison en attente part dès que le joueur devient connu")
    fun pendingLeavesOnceTheGamertagIsKnown() = harness {
        shop.seedDelivery("commande-1", "Kr", gemmes = 50)
        assertEquals(0, poll())

        player(kr, "Kr", gemmes = 0)

        assertEquals(1, poll())
        assertEquals(50L, gemmes(kr))
    }

    @Test
    @DisplayName("Un XUID rempli et connu l'emporte sur le pseudo, même si le pseudo est celui d'un autre joueur")
    fun xuidWinsOverGamertag() = harness {
        player(kr, "Kr")
        player(friend, "Ami")
        shop.seedDelivery("commande-1", "Kr", gemmes = 50, xuid = friend)

        assertEquals(1, poll())

        assertEquals(50L, gemmes(friend))
        assertEquals(0L, gemmes(kr))
    }

    @Test
    @DisplayName("Un XUID rempli mais inconnu : on retombe sur le pseudo")
    fun unknownXuidFallsBackToTheGamertag() = harness {
        player(kr, "Kr")
        shop.seedDelivery("commande-1", "Kr", gemmes = 50, xuid = stranger)

        assertEquals(1, poll())

        assertEquals(50L, gemmes(kr))
        assertEquals(kr, shop.deliveries.getValue("commande-1").xuid, "la ligne note le joueur qui a reçu")
    }

    @Test
    @DisplayName("Deux joueurs de même pseudo (la casse seule les distingue) : celui vu en dernier reçoit")
    fun lastSeenWinsAmongSameGamertag() = harness {
        player(kr, "Kr")
        player(friend, "KR")
        sessions.players.getValue(kr).lastSeenAt = noon.minusSeconds(3600)
        sessions.players.getValue(friend).lastSeenAt = noon

        shop.seedDelivery("commande-1", "kr", gemmes = 50)
        assertEquals(1, poll())

        assertEquals(50L, gemmes(friend))
        assertEquals(0L, gemmes(kr))
    }

    @Test
    @DisplayName("Des livraisons dont personne n'est connu ne bouchent pas le relevé : plus nombreuses qu'un passage, elles laissent passer la suivante")
    fun unknownRowsDoNotBlockTheOthers() = harness {
        player(kr, "Kr")
        repeat(DeliveryRelief.BATCH + 50) { shop.seedDelivery("inconnue-$it", "Fantôme $it", gemmes = 1, createdAt = noon.minusSeconds(86_400L - it)) }
        shop.seedDelivery("la-bonne", "Kr", gemmes = 50, createdAt = noon)

        assertEquals(1, poll())

        assertEquals(50L, gemmes(kr))
        assertEquals(DeliveryRelief.BATCH + 50, shop.deliveries.values.count { it.deliveredAt == null })
    }

    @Test
    @DisplayName("Un passage verse les plus anciennes d'abord, BATCH au plus, et le suivant reprend là où il en est resté")
    fun oldestFirstInBatches() = harness {
        player(kr, "Kr")
        val total = DeliveryRelief.BATCH + 7
        repeat(total) { shop.seedDelivery("commande-%03d".format(it), "Kr", gemmes = 1, createdAt = noon.minusSeconds(1_000L - it)) }

        assertEquals(DeliveryRelief.BATCH, poll())
        assertEquals(DeliveryRelief.BATCH.toLong(), gemmes(kr))
        assertTrue(shop.deliveries.getValue("commande-000").deliveredAt != null, "la plus ancienne est partie la première")
        assertNull(shop.deliveries.getValue("commande-%03d".format(total - 1)).deliveredAt)

        assertEquals(7, poll())
        assertEquals(total.toLong(), gemmes(kr))
    }

    @Test
    @DisplayName("Une livraison qui échoue ne retarde pas les autres : le passage continue et la retente au suivant")
    fun aFailureDoesNotStopThePass() = harness {
        player(kr, "Kr")
        shop.seedDelivery("cassee", "Kr", gemmes = 5, createdAt = noon.minusSeconds(60))
        shop.seedDelivery("saine", "Kr", gemmes = 50, createdAt = noon)
        val breaking = DeliveryRelief(database, FailingCredit(shop, "cassee"), clock)

        assertEquals(1, runBlocking { breaking.poll() })

        assertTrue(shop.movements.any { it.refId == "saine" })
    }

    // --- le rattrapage à join

    @Test
    @DisplayName("relieve (join) : verse ce qui attend ce joueur, par XUID et par pseudo, et laisse le reste")
    fun reliefAtJoin() = harness {
        player(kr, "Kr", gemmes = 0)
        player(friend, "Ami", gemmes = 0)
        shop.seedDelivery("par-xuid", "Autre pseudo", gemmes = 10, xuid = kr)
        shop.seedDelivery("par-pseudo", "kr", gemmes = 20)
        shop.seedDelivery("a-l-ami", "Kr", gemmes = 40, xuid = friend)
        shop.seedDelivery("autre", "Quelqu'un", gemmes = 80)

        database.let { runBlocking { it.transaction { connection -> relief.relieve(connection, kr, "Kr") } } }

        assertEquals(30L, gemmes(kr))
        assertEquals(0L, gemmes(friend))
        assertEquals(setOf("par-xuid", "par-pseudo"), shop.deliveries.values.filter { it.deliveredAt != null }.map { it.orderId }.toSet())
        assertEquals(listOf(kr, kr), shop.published.map { it.xuid })
    }

    @Test
    @DisplayName("relieve : la livraison d'un pseudo qu'un joueur plus récent porte aussi ne revient pas à celui qui arrive")
    fun reliefLeavesTheLatestPlayersDelivery() = harness {
        player(kr, "Kr")
        player(friend, "KR")
        sessions.players.getValue(kr).lastSeenAt = noon.minusSeconds(3600)
        sessions.players.getValue(friend).lastSeenAt = noon
        shop.seedDelivery("commande-1", "kr", gemmes = 50)

        database.let { runBlocking { it.transaction { connection -> relief.relieve(connection, kr, "Kr") } } }

        assertEquals(0L, gemmes(kr))
        assertNull(shop.deliveries.getValue("commande-1").deliveredAt)
        assertEquals(1, poll())
        assertEquals(50L, gemmes(friend))
    }

    @Test
    @DisplayName("join : un joueur inconnu qui avait une commande en attente voit ses Gemmes dans la réponse, une fois ; un second join ne verse rien")
    fun joinCatchesUp() {
        val app = Harness()
        app.shop.seedDelivery("par-pseudo", "Kr", gemmes = 275)
        app.shop.seedDelivery("par-xuid", "Autre", gemmes = 50, xuid = kr)
        testApplication {
            application { siloooModule(app.dependencies()) }

            val first = join()
            assertEquals(325L, first.balances.gemmes)
            assertTrue(app.shop.deliveries.values.all { it.deliveredAt != null })
            assertEquals(setOf("par-pseudo", "par-xuid"), app.shop.movements.map { it.refId }.toSet())
            assertEquals(setOf(GameEventKind.GEMMES_RECUES), app.shop.published.map { it.kind }.toSet())
            assertEquals(listOf(kr, kr), app.shop.published.map { it.xuid })

            val second = join()
            assertEquals(325L, second.balances.gemmes)
            assertEquals(2, app.shop.movements.size)
        }
    }

    @Test
    @DisplayName("join sans relevé branché (Dependencies.deliveries nul) : les livraisons attendent, rien n'est versé")
    fun joinWithoutReliefLeavesDeliveries() {
        val app = Harness()
        app.shop.seedDelivery("par-pseudo", "Kr", gemmes = 275)
        testApplication {
            application { siloooModule(app.dependencies(deliveries = null)) }

            assertEquals(0L, join().balances.gemmes)
            assertNull(app.shop.deliveries.getValue("par-pseudo").deliveredAt)
        }
    }

    // --- la tâche de fond

    @Test
    @DisplayName("Le module lance le relevé quand Dependencies.deliveries et deliveryPoll sont posés : une commande déjà là part tout de suite, une nouvelle au passage suivant")
    fun theModuleRunsThePoll() {
        val app = Harness()
        app.player(kr, "Kr", gemmes = 0)
        app.shop.seedDelivery("avant", "Kr", gemmes = 50)
        testApplication {
            application { siloooModule(app.dependencies(poll = Duration.ofMillis(20))) }
            startApplication()
            waitUntil { app.shop.deliveries.getValue("avant").deliveredAt != null }

            app.shop.seedDelivery("apres", "Kr", gemmes = 275)
            waitUntil { app.shop.deliveries.getValue("apres").deliveredAt != null }
        }
        assertEquals(325L, app.gemmes(kr))
        assertEquals(2, app.shop.movements.size, "chaque commande n'est versée qu'une fois, malgré les passages qui se répètent")
    }

    // --- outils

    private fun Harness.dependencies(deliveries: DeliveryRelief? = relief, poll: Duration? = null) = Dependencies(
        database = database,
        definitions = TestDefinitions.loaded,
        apiKey = KEY,
        // join n'a pas de requestId : si l'idempotence servait, ce magasin le dirait.
        idempotency = Idempotency(database, NeverUsedApiRequestStore),
        economyFigures = TestEconomy(),
        companyBootstrap = TestBootstrap,
        sessionStore = sessions,
        clock = clock,
        shopStore = shop,
        deliveries = deliveries,
        deliveryPoll = poll,
    )

    private suspend fun ApplicationTestBuilder.join(): JoinResponse {
        val response = client.post("/v1/session/join") {
            header(SERVER_KEY_HEADER, KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"xuid":"${kr.text}","gamertag":"Kr"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return SiloooJson.decodeFromString(JoinResponse.serializer(), response.bodyAsText())
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (!condition() && System.nanoTime() < deadline) delay(20)
        assertTrue(condition(), "le relevé n'a pas versé dans les temps")
    }

    /** Un dépôt dont `pendingDeliveries` rend toujours [list] : un passage qui a lu la liste avant qu'un autre ne verse. */
    private class StaleList(private val inner: ShopStore, private val list: List<PendingDelivery>) : ShopStore by inner {
        override fun pendingDeliveries(connection: Connection, limit: Int): List<PendingDelivery> = list
    }

    /** Un dépôt dont le versement de la commande [orderId] plante : une panne de base sur une seule ligne. */
    private class FailingCredit(private val inner: ShopStore, private val orderId: String) : ShopStore by inner {
        override fun creditGemmes(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long {
            if (refId == orderId) throw IllegalStateException("panne de test")
            return inner.creditGemmes(connection, xuid, amount, reason, refId)
        }
    }

    private companion object {
        const val KEY = "cle-de-test"
    }
}
