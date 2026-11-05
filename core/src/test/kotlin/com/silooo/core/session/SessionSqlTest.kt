package com.silooo.core.session

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.api.JoinResponse
import com.silooo.core.api.Role
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.Currency
import com.silooo.core.db.MariaDbSupport
import com.silooo.core.db.Xuid
import com.silooo.core.db.creditBalance
import com.silooo.core.siloooModule
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `join` et `leave` sur la vraie MariaDB : le SQL de [JdbcSessionStore], les verrous, la concurrence, l'atomicité.
 * Ignoré sans Docker (voir [MariaDbSupport]). Les usines de départ sont celles d'un [TestBootstrap] fictif.
 */
class SessionSqlTest {
    private val database get() = MariaDbSupport.database
    private val krText = "2535428000000001"
    private val kr = Xuid.parse(krText)!!
    private val economy = TestEconomy()

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    private fun service(figures: EconomyFigures = economy) = SessionService(database, JdbcSessionStore(), figures, TestBootstrap)

    private suspend fun join(xuid: Xuid = kr, gamertag: String = "Kr", figures: EconomyFigures = economy): JoinResponse =
        service(figures).join(xuid, gamertag)

    private val tables = listOf("players", "player_balances", "companies", "company_members", "company_factories", "archipelago_slots")

    private fun counts(): List<Long> = tables.map { MariaDbSupport.count(it) }

    private fun xuidOf(n: Int): Xuid = Xuid.parse("25354280000000${"%02d".format(n)}")!!

    private suspend fun <T> inParallel(n: Int, block: suspend (Int) -> T): List<T> = coroutineScope {
        val go = CompletableDeferred<Unit>()
        val jobs = (0 until n).map { index -> async(Dispatchers.IO) { go.await(); block(index) } }
        go.complete(Unit)
        jobs.awaitAll()
    }

    // --- le premier passage

