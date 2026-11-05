package com.silooo.core.vote

import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.db.Database
import com.silooo.core.db.RecordingConnection
import com.silooo.core.db.RecordingDatabase
import com.silooo.core.db.Xuid
import com.silooo.core.economy.EngineFixture
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.InMemorySessionStore
import java.sql.Connection
import java.sql.SQLTransactionRollbackException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Les règles de [VoteService] sans base : un faux [InMemoryVoteStore] et une horloge à la main. Les chiffres viennent tous de
 * `definitions/vote.json` (le test lit [rules] et ne recopie rien). Le SQL se teste sur MariaDB dans `VoteSqlTest`.
 */
class VoteServiceTest {
    private val definitions = TestDefinitions.loaded
    private val rules = definitions.vote
    private val start = Instant.parse("2026-11-05T12:00:00Z")
    private val clock = MutableClock(start)
    private val sessions = InMemorySessionStore(start)
    private val store = InMemoryVoteStore(sessions)
    private val connection: Connection = RecordingConnection().connection
    private var present: Set<Xuid> = emptySet()
    private val service = VoteService(definitions, store, clock) { present }

    private val kr = EngineFixture.KR
    private val other = EngineFixture.OTHER
    private val stranger = EngineFixture.STRANGER

    /** Présent d'après les signaux de vie, mais que `players` ne connaît pas. */
    private val ghost = Xuid.parse("2535428000000099")!!

    private fun join(xuid: Xuid, gamertag: String) {
        sessions.enterPlayer(connection, xuid, gamertag)
    }

    private fun vote(gamertag: String, site: String = "Hubyy"): VoteReceipt = service.receiveIn(connection, IncomingVote(gamertag, site))

    private fun lingots(xuid: Xuid): Long = sessions.balances[xuid]?.lingots ?: 0L

    private fun today(): LocalDate = clock.instant().atOffset(ZoneOffset.UTC).toLocalDate()

