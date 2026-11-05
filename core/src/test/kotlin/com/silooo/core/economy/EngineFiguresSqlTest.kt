package com.silooo.core.economy

import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.JoinResponse
import com.silooo.core.db.MariaDbSupport
import com.silooo.core.db.Xuid
import com.silooo.core.definitions.Definitions
import com.silooo.core.definitions.DefinitionsLoader
import com.silooo.core.economy.EngineFixture.Companion.KR
import com.silooo.core.economy.EngineFixture.Companion.OTHER
import com.silooo.core.economy.EngineFixture.Companion.START
import com.silooo.core.economy.EngineFixture.Companion.hours
import com.silooo.core.session.JdbcSessionStore
import com.silooo.core.session.SessionService
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `join` avec le vrai moteur d'économie sur la vraie MariaDB : la Carrière de départ, le plafond et la case reçus de
 * `definitions/`, puis l'encaissement de l'absence (`DONAR`, `DONAR_EARNED`, `last_collect_at`, `version`) écrit par le
 * verrou optimiste. L'horloge est celle du test : on avance le temps sans attendre. Ignoré sans Docker.
 */
class EngineFiguresSqlTest {
    private val database get() = MariaDbSupport.database
    private val definitions = TestDefinitions.loaded
    private val engine = EconomyEngine(definitions)
    private val offline = definitions.economy.offline
    private val start = definitions.economy.prestige.courseStart
    private val store = JdbcSessionStore()
    private val clock = MutableClock(START)

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    private fun service(with: Definitions = definitions): SessionService {
        val withEngine = EconomyEngine(with)
        return SessionService(database, store, EngineFigures(withEngine, store, clock), DefinitionsBootstrap(withEngine, with))
    }

    private fun join(xuid: Xuid = KR, gamertag: String = "Kr"): JoinResponse = runBlocking { service().join(xuid, gamertag) }

