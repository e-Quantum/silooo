package com.silooo.core.pass

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.Balances
import com.silooo.core.api.Idempotency
import com.silooo.core.api.PassClaimed
import com.silooo.core.api.PassState
import com.silooo.core.api.Refusal
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.RefusedBody
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.Currency
import com.silooo.core.db.MariaDbSupport
import com.silooo.core.db.Xuid
import com.silooo.core.db.creditBalance
import com.silooo.core.db.isTransientConflict
import com.silooo.core.economy.EngineFixture.Companion.KR
import com.silooo.core.economy.EngineFixture.Companion.OTHER
import com.silooo.core.session.JdbcSessionStore
import com.silooo.core.shop.JdbcShopStore
import com.silooo.core.shop.ShopRules
import com.silooo.core.shop.ShopService
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
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le pass de saison sur la vraie MariaDB : `JdbcPassStore` ligne à ligne (`season_pass`, `season_claims`, `player_cosmetics`, journal des
 * Lingots), puis [PassService] et la boutique dans de vraies transactions, avec une vraie concurrence, et enfin `getPass`, `claimPass`
 * et `buyItem` par HTTP avec la vraie idempotence. Ce que les tests de route (`PassRoutesTest`, `PassPointsTest`) ne peuvent pas voir :
 * ce qui arrive vraiment dans les tables, la transaction qui part en entier ou pas du tout, et l'ordre des verrous. Ignoré sans Docker
 * (voir [MariaDbSupport]) : la CI les rejoue.
 *
 * Les chiffres attendus (points, Lingots, prix) sortent de `pass.json` et de `definitions/`, jamais d'un littéral recopié.
 */
class PassSqlTest {
    private val database get() = MariaDbSupport.database
    private val definitions = TestDefinitions.loaded
    private val pass = definitions.pass
    private val seasonId = pass.season.id
    private val price = pass.premium.gems
    private val sessions = JdbcSessionStore()
    private val jdbc = JdbcPassStore()
    private val clock = MutableClock(pass.season.startDay.atTime(12, 0).toInstant(ZoneOffset.UTC))
    private val service = PassService(definitions, jdbc, sessions, clock)
    private val key = "cle-de-test"

    private val full = pass.tiers.first { it.free != null && it.premium != null }
    private val fullLingots = checkNotNull(full.free).lingots
    private val fullCosmetic = checkNotNull(full.premium).cosmeticId

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    // --- outils

    private fun bigint(xuid: Xuid) = BigInteger(xuid.text)

    /** Un joueur connu du service : sa ligne `players` et son solde à zéro. */
    private fun player(xuid: Xuid, gamertag: String) {
        MariaDbSupport.insertPlayer(xuid.text, gamertag)
        MariaDbSupport.update("INSERT IGNORE INTO player_balances (xuid) VALUES (?)", bigint(xuid))
    }

    private fun lingots(xuid: Xuid): Long = MariaDbSupport.long("SELECT lingots FROM player_balances WHERE xuid = ?", xuid.text)

    private fun gemmes(xuid: Xuid): Long = MariaDbSupport.long("SELECT gemmes FROM player_balances WHERE xuid = ?", xuid.text)

    /** Donne [amount] Gemmes à [xuid] par le vrai journal (motif `test`). */
    private fun giveGemmes(xuid: Xuid, amount: Long) {
        MariaDbSupport.raw { creditBalance(it, xuid, Currency.GEMMES, amount, "test", "depart") }
    }

    /** Les lignes de `season_pass` : `joueur|saison|points|piste premium (0 ou 1)`. */
    private fun passRows(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(xuid, '|', season_id, '|', points, '|', premium) FROM season_pass ORDER BY xuid, season_id",
    )

    /** Les lignes de `season_claims` : `joueur|saison|palier|piste`. */
    private fun claimRows(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(xuid, '|', season_id, '|', tier, '|', track) FROM season_claims ORDER BY xuid, season_id, tier, track",
    )

    /** Les lignes de `player_cosmetics` : `joueur|cosmétique|source`. */
    private fun cosmeticRows(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(xuid, '|', cosmetic_id, '|', source) FROM player_cosmetics ORDER BY xuid, cosmetic_id",
    )

    /** Les mouvements de Lingots du pass : `joueur|variation|solde après|motif|référence`. */
    private fun passLedger(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(xuid, '|', delta, '|', balance, '|', reason, '|', IFNULL(ref_id, '-')) FROM currency_ledger " +
            "WHERE currency = 'LINGOTS' AND reason = 'pass' ORDER BY id",
    )

