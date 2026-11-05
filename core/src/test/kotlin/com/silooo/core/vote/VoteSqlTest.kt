package com.silooo.core.vote

import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.db.MariaDbSupport
import com.silooo.core.db.Xuid
import com.silooo.core.events.GameEventKind
import java.math.BigInteger
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Les votes sur la vraie MariaDB : ce que les tests de règles ([VoteServiceTest]) ne voient pas, c'est-à-dire ce qui est vraiment
 * écrit dans `server_votes`, `player_balances`, `currency_ledger` et `game_events`, dans une seule transaction, et ce que `DATE()`,
 * `COUNT` et `FOR UPDATE` font des jours UTC et des lignes en attente. Ignoré sans Docker (voir [MariaDbSupport]) : la CI les rejoue.
 *
 * Les Lingots attendus sortent de `definitions/vote.json`, jamais d'un littéral recopié.
 */
class VoteSqlTest {
    private val database get() = MariaDbSupport.database
    private val definitions = TestDefinitions.loaded
    private val rules = definitions.vote
    private val clock = MutableClock(Instant.parse("2026-11-05T12:00:00Z"))
    private var present: Set<Xuid> = emptySet()
    private val service = VoteService(definitions, JdbcVoteStore(), clock) { present }

    private val kr = "2535428000000001"
    private val other = "2535428000000002"
    private val absent = "2535428000000003"
    private val ghost = "2535428000000099"

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    // --- outils

    private fun xuid(text: String): Xuid = checkNotNull(Xuid.parse(text))

    private fun receive(gamertag: String, site: String = "Hubyy"): VoteReceipt =
        runBlocking { service.receive(database, IncomingVote(gamertag, site)) }

    private fun nextDay() {
        clock.advance(Duration.ofDays(1))
    }

    private data class DbVote(val id: Long, val xuid: String?, val gamertag: String, val site: String, val votedAt: LocalDateTime, val rewardedAt: LocalDateTime?)

