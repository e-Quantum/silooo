package com.silooo.core.shop

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.Balances
import com.silooo.core.api.CatalogItem
import com.silooo.core.api.Idempotency
import com.silooo.core.api.JoinResponse
import com.silooo.core.api.Refusal
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.RefusedBody
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.Currency
import com.silooo.core.db.InsufficientBalance
import com.silooo.core.db.MariaDbSupport
import com.silooo.core.db.Xuid
import com.silooo.core.db.creditBalance
import com.silooo.core.db.isTransientConflict
import com.silooo.core.economy.DefinitionsBootstrap
import com.silooo.core.economy.EconomyEngine
import com.silooo.core.economy.EngineFigures
import com.silooo.core.economy.EngineFixture.Companion.KR
import com.silooo.core.economy.EngineFixture.Companion.OTHER
import com.silooo.core.economy.EngineFixture.Companion.START
import com.silooo.core.economy.EngineFixture.Companion.STRANGER
import com.silooo.core.economy.RequestRejected
import com.silooo.core.events.GameEventKind
import com.silooo.core.registration.SiteRegistrations
import com.silooo.core.server.ServerPresence
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
import java.math.BigInteger
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La boutique sur la vraie MariaDB : `JdbcShopStore` ligne à ligne (grades, abonnements, cosmétiques, boosts collectifs, pionniers, achats, Gemmes et journal, livraisons), le
 * relevé des livraisons ([DeliveryRelief]) avec le vrai dépôt, puis `buyItem` et `catalog` par HTTP avec le vrai moteur et la vraie
 * idempotence. Ce que les tests de route (`ShopRoutesTest`, `DeliveryReliefTest`) ne peuvent pas voir : ce qui arrive vraiment dans
 * `shop_deliveries`, `shop_purchases`, `player_ranks`, `player_subscriptions`, `player_cosmetics`, `server_boosts`, `player_balances`, `currency_ledger` et `game_events`,
 * en une seule transaction qui part en entier ou pas du tout, et ce que donne une vraie concurrence. Ignoré sans Docker (voir
 * [MariaDbSupport]) : la CI les rejoue.
 *
 * Les chiffres attendus (prix, durées, Gemmes rendues) sortent de [ShopRules] et de `definitions/`, jamais d'un littéral recopié.
 */
class ShopSqlTest {
    private val database get() = MariaDbSupport.database
    private val definitions = TestDefinitions.loaded
    private val rules = ShopRules(definitions)
    private val engine = EconomyEngine(definitions)
    private val sessions = JdbcSessionStore()
    private val jdbc = JdbcShopStore()
    private val clock = MutableClock(START)
    private val presence = ServerPresence(clock = clock)
    private val key = "cle-de-test"

    private val first = rules.grades.first()
    private val second = rules.grades[1]
    private val top = rules.grades.last()
    private val pilier = rules.subscriptions.single { it.id == ShopRules.PILIER_ID }
    private val perso = rules.subscriptions.single { it.requiredGradeId != null }
    private val cosmetic = rules.cosmetics.first()
    private val otherCosmetic = rules.cosmetics[1]
    private val boost = rules.boost
    private val boostLength = Duration.ofMinutes(boost.minutes)

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    // --- outils

    private fun bigint(xuid: Xuid) = BigInteger(xuid.text)

    private val stampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

    /** Une date comme MariaDB l'écrit en texte (`CAST(... AS CHAR)` d'un `DATETIME(3)`). */
    private fun stamp(instant: Instant): String = stampFormat.format(instant)

    /** Un joueur connu du service : sa ligne `players`, et son solde à zéro sauf si [withBalance] est faux. */
    private fun player(xuid: Xuid, gamertag: String, lastSeen: LocalDateTime? = null, withBalance: Boolean = true) {
        MariaDbSupport.insertPlayer(xuid.text, gamertag)
        if (withBalance) MariaDbSupport.update("INSERT IGNORE INTO player_balances (xuid) VALUES (?)", bigint(xuid))
        if (lastSeen != null) MariaDbSupport.update("UPDATE players SET last_seen_at = ? WHERE xuid = ?", lastSeen, bigint(xuid))
    }

    private fun gemmes(xuid: Xuid): Long = MariaDbSupport.long("SELECT COALESCE(SUM(gemmes), 0) FROM player_balances WHERE xuid = ?", xuid.text)

    /** Donne [amount] Gemmes à [xuid] par le vrai journal (motif `test`, que les relevés de la boutique ne comptent pas). */
    private fun give(xuid: Xuid, amount: Long) {
        MariaDbSupport.raw { creditBalance(it, xuid, Currency.GEMMES, amount, "test", "depart") }
    }

    /** Les mouvements de Gemmes de la boutique : `xuid|variation|solde après|motif|référence`. */
    private fun ledger(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(xuid, '|', delta, '|', balance, '|', reason, '|', IFNULL(ref_id, '-')) FROM currency_ledger " +
            "WHERE currency = 'GEMMES' AND reason IN ('boutique', 'pilier', 'livraison') ORDER BY id",
    )

    /** Une commande du site, pas versée, avec le XUID seulement s'il est rempli. */
    private fun addDelivery(
        orderId: String,
        gamertag: String,
        gemmes: Long,
        xuid: Xuid? = null,
        createdAt: LocalDateTime = LocalDateTime.of(2026, 11, 5, 11, 0),
    ) {
        if (xuid == null) {
            MariaDbSupport.update(
                "INSERT INTO shop_deliveries (order_id, gamertag, gemmes, created_at) VALUES (?, ?, ?, ?)",
                orderId, gamertag, gemmes, createdAt,
            )
        } else {
            MariaDbSupport.update(
                "INSERT INTO shop_deliveries (order_id, gamertag, xuid, gemmes, created_at) VALUES (?, ?, ?, ?, ?)",
                orderId, gamertag, bigint(xuid), gemmes, createdAt,
            )
        }
    }

    /** Les commandes : `commande|XUID rempli (ou -)|attente ou versee`. */
    private fun deliveryRows(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(order_id, '|', IFNULL(CAST(xuid AS CHAR), '-'), '|', IF(delivered_at IS NULL, 'attente', 'versee')) " +
            "FROM shop_deliveries ORDER BY order_id",
    )

