package com.silooo.core.db

import com.silooo.core.api.RefusalCodes
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Les soldes et leur journal sur la vraie MariaDB. Ignoré sans Docker. */
class CurrencyLedgerSqlTest {
    private val database get() = MariaDbSupport.database
    private val kr = Xuid.parse("2535428000000001")!!

    @BeforeEach
    fun prepare() {
        MariaDbSupport.prepare()
        MariaDbSupport.insertPlayer(kr.text)
        MariaDbSupport.update("INSERT INTO player_balances (xuid) VALUES (?)", kr.number)
    }

    private fun balance(column: String = "lingots") =
        MariaDbSupport.long("SELECT $column FROM player_balances WHERE xuid = ?", kr.number)

    private fun ledgerRows() = MariaDbSupport.count("currency_ledger")

    private suspend fun credit(currency: Currency, delta: Long, reason: String = "test", refId: String? = null) =
        database.transaction { creditBalance(it, kr, currency, delta, reason, refId) }

    @Test
    @DisplayName("Un gain augmente le solde et écrit une ligne de journal avec le solde après")
    fun creditWritesBalanceAndLedger() = runBlocking {
        assertEquals(150L, credit(Currency.LINGOTS, 150, "mission", "mission-12"))
        assertEquals(130L, credit(Currency.LINGOTS, -20, "zone", null))

        assertEquals(130L, balance())
        assertEquals(2L, MariaDbSupport.long("SELECT version FROM player_balances WHERE xuid = ?", kr.number))
        assertEquals(
            listOf("LINGOTS|150|150|mission|mission-12", "LINGOTS|-20|130|zone|null"),
            MariaDbSupport.strings(
                "SELECT CONCAT_WS('|', currency, delta, balance, reason, IFNULL(ref_id, 'null')) " +
                    "FROM currency_ledger WHERE xuid = ? ORDER BY id",
                kr.number,
            ),
        )
    }

    @Test
    @DisplayName("Chaque monnaie écrit dans sa colonne et sous son nom dans le journal")
    fun eachCurrencyHasItsColumn() = runBlocking {
        credit(Currency.LINGOTS, 1)
        credit(Currency.GEMMES, 2)
        credit(Currency.JETONS, 3)
        credit(Currency.CLES, 4)

        assertEquals(listOf(1L, 2L, 3L, 4L), listOf(balance("lingots"), balance("gemmes"), balance("jetons"), balance("cles")))
        assertEquals(
            listOf("LINGOTS", "GEMMES", "JETONS", "CLES"),
            MariaDbSupport.strings("SELECT currency FROM currency_ledger ORDER BY id"),
        )
    }

    @Test
    @DisplayName("Dépenser exactement le solde est permis : il tombe à zéro")
    fun spendingEverythingIsAllowed() = runBlocking {
        credit(Currency.GEMMES, 40)
        assertEquals(0L, credit(Currency.GEMMES, -40))
        assertEquals(0L, balance("gemmes"))
    }

    @Test
    @DisplayName("Un retrait qui passerait sous zéro est refusé AVANT d'écrire : ni solde ni journal ne bougent")
    fun overdraftIsRefusedBeforeWriting() = runBlocking {
        credit(Currency.LINGOTS, 30)
        val ledgerBefore = ledgerRows()

        val refused = assertThrows<InsufficientBalance> { runBlocking { credit(Currency.LINGOTS, -100) } }

        assertEquals(Currency.LINGOTS, refused.currency)
        assertEquals(70L, refused.missing)
        assertEquals(30L, balance())
        assertEquals(ledgerBefore, ledgerRows())
        val refusal = refused.toRefusal()
        assertEquals(RefusalCodes.NOT_ENOUGH_LINGOTS, refusal.reason)
        assertEquals(70.0, refusal.missing)
        assertEquals(409, refusal.status.value)
    }

    @Test
    @DisplayName("Le refus des Gemmes porte PAS_ASSEZ_DE_GEMMES ; jetons et clés n'ont pas de code dans le contrat")
    fun refusalCodesPerCurrency() {
        assertEquals(RefusalCodes.NOT_ENOUGH_GEMS, InsufficientBalance(Currency.GEMMES, 5).toRefusal().reason)
        assertThrows<IllegalStateException> { InsufficientBalance(Currency.JETONS, 5).toRefusal() }
        assertThrows<IllegalStateException> { InsufficientBalance(Currency.CLES, 5).toRefusal() }
    }

    @Test
    @DisplayName("Le solde et le journal partent ensemble ou pas du tout : une annulation efface les deux")
    fun rollbackErasesBoth() = runBlocking {
        credit(Currency.LINGOTS, 10)
        val ledgerBefore = ledgerRows()

        assertThrows<IllegalStateException> {
            runBlocking {
                database.transaction { connection ->
                    creditBalance(connection, kr, Currency.LINGOTS, 500, "prestige")
                    creditBalance(connection, kr, Currency.GEMMES, 5, "prestige")
                    error("la suite de l'action échoue")
                }
            }
        }

        assertEquals(10L, balance())
        assertEquals(0L, balance("gemmes"))
        assertEquals(ledgerBefore, ledgerRows())
    }

