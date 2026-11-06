package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinkingTest {
    private val now = Instant.parse("2026-11-06T12:00:00Z")
    private val user = DiscordUser("42", "Ana")
    private val identity = LinkIdentity("123456", "Ana Bedrock", now)

    private class Links : DiscordLinkGateway {
        var outcome = LinkOutcome(LinkStatus.INVALID_CODE)
        var current: LinkIdentity? = null
        val attempts = mutableListOf<Pair<String, String>>()
        var unlinks = 0
        var failure: Exception? = null

        override suspend fun redeem(discordId: String, typed: String): LinkOutcome {
            attempts += discordId to typed
            failure?.let { throw it }
            if (outcome.status == LinkStatus.LINKED) current = outcome.link
            return outcome
        }

        override suspend fun findByDiscord(discordId: String): LinkIdentity? {
            failure?.let { throw it }
            return current
        }

        override suspend fun unlink(discordId: String): LinkIdentity? {
            failure?.let { throw it }
            unlinks++
            return current.also { current = null }
        }
    }

    private inner class Setup(phase: ServerPhase = ServerPhase.ATTENTE) {
        val server = FakeDiscordServer()
        val timer = FakeTimer(now)
        val links = Links()
        val nicknames = mutableListOf<Pair<String, String?>>()
        var nicknameFailure = false
        val roles = listOf("joueur-relie", "pdg", "associe", "prestige-5", "pionnier", "beta", "staff", "niveau-10", "entrepreneur", "rappel-vote").associateWith {
            runBlocking { server.createRole(RoleSpec(it, null, false, false)) }
        }
        val context = BotContext(server, ServerIds(roles, emptyMap()), timer, phase)
        val feature = LinkingFeature(context, links) { id, name ->
            if (nicknameFailure) throw DiscordRefusal("surnom refusé")
            nicknames += id to name
        }

        fun command(name: String, options: Map<String, String> = emptyMap()): FakeReply = runBlocking {
            FakeReply().also { feature.handle(DiscordEvent.CommandUsed(name, options, user, "salon", it)) }
        }

        fun button(id: String, actor: DiscordUser = user): FakeReply = runBlocking {
            FakeReply().also { feature.handle(DiscordEvent.ButtonPressed(id, actor, "salon", "message", it)) }
        }

        fun form(code: String): FakeReply = runBlocking {
            FakeReply().also { feature.handle(DiscordEvent.FormSubmitted(LinkingTexts.FORM_ID, mapOf("code" to code), user, "salon", it)) }
        }

        fun confirmButton(): String = command("delier").sent.single().first.buttons.single().id

        fun assertNoPublicMessages() {
            assertTrue(server.posted.isEmpty())
            assertTrue(server.directs.isEmpty())
        }
    }

    @Test
    fun mandatoryCodeAndPrivateModal() {
        val s = Setup()
        assertTrue(s.feature.commands.first { it.name == "lier" }.options.single { it.name == "code" }.required)
        val reply = s.button(BienvenueTexts.LINK_BUTTON_ID)
        val form = reply.forms.single()
        assertEquals(LinkingTexts.FORM_ID, form.id)
        assertEquals(6, form.fields.single().maxLength)
        assertTrue(form.fields.single().required)
        assertTrue(reply.sent.isEmpty())
        assertTrue(s.links.attempts.isEmpty())
        assertTrue(s.nicknames.isEmpty())
        s.assertNoPublicMessages()
    }

    @Test
    fun onlyVerifiedLinkUpdatesNicknameAndLinkedRole() {
        val s = Setup()
        s.links.outcome = LinkOutcome(LinkStatus.LINKED, identity)
        val reply = s.command("lier", mapOf("code" to "ABC123"))
        assertEquals(listOf(user.id to "ABC123"), s.links.attempts)
        assertEquals(listOf(user.id to identity.gamertag), s.nicknames)
        assertEquals(setOf(s.roles.getValue("joueur-relie")), s.server.memberRoles[user.id])
        assertEquals(LinkingTexts.linked(identity.gamertag), reply.sent.single().first.text)
        assertTrue(reply.sent.single().second)
        s.assertNoPublicMessages()
    }

    @Test
    fun formUsesTheSameProofAsSlashCommand() {
        val s = Setup()
        s.links.outcome = LinkOutcome(LinkStatus.LINKED, identity)
        val reply = s.form("Z9Y8X7")
        assertEquals(listOf(user.id to "Z9Y8X7"), s.links.attempts)
        assertTrue(reply.sent.single().second)
        assertEquals(listOf(user.id to identity.gamertag), s.nicknames)
    }

    @Test
    fun failedProofsNeverGrantRolesOrNickname() {
        val expected = mapOf(
            LinkStatus.INVALID_CODE to (LinkingTexts.INVALID + "\n" + LinkingTexts.CODE_HELP + "\n" + LinkingTexts.WAIT_HELP),
            LinkStatus.EXPIRED_CODE to LinkingTexts.EXPIRED,
            LinkStatus.DISCORD_TAKEN to LinkingTexts.DISCORD_TAKEN,
            LinkStatus.PLAYER_TAKEN to LinkingTexts.PLAYER_TAKEN,
            LinkStatus.RATE_LIMITED to LinkingTexts.RATE_LIMITED,
        )
        for ((status, text) in expected) {
            val s = Setup()
            s.links.outcome = LinkOutcome(status)
            val reply = s.command("lier", mapOf("code" to "ABC123"))
            assertEquals(text, reply.sent.single().first.text)
            assertFalse(reply.sent.single().first.text.contains("ABC123"))
            assertTrue(reply.sent.single().second)
            assertTrue(s.nicknames.isEmpty())
            assertTrue(s.server.memberRoles.isEmpty())
            s.assertNoPublicMessages()
        }
    }

    @Test
    fun missingCodeDoesNotBypassService() {
        val s = Setup()
        s.command("lier")
        assertEquals(listOf(user.id to ""), s.links.attempts)
        assertTrue(s.server.memberRoles.isEmpty())
    }

    @Test
    fun serviceFailureDoesNotExposeCodeOrException() {
        val s = Setup()
        s.links.failure = IllegalStateException("secret ABC123")
        val reply = s.command("lier", mapOf("code" to "ABC123"))
        assertEquals(LinkingTexts.UNAVAILABLE, reply.sent.single().first.text)
        assertTrue(s.nicknames.isEmpty())
        assertTrue(s.server.memberRoles.isEmpty())
    }

    @Test
    fun linkedWithoutIdentityCannotChangeDiscord() {
        val s = Setup()
        s.links.outcome = LinkOutcome(LinkStatus.LINKED)
        assertEquals(LinkingTexts.UNAVAILABLE, s.form("ABC123").sent.single().first.text)
        assertTrue(s.nicknames.isEmpty())
        assertTrue(s.server.memberRoles.isEmpty())
    }

    @Test
    fun nicknameRefusalDoesNotLoseProofOrPreventIndependentRole() {
        val s = Setup()
        s.links.outcome = LinkOutcome(LinkStatus.LINKED, identity)
        s.nicknameFailure = true
        assertEquals(LinkingTexts.PROFILE_FAILED, s.form("ABC123").sent.single().first.text)
        assertEquals(identity, s.links.current)
        assertEquals(setOf(s.roles.getValue("joueur-relie")), s.server.memberRoles[user.id])
    }

    @Test
    fun previewDoesNotUnlinkAndConfirmationIsBoundToItsAuthor() {
        val s = Setup()
        s.links.current = identity
        val preview = s.command("delier")
        assertTrue(preview.sent.single().second)
        assertEquals(0, s.links.unlinks)
        assertEquals(identity, s.links.current)
        assertTrue(s.nicknames.isEmpty())
        val id = preview.sent.single().first.buttons.single().id
        assertEquals(LinkingTexts.OTHER_USER, s.button(id, DiscordUser("99", "Autre")).sent.single().first.text)
        assertEquals(0, s.links.unlinks)
        assertEquals(LinkingTexts.UNLINKED, s.button(id).sent.single().first.text)
        assertEquals(1, s.links.unlinks)
        s.assertNoPublicMessages()
    }

    @Test
    fun unlinkPreservesStaffXpAndUnrelatedRolesAndSecondClickIsHarmless() {
        val s = Setup()
        s.links.current = identity
        s.server.memberRoles[user.id] = s.roles.values.toMutableSet()
        val id = s.confirmButton()
        val reply = s.button(id)
        assertTrue(reply.sent.single().second)
        assertEquals(LinkingTexts.UNLINKED, reply.sent.single().first.text)
        assertEquals(listOf(user.id to null), s.nicknames)
        assertEquals(setOf("staff", "niveau-10", "entrepreneur", "rappel-vote").map { s.roles.getValue(it) }.toSet(), s.server.memberRoles[user.id])
        assertNull(s.links.current)
        assertEquals(LinkingTexts.STALE, s.button(id).sent.single().first.text)
        assertEquals(1, s.links.unlinks)
        assertEquals(1, s.nicknames.size)
    }

    @Test
    fun expiredConfirmationDoesNotUnlink() {
        val s = Setup()
        s.links.current = identity
        val id = s.confirmButton()
        runBlocking { s.timer.advance(Duration.ofMinutes(10)) }
        assertEquals(LinkingTexts.STALE, s.button(id).sent.single().first.text)
        assertEquals(0, s.links.unlinks)
        assertEquals(identity, s.links.current)
    }

    @Test
    fun confirmationCannotUnlinkANewerLinkEvenForSameXuid() {
        val s = Setup()
        s.links.current = identity
        val id = s.confirmButton()
        s.links.current = identity.copy(linkedAt = now.plusSeconds(1))
        assertEquals(LinkingTexts.STALE, s.button(id).sent.single().first.text)
        assertEquals(0, s.links.unlinks)
        assertTrue(s.nicknames.isEmpty())
    }

    @Test
    fun newPreviewInvalidatesPreviousConfirmation() {
        val s = Setup()
        s.links.current = identity
        val old = s.confirmButton()
        val recent = s.confirmButton()
        assertEquals(LinkingTexts.STALE, s.button(old).sent.single().first.text)
        assertEquals(0, s.links.unlinks)
        assertEquals(LinkingTexts.UNLINKED, s.button(recent).sent.single().first.text)
    }

    @Test
    fun unlinkedUserGetsNoConfirmation() {
        val s = Setup()
        val reply = s.command("delier")
        assertEquals(LinkingTexts.NOT_LINKED, reply.sent.single().first.text)
        assertTrue(reply.sent.single().first.buttons.isEmpty())
        assertTrue(reply.sent.single().second)
    }

    @Test
    fun unlinkContinuesRoleCleanupWhenNicknameIsRefused() {
        val s = Setup()
        s.links.current = identity
        s.server.memberRoles[user.id] = s.roles.values.toMutableSet()
        s.nicknameFailure = true
        val reply = s.button(s.confirmButton())
        assertEquals(LinkingTexts.CLEANUP_FAILED, reply.sent.single().first.text)
        assertEquals(setOf("staff", "niveau-10", "entrepreneur", "rappel-vote").map { s.roles.getValue(it) }.toSet(), s.server.memberRoles[user.id])
        assertNull(s.links.current)
    }

    @Test
    fun betaDoesNotClaimTheGameIsStillClosed() {
        val s = Setup(ServerPhase.BETA)
        val modal = s.button(BienvenueTexts.LINK_BUTTON_ID).forms.single()
        assertFalse(modal.fields.single().placeholder!!.contains("fermé"))
        val response = s.form("ABC123").sent.single().first.text
        assertTrue(response.contains(LinkingTexts.CODE_HELP))
        assertFalse(response.contains(LinkingTexts.WAIT_HELP))
    }

    @Test
    fun cancellationIsNotSwallowed() {
        val s = Setup()
        s.links.failure = CancellationException("arrêt")
        assertThrows(CancellationException::class.java) { s.form("ABC123") }
    }

    @Test
    fun unrelatedEventsAreIgnored() {
        val s = Setup()
        assertTrue(s.command("niveau").sent.isEmpty())
        assertTrue(s.button("autre").sent.isEmpty())
        val reply = FakeReply()
        runBlocking { s.feature.handle(DiscordEvent.FormSubmitted("autre", mapOf("code" to "ABC123"), user, "salon", reply)) }
        assertTrue(reply.sent.isEmpty())
        assertTrue(s.links.attempts.isEmpty())
    }
}
