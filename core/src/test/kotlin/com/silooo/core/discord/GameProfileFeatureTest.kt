package com.silooo.core.discord

import java.time.Instant
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GameProfileFeatureTest {
    private class Profiles(private val values: List<GameProfile>) : GameProfileQueries {
        override suspend fun profiles() = values
        override suspend fun profile(discordId: String) = values.firstOrNull { it.discordId == discordId }
    }
    private class Roles : ManagedGameRoleStore {
        val values = mutableMapOf<String, Set<String>>()
        val nickValues = mutableMapOf<String, String>()
        val invited = mutableSetOf<String>()
        override suspend fun roleKeys(discordId: String) = values[discordId].orEmpty()
        override suspend fun replace(discordId: String, roleKeys: Set<String>) { values[discordId] = roleKeys }
        override suspend fun appliedNickname(discordId: String) = nickValues[discordId]
        override suspend fun appliedNickname(discordId: String, nickname: String?) { if (nickname == null) nickValues.remove(discordId) else nickValues[discordId] = nickname }
        override suspend fun knownUsers() = values.keys + nickValues.keys
        override suspend fun claimInvitation(discordId: String, at: Instant) = invited.add(discordId)
        override suspend fun finishInvitation(discordId: String, status: String) {}
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
        assertEquals(1, server.directs.size)
        feature.handle(DiscordEvent.MemberJoined(DiscordUser("42", "Ana"), Instant.parse("2026-11-06T12:01:00Z")))
        assertEquals(1, server.nicknameUpdates.size)
        assertEquals(1, server.directs.size)
        server.memberRoles["42"]?.remove(ids.getValue("prestige-5"))
        feature.handle(DiscordEvent.MemberJoined(DiscordUser("42", "Ana"), Instant.parse("2026-11-06T12:02:00Z")))
        assertTrue(ids.getValue("prestige-5") in server.memberRoles.getValue("42"))
    }

    @Test fun `prestige tier only picks a threshold already reached`() {
        val tiers = PrestigeRoleDefinitions(listOf(PrestigeRoleTier(1, "prestige-1"), PrestigeRoleTier(5, "prestige-5")))
        assertEquals(null, tiers.highest(0))
        assertEquals("prestige-1", tiers.highest(4))
        assertEquals("prestige-5", tiers.highest(5))
    }

    @Test fun `failed role grant retries, orphan cleanup removes only tracked game state`() = runBlocking {
        val server = FakeDiscordServer()
        val playerRole = runBlocking { server.createRole(RoleSpec("joueur-relie", null, false, false)) }
        val staffRole = runBlocking { server.createRole(RoleSpec("staff", null, false, false)) }
        val roles = Roles()
        val profile = GameProfile("42", "123", "Ana", null, null, null, false, false, 0)
        val feature = GameProfileFeature(
            BotContext(server, ServerIds(mapOf("joueur-relie" to playerRole, "staff" to staffRole), emptyMap()), FakeTimer(), ServerPhase.ATTENTE),
            Profiles(listOf(profile)), roles, PrestigeRoleDefinitions(emptyList()), Duration.ofSeconds(30),
        )
        server.failNextRoleGrants = 1
        feature.start()
        assertFalse(playerRole in server.memberRoles["42"].orEmpty())
        feature.handle(DiscordEvent.MemberJoined(DiscordUser("42", "Ana"), Instant.now()))
        assertTrue(playerRole in server.memberRoles["42"].orEmpty())
        roles.values["42"] = setOf("joueur-relie")
        roles.nickValues["42"] = "Ana"
        server.memberRoles.getOrPut("42") { mutableSetOf() }.add(playerRole)
        server.memberRoles.getValue("42").add(staffRole)
        server.nicknames["42"] = "Ana"
        val orphan = GameProfileFeature(
            BotContext(server, ServerIds(mapOf("joueur-relie" to playerRole, "staff" to staffRole), emptyMap()), FakeTimer(), ServerPhase.ATTENTE),
            Profiles(emptyList()), roles, PrestigeRoleDefinitions(emptyList()), Duration.ofSeconds(30),
        )
        orphan.start()
        assertFalse(playerRole in server.memberRoles.getValue("42"))
        assertTrue(staffRole in server.memberRoles.getValue("42"))
        assertEquals(null, server.nicknames["42"])
        assertTrue(roles.roleKeys("42").isEmpty())
    }
}
