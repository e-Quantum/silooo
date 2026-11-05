package com.silooo.core.discord

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Le QG fermé : les messages privés au staff seul, les mentions de rôle sur les rôles du staff seuls. */
class ClosedServerTest {
    private val staff = "900"
    private val moderation = "901"
    private val actus = "800"
    private val fake = FakeDiscordServer()
    private val closed = ClosedServer(fake, setOf(staff, moderation))
    private lateinit var channel: String

    private fun <T> inServer(block: suspend () -> T): T = runBlocking {
        val category = fake.createCategory(CategorySpec("test", null))
        channel = fake.createChannel(category, ChannelSpec("salon", ChannelKind.TEXTE, null, null, null, false))
        block()
    }

    @Test
    @DisplayName("Un message privé à un membre sans rôle du staff est retenu et compté")
    fun directToMemberIsHeld() = inServer {
        fake.memberRoles["42"] = mutableSetOf(actus)
        assertFalse(closed.direct("42", BotMessage("Bonjour")))
        assertFalse(closed.direct("absent", BotMessage("Bonjour")))
        assertTrue(fake.directs.isEmpty())
        assertEquals(2, closed.held)
    }

    @Test
    @DisplayName("Un message privé à un membre du staff ou de la modération part")
    fun directToStaffGoes() = inServer {
        fake.memberRoles["1"] = mutableSetOf(actus, staff)
        fake.memberRoles["2"] = mutableSetOf(moderation)
        assertTrue(closed.direct("1", BotMessage("Bonjour")))
        assertTrue(closed.direct("2", BotMessage("Bonjour")))
        assertEquals(listOf("1", "2"), fake.directs.map { it.first })
        assertEquals(0, closed.held)
    }

    @Test
    @DisplayName("Un membre du staff qui refuse les messages privés reste un refus du membre, pas une retenue")
    fun staffClosedDirect() = inServer {
        fake.memberRoles["1"] = mutableSetOf(staff)
        fake.closedDirects += "1"
        assertFalse(closed.direct("1", BotMessage("Bonjour")))
        assertEquals(0, closed.held)
    }

    @Test
    @DisplayName("Un message posté ne mentionne que les rôles du staff")
    fun postKeepsStaffMentionsOnly() = inServer {
        closed.post(channel, BotMessage("Annonce", mentionRoleIds = setOf(actus, staff), mentionUserIds = setOf("42")))
        val sent = fake.posted.single().message
        assertEquals(setOf(staff), sent.mentionRoleIds)
        assertEquals(setOf("42"), sent.mentionUserIds)
        assertEquals("Annonce", sent.text)
    }

    @Test
    @DisplayName("Un message modifié ne mentionne que les rôles du staff")
    fun editKeepsStaffMentionsOnly() = inServer {
        val id = closed.post(channel, BotMessage("Tableau"))
        closed.edit(channel, id, BotMessage("Tableau", mentionRoleIds = setOf(actus, moderation), mentionUserIds = setOf("7")))
        val sent = fake.edits.single().message
        assertEquals(setOf(moderation), sent.mentionRoleIds)
        assertEquals(setOf("7"), sent.mentionUserIds)
    }

    @Test
    @DisplayName("Sans mention de rôle, ou avec les seuls rôles du staff, le message passe inchangé")
    fun untouchedMessages() = inServer {
        val plain = BotMessage("Texte", mentionUserIds = setOf("42"))
        val staffOnly = BotMessage("Texte du staff", mentionRoleIds = setOf(staff, moderation))
        closed.post(channel, plain)
        closed.post(channel, staffOnly)
        assertSame(plain, fake.posted[0].message)
        assertSame(staffOnly, fake.posted[1].message)
    }

    @Test
    @DisplayName("Un message de rôles du tout public perd toutes ses mentions de rôle")
    fun noStaffRoleLeavesNoMention() = inServer {
        closed.post(channel, BotMessage("Annonce", mentionRoleIds = setOf(actus)))
        assertTrue(fake.posted.single().message.mentionRoleIds.isEmpty())
    }

    @Test
    @DisplayName("send, texte seul, passe tel quel, et le reste va au vrai serveur")
    fun sendAndRestPassThrough() = inServer {
        closed.send(channel, "Bonjour")
        assertEquals(listOf(channel to "Bonjour"), fake.messages)
        closed.status("En ligne")
        assertEquals(listOf("En ligne"), fake.statuses)
        fake.names["42"] = "Alex"
        assertEquals("Alex", closed.memberName("42"))
    }
}