    private fun requestId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    private fun refusalOf(block: () -> Unit): Refusal = assertThrows(Refusal::class.java) { block() }

    private fun claim(xuid: Xuid, tier: Int, track: PassTrack): PassClaimed =
        runBlocking { database.transaction { service.claim(it, xuid, tier, track) } }

    private fun <T> inParallel(count: Int, block: suspend (Int) -> T): List<Result<T>> = runBlocking {
        (0 until count).map { index -> async(Dispatchers.IO) { runCatching { block(index) } } }.awaitAll()
    }

    // --- JdbcPassStore : les lignes

    @Test
    @DisplayName("load ne crée rien ; lock crée la ligne à zéro une seule fois ; un joueur inconnu n'a pas de ligne")
    fun loadAndLock() {
        player(KR, "Kr")

        assertEquals(PassProgress.NOTHING, MariaDbSupport.raw { jdbc.load(it, KR, seasonId) })
        assertEquals(0L, MariaDbSupport.count("season_pass"))

        assertEquals(PassProgress.NOTHING, MariaDbSupport.raw { jdbc.lock(it, KR, seasonId) })
        MariaDbSupport.raw { jdbc.lock(it, KR, seasonId) }
        assertEquals(listOf("${KR.text}|$seasonId|0|0"), passRows())

        assertThrows(IllegalArgumentException::class.java) { MariaDbSupport.raw { jdbc.lock(it, OTHER, seasonId) } }
        assertEquals(1L, MariaDbSupport.count("season_pass"))
        assertTrue(MariaDbSupport.raw { jdbc.isKnownPlayer(it, KR) })
        assertFalse(MariaDbSupport.raw { jdbc.isKnownPlayer(it, OTHER) })
    }

    @Test
    @DisplayName("addPoints crée la ligne si elle manque, additionne sans rien perdre, ne fait rien pour zéro et refuse un nombre négatif")
    fun addPointsAccumulates() {
        player(KR, "Kr")

        MariaDbSupport.raw { jdbc.addPoints(it, KR, seasonId, 30) }
        MariaDbSupport.raw { jdbc.addPoints(it, KR, seasonId, 12) }
        MariaDbSupport.raw { jdbc.addPoints(it, KR, seasonId, 0) }

        assertEquals(listOf("${KR.text}|$seasonId|42|0"), passRows())
        assertThrows(IllegalArgumentException::class.java) { MariaDbSupport.raw { jdbc.addPoints(it, KR, seasonId, -1) } }
        assertEquals(42, MariaDbSupport.raw { jdbc.load(it, KR, seasonId) }.points)
    }

    @Test
    @DisplayName("Chaque saison a ses lignes : les points d'une autre saison ne comptent pas dans celle-ci")
    fun seasonsAreSeparate() {
        player(KR, "Kr")
        MariaDbSupport.raw { jdbc.addPoints(it, KR, "saison-0", 500) }
        MariaDbSupport.raw { jdbc.addPoints(it, KR, seasonId, 7) }

        assertEquals(7, MariaDbSupport.raw { jdbc.load(it, KR, seasonId) }.points)
        assertEquals(500, MariaDbSupport.raw { jdbc.load(it, KR, "saison-0") }.points)
    }

    @Test
    @DisplayName("grantPremium : faux sans ligne, vrai la première fois, faux ensuite ; la piste est lue par load")
    fun grantPremiumOnce() {
        player(KR, "Kr")

        assertFalse(MariaDbSupport.raw { jdbc.grantPremium(it, KR, seasonId) })
        MariaDbSupport.raw { jdbc.lock(it, KR, seasonId) }
        assertTrue(MariaDbSupport.raw { jdbc.grantPremium(it, KR, seasonId) })
        assertFalse(MariaDbSupport.raw { jdbc.grantPremium(it, KR, seasonId) })

        assertEquals(listOf("${KR.text}|$seasonId|0|1"), passRows())
        assertTrue(MariaDbSupport.raw { jdbc.load(it, KR, seasonId) }.premium)
    }