    @Test
    @DisplayName("Premier join : le joueur, ses soldes à zéro, l'entreprise, sa case, le PDG à 100 % et l'usine de départ")
    fun firstJoinCreatesEverything() = runBlocking {
        val response = join()

        assertTrue(response.firstJoin)
        assertEquals(Role.PDG, response.role)
        assertEquals(krText, response.xuid)
        assertEquals("Entreprise de Kr", response.state.name)
        assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 1L), counts())

        assertEquals("Kr", MariaDbSupport.string("SELECT gamertag FROM players WHERE xuid = ?", kr.number))
        assertEquals(UUID(0L, kr.number.toLong()).toString(), MariaDbSupport.string("SELECT CAST(floodgate_uuid AS CHAR) FROM players"))
        assertEquals(
            listOf(0L, 0L, 0L, 0L),
            MariaDbSupport.longs("SELECT lingots FROM player_balances UNION ALL SELECT gemmes FROM player_balances UNION ALL SELECT jetons FROM player_balances UNION ALL SELECT cles FROM player_balances"),
        )
        assertEquals("PDG|100", MariaDbSupport.string("SELECT CONCAT(role, '|', share_percent) FROM company_members WHERE xuid = ?", kr.number))
        assertEquals(response.companyId, MariaDbSupport.long("SELECT company_id FROM company_members WHERE xuid = ?", kr.number))
        assertEquals("usine-de-test|ACTIVE|1", MariaDbSupport.string("SELECT CONCAT(factory_id, '|', state, '|', level) FROM company_factories"))
        assertTrue(MariaDbSupport.long("SELECT COUNT(*) FROM archipelago_slots WHERE allocated_at IS NOT NULL") == 1L)
        assertEquals(
            MariaDbSupport.long("SELECT slot_index FROM companies WHERE id = ?", response.companyId),
            MariaDbSupport.long("SELECT slot_index FROM archipelago_slots"),
        )
        assertTrue(economy.settled.isEmpty(), "rien à encaisser à la création")
    }

    @Test
    @DisplayName("join rejoué : rien n'est créé en plus, et l'état rendu est le même")
    fun joinTwiceIsIdempotent() = runBlocking {
        val first = join()
        val countsAfterFirst = counts()

        val second = join()
        val third = join()

        assertEquals(countsAfterFirst, counts())
        assertFalse(second.firstJoin)
        assertEquals(first.copy(firstJoin = false), second)
        assertEquals(second, third)
    }

    @Test
    @DisplayName("Un joueur déjà dans players mais sans entreprise reçoit la sienne, et c'est un premier passage")
    fun existingPlayerWithoutCompany() = runBlocking {
        MariaDbSupport.insertPlayer(krText, "Ancien")
        MariaDbSupport.update("UPDATE players SET tutorial_step = 2 WHERE xuid = ?", kr.number)

        val response = join(gamertag = "Nouveau")

        assertTrue(response.firstJoin)
        assertEquals(2, response.tutorialStep)
        assertEquals(1L, MariaDbSupport.count("companies"))
        assertEquals("Nouveau", MariaDbSupport.string("SELECT gamertag FROM players"))
        assertEquals(1L, MariaDbSupport.count("player_balances"), "la ligne de soldes manquait : elle est créée")
        assertTrue(economy.settled.isEmpty())
    }

    // --- l'heure de départ

    @Test
    @DisplayName("leave fige last_seen_at ; join le remet à maintenant et donne l'ancienne heure au moteur hors ligne")
    fun lastSeenAtIsFrozenByLeaveAndHandedToTheEngine() = runBlocking {
        val first = join()
        MariaDbSupport.update("UPDATE players SET last_seen_at = '2026-11-01 10:00:00.000' WHERE xuid = ?", kr.number)

        service().leave(kr)
        assertTrue(secondsSinceLastSeen() in 0..60, "leave pose l'heure de maintenant")

        MariaDbSupport.update("UPDATE players SET last_seen_at = '2026-11-01 10:00:00.000' WHERE xuid = ?", kr.number)
        val back = join()

        assertEquals(listOf(first.companyId to Instant.parse("2026-11-01T10:00:00Z")), economy.settled)
        assertEquals(first.copy(firstJoin = false), back)
        assertTrue(secondsSinceLastSeen() in 0..60, "join remet l'heure à maintenant")
    }

    private fun secondsSinceLastSeen() =
        MariaDbSupport.long("SELECT TIMESTAMPDIFF(SECOND, last_seen_at, UTC_TIMESTAMP(3)) FROM players WHERE xuid = ?", kr.number)

    @Test
    @DisplayName("leave d'un joueur inconnu ne crée rien et ne fait pas d'erreur ; rejoué, il laisse tout en place")
    fun leaveIsHarmless() = runBlocking {
        service().leave(kr)
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L), counts())

        join()
        val before = counts()
        service().leave(kr)
        service().leave(kr)
        assertEquals(before, counts())
    }

    // --- l'entreprise

    @Test
    @DisplayName("Deux gamertags égaux à la casse près : le nom d'entreprise, unique, prend un numéro")
    fun companyNamesAreUnique() = runBlocking {
        val names = listOf("Kr", "KR", "kr").mapIndexed { index, gamertag -> join(xuidOf(index + 1), gamertag).state.name }
        assertEquals(listOf("Entreprise de Kr", "Entreprise de KR 2", "Entreprise de kr 3"), names)
    }

    @Test
    @DisplayName("Une case d'archipel libre est réutilisée avant d'en créer une neuve")
    fun freeSlotIsReused() = runBlocking {
        MariaDbSupport.update("INSERT INTO archipelago_slots (slot_index, origin_x, origin_z, allocated_at) VALUES (3, 300, 0, NULL), (4, 400, 0, UTC_TIMESTAMP(3))")

        val response = join()

        assertEquals(3L, MariaDbSupport.long("SELECT slot_index FROM companies WHERE id = ?", response.companyId))
        assertEquals(2L, MariaDbSupport.count("archipelago_slots"), "aucune case neuve")
        assertEquals(0L, MariaDbSupport.long("SELECT COUNT(*) FROM archipelago_slots WHERE allocated_at IS NULL"))
    }

    @Test
    @DisplayName("Sans case libre, la case neuve suit la dernière et prend ses coordonnées du CompanyBootstrap")
    fun newSlotFollowsTheLast() = runBlocking {
        MariaDbSupport.update("INSERT INTO archipelago_slots (slot_index, origin_x, origin_z, allocated_at) VALUES (7, 70, 0, UTC_TIMESTAMP(3))")

        val response = join()

        assertEquals(8L, MariaDbSupport.long("SELECT slot_index FROM companies WHERE id = ?", response.companyId))
        assertEquals("8|0", MariaDbSupport.string("SELECT CONCAT(origin_x, '|', origin_z) FROM archipelago_slots WHERE slot_index = 8"))
    }

    @Test
    @DisplayName("L'état rendu relit l'entreprise : version, zones, incident ouvert, et pas l'incident résolu")
    fun stateIsReadFromTheTables() = runBlocking {
        val first = join()
        val id = first.companyId
        MariaDbSupport.update("INSERT INTO city_zones (company_id, zone_index, bought_by, lingots_paid) VALUES (?, 3, ?, 10), (?, 1, ?, 10)", id, kr.number, id, kr.number)
        MariaDbSupport.update("INSERT INTO factory_incidents (company_id, factory_id, kind, slowdown, resolved_at) VALUES (?, 'usine-de-test', 'panne', 0.50, UTC_TIMESTAMP(3))", id)
        MariaDbSupport.update("INSERT INTO factory_incidents (company_id, factory_id, kind, slowdown) VALUES (?, 'usine-de-test', 'incendie', 0.50)", id)
        MariaDbSupport.update("UPDATE companies SET version = 7, stars = 4, donar = 1234.5 WHERE id = ?", id)

        val again = join().state

        assertEquals(listOf(1, 3), again.zonesOwned)
        assertEquals(7, again.version)
        assertEquals(4, again.stars)
        assertEquals(1234.5, again.donar)
        val incident = MariaDbSupport.long("SELECT id FROM factory_incidents WHERE resolved_at IS NULL")
        assertEquals(incident, again.factories.single().incidentId)
    }

    // --- le profil du joueur

    @Test
    @DisplayName("Les soldes rendus sont ceux de player_balances, après les mouvements du journal")
    fun balancesComeFromTheTable() = runBlocking {
        join()
        database.transaction {
            creditBalance(it, kr, Currency.LINGOTS, 50, "mission")
            creditBalance(it, kr, Currency.GEMMES, 7, "livraison")
        }

        val balances = join().balances

        assertEquals(listOf(50L, 7L, 0L, 0L), listOf(balances.lingots, balances.gemmes, balances.jetons, balances.cles))
    }

    @Test
    @DisplayName("Le grade le plus récent et un Pilier en cours sont rendus ; un Pilier échu ne l'est pas")
    fun rankAndPilier() = runBlocking {
        join()
        MariaDbSupport.update("INSERT INTO player_ranks (xuid, rank_id, granted_at, source) VALUES (?, 'vip', '2026-01-01 00:00:00.000', 'achat'), (?, 'heros', '2026-03-01 00:00:00.000', 'achat')", kr.number, kr.number)
        MariaDbSupport.update("INSERT INTO player_subscriptions (xuid, subscription_id, started_at, ends_at) VALUES (?, 'pilier', '2026-10-01 00:00:00.000', '2999-12-31 10:00:00.250')", kr.number)

        val withPilier = join()
        assertEquals("heros", withPilier.rank)
        assertEquals("2999-12-31T10:00:00.250Z", withPilier.pilierUntil)

        MariaDbSupport.update("UPDATE player_subscriptions SET ends_at = '2020-01-01 00:00:00.000' WHERE xuid = ?", kr.number)
        assertNull(join().pilierUntil, "abonnement échu")
    }

    @Test
    @DisplayName("Gamertag avec accents, ou de 32 caractères hors du plan de base : enregistré tel quel")
    fun unicodeGamertags() = runBlocking {
        val accents = join(xuidOf(1), "Pâtissière")
        assertEquals("Entreprise de Pâtissière", accents.state.name)

        val emoji = "😀".repeat(32)
        join(xuidOf(2), emoji)
        assertEquals(emoji, MariaDbSupport.string("SELECT gamertag FROM players WHERE xuid = ?", xuidOf(2).number))
    }

    // --- l'atomicité et la concurrence

    @Test
    @DisplayName("Si la fin de join échoue, rien n'est créé : ni joueur, ni entreprise, ni case")
    fun failedJoinLeavesNothing() = runBlocking {
        val failing = object : EconomyFigures by TestEconomy() {
            override fun forCompany(connection: Connection, company: CompanyRecord, factories: List<FactoryRecord>): CompanyFigures = error("moteur en panne")
        }

        assertThrows<IllegalStateException> { runBlocking { join(figures = failing) } }

        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L), counts())
    }

    @Test
    @DisplayName("Huit join du même joueur en même temps : une seule entreprise, un seul premier passage")
    fun concurrentJoinsOfOnePlayer() = runBlocking {
        val responses = inParallel(8) { join() }

        assertEquals(1, responses.count { it.firstJoin }, "un seul appel a créé l'entreprise")
        assertEquals(1, responses.map { it.companyId }.toSet().size)
        assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 1L), counts())
    }

    @Test
    @DisplayName("Six joueurs qui arrivent en même temps : six entreprises, six cases distinctes, six noms distincts")
    fun concurrentJoinsOfDifferentPlayers() = runBlocking {
        val responses = inParallel(6) { index -> join(xuidOf(index + 1), "Kr") }

        assertTrue(responses.all { it.firstJoin })
        assertEquals(6, responses.map { it.companyId }.toSet().size)
        assertEquals(6, responses.map { it.state.name.lowercase() }.toSet().size)
        assertEquals(listOf(6L, 6L, 6L, 6L, 6L, 6L), counts())
        assertEquals(
            (0L..5L).toList(),
            MariaDbSupport.longs("SELECT slot_index FROM companies ORDER BY slot_index"),
            "chaque entreprise a sa case, sans trou ni doublon",
        )
    }

    // --- de bout en bout, par HTTP

    @Test
    @DisplayName("Par HTTP sur la vraie base : join, join rejoué, leave")
    fun overHttp() = testApplication {
        application {
            siloooModule(
                Dependencies(database = database,definitions = TestDefinitions.loaded, apiKey = "cle-de-test", economyFigures = economy, companyBootstrap = TestBootstrap),
            )
        }
        suspend fun post(path: String, body: String) = client.post(path) {
            header(SERVER_KEY_HEADER, "cle-de-test")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        val first = post("/v1/session/join", """{"xuid":"$krText","gamertag":"Kr"}""")
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val firstBody = SiloooJson.decodeFromString(JoinResponse.serializer(), first.bodyAsText())
        assertTrue(firstBody.firstJoin)

        val second = SiloooJson.decodeFromString(JoinResponse.serializer(), post("/v1/session/join", """{"xuid":"$krText","gamertag":"Kr"}""").bodyAsText())
        assertEquals(firstBody.copy(firstJoin = false), second)

        val leave = post("/v1/session/leave", """{"xuid":"$krText"}""")
        assertEquals(HttpStatusCode.NoContent, leave.status)
        assertEquals("", leave.bodyAsText())
        assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 1L), counts())
    }
}
