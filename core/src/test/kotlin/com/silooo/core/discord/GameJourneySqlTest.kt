package com.silooo.core.discord

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.MariaDbSupport as Db
import com.silooo.core.db.Xuid
import com.silooo.core.discord.linking.GameDiscordLinks
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Recette D3 : vraie preuve/économie/SQL, assemblage de production et faux réseau Discord. */
class GameJourneySqlTest {
    @BeforeEach fun prepare() = Db.prepare()

    @Test fun provedPlayerGetsProfileCompanyCardAndLosesOnlyGameRolesOnUnlink() = runBlocking {
        val now = Instant.parse("2026-11-06T12:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val game = Dependencies(Db.database, "test-key", definitions = TestDefinitions.loaded, clock = clock)
        Db.insertPlayer("7", "Ana")
        val company = Db.insertCompany("Acier")
        Db.update("update companies set course_number=6 where id=?", company)
        Db.update("insert into company_members (company_id,xuid,role,share_percent) values (?,7,'PDG',100)", company)
        val server = FakeDiscordServer()
        val plan = PlanApplier(ServerPlan.fromClasspath(), server, ServerPhase.BETA, false).run()
        assertTrue(plan.failures.isEmpty(), plan.failures.toString())
        val timer = FakeTimer(now)
        val context = BotContext(server, plan.ids, timer, ServerPhase.BETA)
        val features = BotFeatures.build(TestDefinitions.directory, Db.database, game = game, onlineCount = { 0 })(context)
        // La recette garde les fonctions de liaison et du jeu ; les parcours QG sont recettés séparément.
        val tested = features.filter { it.name in setOf("liaison", "profils-jeu", "commandes-jeu") }
        assertEquals(3, tested.size)
        tested.forEach { it.start() }
        val player = DiscordUser("99", "Ana")
        suspend fun event(e: DiscordEvent) { tested.forEach { it.handle(e) } }
        val before = FakeReply()
        event(DiscordEvent.CommandUsed("entreprise", emptyMap(), player, plan.ids.channel("discussion"), before))
        assertTrue(before.sent.single().first.text.contains("n'est pas relié"))
        val service = GameDiscordLinks(Db.database, clock)
        val code = Db.database.transaction { service.issue(it, checkNotNull(Xuid.parse("7"))) }
        val linked = FakeReply()
        event(DiscordEvent.CommandUsed("lier", mapOf("code" to code.code), player, plan.ids.channel("discussion"), linked))
        assertNotNull(service.findByDiscord("99"))
        Db.update("update site_registrations set pioneer=true,admitted_at=? where discord_user_id='99'", java.time.LocalDateTime.ofInstant(now, ZoneOffset.UTC))
        timer.advance(Duration.ofSeconds(30))
        assertEquals("Ana · P5", server.nicknames["99"])
        for (role in listOf("joueur-relie", "pdg", "beta", "pionnier", "prestige-5")) {
            assertTrue(plan.ids.role(role) in server.memberRoles.getValue("99"), role)
        }
        val card = FakeReply()
        event(DiscordEvent.CommandUsed("entreprise", emptyMap(), player, plan.ids.channel("discussion"), card))
        assertTrue(card.sent.single().second)
        assertTrue(card.sent.single().first.text.contains("Acier"))
        assertTrue(card.sent.single().first.buttons.any { it.label == "Montrer" })
        server.giveRole("99", plan.ids.role("staff")!!)
        val preview = FakeReply()
        event(DiscordEvent.CommandUsed("delier", emptyMap(), player, plan.ids.channel("discussion"), preview))
        val confirm = preview.sent.single().first.buttons.single { it.style == ButtonStyle.DANGER }
        event(DiscordEvent.ButtonPressed(confirm.id, player, plan.ids.channel("discussion"), "1", FakeReply()))
        assertNull(service.findByDiscord("99"))
        assertTrue(plan.ids.role("staff") in server.memberRoles.getValue("99"))
        assertFalse(plan.ids.role("prestige-5") in server.memberRoles.getValue("99"))
        assertEquals(1L, Db.count("companies"))
    }
}
