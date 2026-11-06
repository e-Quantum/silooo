package com.silooo.core.discord

import com.silooo.core.db.FakeDatabase
import com.silooo.core.discord.linking.GameDiscordLinks
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PollsTest {
    @Test
    @DisplayName("La commande place ses options obligatoires avant les réponses facultatives")
    fun requiredOptionsComeFirst() {
        val spec = PollsFeature(BotContext(FakeDiscordServer(), ServerIds.NONE, FakeTimer(), ServerPhase.ATTENTE), EmptyPollStore(), GameDiscordLinks(FakeDatabase())).commands.single()
        assertEquals(listOf("question", "duree-minutes", "consequence", "reponse1", "reponse2"), spec.options.take(5).map { it.name })
        assertTrue(spec.options.take(5).all { it.required })
        assertTrue(spec.options.drop(5).none { it.required })
        assertTrue(spec.staffOnly)
    }

    @Test
    @DisplayName("Une carte de sondage offre un bouton par choix puis affiche les voix sans mention")
    fun cardAndResults() {
        val poll = CommunityPoll("poll-1", "channel", "message", "Quelle direction ?", listOf(PollOption("A", "Ajustement A"), PollOption("B", "Ajustement B")), Instant.parse("2026-11-05T11:00:00Z"), "staff")
        val open = PollTexts.card(poll)
        assertEquals(2, open.buttons.size)
        assertEquals(listOf("community-poll-vote:poll-1:0", "community-poll-vote:poll-1:1"), open.buttons.map { it.id })
        assertFalse(open.text.contains("@everyone"))
        val closed = PollTexts.card(poll.copy(closedAt = Instant.parse("2026-11-05T11:00:00Z")), PollTally(listOf(3, 2), 5))
        assertTrue(closed.buttons.isEmpty())
        assertTrue(closed.text.contains("3 voix"))
        assertTrue(closed.text.contains("Résultat : 5 voix."))
    }

    @Test
    @DisplayName("Une réponse manquante refuse l'ouverture sans toucher aux liaisons ni à la base")
    fun missingSecondAnswerDoesNotPersist() = runBlocking {
        val db = FakeDatabase()
        val store = EmptyPollStore()
        val server = FakeDiscordServer()
        val reply = FakeReply()
        val feature = PollsFeature(BotContext(server, ServerIds(emptyMap(), mapOf("sondages" to "polls")), FakeTimer(), ServerPhase.ATTENTE), store, GameDiscordLinks(db))
        feature.handle(DiscordEvent.CommandUsed("sondage", mapOf("question" to "Quelle direction ?", "duree-minutes" to "30", "consequence" to "La majorité choisit", "reponse1" to "A"), DiscordUser("3", "staff"), "polls", reply))
        assertTrue(reply.sent.single().first.text.contains("deux à cinq réponses"))
        assertTrue(store.created.isEmpty())
        assertEquals(0, db.attempts)
    }

    private class EmptyPollStore : PollStore {
        val created = mutableListOf<CommunityPoll>()
        override suspend fun create(poll: CommunityPoll) { created += poll }
        override suspend fun active(): List<CommunityPoll> = emptyList()
        override suspend fun poll(id: String): CommunityPoll? = null
        override suspend fun attachMessage(id: String, messageId: String) = Unit
        override suspend fun vote(id: String, xuid: String, option: Int, at: Instant): Boolean = false
        override suspend fun tally(id: String): PollTally = PollTally(emptyList(), 0)
        override suspend fun close(id: String, at: Instant): CommunityPoll? = null
        override suspend fun resultPublished(id: String, at: Instant) = Unit
    }
}