    @Test
    @DisplayName("recordClaim : un palier ne se note qu'une fois par piste ; les deux pistes d'un palier sont deux lignes ; load les relit")
    fun recordClaimOnce() {
        player(KR, "Kr")
        MariaDbSupport.raw { jdbc.lock(it, KR, seasonId) }

        assertTrue(MariaDbSupport.raw { jdbc.recordClaim(it, KR, seasonId, PassClaim(full.tier, PassTrack.FREE)) })
        assertFalse(MariaDbSupport.raw { jdbc.recordClaim(it, KR, seasonId, PassClaim(full.tier, PassTrack.FREE)) })
        assertTrue(MariaDbSupport.raw { jdbc.recordClaim(it, KR, seasonId, PassClaim(full.tier, PassTrack.PREMIUM)) })

        assertEquals(listOf("${KR.text}|$seasonId|${full.tier}|GRATUIT", "${KR.text}|$seasonId|${full.tier}|PREMIUM"), claimRows())
        assertEquals(
            setOf(PassClaim(full.tier, PassTrack.FREE), PassClaim(full.tier, PassTrack.PREMIUM)),
            MariaDbSupport.raw { jdbc.load(it, KR, seasonId) }.claims,
        )
    }

    @Test
    @DisplayName("grantCosmetic : un cosmétique ne se donne qu'une fois (la source du premier reste), avec la source pass")
    fun grantCosmeticOnce() {
        player(KR, "Kr")
        MariaDbSupport.update("INSERT INTO player_cosmetics (xuid, cosmetic_id, source) VALUES (?, ?, 'boutique')", bigint(KR), "deja-la")

        assertTrue(MariaDbSupport.raw { jdbc.grantCosmetic(it, KR, fullCosmetic) })
        assertFalse(MariaDbSupport.raw { jdbc.grantCosmetic(it, KR, fullCosmetic) })
        assertFalse(MariaDbSupport.raw { jdbc.grantCosmetic(it, KR, "deja-la") })

        assertEquals(listOf("${KR.text}|deja-la|boutique", "${KR.text}|$fullCosmetic|pass").sorted(), cosmeticRows().filterNotNull().sorted())
    }

    @Test
    @DisplayName("creditLingots verse les Lingots avec une ligne de journal (motif pass) ; lockBalances ne change rien et n'écrit pas de journal")
    fun lingotsGoThroughTheLedger() {
        player(KR, "Kr")

        MariaDbSupport.raw { jdbc.lockBalances(it, KR) }
        assertEquals(0L, MariaDbSupport.count("currency_ledger"))

        val after = MariaDbSupport.raw { jdbc.creditLingots(it, KR, 5, PassService.REASON, "$seasonId:1:GRATUIT") }

        assertEquals(5L, after)
        assertEquals(5L, lingots(KR))
        assertEquals(listOf("${KR.text}|5|5|pass|$seasonId:1:GRATUIT"), passLedger())
    }

    // --- PassService dans de vraies transactions

    @Test
    @DisplayName("claim, piste gratuite : le palier est noté, les Lingots versés avec leur ligne de journal, le tout dans la même transaction")
    fun freeClaimWritesEverything() {
        player(KR, "Kr")
        MariaDbSupport.raw { jdbc.addPoints(it, KR, seasonId, full.points) }

        val claimed = claim(KR, full.tier, PassTrack.FREE)

        assertEquals(PassClaimed(seasonId, full.tier, "GRATUIT", fullLingots, null, Balances(fullLingots, 0, 0, 0)), claimed)
        assertEquals(listOf("${KR.text}|$seasonId|${full.tier}|GRATUIT"), claimRows())
        assertEquals(listOf("${KR.text}|$fullLingots|$fullLingots|pass|$seasonId:${full.tier}:GRATUIT"), passLedger())
        assertEquals(fullLingots, lingots(KR))
        assertEquals(listOf("${KR.text}|$seasonId|${full.points}|0"), passRows(), "réclamer ne dépense pas les points")
    }

    @Test
    @DisplayName("claim, piste premium : le cosmétique est donné (source pass), aucun Lingot, aucune ligne de journal")
    fun premiumClaimGivesTheCosmetic() {
        player(KR, "Kr")
        MariaDbSupport.raw { jdbc.addPoints(it, KR, seasonId, full.points) }
        MariaDbSupport.raw { jdbc.lock(it, KR, seasonId) }
        MariaDbSupport.raw { jdbc.grantPremium(it, KR, seasonId) }

        val claimed = claim(KR, full.tier, PassTrack.PREMIUM)

        assertEquals(PassClaimed(seasonId, full.tier, "PREMIUM", 0L, fullCosmetic, Balances(0, 0, 0, 0)), claimed)
        assertEquals(listOf("${KR.text}|$fullCosmetic|pass"), cosmeticRows())
        assertEquals(listOf("${KR.text}|$seasonId|${full.tier}|PREMIUM"), claimRows())
        assertEquals(0L, MariaDbSupport.count("currency_ledger"))
    }