    private fun double(sql: String, vararg params: Any?): Double = MariaDbSupport.raw { connection ->
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows ->
                check(rows.next()) { "aucune ligne pour $sql" }
                rows.getDouble(1)
            }
        }
    }

    private fun lastCollectAt(companyId: Long): Instant = MariaDbSupport.raw { connection ->
        connection.prepareStatement("SELECT last_collect_at FROM companies WHERE id = ?").use { statement ->
            statement.setLong(1, companyId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getObject(1, LocalDateTime::class.java).toInstant(ZoneOffset.UTC)
            }
        }
    }

    /** Fixe le dernier calcul de production de l'entreprise à l'heure de l'horloge : le point de départ de l'absence. */
    private fun collectedNow(companyId: Long) {
        MariaDbSupport.update("UPDATE companies SET last_collect_at = ? WHERE id = ?", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), companyId)
    }

    private fun donar(companyId: Long) = double("SELECT donar FROM companies WHERE id = ?", companyId)

    private fun donarEarned(companyId: Long) = double("SELECT donar_earned FROM companies WHERE id = ?", companyId)

    private fun version(companyId: Long) = MariaDbSupport.long("SELECT version FROM companies WHERE id = ?", companyId)

    private fun startingRate(multiplier: Double = engine.globalMultiplier(0.0, 0)) = engine.ratePerHour(engine.startingLevels(), multiplier)

    /** Le Donar d'une absence de [away] heures pour un plafond de [cap] heures, au revenu [rate]. */
    private fun expectedEarnings(rate: Double, away: Double, cap: Double) = engine.offlineEarnings(rate, away, cap)

    @Test
    @DisplayName("Le premier join crée la Carrière au niveau 1, le plafond hors ligne des définitions et la case de monde.json")
    fun firstJoinTakesEverythingFromDefinitions() {
        // Un plafond qui n'est pas le défaut de la colonne (8,0) : la colonne ne peut venir que des définitions.
        val changedCap = offline.capHours + 3.5
        val root = Json.parseToJsonElement(TestDefinitions.text(DefinitionsLoader.ECONOMY_FILE)).jsonObject
        val changedOffline = JsonObject(root.getValue("horsLigne").jsonObject + ("plafondHeures" to JsonPrimitive(changedCap)))
        val changed = DefinitionsLoader.parse(
            JsonObject(root + ("horsLigne" to changedOffline)).toString(),
            TestDefinitions.text(DefinitionsLoader.FACTORIES_FILE),
            TestDefinitions.text(DefinitionsLoader.SHOP_FILE),
            TestDefinitions.text(DefinitionsLoader.WORLD_FILE),
            TestDefinitions.text(DefinitionsLoader.MISSIONS_FILE),
            TestDefinitions.text(DefinitionsLoader.PERSONNEL_FILE),
        )

        val response = runBlocking { service(changed).join(KR, "Kr") }

        val factory = response.state.factories.single()
        assertEquals("${start.factoryId}|ACTIVE|${start.level}", MariaDbSupport.string("SELECT CONCAT(factory_id, '|', state, '|', level) FROM company_factories"))
        assertEquals(start.factoryId, factory.factoryId)
        assertEquals(start.level, factory.level)
        assertEquals(changedCap, double("SELECT offline_cap_hours FROM companies WHERE id = ?", response.companyId))

        val grid = definitions.world.archipelago
        assertEquals(
            "${grid.origin.x}|${grid.origin.z}",
            MariaDbSupport.string("SELECT CONCAT(origin_x, '|', origin_z) FROM archipelago_slots WHERE slot_index = 0"),
        )
    }

    @Test
    @DisplayName("Les cases suivantes suivent la grille de monde.json : colonnes puis rangées, écart respecté")
    fun slotsFollowTheGrid() {
        val grid = definitions.world.archipelago
        val players = (1..grid.slotsPerRow + 1).map { Xuid.parse("25354280000001%02d".format(it))!! }
        players.forEachIndexed { index, xuid -> join(xuid, "J$index") }

        val rows = MariaDbSupport.strings("SELECT CONCAT(origin_x, '|', origin_z) FROM archipelago_slots ORDER BY slot_index")
        val expected = players.indices.map { index ->
            "${grid.origin.x + (index % grid.slotsPerRow) * grid.slotSpacing}|${grid.origin.z + (index / grid.slotsPerRow) * grid.slotSpacing}"
        }
        assertEquals(expected, rows)
    }

    @Test
    @DisplayName("join après une absence : offlineCollected, Donar, Donar gagnés, last_collect_at et version en base")
    fun joinAfterAbsencePaysAndWrites() {
        val first = join()
        val id = first.companyId
        assertEquals(0.0, first.offlineCollected)
        collectedNow(id)
        val away = offline.capHours / 2
        clock.advance(hours(away))
        val earned = expectedEarnings(startingRate(), away, offline.capHours)
        assertTrue(earned > 0.0)

        val second = join()

        assertTrue(!second.firstJoin)
        assertGolden(earned, second.offlineCollected, "offlineCollected")
        assertGolden(earned, second.state.donar, "donar de la réponse")
        assertGolden(earned, second.state.donarEarned, "donar gagnés de la réponse")
        assertGolden(earned, donar(id), "donar en base")
        assertGolden(earned, donarEarned(id), "donar_earned en base")
        assertEquals(clock.instant(), lastCollectAt(id))
        assertEquals(1L, version(id))
        assertEquals(1, second.state.version)

        // Rejoué à la même heure : plus rien à encaisser, ni écriture ni nouvelle version.
        val replay = join()
        assertEquals(0.0, replay.offlineCollected)
        assertGolden(earned, donar(id), "donar après le rejeu")
        assertEquals(1L, version(id))

        // Une heure de plus : on ajoute au Donar déjà encaissé.
        clock.advance(hours(1.0))
        val more = expectedEarnings(startingRate(), 1.0, offline.capHours)
        val third = join()
        assertGolden(more, third.offlineCollected, "deuxième encaissement")
        assertGolden(earned + more, donar(id), "donar cumulé")
        assertGolden(earned + more, donarEarned(id), "donar gagnés cumulés")
        assertEquals(2L, version(id))
    }

    @Test
    @DisplayName("Au-delà du plafond, le gain s'arrête au plafond et le surplus est perdu : last_collect_at repart de maintenant")
    fun capIsRespectedInTheDatabase() {
        val id = join().companyId
        collectedNow(id)
        clock.advance(hours(offline.capHours + 10))

        val response = join()

        assertGolden(expectedEarnings(startingRate(), offline.capHours, offline.capHours), response.offlineCollected, "gain au plafond")
        assertGolden(startingRate() * offline.capHours * offline.efficiency, donar(id), "donar en base")
        assertEquals(clock.instant(), lastCollectAt(id))
    }

    @Test
    @DisplayName("Le plafond de la colonne offline_cap_hours de l'entreprise l'emporte sur celui des définitions")
    fun columnCapWins() {
        val id = join().companyId
        val cap = 2.5
        MariaDbSupport.update("UPDATE companies SET offline_cap_hours = ? WHERE id = ?", cap, id)
        collectedNow(id)
        clock.advance(hours(cap + offline.capHours))

        val response = join()

        assertGolden(startingRate() * cap * offline.efficiency, response.offlineCollected, "gain au plafond de la colonne")
    }

    @Test
    @DisplayName("Les Lingots des membres lus en base font le multiplicateur de join et celui du gain hors ligne")
    fun memberLingotsFeedTheMultiplier() {
        val id = join().companyId
        MariaDbSupport.insertPlayer(OTHER.text, "Associe")
        MariaDbSupport.update("INSERT INTO player_balances (xuid, lingots) VALUES (?, ?)", OTHER.number, 8)
        MariaDbSupport.update("INSERT INTO company_members (company_id, xuid, role, share_percent) VALUES (?, ?, 'ASSOCIE', 0)", id, OTHER.number)
        MariaDbSupport.update("UPDATE player_balances SET lingots = ? WHERE xuid = ?", 4, KR.number)
        val multiplier = engine.globalMultiplier(6.0, 0)
        collectedNow(id)

        val sameInstant = join()
        assertEquals(multiplier, sameInstant.state.multiplier)
        assertEquals(startingRate(multiplier), sameInstant.state.ratePerHour)
        assertEquals(0.0, sameInstant.offlineCollected)

        val away = offline.capHours / 2
        clock.advance(hours(away))
        val response = join()
        assertGolden(expectedEarnings(startingRate(multiplier), away, offline.capHours), response.offlineCollected, "gain avec les Lingots des membres")
    }

    @Test
    @DisplayName("Deux associés qui reviennent en même temps ne touchent l'absence qu'une fois")
    fun twoPartnersDoNotCollectTwice() {
        val id = join().companyId
        MariaDbSupport.insertPlayer(OTHER.text, "Associe")
        MariaDbSupport.update("INSERT INTO player_balances (xuid, lingots) VALUES (?, ?)", OTHER.number, 0)
        MariaDbSupport.update("INSERT INTO company_members (company_id, xuid, role, share_percent) VALUES (?, ?, 'ASSOCIE', 0)", id, OTHER.number)
        collectedNow(id)
        val away = offline.capHours / 2
        clock.advance(hours(away))
        val earned = expectedEarnings(startingRate(), away, offline.capHours)

        val responses = runBlocking {
            coroutineScope {
                val go = CompletableDeferred<Unit>()
                val jobs = listOf(KR, OTHER).map { xuid -> async(Dispatchers.IO) { go.await(); service().join(xuid, "J") } }
                go.complete(Unit)
                jobs.awaitAll()
            }
        }

        assertGolden(earned, responses.sumOf { it.offlineCollected }, "Donar encaissé par les deux réponses")
        assertGolden(earned, donar(id), "donar en base")
        assertGolden(earned, donarEarned(id), "donar gagnés en base")
        assertEquals(1L, version(id))
    }
}
