package com.silooo.core.discord.linking

import com.silooo.core.db.MariaDbSupport as Db
import com.silooo.core.db.Xuid
import java.time.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GameDiscordLinksSqlTest {
    private class Time(var at: Instant = Instant.parse("2026-11-06T12:00:00Z")) : Clock() {
        override fun instant() = at
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private val time = Time()
    private val actor = checkNotNull(Xuid.parse("7"))
    private var sequence = 0
    private fun service() = GameDiscordLinks(Db.database, time) { listOf("ABC234", "DEF567", "GHJ789", "KLM234")[sequence++ % 4] }
    private suspend fun issue(s: GameDiscordLinks) = Db.database.transaction { s.issue(it, actor) }
    @BeforeEach fun prepare() { Db.prepare(); Db.insertPlayer("7", "KrTest") }

    @Test fun proofAndUnlinkPreserveGameAndOAuth() = runBlocking {
        val s = service(); val code = issue(s)
        assertNotEquals(code.code, Db.string("select code_hash from discord_game_codes"))
        assertEquals(64, Db.string("select code_hash from discord_game_codes")!!.length)
        assertEquals(time.at.plusSeconds(600), code.expiresAt)
        val result = s.redeem("11", " abc234 ")
        assertEquals(GameLinkStatus.LINKED, result.status)
        val link = checkNotNull(result.link)
        assertEquals("7", link.xuid); assertEquals("KrTest", link.gamertag)
        assertEquals("11", Db.string("select discord_user_id from site_registrations where gamertag_key='krtest'"))
        assertEquals(0L, Db.long("select pioneer from site_registrations where gamertag_key='krtest'"))
        assertNull(Db.string("select admitted_at from site_registrations where gamertag_key='krtest'"))
        assertEquals(0L, Db.count("web_accounts"))
        assertEquals(GameLinkStatus.INVALID_CODE, s.redeem("11", code.code).status)
        assertEquals(link, s.findByDiscord("11"))
        assertEquals(link, s.unlink("11", link.xuid, link.linkedAt))
        assertNull(s.findByDiscord("11")); assertEquals(1L, Db.count("players"))
    }

    @Test fun unsignedXuidDoesNotLosePrecision() = runBlocking {
        val raw = "18446744073709551615"
        Db.insertPlayer(raw, "GrandXuid")
        val s = service()
        val code = Db.database.transaction { s.issue(it, checkNotNull(Xuid.parse(raw))) }
        val linked = s.redeem("11", code.code)
        assertEquals(GameLinkStatus.LINKED, linked.status)
        assertEquals(raw, linked.link?.xuid)
        assertEquals(raw, s.findByDiscord("11")?.xuid)
        assertNotNull(s.unlink("11", raw, linked.link!!.linkedAt))
    }

    @Test fun rotationAndExpiry() = runBlocking {
        val s = service(); val first = issue(s); val second = issue(s)
        assertNotEquals(first.code, second.code)
        assertEquals(GameLinkStatus.INVALID_CODE, s.redeem("11", first.code).status)
        time.at = second.expiresAt
        assertEquals(GameLinkStatus.EXPIRED_CODE, s.redeem("11", second.code).status)
        assertEquals(0L, Db.count("discord_game_links"))
    }

    @Test fun attemptLimitSurvivesRestartAndExpires() = runBlocking {
        val s = service()
        repeat(5) { assertEquals(GameLinkStatus.INVALID_CODE, s.redeem("11", "ZZZ999").status) }
        assertEquals(GameLinkStatus.RATE_LIMITED, service().redeem("11", "ZZZ999").status)
        time.at = time.at.plusSeconds(600)
        assertEquals(GameLinkStatus.INVALID_CODE, service().redeem("11", "ZZZ999").status)
    }

    @Test fun siteAndOAuthConflictsAreNotOverwritten() = runBlocking {
        Db.update("insert into site_registrations(gamertag_key,gamertag,discord_user_id) values('krtest','KrTest','99')")
        val s = service(); val code = issue(s)
        assertEquals(GameLinkStatus.PLAYER_TAKEN, s.redeem("11", code.code).status)
        assertEquals("99", Db.string("select discord_user_id from site_registrations"))
        assertEquals(0L, Db.count("discord_game_links")); assertEquals(1L, Db.count("discord_game_codes"))
        Db.update("delete from site_registrations")
        Db.update("insert into web_accounts(player_xuid,gamertag,discord_user_id) values(7,'KrTest','99')")
        assertEquals(GameLinkStatus.PLAYER_TAKEN, s.redeem("11", code.code).status)
        assertEquals("99", Db.string("select discord_user_id from web_accounts"))
        assertEquals(0L, Db.count("site_registrations"))
    }

    @Test fun concurrentConsumptionHasOneWinner() = runBlocking {
        val s = service(); val code = issue(s)
        val results = coroutineScope { listOf("11", "22").map { id -> async(Dispatchers.IO) { s.redeem(id, code.code).status } }.awaitAll() }
        assertEquals(1, results.count { it == GameLinkStatus.LINKED })
        assertEquals(1, results.count { it == GameLinkStatus.INVALID_CODE })
        assertEquals(1L, Db.count("discord_game_links"))
    }

    @Test fun oldConfirmationCannotRemoveRenewedProof() = runBlocking {
        val s = service()
        val old = checkNotNull(s.redeem("11", issue(s).code).link)
        val fresh = checkNotNull(s.redeem("11", issue(s).code).link)
        assertTrue(fresh.linkedAt.isAfter(old.linkedAt))
        assertNull(s.unlink("11", old.xuid, old.linkedAt))
        assertEquals(fresh, s.findByDiscord("11"))
        assertEquals(fresh, s.unlink("11", fresh.xuid, fresh.linkedAt))
    }

    @Test fun websiteUnlinkInvalidatesProofAndOtherPairIsKept() = runBlocking {
        val s = service(); val link = checkNotNull(s.redeem("11", issue(s).code).link)
        Db.update("update site_registrations set discord_user_id='99' where gamertag_key='krtest'")
        assertNull(s.findByDiscord("11"))
        assertEquals(link, s.unlink("11", link.xuid, link.linkedAt))
        assertEquals("99", Db.string("select discord_user_id from site_registrations"))
    }
}
