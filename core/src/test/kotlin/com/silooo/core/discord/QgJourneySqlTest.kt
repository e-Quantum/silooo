package com.silooo.core.discord

import com.silooo.core.TestDefinitions
import com.silooo.core.db.MariaDbSupport as Db
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Même assemblage que la production, vraie base d'essai et faux réseau : aucun message envoyé à Discord. */
class QgJourneySqlTest {
    @BeforeEach fun prepare() = Db.prepare()
    @Test fun arrivalSpoilTicketAdmissionAndRestart() = runBlocking {
        val server = FakeDiscordServer()
        server.emojis += setOf("silo", "donar", "prestige")
        val plan = PlanApplier(ServerPlan.fromClasspath(), server, ServerPhase.ATTENTE, false).run()
        assertTrue(plan.failures.isEmpty(), plan.failures.toString())
        val timer = FakeTimer(Instant.parse("2026-11-06T12:00:00Z"))
        val context = BotContext(server, plan.ids, timer, ServerPhase.ATTENTE)
        val build = BotFeatures.build(TestDefinitions.directory, Db.database, onlineCount = { 0 })
        val features = build(context)
        features.forEach { it.start() }
        suspend fun event(e: DiscordEvent) { features.forEach { it.handle(e) } }
        val user = DiscordUser("99", "Nouveau", roleIds = setOf(plan.ids.role("pc")!!))
        event(DiscordEvent.MemberJoined(user, timer.now()))
        event(DiscordEvent.OnboardingCompleted(user, timer.now()))
        timer.advance(Duration.ofSeconds(10))
        assertTrue(plan.ids.role("entrepreneur") in server.memberRoles.getValue("99"))
        assertTrue(server.directs.any { it.first == "99" && it.second.text.contains("Le jeu est encore fermé") })
        assertTrue(server.posted.any { it.channelId == plan.ids.channel("discussion") && it.message.mentionUserIds.contains("99") })

        val owner = DiscordUser("11", "Staff", roleIds = setOf(plan.ids.role("staff")!!))
        suspend fun spoil(text: String) {
            event(DiscordEvent.CommandUsed("spoil", mapOf("texte" to text, "seuil" to "1"), owner, plan.ids.channel("coulisses"), FakeReply()))
        }
        spoil("La Scierie est posée.")
        val current = checkNotNull(JdbcSpoilStore(Db.database).current())
        spoil("La Fonderie est posée.")
        server.reactors[Triple(plan.ids.channel("coulisses")!!, current.messageId!!, "silo")] = mutableSetOf("99")
        timer.advance(Duration.ofSeconds(60))
        assertEquals(2, JdbcSpoilStore(Db.database).current()?.number)

        val menu = JdbcBotMessageStore(Db.database).find(TicketTexts.MENU_KEY)!!
        event(DiscordEvent.MenuChosen(TicketTexts.MENU_ID, listOf(TicketTexts.ACCOUNT), user, menu.channelId, menu.messageId, FakeReply()))
        val ticket = Db.string("select channel_id from discord_tickets where discord_user_id='99' and closed_at is null")!!
        assertFalse(server.publicChannelAccess(ticket).view)
        val number = Db.long("select id from discord_tickets where discord_user_id='99'")
        event(DiscordEvent.ButtonPressed(TicketTexts.CLOSE_PREFIX + number, user, ticket, "1", FakeReply()))
        assertNotNull(Db.string("select closed_at from discord_tickets where discord_user_id='99'"))
        assertFalse(server.channels.any { it.id == ticket })

        Db.update("insert into site_registrations(gamertag_key,gamertag,discord_user_id) values('nouveau','Nouveau','99')")
        timer.advance(Duration.ofSeconds(60))
        val card = Db.string("select message_id from discord_admissions where gamertag_key='nouveau'")!!
        event(DiscordEvent.ButtonPressed(AdmissionTexts.ADMIT_PREFIX + "nouveau", owner, plan.ids.channel("admissions"), card, FakeReply()))
        assertNotNull(Db.string("select admitted_at from site_registrations where gamertag_key='nouveau'"))
        assertFalse(plan.ids.role("beta") in server.memberRoles.getValue("99"), "Une association déclarée sur le site ne prouve pas le compte en jeu")

        val count = server.posted.count { it.channelId == menu.channelId }
        val second = build(context).filter { it.name == "tickets" || it.name == "bienvenue" }
        second.forEach { it.start() }
        assertEquals(count, server.posted.count { it.channelId == menu.channelId })
        assertEquals(1L, Db.count("discord_members"))
    }
}
