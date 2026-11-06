package com.silooo.core.discord

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GameCommandsFeatureTest {
    private val now = Instant.parse("2026-11-06T12:00:00Z")
    private class Links(var link: LinkIdentity?) : DiscordLinkGateway {
        var finds = 0
        override suspend fun findByDiscord(discordId: String): LinkIdentity? { finds++; return link }
        override suspend fun redeem(discordId: String, typed: String) = LinkOutcome(LinkStatus.INVALID_CODE)
        override suspend fun unlink(discordId: String, expected: LinkIdentity): LinkIdentity? = null
    }
    private class Queries : GameCommandQueries {
        var called = 0
        var xuid: String? = null
        override suspend fun company(xuid: String): GameCard? { called++; this.xuid = xuid; return GameCard("Entreprise", "Données") }
        override suspend fun factory(xuid: String, factory: String?): GameCard? = company(xuid)
        override suspend fun leaderboard(xuid: String) = GameCard("Classement", "Données")
        override suspend fun votes(xuid: String) = GameCard("Votes", "Données")
    }

    @Test fun `commande exige preuve et bouton est lie au demandeur et a la version`() = runBlocking {
        val links = Links(LinkIdentity("88", "Ana", now))
        val queries = Queries()
        val feature = GameCommandsFeature(BotContext(FakeDiscordServer(), ServerIds.NONE, FakeTimer(now), ServerPhase.ATTENTE), queries, links)
        val reply = FakeReply()
        feature.handle(DiscordEvent.CommandUsed("entreprise", emptyMap(), DiscordUser("42", "Ana"), null, reply))
        assertEquals(1, links.finds)
        assertEquals("88", queries.xuid) // Le profil authentifié, et non une option utilisateur, est la source du XUID.
        assertEquals(true, reply.sent.single().second)
        val button = reply.sent.single().first.buttons.single().id
        val other = FakeReply()
        feature.handle(DiscordEvent.ButtonPressed(button, DiscordUser("43", "Autre"), null, "m", other))
        assertEquals("Seul le membre qui a demandé cette carte peut l'afficher.", other.sent.single().first.text)
        val owner = FakeReply()
        links.link = links.link!!.copy(linkedAt = now.plusSeconds(1))
        feature.handle(DiscordEvent.ButtonPressed(button, DiscordUser("42", "Ana"), null, "m", owner))
        assertEquals(LinkingTexts.STALE, owner.sent.single().first.text)
        assertTrue(owner.sent.single().second)
    }
}