    /** Les événements `GEMMES_RECUES` : `xuid|type|commande|Gemmes|total`. */
    private fun events(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(IFNULL(CAST(xuid AS CHAR), '-'), '|', kind, '|', JSON_VALUE(payload, '\$.orderId'), '|', " +
            "JSON_VALUE(payload, '\$.gemmes'), '|', JSON_VALUE(payload, '\$.gemmesTotal')) FROM game_events WHERE kind = 'GEMMES_RECUES' ORDER BY id",
    )

    /** Les achats : `acheteur|article|Gemmes|destinataire d'un cadeau (ou -)|requestId`. */
    private fun purchaseRows(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(xuid, '|', item_id, '|', gemmes, '|', IFNULL(CAST(for_xuid AS CHAR), '-'), '|', request_id) FROM shop_purchases ORDER BY id",
    )

    /** Les grades : `joueur|grade|source`. */
    private fun rankRows(): List<String?> = MariaDbSupport.strings("SELECT CONCAT(xuid, '|', rank_id, '|', source) FROM player_ranks ORDER BY xuid, rank_id")

    /** Les abonnements : `joueur|abonnement|début|fin|mois`. */
    private fun subscriptionRows(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(xuid, '|', subscription_id, '|', CAST(started_at AS CHAR), '|', CAST(ends_at AS CHAR), '|', months_total) " +
            "FROM player_subscriptions ORDER BY xuid, subscription_id",
    )

    /** Les cosmétiques : `joueur|cosmétique|source|obtenu le`. */
    private fun cosmeticRows(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(xuid, '|', cosmetic_id, '|', source, '|', CAST(acquired_at AS CHAR)) FROM player_cosmetics " +
            "ORDER BY xuid, acquired_at, cosmetic_id",
    )

    /** Les boosts collectifs, dans l'ordre où ils ont été écrits : `acheteur|début|fin|pourcents`. */
    private fun boostRows(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(bought_by, '|', CAST(starts_at AS CHAR), '|', CAST(ends_at AS CHAR), '|', bonus_percent) FROM server_boosts ORDER BY id",
    )

    /** Les fenêtres des boosts, du plus tôt au plus tard : `début|fin`. */
    private fun boostWindows(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(CAST(starts_at AS CHAR), '|', CAST(ends_at AS CHAR)) FROM server_boosts ORDER BY starts_at",
    )

    /** Les événements `BOOST_COLLECTIF` : `joueur (ou -)|acheteur|pourcents|minutes|début|fin`. */
    private fun boostEvents(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(IFNULL(CAST(xuid AS CHAR), '-'), '|', JSON_VALUE(payload, '\$.buyerName'), '|', JSON_VALUE(payload, '\$.percent'), '|', " +
            "JSON_VALUE(payload, '\$.minutes'), '|', JSON_VALUE(payload, '\$.startsAt'), '|', JSON_VALUE(payload, '\$.endsAt')) " +
            "FROM game_events WHERE kind = 'BOOST_COLLECTIF' ORDER BY id",
    )

    /** Les événements `ANNONCE` : `joueur (ou -)|action|cosmétique`. */
    private fun announcements(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(IFNULL(CAST(xuid AS CHAR), '-'), '|', JSON_VALUE(payload, '\$.action'), '|', IFNULL(JSON_VALUE(payload, '\$.cosmeticId'), '-')) " +
            "FROM game_events WHERE kind = 'ANNONCE' ORDER BY id",
    )

    /** Les pourcents du boost qui court à l'instant [at], lus par le vrai dépôt. */
    private fun activeBoostPercent(at: Instant): Int = MariaDbSupport.raw { jdbc.activeBoostPercent(it, at) }

    /** La fin du dernier boost réservé à l'instant [at], lue par le vrai dépôt. */
    private fun lastBoostEnd(at: Instant): Instant? = MariaDbSupport.raw { jdbc.lastBoostEnd(it, at) }

    /** Une inscription du site (`site_registrations`, V7) sous la clé que le site calcule pour [gamertag]. */
    private fun register(gamertag: String, pioneer: Boolean = true) {
        MariaDbSupport.update(
            "INSERT INTO site_registrations (gamertag_key, gamertag, pioneer) VALUES (?, ?, ?)",
            SiteRegistrations.key(gamertag), gamertag, pioneer,
        )
    }

    private fun relief(store: ShopStore = jdbc) = DeliveryRelief(database, store, clock)

    private fun poll(store: ShopStore = jdbc): Int = runBlocking { relief(store).poll() }

    private fun requestId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    /** `buy` du service dans une vraie transaction : tout ou rien, comme la route. */
    private fun buy(actor: Xuid, itemId: String, n: Int = 1, forXuid: Xuid? = null, store: ShopStore = jdbc): Balances {
        val shop = ShopService(rules, store, sessions, clock)
        return runBlocking { database.transaction { shop.buy(it, actor, requestId(n), itemId, forXuid) } }
    }

    private fun refusalOf(block: () -> Unit): Refusal = assertThrows(Refusal::class.java) { block() }

    /** Lance [count] appels en même temps (le pool a huit connexions) ; chacun rend son résultat ou son erreur. */
    private fun <T> inParallel(count: Int, block: suspend (Int) -> T): List<Result<T>> = runBlocking {
        (0 until count).map { index -> async(Dispatchers.IO) { runCatching { block(index) } } }.awaitAll()
    }

    /** Un dépôt qui écrit comme le vrai, sauf la publication d'événement : la dernière écriture d'une livraison échoue. */
    private class FailingPublish(private val real: ShopStore = JdbcShopStore()) : ShopStore by real {
        override fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long =
            throw IllegalStateException("publication impossible (test)")
    }

    /** Un dépôt qui échoue au crédit des Gemmes d'une livraison : la ligne est déjà marquée à ce moment-là. */
    private class FailingCredit(private val real: ShopStore = JdbcShopStore()) : ShopStore by real {
        override fun creditGemmes(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long =
            throw IllegalStateException("crédit impossible (test)")
    }

    /** Un dépôt qui échoue à donner le grade : l'achat est noté et payé à ce moment-là. */
    private class FailingGrant(private val real: ShopStore = JdbcShopStore()) : ShopStore by real {
        override fun grantRank(connection: Connection, xuid: Xuid, rankId: String, source: String, at: Instant): Boolean =
            throw IllegalStateException("grade impossible (test)")
    }

    /** Un dépôt qui échoue à écrire l'abonnement : l'achat est noté et payé à ce moment-là. */
    private class FailingSave(private val real: ShopStore = JdbcShopStore()) : ShopStore by real {
        override fun saveSubscription(connection: Connection, xuid: Xuid, subscriptionId: String, term: SubscriptionTerm) {
            throw IllegalStateException("abonnement impossible (test)")
        }
    }

    /** Un dépôt qui échoue à donner le cosmétique : l'achat est noté et payé à ce moment-là. */
    private class FailingCosmeticGrant(private val real: ShopStore = JdbcShopStore()) : ShopStore by real {
        override fun grantCosmetic(connection: Connection, xuid: Xuid, cosmeticId: String, source: String, at: Instant): Boolean =
            throw IllegalStateException("cosmétique impossible (test)")
    }

    /** Un dépôt qui échoue à écrire le boost : l'achat est noté et payé à ce moment-là. */
    private class FailingBoostInsert(private val real: ShopStore = JdbcShopStore()) : ShopStore by real {
        override fun insertBoost(connection: Connection, boughtBy: Xuid, window: BoostWindow, bonusPercent: Int) {
            throw IllegalStateException("boost impossible (test)")
        }
    }

    // --- JdbcShopStore : possessions

    @Test
    @DisplayName("grantRank : un grade ne se donne qu'une fois (faux au second appel, la source du premier reste), et loadHoldings le relit")
    fun grantRankIsOncePerPlayer() {
        player(KR, "Kr")

        assertTrue(MariaDbSupport.raw { jdbc.grantRank(it, KR, first.id, "achat", START) })
        assertFalse(MariaDbSupport.raw { jdbc.grantRank(it, KR, first.id, "cadeau", START.plusSeconds(60)) })
        assertTrue(MariaDbSupport.raw { jdbc.grantRank(it, KR, second.id, "cadeau", START.plusSeconds(120)) })

        assertEquals(setOf("${KR.text}|${first.id}|achat", "${KR.text}|${second.id}|cadeau"), rankRows().toSet())
        assertEquals(2, rankRows().size)
        assertEquals(setOf(first.id, second.id), MariaDbSupport.raw { jdbc.loadHoldings(it, KR) }.ranks)
        assertEquals(Holdings.NOTHING, MariaDbSupport.raw { jdbc.loadHoldings(it, OTHER) })
    }

    @Test
    @DisplayName("saveSubscription et loadSubscription : une ligne par joueur et abonnement, remplacée à chaque écriture ; loadHoldings garde aussi l'abonnement échu")
    fun subscriptionRoundTrip() {
        player(KR, "Kr")
        val term = SubscriptionTerm(START, START.plus(Duration.ofDays(28)), 1)
        assertNull(MariaDbSupport.raw { jdbc.loadSubscription(it, KR, pilier.id) })

        MariaDbSupport.raw { jdbc.saveSubscription(it, KR, pilier.id, term) }
        assertEquals(term, MariaDbSupport.raw { jdbc.loadSubscription(it, KR, pilier.id) })
        val later = SubscriptionTerm(START, START.plus(Duration.ofDays(56)), 2)
        MariaDbSupport.raw { jdbc.saveSubscription(it, KR, pilier.id, later) }

        assertEquals(later, MariaDbSupport.raw { jdbc.loadSubscription(it, KR, pilier.id) })
        assertEquals(1, subscriptionRows().size, "une seule ligne par abonnement")
        assertEquals(mapOf(pilier.id to later.endsAt), MariaDbSupport.raw { jdbc.loadHoldings(it, KR) }.subscriptionEnds)
        assertNull(MariaDbSupport.raw { jdbc.loadSubscription(it, KR, perso.id) }, "un autre abonnement n'existe pas pour autant")
        assertNull(MariaDbSupport.raw { jdbc.loadSubscription(it, OTHER, pilier.id) }, "ni celui d'un autre joueur")

        val ended = SubscriptionTerm(START.minus(Duration.ofDays(60)), START.minus(Duration.ofDays(32)), 1)
        MariaDbSupport.raw { jdbc.saveSubscription(it, KR, pilier.id, ended) }
        val holdings = MariaDbSupport.raw { jdbc.loadHoldings(it, KR) }
        assertEquals(ended.endsAt, holdings.subscriptionEnds[pilier.id], "la date échue reste lue")
        assertFalse(holdings.isActive(pilier.id, START))
    }

    @Test
    @DisplayName("ceoHoldings : les grades et les abonnements du seul PDG de l'entreprise, jamais ceux d'un associé ni d'une autre entreprise")
    fun ceoHoldingsAreTheCeosOnly() {
        player(KR, "Kr")
        player(OTHER, "Autre")
        player(STRANGER, "Etranger")
        val company = MariaDbSupport.insertCompany("A", 0)
        val elsewhere = MariaDbSupport.insertCompany("B", 1)
        val empty = MariaDbSupport.insertCompany("C", 2)
        MariaDbSupport.update("INSERT INTO company_members (company_id, xuid, role, share_percent) VALUES (?, ?, 'PDG', 100)", company, bigint(KR))
        MariaDbSupport.update("INSERT INTO company_members (company_id, xuid, role, share_percent) VALUES (?, ?, 'ASSOCIE', 0)", company, bigint(OTHER))
        MariaDbSupport.update("INSERT INTO company_members (company_id, xuid, role, share_percent) VALUES (?, ?, 'PDG', 100)", elsewhere, bigint(STRANGER))
        MariaDbSupport.raw { c ->
            jdbc.grantRank(c, KR, first.id, "achat", START)
            jdbc.grantRank(c, KR, second.id, "achat", START)
            jdbc.saveSubscription(c, KR, pilier.id, SubscriptionTerm(START, START.plus(Duration.ofDays(28)), 1))
            rules.grades.forEach { jdbc.grantRank(c, OTHER, it.id, "achat", START) }
            jdbc.saveSubscription(c, OTHER, pilier.id, SubscriptionTerm(START, START.plus(Duration.ofDays(99)), 3))
            jdbc.grantRank(c, STRANGER, top.id, "staff", START)
        }

        val ceo = MariaDbSupport.raw { jdbc.ceoHoldings(it, company) }

        assertEquals(setOf(first.id, second.id), ceo.ranks)
        assertEquals(mapOf(pilier.id to START.plus(Duration.ofDays(28))), ceo.subscriptionEnds)
        assertEquals(setOf(top.id), MariaDbSupport.raw { jdbc.ceoHoldings(it, elsewhere) }.ranks)
        assertEquals(Holdings.NOTHING, MariaDbSupport.raw { jdbc.ceoHoldings(it, empty) }, "une entreprise sans PDG n'a aucun bonus")
    }

    // --- JdbcShopStore : cosmétiques, boosts collectifs, pionniers

    @Test
    @DisplayName("grantCosmetic : un cosmétique ne se donne qu'une fois (faux au second appel, la source et la date du premier restent), et loadHoldings le relit")
    fun grantCosmeticIsOncePerPlayer() {
        player(KR, "Kr")
        val later = START.plusSeconds(120)

        assertTrue(MariaDbSupport.raw { jdbc.grantCosmetic(it, KR, cosmetic.id, ShopService.SOURCE_COSMETIC, START) })
        assertFalse(MariaDbSupport.raw { jdbc.grantCosmetic(it, KR, cosmetic.id, PioneerTitle.SOURCE_SITE, START.plusSeconds(60)) })
        assertTrue(MariaDbSupport.raw { jdbc.grantCosmetic(it, KR, otherCosmetic.id, PioneerTitle.SOURCE_SITE, later) })

        assertEquals(
            listOf(
                "${KR.text}|${cosmetic.id}|${ShopService.SOURCE_COSMETIC}|${stamp(START)}",
                "${KR.text}|${otherCosmetic.id}|${PioneerTitle.SOURCE_SITE}|${stamp(later)}",
            ),
            cosmeticRows(),
        )
        assertEquals(setOf(cosmetic.id, otherCosmetic.id), MariaDbSupport.raw { jdbc.loadHoldings(it, KR) }.cosmetics)
        assertEquals(Holdings.NOTHING, MariaDbSupport.raw { jdbc.loadHoldings(it, OTHER) })
    }

    @Test
    @DisplayName("ceoHoldings ne lit pas les cosmétiques : ceux du PDG ne comptent pour aucun bonus")
    fun ceoHoldingsLeaveCosmeticsAlone() {
        player(KR, "Kr")
        val company = MariaDbSupport.insertCompany("A", 0)
        MariaDbSupport.update("INSERT INTO company_members (company_id, xuid, role, share_percent) VALUES (?, ?, 'PDG', 100)", company, bigint(KR))
        MariaDbSupport.raw { jdbc.grantCosmetic(it, KR, cosmetic.id, ShopService.SOURCE_COSMETIC, START) }

        assertEquals(Holdings.NOTHING, MariaDbSupport.raw { jdbc.ceoHoldings(it, company) })
        assertEquals(setOf(cosmetic.id), MariaDbSupport.raw { jdbc.loadHoldings(it, KR) }.cosmetics)
    }

    @Test
    @DisplayName("gamertagOf rend le gamertag que le service connaît ; un joueur inconnu lève une erreur")
    fun gamertagOfReadsThePlayer() {
        player(KR, "Kr Majuscule")

        assertEquals("Kr Majuscule", MariaDbSupport.raw { jdbc.gamertagOf(it, KR) })
        assertThrows(IllegalStateException::class.java) { MariaDbSupport.raw { jdbc.gamertagOf(it, STRANGER) } }
    }

    @Test
    @DisplayName("Boost : sans boost, la fin est nulle et la hausse est zéro ; un boost court de son début (inclus) à sa fin (exclue) ; la fin réservée ne voit que les boosts non échus")
    fun boostWindowStartsIncludedAndEndsExcluded() {
        player(KR, "Kr")
        assertNull(lastBoostEnd(START))
        assertEquals(0, activeBoostPercent(START))
        val window = BoostWindow(START.plusSeconds(60), START.plusSeconds(60).plus(boostLength))

        MariaDbSupport.raw { jdbc.insertBoost(it, KR, window, boost.bonusPercent) }

        assertEquals(listOf("${KR.text}|${stamp(window.startsAt)}|${stamp(window.endsAt)}|${boost.bonusPercent}"), boostRows())
        assertEquals(0, activeBoostPercent(window.startsAt.minusMillis(1)), "juste avant le début")
        assertEquals(boost.bonusPercent, activeBoostPercent(window.startsAt), "à l'instant du début")
        assertEquals(boost.bonusPercent, activeBoostPercent(window.endsAt.minusMillis(1)), "à la dernière milliseconde")
        assertEquals(0, activeBoostPercent(window.endsAt), "à l'instant de la fin")
        assertEquals(window.endsAt, lastBoostEnd(START), "en attente : sa fin est déjà réservée")
        assertEquals(window.endsAt, lastBoostEnd(window.endsAt.minusMillis(1)))
        assertNull(lastBoostEnd(window.endsAt), "échu")
    }

    @Test
    @DisplayName("Deux boosts qui se suivent : à chaque instant un seul court, avec ses pourcents ; la fin réservée est celle du dernier")
    fun chainedBoostsAreReadOneAtATime() {
        player(KR, "Kr")
        player(OTHER, "Autre")
        val firstWindow = BoostWindow(START, START.plus(boostLength))
        val secondWindow = BoostWindow(firstWindow.endsAt, firstWindow.endsAt.plus(boostLength))
        MariaDbSupport.raw { jdbc.insertBoost(it, KR, firstWindow, boost.bonusPercent) }
        MariaDbSupport.raw { jdbc.insertBoost(it, OTHER, secondWindow, boost.bonusPercent + 5) }

        assertEquals(boost.bonusPercent, activeBoostPercent(START))
        assertEquals(boost.bonusPercent, activeBoostPercent(firstWindow.endsAt.minusMillis(1)))
        assertEquals(boost.bonusPercent + 5, activeBoostPercent(firstWindow.endsAt), "le second commence où le premier finit")
        assertEquals(0, activeBoostPercent(secondWindow.endsAt))
        assertEquals(secondWindow.endsAt, lastBoostEnd(START))
        assertEquals(secondWindow.endsAt, lastBoostEnd(firstWindow.endsAt), "le premier est échu, le second court")
    }

    @Test
    @DisplayName("server_boosts (V9) refuse une fenêtre vide ou renversée, des pourcents à zéro et un acheteur que le service ne connaît pas")
    fun boostTableRefusesNonsense() {
        player(KR, "Kr")
        val window = BoostWindow(START, START.plus(boostLength))

        assertThrows(SQLException::class.java) { MariaDbSupport.raw { jdbc.insertBoost(it, KR, BoostWindow(START, START), boost.bonusPercent) } }
        assertThrows(SQLException::class.java) { MariaDbSupport.raw { jdbc.insertBoost(it, KR, BoostWindow(window.endsAt, window.startsAt), boost.bonusPercent) } }
        assertThrows(SQLException::class.java) { MariaDbSupport.raw { jdbc.insertBoost(it, KR, window, 0) } }
        assertThrows(SQLException::class.java) { MariaDbSupport.raw { jdbc.insertBoost(it, OTHER, window, boost.bonusPercent) } }

        assertEquals(emptyList<String?>(), boostRows())
    }

    @Test
    @DisplayName("isPioneer lit site_registrations par la clé du site : vrai pour un pionnier inscrit, faux pour un inscrit qui ne l'est pas et pour un inconnu")
    fun isPioneerReadsTheSiteRegistrations() {
        register("Grand Kr", pioneer = true)
        register("Retardataire", pioneer = false)

        assertTrue(MariaDbSupport.raw { jdbc.isPioneer(it, SiteRegistrations.key("  GRAND   kr ")) })
        assertFalse(MariaDbSupport.raw { jdbc.isPioneer(it, SiteRegistrations.key("Retardataire")) })
        assertFalse(MariaDbSupport.raw { jdbc.isPioneer(it, SiteRegistrations.key("Inconnu")) })
        assertFalse(MariaDbSupport.raw { jdbc.isPioneer(it, SiteRegistrations.key("Grand")) })
    }

    @Test
    @DisplayName("Le titre Pionnier avec le vrai dépôt : écrit une fois (source site, date du service), annoncé une fois, quel que soit le nombre de join ; rien pour un joueur non inscrit")
    fun pioneerTitleIsGivenOnce() {
        player(KR, "Kr")
        player(OTHER, "Autre")
        register("kr")
        val titles = PioneerTitle(jdbc, clock)

        repeat(3) { runBlocking { database.transaction { connection -> titles.relieve(connection, KR, " KR ") } } }
        runBlocking { database.transaction { connection -> titles.relieve(connection, OTHER, "Autre") } }

        assertEquals(listOf("${KR.text}|${ShopRules.PIONEER_TITLE_ID}|${PioneerTitle.SOURCE_SITE}|${stamp(START)}"), cosmeticRows())
        assertEquals(listOf("${KR.text}|${PioneerTitle.ACTION}|${ShopRules.PIONEER_TITLE_ID}"), announcements())
    }

    @Test
    @DisplayName("Le titre Pionnier donné par quatre join en même temps : une seule ligne, une seule annonce (les conflits transitoires se rejouent)")
    fun concurrentPioneerTitlesAreGivenOnce() {
        player(KR, "Kr")
        register("Kr")
        val titles = PioneerTitle(jdbc, clock)

        val results = inParallel(4) { database.transaction { connection -> titles.relieve(connection, KR, "Kr") } }

        results.mapNotNull { it.exceptionOrNull() }.forEach { assertTrue(it.isTransientConflict(), "un conflit à rejouer, pas : $it") }
        runBlocking { database.transaction { connection -> titles.relieve(connection, KR, "Kr") } }
        assertEquals(1, cosmeticRows().size)
        assertEquals(1, announcements().size)
    }

    // --- JdbcShopStore : achats et Gemmes

    @Test
    @DisplayName("recordPurchase : l'achat est écrit (avec le destinataire d'un cadeau), et un requestId ne sert qu'une fois (RequestRejected, rien d'écrit)")
    fun recordPurchaseKeepsTheRequestIdUnique() {
        player(KR, "Kr")
        player(OTHER, "Autre")

        MariaDbSupport.raw { jdbc.recordPurchase(it, KR, first.id, first.gems, null, requestId(1), START) }
        MariaDbSupport.raw { jdbc.recordPurchase(it, KR, second.id, second.gems, OTHER, requestId(2), START) }

        assertEquals(
            listOf(
                "${KR.text}|${first.id}|${first.gems}|-|${requestId(1)}",
                "${KR.text}|${second.id}|${second.gems}|${OTHER.text}|${requestId(2)}",
            ),
            purchaseRows(),
        )
        assertThrows(RequestRejected::class.java) {
            MariaDbSupport.raw { jdbc.recordPurchase(it, OTHER, top.id, top.gems, null, requestId(1), START) }
        }
        assertEquals(2, purchaseRows().size)
    }

    @Test
    @DisplayName("lockGemmes, spendGemmes et creditGemmes : le solde et le journal bougent ensemble (motif, référence) ; un solde trop juste lève InsufficientBalance sans rien écrire")
    fun gemmesMoveWithTheLedger() {
        player(KR, "Kr")
        give(KR, 100)

        assertEquals(100L, MariaDbSupport.raw { jdbc.lockGemmes(it, KR) })
        assertEquals(emptyList<String?>(), ledger(), "verrouiller n'écrit rien")
        assertEquals(70L, MariaDbSupport.raw { jdbc.spendGemmes(it, KR, 30, "boutique", first.id) })
        assertEquals(75L, MariaDbSupport.raw { jdbc.creditGemmes(it, KR, 5, "pilier", pilier.id) })

        assertEquals(75L, gemmes(KR))
        assertEquals(listOf("${KR.text}|-30|70|boutique|${first.id}", "${KR.text}|5|75|pilier|${pilier.id}"), ledger())
        val failure = assertThrows(InsufficientBalance::class.java) { MariaDbSupport.raw { jdbc.spendGemmes(it, KR, 76, "boutique", first.id) } }
        assertEquals(1L, failure.missing)
        assertEquals(75L, gemmes(KR))
        assertEquals(2, ledger().size)
    }

    // --- JdbcShopStore : livraisons

    @Test
    @DisplayName("pendingDeliveries : seulement les commandes non versées dont le joueur est connu (XUID rempli et connu, ou pseudo connu), de la plus ancienne à la plus récente, limite comprise")
    fun pendingDeliveriesListsTheResolvableOnes() {
        player(KR, "Kr")
        player(OTHER, "Autre")
        addDelivery("a-fantome", "Fantome", 10, createdAt = LocalDateTime.of(2026, 11, 5, 9, 0))
        addDelivery("b-kr", "kr", 20, createdAt = LocalDateTime.of(2026, 11, 5, 10, 0))
        addDelivery("c-xuid", "Fantome", 30, xuid = OTHER, createdAt = LocalDateTime.of(2026, 11, 5, 10, 30))
        addDelivery("d-xuid-inconnu", "Fantome", 40, xuid = STRANGER, createdAt = LocalDateTime.of(2026, 11, 5, 10, 45))
        addDelivery("e-fait", "Kr", 50, createdAt = LocalDateTime.of(2026, 11, 5, 8, 0))
        MariaDbSupport.update("UPDATE shop_deliveries SET delivered_at = ? WHERE order_id = 'e-fait'", LocalDateTime.of(2026, 11, 5, 8, 5))

        val all = MariaDbSupport.raw { jdbc.pendingDeliveries(it, 10) }

        assertEquals(listOf("b-kr", "c-xuid"), all.map { it.orderId })
        assertEquals(PendingDelivery("b-kr", "kr", null, 20), all[0])
        assertEquals(PendingDelivery("c-xuid", "Fantome", OTHER, 30), all[1])
        assertEquals(listOf("b-kr"), MariaDbSupport.raw { jdbc.pendingDeliveries(it, 1) }.map { it.orderId })
        assertEquals(listOf("b-kr"), MariaDbSupport.raw { jdbc.pendingDeliveriesFor(it, KR, "Kr") }.map { it.orderId })
        assertEquals(listOf("c-xuid"), MariaDbSupport.raw { jdbc.pendingDeliveriesFor(it, OTHER, "Autre") }.map { it.orderId })
        assertEquals(
            listOf("a-fantome", "c-xuid", "d-xuid-inconnu"),
            MariaDbSupport.raw { jdbc.pendingDeliveriesFor(it, OTHER, "fantome") }.map { it.orderId },
        )
    }

    @Test
    @DisplayName("recipientOf : le XUID rempli et connu d'abord ; sinon le joueur de ce pseudo (casse ignorée) vu en dernier ; personne : null")
    fun recipientOfPrefersTheKnownXuidThenTheLatestSeen() {
        player(KR, "Pareil", lastSeen = LocalDateTime.of(2026, 1, 1, 0, 0))
        player(OTHER, "pAREIL", lastSeen = LocalDateTime.of(2026, 2, 1, 0, 0))
        player(STRANGER, "Seul")
        val ghost = Xuid.parse("2535428000000099")

        fun recipient(gamertag: String, xuid: Xuid?) = MariaDbSupport.raw { jdbc.recipientOf(it, PendingDelivery("x", gamertag, xuid, 1)) }

        assertEquals(OTHER, recipient("PAREIL", null), "le plus récemment vu gagne")
        assertEquals(KR, recipient("PAREIL", KR), "un XUID rempli et connu passe avant le pseudo")
        assertEquals(STRANGER, recipient("seul", null), "la casse du pseudo ne compte pas")
        assertEquals(OTHER, recipient("Personne", OTHER))
        assertEquals(STRANGER, recipient("Seul", ghost), "un XUID rempli mais inconnu se rabat sur le pseudo")
        assertNull(recipient("Personne", null))
        assertNull(recipient("Personne", ghost))
    }

    @Test
    @DisplayName("markDelivered : la ligne se marque une fois (vrai, date et XUID écrits), puis plus jamais (faux) ; une commande inconnue rend faux")
    fun markDeliveredTouchesOneRow() {
        player(KR, "Kr")
        addDelivery("ord-1", "Kr", 100)

        assertTrue(MariaDbSupport.raw { jdbc.markDelivered(it, "ord-1", KR, START) })
        assertFalse(MariaDbSupport.raw { jdbc.markDelivered(it, "ord-1", KR, START.plusSeconds(5)) })
        assertFalse(MariaDbSupport.raw { jdbc.markDelivered(it, "ord-inconnue", KR, START) })

        assertEquals(listOf("ord-1|${KR.text}|versee"), deliveryRows())
        assertEquals(stamp(START), MariaDbSupport.string("SELECT CAST(delivered_at AS CHAR) FROM shop_deliveries WHERE order_id = 'ord-1'"))
    }

    // --- DeliveryRelief : le relevé

    @Test
    @DisplayName("poll : la commande est versée une fois (date, XUID, Gemmes, journal `livraison`, événement GEMMES_RECUES) même si on relève deux fois ; la ligne de solde manquante est créée")
    fun pollCreditsOnceEvenIfPolledTwice() {
        player(KR, "Kr", withBalance = false)
        addDelivery("ord-1", "kr", 300)

        assertEquals(1, poll())
        clock.advance(Duration.ofSeconds(30))
        assertEquals(0, poll(), "le second passage ne verse rien")
        assertEquals(0, poll())

        assertEquals(300L, gemmes(KR))
        assertEquals(listOf("${KR.text}|300|300|livraison|ord-1"), ledger())
        assertEquals(listOf("ord-1|${KR.text}|versee"), deliveryRows())
        assertEquals(stamp(START), MariaDbSupport.string("SELECT CAST(delivered_at AS CHAR) FROM shop_deliveries WHERE order_id = 'ord-1'"))
        assertEquals(listOf("${KR.text}|GEMMES_RECUES|ord-1|300|300"), events())
    }

    @Test
    @DisplayName("poll : le XUID rempli et connu prime sur le pseudo ; un pseudo inconnu reste en attente, intact, sans rien écrire")
    fun pollKeepsTheUnknownPending() {
        player(KR, "Kr")
        player(OTHER, "Autre")
        addDelivery("ord-xuid", "Fantome", 40, xuid = OTHER)
        addDelivery("ord-pseudo", "KR", 20)
        addDelivery("ord-inconnu", "Fantome", 70)
        addDelivery("ord-xuid-inconnu", "Fantome", 80, xuid = STRANGER)

        assertEquals(2, poll())

        assertEquals(40L, gemmes(OTHER))
        assertEquals(20L, gemmes(KR))
        assertEquals(
            listOf("ord-inconnu|-|attente", "ord-pseudo|${KR.text}|versee", "ord-xuid|${OTHER.text}|versee", "ord-xuid-inconnu|${STRANGER.text}|attente"),
            deliveryRows(),
        )
        assertEquals(2, ledger().size)
        assertEquals(2, events().size)
        assertEquals(0, poll(), "rien d'autre à verser tant que les joueurs sont inconnus")
    }

    @Test
    @DisplayName("Plusieurs passages en même temps, avec le rattrapage du join : chaque commande est versée une seule fois, jamais deux")
    fun concurrentPassesCreditOnce() {
        player(KR, "Kr")
        addDelivery("ord-1", "Kr", 100, createdAt = LocalDateTime.of(2026, 11, 5, 10, 0))
        addDelivery("ord-2", "Kr", 200, createdAt = LocalDateTime.of(2026, 11, 5, 10, 1))
        addDelivery("ord-3", "Kr", 400, createdAt = LocalDateTime.of(2026, 11, 5, 10, 2))

        inParallel(6) { index ->
            if (index % 2 == 0) {
                relief().poll()
            } else {
                database.transaction { connection -> relief().relieve(connection, KR, "Kr") }
                0
            }
        }
        // Un passage qui a perdu un verrou a laissé sa commande pour le suivant : on relève jusqu'au bout.
        repeat(3) { poll() }

        assertEquals(listOf("ord-1|${KR.text}|versee", "ord-2|${KR.text}|versee", "ord-3|${KR.text}|versee"), deliveryRows())
        assertEquals(700L, gemmes(KR))
        assertEquals(3, ledger().size, "une ligne de journal par commande")
        assertEquals(setOf("ord-1", "ord-2", "ord-3"), MariaDbSupport.strings("SELECT ref_id FROM currency_ledger WHERE reason = 'livraison'").toSet())
        assertEquals(3, events().size)
    }

    @Test
    @DisplayName("Un pseudo inconnu à la commande est rattrapé au join : les Gemmes sont versées dans la réponse, et un second join ne les verse pas deux fois")
    fun unknownGamertagIsCaughtUpAtJoin() {
        addDelivery("ord-1", "Nouveau", 250)
        addDelivery("ord-2", "Autre pseudo", 90)
        assertEquals(0, poll(), "personne ne correspond encore")
        assertEquals(listOf("ord-1|-|attente", "ord-2|-|attente"), deliveryRows())

        val service = SessionService(
            database,
            sessions,
            EngineFigures(engine, sessions, clock),
            DefinitionsBootstrap(engine, definitions),
            deliveries = relief(),
        )
        val joined = runBlocking { service.join(KR, "nouveau") }

        assertEquals(250L, joined.balances.gemmes)
        assertEquals(250L, gemmes(KR))
        assertEquals(listOf("ord-1|${KR.text}|versee", "ord-2|-|attente"), deliveryRows())
        assertEquals(listOf("${KR.text}|250|250|livraison|ord-1"), ledger())
        assertEquals(listOf("${KR.text}|GEMMES_RECUES|ord-1|250|250"), events())

        val again = runBlocking { service.join(KR, "nouveau") }
        assertEquals(250L, again.balances.gemmes)
        assertEquals(1, ledger().size)
        assertEquals(0, poll(), "la commande de l'autre pseudo attend toujours")
    }

    @Test
    @DisplayName("Le rattrapage du join ne verse que les commandes du joueur : celle d'un pseudo partagé et d'un autre joueur vu plus récemment lui échappe")
    fun relieveSkipsWhatBelongsToAnotherPlayer() {
        player(KR, "Pareil", lastSeen = LocalDateTime.of(2026, 1, 1, 0, 0))
        player(OTHER, "Pareil", lastSeen = LocalDateTime.of(2026, 2, 1, 0, 0))
        addDelivery("ord-1", "pareil", 60)
        addDelivery("ord-2", "Pareil", 15, xuid = KR)

        MariaDbSupport.raw { relief().relieve(it, KR, "Pareil") }

        assertEquals(15L, gemmes(KR), "seule la commande à son XUID est la sienne")
        assertEquals(0L, gemmes(OTHER))
        assertEquals(listOf("ord-1|-|attente", "ord-2|${KR.text}|versee"), deliveryRows())
        assertEquals(1, poll(), "le relevé de fond la verse à celui qui a été vu en dernier")
        assertEquals(60L, gemmes(OTHER))
    }

    @Test
    @DisplayName("Une livraison part en entier ou pas du tout : si l'événement ou le crédit échoue, la ligne reste en attente, sans Gemmes ni journal ; un join annulé non plus ne verse rien")
    fun deliveryRollsBackAsOne() {
        player(KR, "Kr")
        addDelivery("ord-1", "Kr", 500)

        assertEquals(0, poll(FailingPublish()), "la publication échoue : tout est annulé")
        assertEquals(0, poll(FailingCredit()), "le crédit échoue : la ligne marquée est remise en attente")
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                database.transaction { connection ->
                    relief().relieve(connection, KR, "Kr")
                    check(false) { "join annulé (test)" }
                }
            }
        }

        assertEquals(listOf("ord-1|-|attente"), deliveryRows())
        assertEquals(0L, gemmes(KR))
        assertEquals(emptyList<String?>(), ledger())
        assertEquals(emptyList<String?>(), events())
        assertEquals(1, poll(), "le passage suivant, avec un dépôt qui marche, la verse")
        assertEquals(500L, gemmes(KR))
        assertEquals(1, ledger().size)
    }

    @Test
    @DisplayName("Le pseudo d'une livraison se compare par la clé du site : casse, espaces des bords et espaces doublés sans effet ; un préfixe ou une lettre de plus ne passent pas")
    fun deliveryGamertagIsMatchedByTheSiteKey() {
        player(KR, "Kr Majuscule")
        player(OTHER, "Ami")
        addDelivery("casse", "KR MAJUSCULE", 1)
        addDelivery("bords", "  kr majuscule \t", 10)
        addDelivery("doubles", "Kr   Majuscule", 100)
        val near = listOf(".Kr Majuscule", "Kr Majuscul", "KrMajuscule", "Kr Majuscule 2")
        near.forEachIndexed { n, gamertag -> addDelivery("proche-$n", gamertag, 7) }
        val matching = setOf("casse", "bords", "doubles")

        val pending = MariaDbSupport.raw { jdbc.pendingDeliveries(it, 50) }
        assertEquals(matching, pending.map { it.orderId }.toSet(), "le relevé ne rend que celles dont le pseudo a la même clé")
        assertEquals(matching, MariaDbSupport.raw { jdbc.pendingDeliveriesFor(it, KR, "kr  MAJUSCULE ") }.map { it.orderId }.toSet())
        assertEquals(emptyList<PendingDelivery>(), MariaDbSupport.raw { jdbc.pendingDeliveriesFor(it, OTHER, "Ami") })
        pending.forEach { delivery -> assertEquals(KR, MariaDbSupport.raw { jdbc.recipientOf(it, delivery) }, delivery.orderId) }
        near.forEach { gamertag ->
            assertNull(MariaDbSupport.raw { jdbc.recipientOf(it, PendingDelivery("proche", gamertag, null, 7)) }, gamertag)
        }
    }

    @Test
    @DisplayName("Le relevé verse une fois les livraisons dont le pseudo a la clé d'un joueur connu ; les pseudos qui n'ont que l'air proches restent en attente")
    fun pollDeliversByTheSiteKey() {
        player(KR, "Kr Majuscule")
        addDelivery("casse", "KR MAJUSCULE", 1)
        addDelivery("bords", "  kr majuscule \t", 10)
        addDelivery("doubles", "Kr   Majuscule", 100)
        addDelivery("proche", ".Kr Majuscule", 7)

        assertEquals(3, poll())
        assertEquals(0, poll(), "le second passage ne verse rien")

        assertEquals(111L, gemmes(KR))
        assertEquals(
            listOf("bords|${KR.text}|versee", "casse|${KR.text}|versee", "doubles|${KR.text}|versee", "proche|-|attente"),
            deliveryRows(),
        )
    }

    @Test
    @DisplayName("recipientOf : deux joueurs dont le gamertag a la même clé, celui qu'on a vu en dernier reçoit")
    fun recipientOfSameKeyPrefersTheLatestSeen() {
        player(KR, "Kr Majuscule", lastSeen = LocalDateTime.of(2026, 11, 1, 10, 0))
        player(OTHER, "KR  majuscule", lastSeen = LocalDateTime.of(2026, 11, 3, 10, 0))
        val delivery = PendingDelivery("o-1", "kr majuscule", null, 5)

        assertEquals(OTHER, MariaDbSupport.raw { jdbc.recipientOf(it, delivery) })

        MariaDbSupport.update("UPDATE players SET last_seen_at = ? WHERE xuid = ?", LocalDateTime.of(2026, 11, 4, 10, 0), bigint(KR))
        assertEquals(KR, MariaDbSupport.raw { jdbc.recipientOf(it, delivery) })
    }

    // --- ShopService : les achats

    @Test
    @DisplayName("buyItem d'un grade : le solde, le journal `boutique`, l'achat et le grade `achat` sont écrits ensemble ; aucun événement")
    fun buyGradeWritesEverythingTogether() {
        player(KR, "Kr")
        give(KR, first.gems + 10)

        val balances = buy(KR, first.id)

        assertEquals(10L, balances.gemmes)
        assertEquals(10L, gemmes(KR))
        assertEquals(listOf("${KR.text}|-${first.gems}|10|boutique|${first.id}"), ledger())
        assertEquals(listOf("${KR.text}|${first.id}|${first.gems}|-|${requestId(1)}"), purchaseRows())
        assertEquals(listOf("${KR.text}|${first.id}|achat"), rankRows())
        assertEquals(0L, MariaDbSupport.count("game_events"))
    }

    @Test
    @DisplayName("buyItem : les refus (PAS_ASSEZ_DE_GEMMES avec ce qui manque, GRADE_PRECEDENT_REQUIS, DEJA_POSSEDE) n'écrivent rien en base")
    fun refusalsWriteNothing() {
        player(KR, "Kr")
        give(KR, first.gems - 1)

        val poor = refusalOf { buy(KR, first.id, n = 1) }
        assertEquals(RefusalCodes.NOT_ENOUGH_GEMS, poor.reason)
        assertEquals(1.0, poor.missing)
        give(KR, top.gems + first.gems)
        assertEquals(RefusalCodes.PREVIOUS_RANK_REQUIRED, refusalOf { buy(KR, second.id, n = 2) }.reason)
        buy(KR, first.id, n = 3)
        val gems = gemmes(KR)
        val ledgerBefore = ledger()

        assertEquals(RefusalCodes.ALREADY_OWNED, refusalOf { buy(KR, first.id, n = 4) }.reason)
        assertEquals(RefusalCodes.PREVIOUS_RANK_REQUIRED, refusalOf { buy(KR, top.id, n = 5) }.reason)
        assertEquals(RefusalCodes.PREVIOUS_RANK_REQUIRED, refusalOf { buy(KR, perso.id, n = 6) }.reason)
        assertThrows(RequestRejected::class.java) { buy(KR, "cosmetique-inconnu", n = 7) }
        assertThrows(RequestRejected::class.java) { buy(STRANGER, first.id, n = 8) }

        assertEquals(gems, gemmes(KR))
        assertEquals(ledgerBefore, ledger())
        assertEquals(listOf("${KR.text}|${first.id}|${first.gems}|-|${requestId(3)}"), purchaseRows())
        assertEquals(listOf("${KR.text}|${first.id}|achat"), rankRows())
    }

    @Test
    @DisplayName("Un cadeau : l'acheteur paie, le destinataire reçoit le grade (source `cadeau`) ; ses propres possessions décident du refus, pas celles de l'acheteur")
    fun giftGoesToTheRecipient() {
        player(KR, "Kr")
        player(OTHER, "Autre")
        give(KR, first.gems + second.gems)

        buy(KR, first.id, n = 1, forXuid = OTHER)

        assertEquals(second.gems, gemmes(KR))
        assertEquals(0L, gemmes(OTHER))
        assertEquals(listOf("${OTHER.text}|${first.id}|cadeau"), rankRows())
        assertEquals(listOf("${KR.text}|${first.id}|${first.gems}|${OTHER.text}|${requestId(1)}"), purchaseRows())
        assertEquals(listOf("${KR.text}|-${first.gems}|${second.gems}|boutique|${first.id}"), ledger())
        assertEquals(RefusalCodes.ALREADY_OWNED, refusalOf { buy(KR, first.id, n = 2, forXuid = OTHER) }.reason)
        buy(KR, second.id, n = 3, forXuid = OTHER)
        assertEquals(setOf("${OTHER.text}|${first.id}|cadeau", "${OTHER.text}|${second.id}|cadeau"), rankRows().toSet())
        assertThrows(RequestRejected::class.java) { buy(KR, first.id, n = 4, forXuid = STRANGER) }
    }

    @Test
    @DisplayName("Le Pilier : un achat l'ouvre pour sa durée et rend ses Gemmes ; le suivant s'ajoute à sa fin s'il court, recommence à maintenant s'il est échu ; les mois s'additionnent")
    fun pilierExtendsFromTheEnd() {
        player(KR, "Kr")
        give(KR, pilier.gems * 3)
        val length = Duration.ofDays(pilier.days.toLong())

        val firstBuy = buy(KR, pilier.id, n = 1)
        assertEquals(pilier.gems * 2 + pilier.gemsReturned, firstBuy.gemmes)
        assertEquals(listOf("${KR.text}|${pilier.id}|${stamp(START)}|${stamp(START.plus(length))}|1"), subscriptionRows())
        assertEquals(
            listOf("${KR.text}|-${pilier.gems}|${pilier.gems * 2}|boutique|${pilier.id}", "${KR.text}|${pilier.gemsReturned}|${firstBuy.gemmes}|pilier|${pilier.id}"),
            ledger(),
        )

        clock.advance(Duration.ofDays(10))
        buy(KR, pilier.id, n = 2)
        assertEquals(
            listOf("${KR.text}|${pilier.id}|${stamp(START)}|${stamp(START.plus(length).plus(length))}|2"),
            subscriptionRows(),
            "il court : la prolongation part de sa fin",
        )

        clock.advance(length.multipliedBy(3))
        val restart = clock.instant()
        buy(KR, pilier.id, n = 3)
        assertEquals(
            listOf("${KR.text}|${pilier.id}|${stamp(restart)}|${stamp(restart.plus(length))}|3"),
            subscriptionRows(),
            "échu : il recommence à maintenant",
        )
        assertEquals(3, purchaseRows().size)
        assertEquals(gemmes(KR), MariaDbSupport.long("SELECT SUM(delta) FROM currency_ledger WHERE currency = 'GEMMES' AND xuid = ?", KR.text))
    }

    @Test
    @DisplayName("Le Perso demande le grade Ultime du destinataire : refusé sans lui, acheté avec, et un cadeau compte sur l'Ultime du destinataire")
    fun persoNeedsTheTopGrade() {
        player(KR, "Kr")
        player(OTHER, "Autre")
        give(KR, perso.gems * 2)
        assertEquals(RefusalCodes.PREVIOUS_RANK_REQUIRED, refusalOf { buy(KR, perso.id, n = 1) }.reason)
        MariaDbSupport.raw { c -> rules.grades.forEach { jdbc.grantRank(c, KR, it.id, "staff", START) } }

        assertEquals(RefusalCodes.PREVIOUS_RANK_REQUIRED, refusalOf { buy(KR, perso.id, n = 2, forXuid = OTHER) }.reason, "l'Ultime de l'acheteur ne suffit pas")
        buy(KR, perso.id, n = 3)

        assertEquals(listOf("${KR.text}|${perso.id}|${stamp(START)}|${stamp(START.plus(Duration.ofDays(perso.days.toLong())))}|1"), subscriptionRows())
        assertEquals(perso.gems + perso.gemsReturned, gemmes(KR))
    }

    @Test
    @DisplayName("Un requestId déjà servi à un achat est refusé (RequestRejected) sans payer une seconde fois")
    fun replayedRequestIdIsRejectedWithoutPaying() {
        player(KR, "Kr")
        give(KR, pilier.gems * 2)
        buy(KR, pilier.id, n = 1)
        val gems = gemmes(KR)

        assertThrows(RequestRejected::class.java) { buy(KR, pilier.id, n = 1) }

        assertEquals(gems, gemmes(KR))
        assertEquals(1, purchaseRows().size)
        assertEquals(1, subscriptionRows().size)
        assertEquals("1", MariaDbSupport.string("SELECT months_total FROM player_subscriptions WHERE xuid = ?", KR.text))
    }

    @Test
    @DisplayName("Un achat part en entier ou pas du tout : si le grade ou l'abonnement ne s'écrit pas, ni les Gemmes, ni le journal, ni l'achat ne restent")
    fun purchaseRollsBackAsOne() {
        player(KR, "Kr")
        give(KR, first.gems + pilier.gems)

        assertThrows(IllegalStateException::class.java) { buy(KR, first.id, n = 1, store = FailingGrant()) }
        assertThrows(IllegalStateException::class.java) { buy(KR, pilier.id, n = 2, store = FailingSave()) }

        assertEquals(first.gems + pilier.gems, gemmes(KR))
        assertEquals(emptyList<String?>(), ledger())
        assertEquals(emptyList<String?>(), purchaseRows())
        assertEquals(emptyList<String?>(), rankRows())
        assertEquals(emptyList<String?>(), subscriptionRows())
        buy(KR, first.id, n = 1)
        assertEquals(1, purchaseRows().size, "le même requestId sert encore : le premier essai n'a rien laissé")
    }

    @Test
    @DisplayName("Deux achats du même grade en même temps : un seul passe, les Gemmes ne sont retirées qu'une fois, un seul grade est écrit")
    fun concurrentPurchasesOfTheSameGradeChargeOnce() {
        player(KR, "Kr")
        give(KR, first.gems * 3)

        val results = inParallel(4) { index -> buy(KR, first.id, n = index + 1) }

        assertEquals(1, results.count { it.isSuccess }, "un seul achat réussit : $results")
        results.mapNotNull { it.exceptionOrNull() }.forEach { failure ->
            val allowed = (failure is Refusal && failure.reason == RefusalCodes.ALREADY_OWNED) || failure.isTransientConflict()
            assertTrue(allowed, "DEJA_POSSEDE ou un conflit à rejouer, pas : $failure")
        }
        assertEquals(first.gems * 2, gemmes(KR))
        assertEquals(1, ledger().size)
        assertEquals(1, purchaseRows().size)
        assertEquals(1, rankRows().size)
    }

    @Test
    @DisplayName("buyItem d'un cosmétique : le solde, le journal `boutique`, l'achat et le cosmétique (source `boutique`) sont écrits ensemble ; ni grade, ni boost, ni événement")
    fun buyCosmeticWritesEverythingTogether() {
        player(KR, "Kr")
        give(KR, cosmetic.gems + 10)

        val balances = buy(KR, cosmetic.id)

        assertEquals(10L, balances.gemmes)
        assertEquals(10L, gemmes(KR))
        assertEquals(listOf("${KR.text}|-${cosmetic.gems}|10|boutique|${cosmetic.id}"), ledger())
        assertEquals(listOf("${KR.text}|${cosmetic.id}|${cosmetic.gems}|-|${requestId(1)}"), purchaseRows())
        assertEquals(listOf("${KR.text}|${cosmetic.id}|${ShopService.SOURCE_COSMETIC}|${stamp(START)}"), cosmeticRows())
        assertEquals(emptyList<String?>(), rankRows())
        assertEquals(emptyList<String?>(), boostRows())
        assertEquals(0L, MariaDbSupport.count("game_events"))
    }

    @Test
    @DisplayName("buyItem d'un cosmétique offert : l'acheteur paie, le destinataire le reçoit (source `boutique`), l'achat note le destinataire")
    fun giftedCosmeticGoesToTheRecipient() {
        player(KR, "Kr")
        player(OTHER, "Autre")
        give(KR, cosmetic.gems)

        buy(KR, cosmetic.id, forXuid = OTHER)

        assertEquals(0L, gemmes(KR))
        assertEquals(0L, gemmes(OTHER))
        assertEquals(listOf("${OTHER.text}|${cosmetic.id}|${ShopService.SOURCE_COSMETIC}|${stamp(START)}"), cosmeticRows())
        assertEquals(listOf("${KR.text}|${cosmetic.id}|${cosmetic.gems}|${OTHER.text}|${requestId(1)}"), purchaseRows())
        assertEquals(listOf("${KR.text}|-${cosmetic.gems}|0|boutique|${cosmetic.id}"), ledger())
    }

    @Test
    @DisplayName("buyItem d'un cosmétique refusé (DEJA_POSSEDE, PAS_ASSEZ_DE_GEMMES avec ce qui manque) : rien n'est écrit")
    fun cosmeticRefusalsWriteNothing() {
        player(KR, "Kr")
        player(OTHER, "Autre")
        give(KR, cosmetic.gems * 2)
        buy(KR, cosmetic.id, n = 1)
        fun snapshot() = listOf(gemmes(KR), gemmes(OTHER), ledger(), purchaseRows(), cosmeticRows())
        val before = snapshot()

        assertEquals(RefusalCodes.ALREADY_OWNED, refusalOf { buy(KR, cosmetic.id, n = 2) }.reason)
        val poor = refusalOf { buy(OTHER, otherCosmetic.id, n = 3) }
        assertEquals(RefusalCodes.NOT_ENOUGH_GEMS, poor.reason)
        assertEquals(otherCosmetic.gems.toDouble(), poor.missing)

        assertEquals(before, snapshot())
    }

    @Test
    @DisplayName("buyItem du boost : le solde, le journal, l'achat, la ligne de server_boosts et l'événement BOOST_COLLECTIF (sans joueur) sont écrits ensemble ; il commence maintenant")
    fun buyBoostWritesEverythingTogether() {
        player(KR, "Kr")
        give(KR, boost.gems + 3)

        val balances = buy(KR, boost.id)

        val ends = START.plus(boostLength)
        assertEquals(3L, balances.gemmes)
        assertEquals(listOf("${KR.text}|-${boost.gems}|3|boutique|${boost.id}"), ledger())
        assertEquals(listOf("${KR.text}|${boost.id}|${boost.gems}|-|${requestId(1)}"), purchaseRows())
        assertEquals(listOf("${KR.text}|${stamp(START)}|${stamp(ends)}|${boost.bonusPercent}"), boostRows())
        assertEquals(listOf("-|Kr|${boost.bonusPercent}|${boost.minutes}|$START|$ends"), boostEvents())
        assertEquals(1L, MariaDbSupport.count("game_events"))
        assertEquals(emptyList<String?>(), cosmeticRows())
        assertEquals(emptyList<String?>(), rankRows())
        assertEquals(boost.bonusPercent, activeBoostPercent(START))
    }

    @Test
    @DisplayName("buyItem du boost pendant un autre boost : le second commence à la fin du premier, la hausse n'est jamais doublée ; échu, le suivant recommence à maintenant")
    fun boostBoughtDuringAnotherStartsAfterIt() {
        player(KR, "Kr")
        player(OTHER, "Autre")
        give(KR, boost.gems * 2)
        give(OTHER, boost.gems)

        buy(KR, boost.id, n = 1)
        clock.advance(Duration.ofSeconds(1))
        buy(OTHER, boost.id, n = 2)

        val firstEnd = START.plus(boostLength)
        val secondEnd = firstEnd.plus(boostLength)
        assertEquals(
            listOf(
                "${KR.text}|${stamp(START)}|${stamp(firstEnd)}|${boost.bonusPercent}",
                "${OTHER.text}|${stamp(firstEnd)}|${stamp(secondEnd)}|${boost.bonusPercent}",
            ),
            boostRows(),
        )
        assertEquals(
            listOf(
                "-|Kr|${boost.bonusPercent}|${boost.minutes}|$START|$firstEnd",
                "-|Autre|${boost.bonusPercent}|${boost.minutes}|$firstEnd|$secondEnd",
            ),
            boostEvents(),
        )
        assertEquals(boost.bonusPercent, activeBoostPercent(clock.instant()), "le premier court, une seule hausse")
        assertEquals(boost.bonusPercent, activeBoostPercent(firstEnd), "le second court, une seule hausse")
        assertEquals(0, activeBoostPercent(secondEnd))

        clock.advance(boostLength.multipliedBy(3))
        buy(KR, boost.id, n = 3)

        val now = clock.instant()
        assertEquals("${KR.text}|${stamp(now)}|${stamp(now.plus(boostLength))}|${boost.bonusPercent}", boostRows().last())
    }

    @Test
    @DisplayName("buyItem du boost : offert, il est refusé en requête invalide ; un solde trop juste le refuse en PAS_ASSEZ_DE_GEMMES ; rien n'est écrit")
    fun boostRefusalsWriteNothing() {
        player(KR, "Kr")
        player(OTHER, "Autre")
        give(KR, boost.gems - 1)

        assertThrows(RequestRejected::class.java) { buy(KR, boost.id, n = 1, forXuid = OTHER) }
        val poor = refusalOf { buy(KR, boost.id, n = 2) }

        assertEquals(RefusalCodes.NOT_ENOUGH_GEMS, poor.reason)
        assertEquals(1.0, poor.missing)
        assertEquals(boost.gems - 1, gemmes(KR))
        assertEquals(emptyList<String?>(), ledger())
        assertEquals(emptyList<String?>(), purchaseRows())
        assertEquals(emptyList<String?>(), boostRows())
        assertEquals(0L, MariaDbSupport.count("game_events"))
    }

    @Test
    @DisplayName("Un achat de cosmétique ou de boost part en entier ou pas du tout : si le cosmétique, le boost ou son événement ne s'écrit pas, ni les Gemmes, ni le journal, ni l'achat ne restent")
    fun cosmeticAndBoostPurchasesRollBackAsOne() {
        player(KR, "Kr")
        give(KR, cosmetic.gems + boost.gems)

        assertThrows(IllegalStateException::class.java) { buy(KR, cosmetic.id, n = 1, store = FailingCosmeticGrant()) }
        assertThrows(IllegalStateException::class.java) { buy(KR, boost.id, n = 2, store = FailingBoostInsert()) }
        assertThrows(IllegalStateException::class.java) { buy(KR, boost.id, n = 2, store = FailingPublish()) }

        assertEquals(cosmetic.gems + boost.gems, gemmes(KR))
        assertEquals(emptyList<String?>(), ledger())
        assertEquals(emptyList<String?>(), purchaseRows())
        assertEquals(emptyList<String?>(), cosmeticRows())
        assertEquals(emptyList<String?>(), boostRows(), "la ligne écrite avant l'échec de la publication est partie avec le reste")
        assertEquals(0L, MariaDbSupport.count("game_events"))
        buy(KR, cosmetic.id, n = 1)
        buy(KR, boost.id, n = 2)
        assertEquals(2, purchaseRows().size, "les mêmes requestId servent encore : les premiers essais n'ont rien laissé")
        assertEquals(1, boostRows().size)
    }

    @Test
    @DisplayName("Deux achats du même cosmétique en même temps : un seul passe, les Gemmes ne sont retirées qu'une fois, un seul cosmétique est écrit")
    fun concurrentPurchasesOfTheSameCosmeticChargeOnce() {
        player(KR, "Kr")
        give(KR, cosmetic.gems * 3)

        val results = inParallel(4) { index -> buy(KR, cosmetic.id, n = index + 1) }

        assertEquals(1, results.count { it.isSuccess }, "un seul achat réussit : $results")
        results.mapNotNull { it.exceptionOrNull() }.forEach { failure ->
            val allowed = (failure is Refusal && failure.reason == RefusalCodes.ALREADY_OWNED) || failure.isTransientConflict()
            assertTrue(allowed, "DEJA_POSSEDE ou un conflit à rejouer, pas : $failure")
        }
        assertEquals(cosmetic.gems * 2, gemmes(KR))
        assertEquals(1, ledger().size)
        assertEquals(1, purchaseRows().size)
        assertEquals(1, cosmeticRows().size)
    }

    @Test
    @DisplayName("Des boosts achetés en même temps se suivent sans se chevaucher : les fenêtres se touchent à partir de maintenant (les conflits transitoires se rejouent), chaque achat est payé et annoncé une fois")
    fun concurrentBoostPurchasesChain() {
        player(KR, "Kr")
        val count = 4
        give(KR, boost.gems * count)

        val results = inParallel(count) { index -> buy(KR, boost.id, n = index + 1) }

        results.mapNotNull { it.exceptionOrNull() }.forEach { assertTrue(it.isTransientConflict(), "un conflit à rejouer, pas : $it") }
        // Le client rejoue un conflit avec le même requestId : la transaction avait tout défait, rien n'est payé deux fois.
        results.forEachIndexed { index, result -> if (result.isFailure) buy(KR, boost.id, n = index + 1) }

        val expected = (0 until count).map { i ->
            "${stamp(START.plus(boostLength.multipliedBy(i.toLong())))}|${stamp(START.plus(boostLength.multipliedBy(i + 1L)))}"
        }
        assertEquals(expected, boostWindows(), "les fenêtres se touchent, sans trou ni recouvrement")
        assertEquals(0L, gemmes(KR))
        assertEquals(count, purchaseRows().size)
        assertEquals(count, ledger().size)
        assertEquals(count, boostEvents().size)
        for (i in 0 until count) {
            val inside = START.plus(boostLength.multipliedBy(i.toLong())).plusSeconds(1)
            assertEquals(boost.bonusPercent, activeBoostPercent(inside), "un seul boost court à la fois")
        }
        assertEquals(0, activeBoostPercent(START.plus(boostLength.multipliedBy(count.toLong()))))
    }

    // --- HTTP : catalog et buyItem avec le vrai moteur

    private fun dependencies(): Dependencies = Dependencies(
        database = database,
        definitions = definitions,
        apiKey = key,
        economy = engine,
        idempotency = Idempotency(database),
        sessionStore = sessions,
        clock = clock,
        economyFigures = EngineFigures(engine, sessions, clock, shop = ShopFigures(rules, jdbc, clock)),
        companyBootstrap = DefinitionsBootstrap(engine, definitions),
        shopStore = jdbc,
        deliveries = relief(),
        pendingTitles = PioneerTitle(jdbc, clock),
        serverPresence = presence,
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

    private suspend fun ApplicationTestBuilder.join(xuid: Xuid, gamertag: String): JoinResponse {
        val response = post("/v1/session/join", """{"xuid":"${xuid.text}","gamertag":"$gamertag"}""")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return SiloooJson.decodeFromString(JoinResponse.serializer(), response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.buyItem(actor: Xuid, itemId: String, n: Int = 1, forXuid: Xuid? = null): HttpResponse {
        val gift = forXuid?.let { ""","forXuid":"${it.text}"""" } ?: ""
        return post("/v1/shop/purchases", """{"requestId":"${requestId(n)}","actor":"${actor.text}","itemId":"$itemId"$gift}""")
    }

    private suspend fun ApplicationTestBuilder.catalog(actor: Xuid): List<CatalogItem> {
        val response = client.get("/v1/shop/catalog?actor=${actor.text}") { header(SERVER_KEY_HEADER, key) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return SiloooJson.decodeFromString(ListSerializer(CatalogItem.serializer()), response.bodyAsText())
    }

    @Test
    @DisplayName("HTTP : catalog, puis buyItem (solde rendu, base écrite) ; le même requestId rejoué rend la même réponse sans payer deux fois ; le catalogue suit l'achat")
    fun buyItemOverHttpIsIdempotent() = withApp {
        join(KR, "Kr")
        give(KR, first.gems + 5)
        val before = catalog(KR)
        assertEquals((rules.grades + rules.subscriptions + rules.cosmetics + rules.boost).map { it.id }, before.map { it.itemId })
        assertTrue(before.single { it.itemId == first.id }.available)
        assertFalse(before.single { it.itemId == second.id }.available)

        val bought = buyItem(KR, first.id)
        val replay = buyItem(KR, first.id)

        assertEquals(HttpStatusCode.OK, bought.status)
        assertEquals(HttpStatusCode.OK, replay.status)
        val balances = SiloooJson.decodeFromString(Balances.serializer(), bought.bodyAsText())
        assertEquals(5L, balances.gemmes)
        assertEquals(balances, SiloooJson.decodeFromString(Balances.serializer(), replay.bodyAsText()), "la même réponse")
        assertEquals(5L, gemmes(KR))
        assertEquals(1, purchaseRows().size)
        assertEquals(1, ledger().size)
        assertEquals(listOf("${KR.text}|${first.id}|achat"), rankRows())
        val after = catalog(KR)
        assertTrue(after.single { it.itemId == first.id }.owned)
        assertFalse(after.single { it.itemId == first.id }.available)
        assertTrue(after.single { it.itemId == second.id }.available)
        assertEquals(first.id, join(KR, "Kr").rank, "le join montre le grade acheté")
    }

    @Test
    @DisplayName("HTTP : buyItem refuse en 409 (PAS_ASSEZ_DE_GEMMES avec `missing`) et en 400 (article inconnu, destinataire inconnu, acheteur inconnu), sans rien écrire")
    fun buyItemRefusalsOverHttp() = withApp {
        join(KR, "Kr")
        give(KR, first.gems - 3)

        val poor = buyItem(KR, first.id, n = 1)
        assertEquals(HttpStatusCode.Conflict, poor.status)
        val refused = SiloooJson.decodeFromString(RefusedBody.serializer(), poor.bodyAsText())
        assertEquals(RefusalCodes.NOT_ENOUGH_GEMS, refused.reason)
        assertEquals(3.0, refused.missing)
        assertEquals(HttpStatusCode.BadRequest, buyItem(KR, "cosmetique-inconnu", n = 2).status)
        assertEquals(HttpStatusCode.BadRequest, buyItem(KR, first.id, n = 3, forXuid = STRANGER).status)
        assertEquals(HttpStatusCode.BadRequest, buyItem(STRANGER, first.id, n = 4).status)

        assertEquals(first.gems - 3, gemmes(KR))
        assertEquals(emptyList<String?>(), purchaseRows())
        assertEquals(emptyList<String?>(), ledger())
        assertEquals(emptyList<String?>(), rankRows())
    }

    @Test
    @DisplayName("HTTP : un cadeau de Pilier rend ses Gemmes au destinataire, et le catalogue du destinataire le montre acquis")
    fun giftPilierOverHttp() = withApp {
        join(KR, "Kr")
        join(OTHER, "Autre")
        give(KR, pilier.gems)

        val gift = buyItem(KR, pilier.id, forXuid = OTHER)

        assertEquals(HttpStatusCode.OK, gift.status, gift.bodyAsText())
        assertEquals(pilier.gemsReturned, gemmes(OTHER), "les Gemmes rendues vont au destinataire")
        assertEquals(0L, gemmes(KR))
        assertTrue(catalog(OTHER).single { it.itemId == pilier.id }.owned)
        assertFalse(catalog(KR).single { it.itemId == pilier.id }.owned)
        assertEquals(listOf("${OTHER.text}|${pilier.id}|${stamp(START)}|${stamp(START.plus(Duration.ofDays(pilier.days.toLong())))}|1"), subscriptionRows())
    }

    @Test
    @DisplayName("HTTP : le join d'un joueur dont une commande attendait son pseudo lui verse les Gemmes avant de répondre (le module câble le relevé)")
    fun joinOverHttpRelievesTheDelivery() = withApp {
        addDelivery("ord-1", "Nouveau", 120)

        val joined = join(KR, "Nouveau")

        assertEquals(120L, joined.balances.gemmes)
        assertEquals(listOf("ord-1|${KR.text}|versee"), deliveryRows())
        assertEquals(listOf("${KR.text}|120|120|livraison|ord-1"), ledger())
        assertEquals(listOf("${KR.text}|GEMMES_RECUES|ord-1|120|120"), events())
    }

    @Test
    @DisplayName("HTTP : buyItem d'un cosmétique puis du boost : soldes, catalogue et join suivent ; le boost offert est refusé en 400 ; la base garde le boost qui court")
    fun buyCosmeticAndBoostOverHttp() = withApp {
        join(KR, "Kr")
        join(OTHER, "Autre")
        give(KR, cosmetic.gems + boost.gems + 5)

        val cosmeticBought = buyItem(KR, cosmetic.id, n = 1)
        val boostBought = buyItem(KR, boost.id, n = 2)
        val gift = buyItem(KR, boost.id, n = 3, forXuid = OTHER)

        assertEquals(HttpStatusCode.OK, cosmeticBought.status, cosmeticBought.bodyAsText())
        assertEquals(HttpStatusCode.OK, boostBought.status, boostBought.bodyAsText())
        assertEquals(5L, SiloooJson.decodeFromString(Balances.serializer(), boostBought.bodyAsText()).gemmes)
        assertEquals(HttpStatusCode.BadRequest, gift.status, gift.bodyAsText())
        val after = catalog(KR)
        assertTrue(after.single { it.itemId == cosmetic.id }.owned)
        assertFalse(after.single { it.itemId == cosmetic.id }.available)
        assertFalse(after.single { it.itemId == boost.id }.owned)
        assertTrue(after.single { it.itemId == boost.id }.available, "le boost se rachète à volonté")
        assertEquals(listOf(cosmetic.id), join(KR, "Kr").cosmetics)
        assertEquals(emptyList<String>(), join(OTHER, "Autre").cosmetics)
        assertEquals(2, purchaseRows().size)
        assertEquals(1, boostRows().size)
        assertEquals(boost.bonusPercent, activeBoostPercent(START))
    }

    @Test
    @DisplayName("HTTP : le join d'un pionnier inscrit sur le site lui donne le titre Pionnier (dans la réponse, une seule annonce) ; un joueur non inscrit n'a rien")
    fun joinOverHttpGivesThePioneerTitle() = withApp {
        register("Kr")

        val joined = join(KR, "Kr")
        val again = join(KR, "Kr")
        val other = join(OTHER, "Autre")

        assertEquals(listOf(ShopRules.PIONEER_TITLE_ID), joined.cosmetics)
        assertEquals(listOf(ShopRules.PIONEER_TITLE_ID), again.cosmetics)
        assertEquals(emptyList<String>(), other.cosmetics)
        assertEquals(listOf("${KR.text}|${ShopRules.PIONEER_TITLE_ID}|${PioneerTitle.SOURCE_SITE}|${stamp(START)}"), cosmeticRows())
        assertEquals(listOf("${KR.text}|${PioneerTitle.ACTION}|${ShopRules.PIONEER_TITLE_ID}"), announcements())
    }
}