    @Test
    @DisplayName("claim refusé : la transaction est annulée en entier, y compris la ligne de pass que le verrou venait de créer")
    fun aRefusedClaimLeavesNothing() {
        player(KR, "Kr")

        val refusal = refusalOf { claim(KR, full.tier, PassTrack.FREE) }

        assertEquals(RefusalCodes.TIER_NOT_REACHED, refusal.reason)
        assertEquals(full.points.toDouble(), refusal.missing)
        assertEquals(0L, MariaDbSupport.count("season_pass"))
        assertEquals(0L, MariaDbSupport.count("season_claims"))
        assertEquals(0L, MariaDbSupport.count("currency_ledger"))
    }

    @Test
    @DisplayName("claim : un palier déjà réclamé est refusé (DEJA_RECLAME) et ne verse rien de plus")
    fun alreadyClaimedIsRefused() {
        player(KR, "Kr")
        MariaDbSupport.raw { jdbc.addPoints(it, KR, seasonId, full.points) }
        claim(KR, full.tier, PassTrack.FREE)

        val refusal = refusalOf { claim(KR, full.tier, PassTrack.FREE) }

        assertEquals(RefusalCodes.ALREADY_CLAIMED, refusal.reason)
        assertEquals(1, passLedger().size)
        assertEquals(fullLingots, lingots(KR))
    }

    @Test
    @DisplayName("Plusieurs réclamations du même palier en même temps : une seule réussit, les autres sont refusées, Lingots versés une fois")
    fun concurrentClaimsOfTheSameTierPayOnce() {
        player(KR, "Kr")
        MariaDbSupport.raw { jdbc.addPoints(it, KR, seasonId, full.points) }

        val results = inParallel(6) { claim(KR, full.tier, PassTrack.FREE) }

        assertTrue(results.none { it.exceptionOrNull()?.isTransientConflict() == true }, "les verrous se prennent dans le même ordre : aucun conflit")
        assertEquals(1, results.count { it.isSuccess })
        results.filter { it.isFailure }.forEach {
            assertEquals(RefusalCodes.ALREADY_CLAIMED, (it.exceptionOrNull() as Refusal).reason)
        }
        assertEquals(1, claimRows().size)
        assertEquals(1, passLedger().size)
        assertEquals(fullLingots, lingots(KR))
    }

    @Test
    @DisplayName("Des réclamations et des actions qui rapportent des points, pour le même joueur, en même temps : aucun conflit de verrou, aucun point perdu")
    fun claimsAndPointsDoNotDeadlock() {
        player(KR, "Kr")
        val tiers = pass.tiers.take(4)
        MariaDbSupport.raw { jdbc.addPoints(it, KR, seasonId, tiers.last().points) }
        val perMission = pass.points.perMission

        val results = inParallel(8) { index ->
            if (index % 2 == 0) {
                claim(KR, tiers[index / 2].tier, PassTrack.FREE)
            } else {
                // Comme une mission terminée : les Lingots d'abord, puis les points.
                runBlocking {
                    database.transaction {
                        jdbc.creditLingots(it, KR, 1, "mission", "mission-$index")
                        service.onMissionCompleted(it, KR)
                    }
                }
            }
        }

        results.filter { it.isFailure }.forEach { throw checkNotNull(it.exceptionOrNull()) }
        val rewards = tiers.sumOf { checkNotNull(it.free).lingots }
        assertEquals(tiers.size, claimRows().size)
        assertEquals(rewards + 4, lingots(KR))
        assertEquals(tiers.last().points + 4 * perMission, MariaDbSupport.raw { jdbc.load(it, KR, seasonId) }.points)
    }

    @Test
    @DisplayName("Des points ajoutés en même temps pour un joueur qui n'a pas encore de ligne : tous comptent")
    fun concurrentPointsAreAllCounted() {
        player(KR, "Kr")
        val perOrder = pass.points.perOrder

        val results = inParallel(8) { runBlocking { database.transaction { service.onOrderCompleted(it, KR) } } }

        results.filter { it.isFailure }.forEach { throw checkNotNull(it.exceptionOrNull()) }
        assertEquals(8 * perOrder, MariaDbSupport.raw { jdbc.load(it, KR, seasonId) }.points)
        assertEquals(1L, MariaDbSupport.count("season_pass"))
    }

