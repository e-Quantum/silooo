package com.silooo.core.discord

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.MariaDbSupport as Db
import com.silooo.core.discord.linking.GameDiscordLinks
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CommunityLiveSqlTest {
    @BeforeEach fun prepare() = Db.prepare()

    @Test fun liveSnapshotUsesCanonicalReadsAndRestartsEditSameBoardsWithoutReplayingRecords() = runBlocking {
        val now = Instant.parse("2026-11-06T12:00:00Z")
        val game = Dependencies(Db.database, "test-key", definitions = TestDefinitions.loaded, clock = Clock.fixed(now, ZoneOffset.UTC))
        Db.insertCompany("Acier")
        val server = FakeDiscordServer()
        val plan = PlanApplier(ServerPlan.fromClasspath(), server, ServerPhase.BETA, false).run()
        assertTrue(plan.failures.isEmpty())
        val timer = FakeTimer(now)
        val context = BotContext(server, plan.ids, timer, ServerPhase.BETA)
        val messages = JdbcBotMessageStore(Db.database)
        fun feature(context: BotContext) = CommunityGameBoardsFeature(context, game, GameDiscordLinks(Db.database, game.clock), messages, JdbcGameEventCursorStore(Db.database), "https://silooo.com")
        feature(context).start()
        val silo = plan.ids.channel("le-silo")!!
        val records = plan.ids.channel("records")!!
        val initial = server.posted.single { it.channelId == silo }
        assertTrue(initial.message.text.contains("Production du serveur"))
        assertNotNull(initial.message.image)
        Db.update("insert into game_events(kind,payload) values('PRESTIGE_ENTREPRISE',?)", """{"companyId":1,"companyName":"Acier"}""")
        timer.advance(Duration.ofSeconds(30))
        assertEquals(1, server.posted.count { it.channelId == silo })
        assertTrue(server.edits.any { it.channelId == silo && it.messageId == initial.messageId })
        assertEquals(1, server.posted.count { it.channelId == records })
        val afterRestart = FakeTimer(now.plusSeconds(60))
        feature(BotContext(server, plan.ids, afterRestart, ServerPhase.BETA)).start()
        assertEquals(1, server.posted.count { it.channelId == silo })
        assertEquals(1, server.posted.count { it.channelId == records })
        Db.update("update companies set era=2")
        afterRestart.advance(Duration.ofSeconds(30))
        assertEquals(2, server.posted.count { it.channelId == records })
        afterRestart.advance(Duration.ofSeconds(30))
        assertEquals(2, server.posted.count { it.channelId == records })
    }
}
