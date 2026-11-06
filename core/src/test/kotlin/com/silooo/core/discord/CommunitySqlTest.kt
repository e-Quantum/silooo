package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import com.silooo.core.db.Xuid
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Les sondages et les bureaux s'appuient sur la vraie MariaDB/V16. Ignoré sans Docker : la CI le joue. */
class CommunitySqlTest {
    private val start = Instant.parse("2026-11-06T12:00:00Z")
    private val polls get() = JdbcPollStore(MariaDbSupport.database)

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    @Test
    @DisplayName("Un XUID ne peut compter qu'une fois, peut changer son choix, et le sondage survit au redémarrage")
    fun oneVotePerProvedXuidAndRestart() = runBlocking {
        val poll = CommunityPoll("poll-1", "channel", null, "Quelle direction ?", listOf(PollOption("A", "A choisit"), PollOption("B", "B choisit")), start.plusSeconds(600), "staff")
        polls.create(poll)
        val xuid = "18446744073709551615"
        MariaDbSupport.insertPlayer(xuid)
        assertTrue(polls.vote(poll.id, xuid, 0, start))
        assertTrue(polls.vote(poll.id, xuid, 1, start.plusSeconds(30)))
        assertEquals(PollTally(listOf(0, 1), 1), polls.tally(poll.id))
        assertEquals(1L, MariaDbSupport.count("discord_poll_votes"))

        val restarted = JdbcPollStore(MariaDbSupport.database)
        assertEquals(poll.id, restarted.active().single().id)
        assertNotNull(restarted.close(poll.id, start.plusSeconds(600)))
        assertEquals(start.plusSeconds(600), restarted.close(poll.id, start.plusSeconds(601))?.closedAt)
        assertFalse(restarted.vote(poll.id, xuid, 0, start.plusSeconds(601)))
        assertEquals(1, restarted.active().size, "le résultat fermé reste à publier après redémarrage")
        restarted.resultPublished(poll.id, start.plusSeconds(602))
        assertTrue(restarted.active().isEmpty())
    }

    @Test
    @DisplayName("Le curseur démarre au dernier événement historique et reprend après le redémarrage")
    fun eventCursorSkipsOldHistoryAndPersists() = runBlocking {
        MariaDbSupport.update("insert into game_events (kind, payload) values ('ANNONCE', '{}')")
        val cursor = JdbcGameEventCursorStore(MariaDbSupport.database)
        val first = cursor.initializeToLatest()
        assertEquals(1L, first)
        MariaDbSupport.update("insert into game_events (kind, payload) values ('ANNONCE', '{}')")
        assertEquals(first, JdbcGameEventCursorStore(MariaDbSupport.database).current())
        cursor.advance(2)
        assertEquals(2L, JdbcGameEventCursorStore(MariaDbSupport.database).current())
    }

    @Test
    @DisplayName("Seuls les membres actuels ayant une paire de preuve encore confirmée écrivent dans le bureau")
    fun bureauWritersRequireCurrentMembershipAndProof() = runBlocking {
        val companyId = MariaDbSupport.insertCompany("Bureau", 1)
        listOf("7", "8", "9").forEach { MariaDbSupport.insertPlayer(it) }
        MariaDbSupport.update("insert into company_members (company_id, xuid, role, share_percent) values (?, ?, 'PDG', 60)", companyId, java.math.BigInteger("7"))
        MariaDbSupport.update("insert into company_members (company_id, xuid, role, share_percent) values (?, ?, 'ASSOCIE', 40)", companyId, java.math.BigInteger("8"))
        MariaDbSupport.update("insert into discord_game_links (player_xuid, discord_user_id, gamertag_key, linked_at) values (?, ?, ?, ?)", java.math.BigInteger("7"), "700", "j7", LocalDateTime.ofInstant(start, ZoneOffset.UTC))
        MariaDbSupport.update("insert into site_registrations (gamertag_key, gamertag, discord_user_id, linked_at, pioneer) values (?, ?, ?, ?, false)", "j7", "J7", "700", LocalDateTime.ofInstant(start, ZoneOffset.UTC))
        // Le XUID 8 est lié, mais sa paire a été détachée du site : elle n'autorise plus l'écriture.
        MariaDbSupport.update("insert into discord_game_links (player_xuid, discord_user_id, gamertag_key, linked_at) values (?, ?, ?, ?)", java.math.BigInteger("8"), "800", "j8", LocalDateTime.ofInstant(start, ZoneOffset.UTC))
        MariaDbSupport.update("insert into site_registrations (gamertag_key, gamertag, discord_user_id, linked_at, pioneer) values (?, ?, null, null, false)", "j8", "J8")
        // Le XUID 9 a une preuve valide mais ne fait pas partie de l'entreprise.
        MariaDbSupport.update("insert into discord_game_links (player_xuid, discord_user_id, gamertag_key, linked_at) values (?, ?, ?, ?)", java.math.BigInteger("9"), "900", "j9", LocalDateTime.ofInstant(start, ZoneOffset.UTC))
        MariaDbSupport.update("insert into site_registrations (gamertag_key, gamertag, discord_user_id, linked_at, pioneer) values (?, ?, ?, ?, false)", "j9", "J9", "900", LocalDateTime.ofInstant(start, ZoneOffset.UTC))

        val writers = verifiedCompanyDiscordMembers(MariaDbSupport.database, companyId)
        assertEquals(setOf("700"), writers)
    }
}
