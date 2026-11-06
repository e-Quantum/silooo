package com.silooo.core.discord

import com.silooo.core.TestDefinitions
import com.silooo.core.db.MariaDbSupport
import com.silooo.core.db.Xuid
import com.silooo.core.economy.*
import com.silooo.core.findings.*
import com.silooo.core.mission.*
import com.silooo.core.sitedata.JdbcSiteStatsStore
import com.silooo.core.session.*
import com.silooo.core.vote.JdbcVoteStore
import com.silooo.core.vote.VoteService
import java.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Requêtes des profils, réservations d'invitation et cartes sur les vraies tables MariaDB. */
class GameProfilesSqlTest {
    private val db get() = MariaDbSupport.database
    private val definitions = TestDefinitions.loaded
    private val clock = Clock.systemUTC()
    private val sessionStore = JdbcSessionStore()

    @BeforeEach fun prepare() = MariaDbSupport.prepare()

    private fun prove(discord: String = "420", admitted: Boolean = true): Long = runBlocking {
        val xuid = checkNotNull(Xuid.parse("42"))
        SessionService(db, sessionStore, EngineFigures(EconomyEngine(definitions), sessionStore, clock), DefinitionsBootstrap(EconomyEngine(definitions), definitions))
            .join(xuid, "Ana")
        MariaDbSupport.update(
            "insert into site_registrations (gamertag_key,gamertag,discord_user_id,pioneer,linked_at,admitted_at) values ('ana','Ana',?,true,UTC_TIMESTAMP(3),IF(?,UTC_TIMESTAMP(3),NULL))",
            discord, admitted,
        )
        MariaDbSupport.update(
            "insert into discord_game_links (player_xuid,discord_user_id,gamertag_key,linked_at) values (?,?, 'ana', UTC_TIMESTAMP(3))",
            java.math.BigInteger("42"), discord,
        )
        MariaDbSupport.long("select company_id from company_members where xuid = ?", java.math.BigInteger("42"))
    }

    @Test fun `profile query requires active matching proof and derives data from the game`() = runBlocking {
        prove()
        val query = JdbcGameProfileQueries(db)
        val p = checkNotNull(query.profile("420"))
        assertEquals("42", p.xuid)
        assertEquals("Ana", p.gamertag)
        assertNotNull(p.companyId)
        assertEquals(0, p.courseNumber)
        assertTrue(p.pioneer)
        assertTrue(p.beta)
        assertNull(query.profile("421"))
        MariaDbSupport.update("update site_registrations set discord_user_id = null, linked_at = null where gamertag_key = 'ana'")
        assertNull(query.profile("420"))
        assertTrue(query.profiles().isEmpty())
    }

    @Test fun `managed state and invitation claim survive repeated calls`() = runBlocking {
        val store = JdbcManagedGameRoleStore(db)
        assertTrue(store.claimInvitation("420", clock.instant()))
        assertFalse(store.claimInvitation("420", clock.instant()))
        store.finishInvitation("420", "dm_closed")
        store.appliedNickname("420", "Ana · P0")
        store.replace("420", setOf("joueur-relie", "pionnier"))
        assertEquals(setOf("joueur-relie", "pionnier"), store.roleKeys("420"))
        assertEquals("Ana · P0", store.appliedNickname("420"))
        assertEquals(setOf("420"), store.knownUsers())
        assertEquals("dm_closed", MariaDbSupport.string("select status from discord_game_invitations where discord_user_id='420'"))
    }

    @Test fun `command adapter reads company stats and vote services`() = runBlocking {
        val companyId = prove(admitted = false)
        val engine = EconomyEngine(definitions)
        val figures = EngineFigures(engine, sessionStore, clock)
        val companies = CompanyService(
            sessionStore, JdbcCompanyStore(), engine, figures,
            MissionService(definitions, JdbcMissionStore()), FindingService(definitions, JdbcFindingStore()),
        )
        val voteService = VoteService(definitions, JdbcVoteStore(), clock) { emptySet() }
        val query = JdbcGameCommandQueries(db, companies, JdbcSiteStatsStore(), voteService, clock)
        assertTrue(checkNotNull(query.company("42")).text.contains("P0"))
        assertNotNull(query.factory("42", null))
        assertTrue(query.leaderboard("42").text.contains("Aucun Lingot"))
        assertTrue(query.votes("42").text.contains("0 vote(s)"))
        assertTrue(companyId > 0)
    }
}