    @Test
    @DisplayName("Un delta à zéro ne fait rien : pas de ligne de journal, pas de changement de version")
    fun zeroDeltaIsANoOp() = runBlocking {
        credit(Currency.LINGOTS, 5)
        assertEquals(5L, credit(Currency.LINGOTS, 0))
        assertEquals(1L, ledgerRows())
        assertEquals(1L, MariaDbSupport.long("SELECT version FROM player_balances WHERE xuid = ?", kr.number))
    }

    @Test
    @DisplayName("Un joueur sans ligne de soldes en reçoit une à zéro ; un joueur inconnu est une erreur")
    fun balanceRowIsCreatedOnDemand() = runBlocking {
        MariaDbSupport.insertPlayer("2535428000000002")
        val other = Xuid.parse("2535428000000002")!!
        assertEquals(25L, database.transaction { creditBalance(it, other, Currency.LINGOTS, 25, "mission") })

        val ghost = Xuid.parse("2535428000000099")!!
        assertThrows<IllegalArgumentException> {
            runBlocking { database.transaction { creditBalance(it, ghost, Currency.LINGOTS, 1, "mission") } }
        }
        assertEquals(0L, MariaDbSupport.long("SELECT COUNT(*) FROM player_balances WHERE xuid = ?", ghost.number))
    }

    @Test
    @DisplayName("Un XUID au-delà de 2^63 passe par le journal sans perdre un chiffre")
    fun hugeXuid() = runBlocking {
        val huge = Xuid.parse("18446744073709551615")!!
        MariaDbSupport.insertPlayer(huge.text)
        database.transaction { creditBalance(it, huge, Currency.LINGOTS, 7, "mission") }
        assertEquals("18446744073709551615", MariaDbSupport.string("SELECT CAST(xuid AS CHAR) FROM currency_ledger WHERE delta = 7"))
    }

    @Test
    @DisplayName("Les paramètres hors colonne sont refusés avant toute écriture")
    fun parametersAreChecked() = runBlocking {
        assertThrows<IllegalArgumentException> { runBlocking { credit(Currency.LINGOTS, 1, reason = "") } }
        assertThrows<IllegalArgumentException> { runBlocking { credit(Currency.LINGOTS, 1, reason = "x".repeat(33)) } }
        assertThrows<IllegalArgumentException> { runBlocking { credit(Currency.LINGOTS, 1, refId = "r".repeat(65)) } }
        assertEquals(0L, ledgerRows())

        credit(Currency.LINGOTS, Long.MAX_VALUE)
        assertThrows<IllegalArgumentException> { runBlocking { credit(Currency.LINGOTS, 1) } }
        assertEquals(Long.MAX_VALUE, balance(), "le débordement n'écrit rien")
    }

    @Test
    @DisplayName("Le CHECK de la base reste le filet : une écriture directe en négatif est refusée par MariaDB")
    fun checkConstraintIsTheSafetyNet() {
        val error = assertThrows<SQLException> {
            MariaDbSupport.update("UPDATE player_balances SET lingots = -1 WHERE xuid = ?", kr.number)
        }
        assertTrue(error.message!!.contains("ck_balances_positive", ignoreCase = true), error.message)
        assertEquals(0L, balance())
    }

    @Test
    @DisplayName("Dix dépenses de 30 en même temps sur 100 Lingots : trois passent, sept sont refusées, le solde finit à 10")
    fun concurrentSpendingNeverOverdraws() = runBlocking {
        credit(Currency.LINGOTS, 100)
        val go = CountDownLatch(1)
        val outcomes = (1..10).map {
            async(Dispatchers.IO) {
                go.await(30, TimeUnit.SECONDS)
                try {
                    database.transaction { creditBalance(it, kr, Currency.LINGOTS, -30, "marche") }
                    true
                } catch (e: InsufficientBalance) {
                    false
                }
            }
        }
        go.countDown()
        val results = outcomes.awaitAll()

        assertEquals(3, results.count { it })
        assertEquals(10L, balance())
        assertEquals(4L, ledgerRows(), "le gain de départ et les trois dépenses")
        assertEquals(
            listOf(70L, 40L, 10L),
            MariaDbSupport.longs("SELECT balance FROM currency_ledger WHERE delta < 0 ORDER BY id"),
            "chaque ligne garde le solde juste après SON mouvement",
        )
        assertNull(MariaDbSupport.string("SELECT ref_id FROM currency_ledger WHERE delta > 0"))
    }
}