    private fun dbVotes(): List<DbVote> = MariaDbSupport.raw { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id, CAST(xuid AS CHAR), gamertag, site, voted_at, rewarded_at FROM server_votes ORDER BY id").use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            DbVote(
                                rows.getLong(1),
                                rows.getString(2),
                                rows.getString(3),
                                rows.getString(4),
                                rows.getObject(5, LocalDateTime::class.java),
                                rows.getObject(6, LocalDateTime::class.java),
                            ),
                        )
                    }
                }
            }
        }
    }

    private data class DbLedger(val xuid: String, val currency: String, val delta: Long, val balance: Long, val reason: String, val refId: String?)

    private fun dbLedger(): List<DbLedger> = MariaDbSupport.raw { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT CAST(xuid AS CHAR), currency, delta, balance, reason, ref_id FROM currency_ledger ORDER BY id").use { rows ->
                buildList {
                    while (rows.next()) add(DbLedger(rows.getString(1), rows.getString(2), rows.getLong(3), rows.getLong(4), rows.getString(5), rows.getString(6)))
                }
            }
        }
    }

    private data class DbEvent(val xuid: String?, val kind: String, val payload: JsonObject)

    private fun dbEvents(): List<DbEvent> = MariaDbSupport.raw { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT CAST(xuid AS CHAR), kind, payload FROM game_events ORDER BY id").use { rows ->
                buildList {
                    while (rows.next()) add(DbEvent(rows.getString(1), rows.getString(2), Json.parseToJsonElement(rows.getString(3)).jsonObject))
                }
            }
        }
    }

    private fun lingotsOf(xuid: String): Long =
        MariaDbSupport.longs("SELECT lingots FROM player_balances WHERE xuid = ?", BigInteger(xuid)).singleOrNull() ?: 0L

    private fun seen(xuid: String, at: Instant) {
        MariaDbSupport.update("UPDATE players SET last_seen_at = ? WHERE xuid = ?", LocalDateTime.ofInstant(at, ZoneOffset.UTC), BigInteger(xuid))
    }

    // --- un vote d'un joueur connu

    @Test
    @DisplayName("Un vote d'un joueur connu : la ligne est versée à son nom, un mouvement `vote` référencé par l'id, un solde, une annonce au joueur")
    fun knownPlayerVoteIsWrittenEverywhere() {
        MariaDbSupport.insertPlayer(kr, "Kr")

        val receipt = receive("Kr")

        assertEquals(xuid(kr), receipt.xuid)
        val row = dbVotes().single()
        assertEquals(receipt.voteId, row.id)
        assertEquals(kr, row.xuid)
        assertEquals("Kr", row.gamertag)
        assertEquals("hubyy", row.site)
        assertEquals(LocalDateTime.of(2026, 11, 5, 12, 0), row.votedAt)
        assertNotNull(row.rewardedAt)
        assertEquals(rules.reward.lingotsPerVote, lingotsOf(kr))
        assertEquals(
            listOf(DbLedger(kr, "LINGOTS", rules.reward.lingotsPerVote, rules.reward.lingotsPerVote, "vote", receipt.voteId.toString())),
            dbLedger(),
        )
        val event = dbEvents().single()
        assertEquals(kr, event.xuid)
        assertEquals(GameEventKind.ANNONCE.name, event.kind)
        assertEquals("vote", event.payload.getValue("action").jsonPrimitive.content)
        assertEquals(rules.reward.lingotsPerVote, event.payload.getValue("rewardLingots").jsonPrimitive.long)
        assertEquals(1, event.payload.getValue("streak").jsonPrimitive.int)
    }

    @Test
    @DisplayName("Rattraper ou relire un vote déjà versé ne verse rien de plus : le vote n'est crédité qu'une fois")
    fun aRewardedVoteIsNeverCreditedTwice() {
        MariaDbSupport.insertPlayer(kr, "Kr")
        receive("Kr")

        repeat(3) { runBlocking { database.transaction { service.relieve(it, xuid(kr), "Kr") } } }

        assertEquals(rules.reward.lingotsPerVote, lingotsOf(kr))
        assertEquals(1, dbLedger().size)
        assertEquals(1, dbEvents().size)
    }

    @Test
    @DisplayName("Le pseudo se retrouve sans tenir compte de la casse, et le joueur vu en dernier gagne")
    fun gamertagLookupIgnoresCaseAndTakesTheLatest() {
        MariaDbSupport.insertPlayer(kr, "Kr")
        MariaDbSupport.insertPlayer(other, "KR")
        seen(kr, Instant.parse("2026-11-04T10:00:00Z"))
        seen(other, Instant.parse("2026-11-05T09:00:00Z"))

        val receipt = receive("kr")

        assertEquals(xuid(other), receipt.xuid)
        assertEquals(other, dbVotes().single().xuid)
        assertEquals(0L, lingotsOf(kr))
        assertEquals(rules.reward.lingotsPerVote, lingotsOf(other))
    }

    @Test
    @DisplayName("Un pseudo et un site de 32 caractères entrent dans les colonnes, accents compris")
    fun longestNamesFitTheColumns() {
        val name = "é".repeat(Votifier2.MAX_NAME_LENGTH)
        val site = "s".repeat(Votifier2.MAX_NAME_LENGTH)

        receive(name, site)

        val row = dbVotes().single()
        assertEquals(name, row.gamertag)
        assertEquals(site, row.site)
    }

    // --- un pseudo inconnu et le join

    @Test
    @DisplayName("Un pseudo inconnu : la ligne reste en attente (sans joueur), ni Lingots ni annonce ; le vote compte dans la journée")
    fun unknownGamertagWaits() {
        val receipt = receive("Inconnu")

        assertNull(receipt.xuid)
        val row = dbVotes().single()
        assertNull(row.xuid)
        assertNull(row.rewardedAt)
        assertTrue(dbLedger().isEmpty())
        assertTrue(dbEvents().isEmpty())
        assertEquals(1, runBlocking { database.transaction { service.today(it, null) } }.votes)
    }

    @Test
    @DisplayName("Le join rattrape le vote du pseudo une fois que le joueur existe : ligne versée, Lingots, annonce, une seule fois")
    fun joinCatchesUpAWaitingVote() {
        val receipt = receive("Kr")
        MariaDbSupport.insertPlayer(kr, "Kr")

        repeat(2) { runBlocking { database.transaction { service.relieve(it, xuid(kr), "Kr") } } }

        val row = dbVotes().single()
        assertEquals(kr, row.xuid)
        assertNotNull(row.rewardedAt)
        assertEquals(rules.reward.lingotsPerVote, lingotsOf(kr))
        assertEquals(listOf(DbLedger(kr, "LINGOTS", rules.reward.lingotsPerVote, rules.reward.lingotsPerVote, "vote", receipt.voteId.toString())), dbLedger())
        val event = dbEvents().single()
        assertEquals(kr, event.xuid)
        assertEquals("vote", event.payload.getValue("action").jsonPrimitive.content)
    }

    @Test
    @DisplayName("Un pseudo porté par un joueur vu plus récemment n'est pas celui du premier : les votes attendent le bon")
    fun joinSkipsAGamertagAnotherPlayerNowCarries() {
        val receipt = receive("Kr")
        MariaDbSupport.insertPlayer(kr, "Kr")
        MariaDbSupport.insertPlayer(other, "Kr")
        seen(kr, Instant.parse("2026-11-04T10:00:00Z"))
        seen(other, Instant.parse("2026-11-05T11:00:00Z"))

        runBlocking { database.transaction { service.relieve(it, xuid(kr), "Kr") } }
        assertNull(dbVotes().single().rewardedAt)
        assertTrue(dbLedger().isEmpty())

        runBlocking { database.transaction { service.relieve(it, xuid(other), "Kr") } }
        assertEquals(other, dbVotes().single().xuid)
        assertEquals(receipt.voteId.toString(), dbLedger().single().refId)
        assertEquals(0L, lingotsOf(kr))
        assertEquals(rules.reward.lingotsPerVote, lingotsOf(other))
    }

    @Test
    @DisplayName("Deux join du même joueur en même temps : le vote en attente n'est versé qu'une fois (FOR UPDATE et marqueur)")
    fun concurrentJoinsCreditOnce() = runBlocking {
        receive("Kr")
        MariaDbSupport.insertPlayer(kr, "Kr")

        (1..6).map {
            async(Dispatchers.IO) { database.transaction { service.relieve(it, xuid(kr), "Kr") } }
        }.awaitAll()

        assertEquals(rules.reward.lingotsPerVote, lingotsOf(kr))
        assertEquals(1, dbLedger().size)
        assertEquals(1, dbEvents().size)
    }

    // --- tout ou rien

    private class FailingPublish(private val inner: VoteStore) : VoteStore by inner {
        override fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long =
            throw IllegalStateException("panne de l'écriture de l'événement")
    }

    @Test
    @DisplayName("Une panne après le crédit annule tout : la réception ne laisse ni ligne, ni Lingots, ni mouvement")
    fun receptionIsAllOrNothing() {
        MariaDbSupport.insertPlayer(kr, "Kr")
        val failing = VoteService(definitions, FailingPublish(JdbcVoteStore()), clock) { present }

        assertThrows<IllegalStateException> { runBlocking { failing.receive(database, IncomingVote("Kr", "Hubyy")) } }

        assertEquals(0L, MariaDbSupport.count("server_votes"))
        assertEquals(0L, MariaDbSupport.count("currency_ledger"))
        assertEquals(0L, MariaDbSupport.count("game_events"))
        assertEquals(0L, lingotsOf(kr))
    }

    @Test
    @DisplayName("Une panne pendant le rattrapage du join annule le versement : la ligne reste en attente et le join suivant la verse")
    fun catchUpIsAllOrNothing() {
        receive("Kr")
        MariaDbSupport.insertPlayer(kr, "Kr")
        val failing = VoteService(definitions, FailingPublish(JdbcVoteStore()), clock) { present }

        assertThrows<IllegalStateException> { runBlocking { database.transaction { failing.relieve(it, xuid(kr), "Kr") } } }

        assertNull(dbVotes().single().rewardedAt, "le marqueur est annulé avec le reste")
        assertNull(dbVotes().single().xuid)
        assertEquals(0L, MariaDbSupport.count("currency_ledger"))
        assertEquals(0L, lingotsOf(kr))

        runBlocking { database.transaction { service.relieve(it, xuid(kr), "Kr") } }
        assertEquals(rules.reward.lingotsPerVote, lingotsOf(kr))
    }

    // --- la série

    @Test
    @DisplayName("La série sur plusieurs jours UTC : le bonus au serie.jours-ième jour de suite, pas avant ; un jour sans vote la remet à zéro")
    fun streakAcrossDays() {
        MariaDbSupport.insertPlayer(kr, "Kr")

        val grants = mutableListOf<VoteGrant>()
        for (day in 1..rules.streak.days) {
            grants += checkNotNull(receive("Kr").grant)
            if (day < rules.streak.days) nextDay()
        }

        assertEquals((1..rules.streak.days).toList(), grants.map { it.streak })
        assertEquals(List(rules.streak.days - 1) { 0L } + rules.streak.bonusLingots, grants.map { it.streakBonusLingots })
        assertEquals(rules.streak.days * rules.reward.lingotsPerVote + rules.streak.bonusLingots, lingotsOf(kr))
        assertEquals(rules.streak.days, dbLedger().size)

        nextDay()
        nextDay()
        assertEquals(1, receive("Kr").grant!!.streak, "un jour sans vote")
    }

    @Test
    @DisplayName("Le jour est le jour UTC : un vote à 23 h 59 min 59,999 s et un autre à minuit sont de deux jours, pour le décompte et pour la série")
    fun dayBoundaryInSql() {
        MariaDbSupport.insertPlayer(kr, "Kr")
        clock.now = Instant.parse("2026-11-05T23:59:59.999Z")
        val before = receive("Kr")
        clock.now = Instant.parse("2026-11-06T00:00:00Z")
        val after = receive("Kr")

        assertEquals(1, before.rankToday)
        assertEquals(1, after.rankToday)
        assertEquals(2, after.grant!!.streak)
        val today = runBlocking { database.transaction { service.today(it, xuid(kr)) } }
        assertEquals("2026-11-06", today.day)
        assertEquals(1, today.votes)
        assertEquals(1, today.mine.votes)
        assertEquals(2, today.mine.streak)
    }

    // --- la Vote party

    @Test
    @DisplayName("La Vote party va aux joueurs présents que players connaît, une fois ce jour-là, avec une annonce pour tout le serveur")
    fun partyGoesToPresentPlayersOnce() {
        MariaDbSupport.insertPlayer(kr, "Kr")
        MariaDbSupport.insertPlayer(other, "Autre")
        MariaDbSupport.insertPlayer(absent, "Absent")
        present = setOf(xuid(other), xuid(kr), xuid(ghost))

        repeat(rules.party.votesPerDay - 1) { receive("Inconnu$it") }
        assertTrue(dbLedger().isEmpty())
        val goal = receive("Dernier")
        repeat(5) { receive("Encore$it") }

        val party = checkNotNull(goal.party)
        assertEquals(listOf(xuid(kr), xuid(other)), party.players)
        val ledger = dbLedger()
        assertEquals(
            listOf(
                DbLedger(kr, "LINGOTS", rules.party.lingotsPerPlayer, rules.party.lingotsPerPlayer, "vote-party", "2026-11-05"),
                DbLedger(other, "LINGOTS", rules.party.lingotsPerPlayer, rules.party.lingotsPerPlayer, "vote-party", "2026-11-05"),
            ),
            ledger,
        )
        assertEquals(0L, lingotsOf(absent))
        val event = dbEvents().single()
        assertNull(event.xuid, "pour tout le serveur")
        assertEquals("voteParty", event.payload.getValue("action").jsonPrimitive.content)
        assertEquals(2, event.payload.getValue("players").jsonPrimitive.int)
        assertEquals(rules.party.votesPerDay + 5L, MariaDbSupport.count("server_votes"))
    }

    @Test
    @DisplayName("Des réceptions en parallèle, sur plusieurs connexions du pool, ne lancent la fête qu'une fois")
    fun concurrentReceptionsStartThePartyOnce() = runBlocking {
        MariaDbSupport.insertPlayer(kr, "Kr")
        present = setOf(xuid(kr))

        val receipts = (1..(rules.party.votesPerDay + 10)).map { n ->
            async(Dispatchers.IO) { service.receive(database, IncomingVote("Inconnu$n", "Hubyy")) }
        }.awaitAll()

        assertEquals(1, receipts.count { it.party != null })
        assertEquals(rules.party.lingotsPerPlayer, lingotsOf(kr))
        assertEquals(1, dbLedger().count { it.reason == "vote-party" })
        assertEquals(1, dbEvents().count { it.payload.getValue("action").jsonPrimitive.content == "voteParty" })
        assertEquals((1..receipts.size).toList(), receipts.map { it.rankToday }.sorted())
    }

    @Test
    @DisplayName("Le lendemain, la fête revient quand l'objectif est de nouveau atteint : une ligne de plus par joueur présent")
    fun partyComesBackTheNextDay() {
        MariaDbSupport.insertPlayer(kr, "Kr")
        present = setOf(xuid(kr))
        repeat(rules.party.votesPerDay) { receive("Inconnu$it") }
        nextDay()
        repeat(rules.party.votesPerDay) { receive("Autre$it") }

        assertEquals(listOf("2026-11-05", "2026-11-06"), dbLedger().map { it.refId })
        assertEquals(2 * rules.party.lingotsPerPlayer, lingotsOf(kr))
        assertFalse(dbEvents().isEmpty())
    }

    // --- todayVotes

    @Test
    @DisplayName("today : le total du jour, les votes du joueur et sa série, lus dans la base, sans rien écrire")
    fun todayReadsTheDatabase() {
        MariaDbSupport.insertPlayer(kr, "Kr")
        MariaDbSupport.insertPlayer(other, "Autre")
        receive("Kr")
        nextDay()
        receive("Kr")
        receive("Kr")
        receive("Autre")
        receive("Inconnu")
        val rows = MariaDbSupport.count("server_votes")
        val ledger = MariaDbSupport.count("currency_ledger")

        val mine = runBlocking { database.transaction { service.today(it, xuid(kr)) } }
        val nobody = runBlocking { database.transaction { service.today(it, null) } }

        assertEquals(4, mine.votes)
        assertEquals(2, mine.mine.votes)
        assertEquals(2, mine.mine.streak)
        assertEquals(0, nobody.mine.votes)
        assertEquals(0, nobody.mine.streak)
        assertEquals(rules.party.votesPerDay, mine.goal)
        assertFalse(mine.partyReached)
        assertEquals(rows, MariaDbSupport.count("server_votes"))
        assertEquals(ledger, MariaDbSupport.count("currency_ledger"))
    }
}