    @Test
    @DisplayName("Hors saison, aucune ligne de pass n'est écrite par les actions qui rapportent des points")
    fun nothingIsWrittenOutsideTheSeason() {
        player(KR, "Kr")
        val after = PassService(definitions, jdbc, sessions, MutableClock(pass.season.endDay.plusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC)))

        runBlocking {
            database.transaction {
                after.onPrestige(it, KR)
                after.onOrderCompleted(it, KR)
                after.onMissionCompleted(it, KR)
            }
        }

        assertEquals(0L, MariaDbSupport.count("season_pass"))
    }

    // --- la boutique

    private fun shop(): ShopService = ShopService(ShopRules(definitions), JdbcShopStore(), sessions, clock, service)

    private fun buy(actor: Xuid, n: Int = 1, forXuid: Xuid? = null): Balances =
        runBlocking { database.transaction { shop().buy(it, actor, requestId(n), PassRules.PREMIUM_ITEM_ID, forXuid) } }

    @Test
    @DisplayName("buy pass-premium : le prix est dépensé, l'achat noté dans shop_purchases, la piste ouverte, dans la même transaction")
    fun buyingThePremiumTrack() {
        player(KR, "Kr")
        giveGemmes(KR, price + 5)

        val balances = buy(KR)

        assertEquals(5L, balances.gemmes)
        assertEquals(5L, gemmes(KR))
        assertEquals(listOf("${KR.text}|$seasonId|0|1"), passRows())
        assertEquals(
            listOf("${KR.text}|pass-premium|$price|-|${requestId(1)}"),
            MariaDbSupport.strings("SELECT CONCAT(xuid, '|', item_id, '|', gemmes, '|', IFNULL(CAST(for_xuid AS CHAR), '-'), '|', request_id) FROM shop_purchases"),
        )
    }

    @Test
    @DisplayName("buy pass-premium en cadeau : l'acheteur paie, la piste du destinataire s'ouvre, pas celle de l'acheteur")
    fun giftingThePremiumTrack() {
        player(KR, "Kr")
        player(OTHER, "Ami")
        giveGemmes(KR, price)

        buy(KR, forXuid = OTHER)

        assertEquals(0L, gemmes(KR))
        assertEquals(listOf("${OTHER.text}|$seasonId|0|1"), passRows())
    }

    @Test
    @DisplayName("buy pass-premium refusé (hors saison, déjà possédée, pas assez de Gemmes) : rien n'est payé ni écrit")
    fun refusedPurchasesLeaveNothing() {
        player(KR, "Kr")
        giveGemmes(KR, price - 1)
        assertEquals(RefusalCodes.NOT_ENOUGH_GEMS, refusalOf { buy(KR, n = 1) }.reason)
        assertEquals(price - 1, gemmes(KR))

        giveGemmes(KR, price)
        buy(KR, n = 2)
        giveGemmes(KR, price)
        val owned = gemmes(KR)
        assertEquals(RefusalCodes.ALREADY_OWNED, refusalOf { buy(KR, n = 3) }.reason)
        assertEquals(owned, gemmes(KR))
        assertEquals(1L, MariaDbSupport.count("shop_purchases"))

        val late = ShopService(
            ShopRules(definitions),
            JdbcShopStore(),
            sessions,
            MutableClock(Instant.parse("2000-01-01T00:00:00Z")),
            PassService(definitions, jdbc, sessions, MutableClock(Instant.parse("2000-01-01T00:00:00Z"))),
        )
        player(OTHER, "Ami")
        giveGemmes(OTHER, price)
        val refusal = refusalOf { runBlocking { database.transaction { late.buy(it, OTHER, requestId(4), PassRules.PREMIUM_ITEM_ID, null) } } }
        assertEquals(RefusalCodes.SEASON_INACTIVE, refusal.reason)
        assertEquals(price, gemmes(OTHER))
    }

    @Test
    @DisplayName("Plusieurs achats de la piste premium en même temps : un seul passe, les Gemmes ne sont dépensées qu'une fois")
    fun concurrentPurchasesChargeOnce() {
        player(KR, "Kr")
        giveGemmes(KR, 3 * price)

        val results = inParallel(4) { index -> buy(KR, n = index + 1) }

        assertTrue(results.none { it.exceptionOrNull()?.isTransientConflict() == true }, "aucun conflit de verrou")
        assertEquals(1, results.count { it.isSuccess })
        results.filter { it.isFailure }.forEach { assertEquals(RefusalCodes.ALREADY_OWNED, (it.exceptionOrNull() as Refusal).reason) }
        assertEquals(2 * price, gemmes(KR))
        assertEquals(1L, MariaDbSupport.count("shop_purchases"))
        assertEquals(listOf("${KR.text}|$seasonId|0|1"), passRows())
    }

    // --- HTTP : getPass, claimPass, buyItem avec la vraie idempotence

    private fun dependencies(): Dependencies = Dependencies(
        database = database,
        definitions = definitions,
        apiKey = key,
        idempotency = Idempotency(database),
        sessionStore = sessions,
        clock = clock,
        shopStore = JdbcShopStore(),
        pass = service,
    )

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { siloooModule(dependencies()) }
        block()
    }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String): HttpResponse =
        client.post(path) {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    @DisplayName("Par HTTP : acheter la piste, réclamer les deux pistes d'un palier, rejouer une réclamation sans rien verser de plus, relire le pass")
    fun fullFlowOverHttp() = withApp {
        player(KR, "Kr")
        giveGemmes(KR, price)
        MariaDbSupport.raw { jdbc.addPoints(it, KR, seasonId, full.points) }

        val bought = post("/v1/shop/purchases", """{"requestId":"${requestId(1)}","actor":"${KR.text}","itemId":"pass-premium"}""")
        assertEquals(HttpStatusCode.OK, bought.status, bought.bodyAsText())

        val body = """{"requestId":"${requestId(2)}","actor":"${KR.text}","tier":${full.tier},"track":"GRATUIT"}"""
        val first = post("/v1/pass/claims", body)
        val replay = post("/v1/pass/claims", body)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertEquals(first.bodyAsText(), replay.bodyAsText())
        assertEquals(1, passLedger().size, "le rejeu ne verse rien")

        val premium = post("/v1/pass/claims", """{"requestId":"${requestId(3)}","actor":"${KR.text}","tier":${full.tier},"track":"PREMIUM"}""")
        assertEquals(HttpStatusCode.OK, premium.status, premium.bodyAsText())
        assertEquals(fullCosmetic, SiloooJson.decodeFromString(PassClaimed.serializer(), premium.bodyAsText()).cosmeticId)

        val again = post("/v1/pass/claims", """{"requestId":"${requestId(4)}","actor":"${KR.text}","tier":${full.tier},"track":"GRATUIT"}""")
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals(RefusalCodes.ALREADY_CLAIMED, SiloooJson.decodeFromString(RefusedBody.serializer(), again.bodyAsText()).reason)

        val state = client.get("/v1/pass?actor=${KR.text}") { header(SERVER_KEY_HEADER, key) }
        assertEquals(HttpStatusCode.OK, state.status, state.bodyAsText())
        val decoded = SiloooJson.decodeFromString(PassState.serializer(), state.bodyAsText())
        assertEquals(full.points, decoded.points)
        assertEquals(true, decoded.premium)
        assertEquals(listOf("${full.tier}-GRATUIT", "${full.tier}-PREMIUM"), decoded.claimed)
        assertEquals(fullLingots, lingots(KR))
    }

    @Test
    @DisplayName("Par HTTP : une réclamation sous le seuil répond 409 PALIER_NON_ATTEINT et ne laisse aucune trace ; un joueur inconnu répond 400")
    fun refusalsOverHttp() = withApp {
        player(KR, "Kr")

        val short = post("/v1/pass/claims", """{"requestId":"${requestId(1)}","actor":"${KR.text}","tier":${full.tier},"track":"GRATUIT"}""")
        assertEquals(HttpStatusCode.Conflict, short.status, short.bodyAsText())
        val refused = SiloooJson.decodeFromString(RefusedBody.serializer(), short.bodyAsText())
        assertEquals(RefusalCodes.TIER_NOT_REACHED, refused.reason)
        assertEquals(full.points.toDouble(), refused.missing)
        assertEquals(0L, MariaDbSupport.count("season_claims"))

        val unknown = post("/v1/pass/claims", """{"requestId":"${requestId(2)}","actor":"${OTHER.text}","tier":${full.tier},"track":"GRATUIT"}""")
        assertEquals(HttpStatusCode.BadRequest, unknown.status, unknown.bodyAsText())
        assertEquals(0L, MariaDbSupport.count("season_pass"))
    }
}