    private fun nextDay() {
        clock.now = clock.now.plus(Duration.ofDays(1))
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun votesCredits(xuid: Xuid) = store.credits.filter { it.xuid == xuid && it.reason == VoteService.REASON_VOTE }

    // --- un vote d'un joueur connu

    @Test
    @DisplayName("Un vote d'un joueur connu : ligne versée à son nom, Lingots crédités (motif vote, référence = id), annonce au joueur")
    fun knownPlayerIsRewarded() {
        join(kr, "Kr")

        val receipt = vote("Kr")

        assertEquals(kr, receipt.xuid)
        assertEquals(VoteGrant(lingots = rules.reward.lingotsPerVote, streak = 1, streakBonusLingots = 0), receipt.grant)
        assertEquals(1, receipt.rankToday)
        assertNull(receipt.party)
        val row = store.row(receipt.voteId)
        assertEquals(kr, row.xuid)
        assertTrue(row.rewarded)
        assertEquals("Kr", row.gamertag)
        assertEquals("hubyy", row.site, "le site connu est gardé sous son identifiant")
        assertEquals(start, row.votedAt)
        assertEquals(rules.reward.lingotsPerVote, lingots(kr))
        assertEquals(
            listOf(InMemoryVoteStore.Credit(kr, rules.reward.lingotsPerVote, "vote", receipt.voteId.toString())),
            store.credits,
        )
        val event = store.published.single()
        assertEquals(GameEventKind.ANNONCE, event.kind)
        assertEquals(kr, event.xuid)
        assertEquals("vote", event.payload.text("action"))
        assertEquals("hubyy", event.payload.text("site"))
        assertEquals(rules.reward.lingotsPerVote, event.payload.getValue("rewardLingots").jsonPrimitive.long)
        assertEquals(1, event.payload.getValue("streak").jsonPrimitive.int)
        assertEquals(0L, event.payload.getValue("streakBonusLingots").jsonPrimitive.long)
    }

    @Test
    @DisplayName("Le pseudo se retrouve sans tenir compte de la casse, et c'est le joueur vu en dernier qui gagne")
    fun gamertagLookupIgnoresCaseAndTakesTheLatest() {
        join(kr, "Kr")
        clock.advance(Duration.ofMinutes(5))
        sessions.now = clock.instant()
        join(other, "kr")

        val receipt = vote("KR")

        assertEquals(other, receipt.xuid, "le dernier vu porte le pseudo")
        assertEquals(0L, lingots(kr))
        assertEquals(rules.reward.lingotsPerVote, lingots(other))
    }

    @Test
    @DisplayName("Un site inconnu est gardé sous le nom qu'il annonce ; un site connu, sous son id, quelle que soit la casse")
    fun siteIsNormalised() {
        join(kr, "Kr")

        val known = vote("Kr", site = "HUBYY")
        val unknown = vote("Kr", site = " Autre Site ")

        assertEquals("hubyy", store.row(known.voteId).site)
        assertEquals("Autre Site", store.row(unknown.voteId).site)
    }

    // --- un pseudo inconnu

    @Test
    @DisplayName("Un pseudo inconnu : la ligne attend (sans joueur, non versée), aucun Lingot, aucune annonce ; le vote compte dans la journée")
    fun unknownGamertagWaits() {
        val receipt = vote("Inconnu")

        assertNull(receipt.xuid)
        assertNull(receipt.grant)
        assertEquals(1, receipt.rankToday)
        val row = store.row(receipt.voteId)
        assertNull(row.xuid)
        assertFalse(row.rewarded)
        assertEquals("Inconnu", row.gamertag)
        assertTrue(store.credits.isEmpty())
        assertTrue(store.published.isEmpty())
    }

    @Test
    @DisplayName("Le join rattrape le vote en attente de son pseudo : Lingots et annonce, une seule fois")
    fun joinCatchesUpAWaitingVote() {
        val waiting = vote("Kr")
        join(kr, "Kr")

        service.relieve(connection, kr, "Kr")

        val row = store.row(waiting.voteId)
        assertEquals(kr, row.xuid)
        assertTrue(row.rewarded)
        assertEquals(rules.reward.lingotsPerVote, lingots(kr))
        assertEquals(
            listOf(InMemoryVoteStore.Credit(kr, rules.reward.lingotsPerVote, "vote", waiting.voteId.toString())),
            store.credits,
        )
        assertEquals("vote", store.published.single().payload.text("action"))
        assertEquals(kr, store.published.single().xuid)

        service.relieve(connection, kr, "Kr")
        service.relieve(connection, kr, "Kr")

        assertEquals(rules.reward.lingotsPerVote, lingots(kr), "un second join ne verse rien de plus")
        assertEquals(1, store.credits.size)
        assertEquals(1, store.published.size)
    }

    @Test
    @DisplayName("Un join sans vote en attente n'écrit rien")
    fun joinWithNothingWaitingWritesNothing() {
        join(kr, "Kr")
        vote("Kr")
        val credits = store.credits.size
        val events = store.published.size

        service.relieve(connection, kr, "Kr")
        service.relieve(connection, other, "Autre")

        assertEquals(credits, store.credits.size)
        assertEquals(events, store.published.size)
    }

    @Test
    @DisplayName("Un vote rattrapé au join ne relance pas la fête : seul le vote reçu à l'objectif la lance, et une seule fois")
    fun aCaughtUpVoteNeverStartsTheParty() {
        repeat(rules.party.votesPerDay) { vote("Inconnu$it") }
        val announces = store.published.count { it.payload.text("action") == "voteParty" }
        assertEquals(1, announces, "la fête a eu lieu à la réception du vote de rang ${rules.party.votesPerDay}")
        join(kr, "Inconnu0")
        present = setOf(kr)

        service.relieve(connection, kr, "Inconnu0")

        assertEquals(announces, store.published.count { it.payload.text("action") == "voteParty" })
        assertTrue(store.credits.none { it.reason == VoteService.REASON_PARTY })
        assertEquals(rules.reward.lingotsPerVote, lingots(kr), "le joueur reçoit son vote, pas une fête de plus")
    }

    @Test
    @DisplayName("Un pseudo porté par un joueur vu plus récemment n'est pas le sien : ses votes attendent celui-là")
    fun aStolenGamertagKeepsItsVotesWaiting() {
        val waiting = vote("Kr")
        join(kr, "Kr")
        clock.advance(Duration.ofMinutes(5))
        sessions.now = clock.instant()
        join(other, "Kr")

        service.relieve(connection, kr, "Kr")

        assertFalse(store.row(waiting.voteId).rewarded, "kr n'est plus le porteur du pseudo")
        assertTrue(store.credits.isEmpty())

        service.relieve(connection, other, "Kr")

        assertEquals(other, store.row(waiting.voteId).xuid)
        assertEquals(rules.reward.lingotsPerVote, lingots(other))
        assertEquals(0L, lingots(kr))
    }

    @Test
    @DisplayName("Un join ne rattrape pas plus de 100 votes à la fois ; le suivant verse le reste")
    fun catchUpIsBounded() {
        repeat(VoteService.MAX_CATCH_UP + 5) { vote("Kr") }
        join(kr, "Kr")

        service.relieve(connection, kr, "Kr")
        assertEquals(VoteService.MAX_CATCH_UP, store.rows.count { it.rewarded })

        service.relieve(connection, kr, "Kr")
        assertEquals(VoteService.MAX_CATCH_UP + 5, store.rows.count { it.rewarded })
        assertEquals(VoteService.MAX_CATCH_UP + 5, votesCredits(kr).size)
    }

    // --- la série

    @Test
    @DisplayName("Au bout de serie.jours jours de suite, le vote de ce jour-là donne le bonus ; avant, rien de plus que la récompense")
    fun streakBonusOnTheGoalDay() {
        join(kr, "Kr")
        val days = rules.streak.days

        for (day in 1..days) {
            val receipt = vote("Kr")
            val expected = if (day == days) rules.reward.lingotsPerVote + rules.streak.bonusLingots else rules.reward.lingotsPerVote
            assertEquals(day, receipt.grant!!.streak, "série au jour $day")
            assertEquals(expected, receipt.grant!!.lingots, "Lingots au jour $day")
            assertEquals(if (day == days) rules.streak.bonusLingots else 0L, receipt.grant!!.streakBonusLingots)
            if (day < days) nextDay()
        }

        val last = store.published.last()
        assertEquals(rules.streak.bonusLingots, last.payload.getValue("streakBonusLingots").jsonPrimitive.long)
        assertEquals(days, last.payload.getValue("streak").jsonPrimitive.int)
        assertEquals(days * rules.reward.lingotsPerVote + rules.streak.bonusLingots, lingots(kr))
    }

    @Test
    @DisplayName("Un second vote le même jour que celui du bonus ne le redonne pas")
    fun bonusOnlyOnTheFirstVoteOfTheDay() {
        join(kr, "Kr")
        repeat(rules.streak.days - 1) {
            vote("Kr")
            nextDay()
        }

        val first = vote("Kr")
        val second = vote("Kr")

        assertEquals(rules.streak.bonusLingots, first.grant!!.streakBonusLingots)
        assertEquals(0L, second.grant!!.streakBonusLingots)
        assertEquals(rules.reward.lingotsPerVote, second.grant!!.lingots)
        assertEquals(rules.streak.days, second.grant!!.streak)
    }

    @Test
    @DisplayName("Un jour sans vote remet la série à zéro : il faut de nouveau serie.jours jours pour le bonus")
    fun aGapResetsTheStreak() {
        join(kr, "Kr")
        repeat(rules.streak.days - 1) {
            vote("Kr")
            nextDay()
        }
        nextDay() // un jour sans vote

        val after = vote("Kr")

        assertEquals(1, after.grant!!.streak)
        assertEquals(0L, after.grant!!.streakBonusLingots)
        assertEquals(rules.reward.lingotsPerVote, after.grant!!.lingots)
    }

    @Test
    @DisplayName("Le bonus revient à chaque multiple de serie.jours")
    fun bonusComesBackOnEveryMultiple() {
        join(kr, "Kr")
        val bonusDays = mutableListOf<Int>()

        for (day in 1..(3 * rules.streak.days)) {
            val receipt = vote("Kr")
            if (receipt.grant!!.streakBonusLingots > 0) bonusDays += day
            nextDay()
        }

        assertEquals(listOf(rules.streak.days, 2 * rules.streak.days, 3 * rules.streak.days), bonusDays)
    }

    @Test
    @DisplayName("Un vote rattrapé au join compte dans la série de son propre jour, et le bonus tombe sur son jour")
    fun aCaughtUpVoteCountsInItsOwnDay() {
        repeat(rules.streak.days) {
            vote("Kr")
            if (it < rules.streak.days - 1) nextDay()
        }
        join(kr, "Kr")

        service.relieve(connection, kr, "Kr")

        val credits = votesCredits(kr)
        assertEquals(rules.streak.days, credits.size)
        assertEquals(List(rules.streak.days - 1) { rules.reward.lingotsPerVote } + (rules.reward.lingotsPerVote + rules.streak.bonusLingots), credits.map { it.amount })
        assertEquals(rules.streak.days, store.published.last().payload.getValue("streak").jsonPrimitive.int)
    }

    // --- la Vote party

    @Test
    @DisplayName("Le vote qui atteint l'objectif lance la fête : chaque joueur présent et connu reçoit les Lingots, une annonce pour tout le serveur")
    fun theGoalVoteStartsTheParty() {
        join(kr, "Kr")
        join(other, "Autre")
        join(stranger, "Absent")
        present = setOf(other, kr, ghost)

        for (n in 1 until rules.party.votesPerDay) {
            val receipt = vote("Inconnu$n")
            assertNull(receipt.party, "vote $n sur ${rules.party.votesPerDay}")
        }
        assertTrue(store.credits.isEmpty())

        val goal = vote("Inconnu")

        val party = checkNotNull(goal.party)
        assertEquals(today(), party.day)
        assertEquals(rules.party.votesPerDay, party.votes)
        assertEquals(rules.party.lingotsPerPlayer, party.lingotsPerPlayer)
        assertEquals(listOf(kr, other), party.players, "les présents que players connaît, dans l'ordre des XUID")
        assertEquals(
            listOf(
                InMemoryVoteStore.Credit(kr, rules.party.lingotsPerPlayer, "vote-party", today().toString()),
                InMemoryVoteStore.Credit(other, rules.party.lingotsPerPlayer, "vote-party", today().toString()),
            ),
            store.credits,
        )
        assertEquals(0L, lingots(stranger), "l'absent ne reçoit rien")
        val announce = store.published.single()
        assertEquals(GameEventKind.ANNONCE, announce.kind)
        assertNull(announce.xuid, "pour tout le serveur")
        assertEquals("voteParty", announce.payload.text("action"))
        assertEquals(today().toString(), announce.payload.text("day"))
        assertEquals(rules.party.votesPerDay, announce.payload.getValue("votes").jsonPrimitive.int)
        assertEquals(rules.party.lingotsPerPlayer, announce.payload.getValue("rewardLingots").jsonPrimitive.long)
        assertEquals(2, announce.payload.getValue("players").jsonPrimitive.int)
    }

    @Test
    @DisplayName("Les votes suivants du jour comptent mais ne redonnent pas la fête")
    fun laterVotesDoNotRepeatTheParty() {
        join(kr, "Kr")
        present = setOf(kr)
        repeat(rules.party.votesPerDay) { vote("Inconnu$it") }
        val partyCredits = store.credits.count { it.reason == VoteService.REASON_PARTY }
        val announces = store.published.size

        repeat(5) {
            val later = vote("Autre$it")
            assertNull(later.party)
        }

        assertEquals(1, partyCredits)
        assertEquals(partyCredits, store.credits.count { it.reason == VoteService.REASON_PARTY })
        assertEquals(announces, store.published.size)
        assertEquals(rules.party.votesPerDay + 5, service.today(connection, null).votes)
        assertEquals(rules.party.lingotsPerPlayer, lingots(kr))
    }

    @Test
    @DisplayName("Le lendemain, un nouveau décompte : la fête revient quand l'objectif est de nouveau atteint")
    fun thePartyComesBackTheNextDay() {
        join(kr, "Kr")
        present = setOf(kr)
        repeat(rules.party.votesPerDay) { vote("Inconnu$it") }
        val firstDay = today()
        nextDay()

        assertEquals(0, service.today(connection, null).votes)
        repeat(rules.party.votesPerDay - 1) { assertNull(vote("Autre$it").party) }
        val goal = vote("Dernier")

        assertEquals(today(), goal.party!!.day)
        assertEquals(
            listOf(firstDay.toString(), today().toString()),
            store.credits.filter { it.reason == VoteService.REASON_PARTY }.map { it.refId },
        )
        assertEquals(2 * rules.party.lingotsPerPlayer, lingots(kr))
    }

    @Test
    @DisplayName("Sans joueur présent, la fête a lieu quand même : l'annonce est écrite, personne n'est crédité")
    fun thePartyWithNobodyPresent() {
        repeat(rules.party.votesPerDay) { vote("Inconnu$it") }

        val announce = store.published.single()
        assertNull(announce.xuid)
        assertEquals(0, announce.payload.getValue("players").jsonPrimitive.int)
        assertTrue(store.credits.isEmpty())
    }

    @Test
    @DisplayName("Les votes de joueurs connus et inconnus comptent ensemble vers l'objectif")
    fun knownAndUnknownVotesCountTogether() {
        join(kr, "Kr")
        present = setOf(kr)
        repeat(rules.party.votesPerDay / 2) { vote("Kr") }
        var party: PartyOutcome? = null
        for (n in (rules.party.votesPerDay / 2) until rules.party.votesPerDay) party = vote("Inconnu$n").party

        assertEquals(rules.party.votesPerDay, checkNotNull(party).votes)
    }

    @Test
    @DisplayName("Des réceptions en parallèle ne lancent la fête qu'une fois")
    fun concurrentReceptionsStartThePartyOnce() = runBlocking {
        join(kr, "Kr")
        present = setOf(kr)
        val database = RecordingDatabase()

        val receipts = (1..(rules.party.votesPerDay + 15)).map { n ->
            async(Dispatchers.Default) { service.receive(database, IncomingVote("Inconnu$n", "Hubyy")) }
        }.awaitAll()

        assertEquals(1, receipts.count { it.party != null })
        assertEquals(1, store.credits.count { it.reason == VoteService.REASON_PARTY })
        assertEquals(1, store.published.count { it.payload.text("action") == "voteParty" })
        assertEquals((1..receipts.size).toList(), receipts.map { it.rankToday }.sorted(), "chaque vote a son propre rang")
    }

    // --- receive : la transaction et les essais

    @Test
    @DisplayName("receive passe par une transaction de la base et rend le même reçu que receiveIn")
    fun receiveRunsInATransaction() = runBlocking {
        join(kr, "Kr")

        val receipt = service.receive(RecordingDatabase(), IncomingVote("Kr", "Hubyy"))

        assertEquals(kr, receipt.xuid)
        assertEquals(rules.reward.lingotsPerVote, lingots(kr))
    }

    private class FlakyDatabase(private val failures: Int, private val error: () -> Exception) : Database {
        var attempts = 0
        private val recording = RecordingConnection()

        override suspend fun <T> transaction(block: (Connection) -> T): T {
            attempts++
            if (attempts <= failures) throw error()
            return block(recording.connection)
        }

        override fun close() = Unit
    }

    @Test
    @DisplayName("Un blocage de la base est rejoué : le vote est reçu au deuxième essai, une seule fois")
    fun receiveRetriesATransientConflict() = runBlocking {
        join(kr, "Kr")
        val database = FlakyDatabase(failures = 1) { SQLTransactionRollbackException("deadlock", "40001", 1213) }

        val receipt = service.receive(database, IncomingVote("Kr", "Hubyy"))

        assertEquals(2, database.attempts)
        assertEquals(kr, receipt.xuid)
        assertEquals(1, store.rows.size)
        assertEquals(1, store.credits.size)
    }

    @Test
    @DisplayName("Un blocage qui dure remonte après trois essais ; une autre erreur n'est pas rejouée")
    fun receiveGivesUpAndDoesNotRetryOtherErrors() = runBlocking {
        val locked = FlakyDatabase(failures = Int.MAX_VALUE) { SQLTransactionRollbackException("deadlock", "40001", 1213) }
        assertThrows<SQLTransactionRollbackException> { service.receive(locked, IncomingVote("Kr", "Hubyy")) }
        assertEquals(3, locked.attempts)

        val broken = FlakyDatabase(failures = Int.MAX_VALUE) { IllegalStateException("panne") }
        assertThrows<IllegalStateException> { service.receive(broken, IncomingVote("Kr", "Hubyy")) }
        assertEquals(1, broken.attempts)
    }

    // --- todayVotes

    @Test
    @DisplayName("today sans vote : les chiffres du JSON, zéro vote, objectif non atteint, la part du joueur à zéro")
    fun todayWithoutVotes() {
        val today = service.today(connection, null)

        assertEquals(today().toString(), today.day)
        assertEquals(0, today.votes)
        assertEquals(rules.party.votesPerDay, today.goal)
        assertFalse(today.partyReached)
        assertEquals(rules.reward.lingotsPerVote, today.rewardLingots)
        assertEquals(rules.party.lingotsPerPlayer, today.partyRewardLingots)
        assertEquals(rules.streak.days, today.streakGoalDays)
        assertEquals(rules.streak.bonusLingots, today.streakBonusLingots)
        assertEquals(0, today.mine.votes)
        assertEquals(0, today.mine.streak)
        assertEquals(0, service.today(connection, kr).mine.votes, "un joueur qui n'a jamais voté")
    }

    @Test
    @DisplayName("today avec un joueur : ses votes du jour et sa série ; les votes des autres ne comptent que dans le total")
    fun todayWithAPlayer() {
        join(kr, "Kr")
        join(other, "Autre")
        vote("Kr")
        nextDay()
        vote("Kr")
        vote("Kr")
        vote("Autre")
        vote("Inconnu")

        val mine = service.today(connection, kr)
        val theirs = service.today(connection, other)

        assertEquals(4, mine.votes, "le total du jour : 4 votes aujourd'hui, 1 hier")
        assertEquals(2, mine.mine.votes)
        assertEquals(2, mine.mine.streak)
        assertEquals(1, theirs.mine.votes)
        assertEquals(1, theirs.mine.streak)
    }

    @Test
    @DisplayName("La série affichée reste en vie la journée suivant le dernier vote, puis tombe à zéro")
    fun todayStreakStaysAliveForADay() {
        join(kr, "Kr")
        vote("Kr")

        nextDay()
        assertEquals(1, service.today(connection, kr).mine.streak, "hier suffit encore")
        assertEquals(0, service.today(connection, kr).mine.votes)

        nextDay()
        assertEquals(0, service.today(connection, kr).mine.streak, "avant-hier : perdue")
    }

    @Test
    @DisplayName("today dit que la Vote party est atteinte à partir de l'objectif, et ne change rien en base")
    fun todayReportsThePartyWithoutWriting() {
        repeat(rules.party.votesPerDay - 1) { vote("Inconnu$it") }
        assertFalse(service.today(connection, null).partyReached)
        vote("Dernier")
        val rows = store.rows.size
        val credits = store.credits.size
        val events = store.published.size

        val today = service.today(connection, kr)

        assertTrue(today.partyReached)
        assertEquals(rules.party.votesPerDay, today.votes)
        assertEquals(rows, store.rows.size)
        assertEquals(credits, store.credits.size)
        assertEquals(events, store.published.size)
    }

    @Test
    @DisplayName("Le jour est le jour UTC : un vote à 23 h 59 min 59 s et le suivant à minuit sont de deux jours")
    fun dayBoundaryIsUtcMidnight() {
        join(kr, "Kr")
        clock.now = Instant.parse("2026-11-05T23:59:59Z")
        val before = vote("Kr")
        clock.now = Instant.parse("2026-11-06T00:00:00Z")
        val after = vote("Kr")

        assertEquals(1, before.rankToday)
        assertEquals(1, after.rankToday, "nouveau jour, nouveau décompte")
        assertEquals(2, after.grant!!.streak)
        assertEquals("2026-11-06", service.today(connection, kr).day)
    }
}
