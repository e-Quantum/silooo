package com.silooo.core.discord

import java.time.Instant
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class GameProfileFeatureTest {
    private class Profiles(private val values: List<GameProfile>) : GameProfileQueries {
        override suspend fun profiles() = values
        override suspend fun profile(discordId: String) = values.firstOrNull { it.discordId == discordId }
    }
    private class Roles : ManagedGameRoleStore {
        val values = mutableMapOf<String, Set<String>>()
        override suspend fun roleKeys(discordId: String) = values[discordId].orEmpty()
        override suspend fun replace(discordId: String, roleKeys: Set<String>) { values[discordId] = roleKeys }
        override suspend fun knownUsers() = values.keys
    }

    @Test fun `sync uses verified profile and manages only game roles`() = runBlocking {
        val server = FakeDiscordServer()
        val keys = listOf("staff", "joueur-relie", "pdg", "associe", "pionnier", "beta", "prestige-1", "prestige-5")
        val ids = keys.associateWith { kotlinx.coroutines.runBlocking { server.createRole(RoleSpec(it, null, false, false)) } }
        val roles = Roles()
        val profile = GameProfile("42", "123", "Ana", 15, 5, "PDG", pioneer = true, beta = true, prestige = 5)
        val feature = GameProfileFeature(
            BotContext(server, ServerIds(ids, emptyMap()), FakeTimer(Instant.parse("2026-11-06T12:00:00Z")), ServerPhase.ATTENTE),
            Profiles(listOf(profile)), roles,
            PrestigeRoleDefinitions(listOf(PrestigeRoleTier(1, "prestige-1"), PrestigeRoleTier(5, "prestige-5"))), Duration.ofSeconds(30),
        )
        feature.start()
        assertEquals("Ana · P5", server.nicknames["42"])
        assertEquals(setOf("joueur-relie", "pdg", "pionnier", "beta", "prestige-5"), roles.roleKeys("42"))
        assertFalse("staff" in roles.roleKeys("42"))
    }

    @Test fun `prestige tier only picks a threshold already reached`() {
        val tiers = PrestigeRoleDefinitions(listOf(PrestigeRoleTier(1, "prestige-1"), PrestigeRoleTier(5, "prestige-5")))
        assertEquals(null, tiers.highest(0))
        assertEquals("prestige-1", tiers.highest(4))
        assertEquals("prestige-5", tiers.highest(5))
    }
}
