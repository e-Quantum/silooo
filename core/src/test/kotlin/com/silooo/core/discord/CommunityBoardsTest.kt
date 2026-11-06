package com.silooo.core.discord

import com.silooo.core.site.NewsArticle
import java.time.LocalDate
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CommunityBoardsTest {
    @Test
    @DisplayName("Les patch notes existantes ne sont pas republiées au démarrage, une nouvelle actu produit une carte")
    fun patchNotesSkipHistoryAndPublishNewArticles() = runBlocking {
        val fake = FakeDiscordServer()
        val category = fake.createCategory(CategorySpec("Jeu", null))
        val channel = fake.createChannel(category, ChannelSpec("patch-notes", ChannelKind.TEXTE, null, emptySet(), null, false))
        val timer = FakeTimer()
        val state = MemoryCommunityStateStore()
        val articles = mutableListOf(article("premiere", LocalDate.parse("2026-11-01")))
        val feature = PatchNotesFeature(
            BotContext(fake, ServerIds(emptyMap(), mapOf("patch-notes" to channel)), timer, ServerPhase.BETA),
            "https://silooo.com",
            state,
            articles,
        )
        feature.start()
        assertTrue(fake.posted.isEmpty(), "le premier démarrage ne rediffuse pas l'historique")
        assertEquals("premiere", state.values[CommunityBoardTexts.PATCH_CURSOR])

        articles += article("deuxieme", LocalDate.parse("2026-11-06"))
        timer.advance(CommunityBoardsTestInterval.PATCH)
        assertEquals(1, fake.posted.size)
        assertTrue(fake.posted.single().message.text.contains("Deuxieme"))
        assertEquals("https://silooo.com/actus/deuxieme/", fake.posted.single().message.buttons.single().url)
        assertEquals("deuxieme", state.values[CommunityBoardTexts.PATCH_CURSOR])
    }

    @Test
    @DisplayName("Le staff met à jour le statut dans le fil et Oooscar modifie sa carte")
    fun suggestionStatusEditsItsThreadCard() = runBlocking {
        val fake = FakeDiscordServer()
        val category = fake.createCategory(CategorySpec("Suggestions", null))
        val parent = fake.createChannel(category, ChannelSpec("suggestions", ChannelKind.FORUM, null, null, null, false))
        val thread = fake.createChannel(category, ChannelSpec("idee", ChannelKind.TEXTE, null, null, null, false))
        fake.parents[thread] = parent
        val state = MemoryCommunityStateStore()
        val messages = InMemoryBotMessageStore()
        val context = BotContext(fake, ServerIds(mapOf("staff" to "staff-role"), mapOf("suggestions" to parent)), FakeTimer(), ServerPhase.BETA)
        val feature = SuggestionStatusFeature(context, state, messages)
        val author = DiscordUser("17", "staff", roleIds = setOf("staff-role"))

        feature.handle(DiscordEvent.CommandUsed("suggestion", mapOf("statut" to "acceptee"), author, thread, FakeReply()))
        feature.handle(DiscordEvent.CommandUsed("suggestion", mapOf("statut" to "faite"), author, thread, FakeReply()))

        assertEquals(1, fake.posted.count { it.channelId == thread })
        assertEquals(1, fake.edits.count { it.channelId == thread })
        assertTrue(fake.edits.single().message.text.contains("faite"))
        assertEquals("faite", state.values["suggestion-status:$thread"])
    }

    private fun article(slug: String, date: LocalDate) = NewsArticle(
        slug, slug.replaceFirstChar { it.uppercase() }, "Une mise à jour publiée.", date, false, true,
        null, "", emptyList(), 1,
    )

    private class MemoryCommunityStateStore : CommunityStateStore {
        val values = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = values[key]
        override suspend fun put(key: String, value: String) { values[key] = value }
    }

    private object CommunityBoardsTestInterval { val PATCH = Duration.ofMinutes(5) }
}
