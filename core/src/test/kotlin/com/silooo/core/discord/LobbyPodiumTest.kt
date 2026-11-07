package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LobbyPodiumTest {
    private val zone = ZoneId.of("Europe/Paris")

    private class Store(private val lines: Map<LocalDate, List<PodiumLine>>) : LobbyPodiumStore {
        val claims = mutableSetOf<LocalDate>()
        override suspend fun podium(week: LocalDate, size: Int) = lines[week].orEmpty().take(size)
        override suspend fun claim(week: LocalDate): Boolean = claims.add(week)
    }

    /** Un salon « discussion » dans le faux Discord : le podium y écrit. */
    private fun context(server: FakeDiscordServer, timer: FakeTimer): BotContext = runBlocking {
        val category = server.createCategory(CategorySpec("Accueil", null))
        val chat = server.createChannel(category, ChannelSpec("discussion", ChannelKind.TEXTE, null, null, null, false))
        BotContext(server, ServerIds(emptyMap(), mapOf("discussion" to chat)), timer, ServerPhase.ATTENTE)
    }

    @Test
    fun `Le lundi a neuf heures, le podium de la semaine finie part une fois, les non relies restent anonymes`() = runBlocking {
        val server = FakeDiscordServer()
        val timer = FakeTimer(Instant.parse("2026-11-09T07:59:59Z")) // lundi 8 h 59 à Paris
        val week = LocalDate.parse("2026-11-02")
        val store = Store(mapOf(week to listOf(PodiumLine("Ana", 42), PodiumLine(null, 30), PodiumLine("Kr", 1), PodiumLine("Zoe", 0))))
        val context = context(server, timer)
        val chat = context.ids.channel("discussion")
        fun feature() = LobbyPodiumFeature(context, ReportRules(9, 7, 7), zone, 3, store)
        feature().start()
        assertTrue(server.messages.isEmpty(), "le lundi précédent n'avait rien à poster")
        timer.advance(Duration.ofSeconds(1))
        val (channel, text) = server.messages.single()
        assertEquals(chat, channel)
        assertEquals(
            listOf(LobbyPodiumTexts.TITLE, "1. Ana, 42 blocs", "2. un joueur non relié, 30 blocs", "3. Kr, 1 bloc", LobbyPodiumTexts.FOOTER),
            text.lines(),
        )
        // Un redémarrage le même lundi ne poste pas deux fois.
        feature().start()
        assertEquals(1, server.messages.size)
    }

    @Test
    fun `Une semaine sans saut ne poste rien`() = runBlocking {
        val server = FakeDiscordServer()
        val timer = FakeTimer(Instant.parse("2026-11-09T08:00:01Z"))
        LobbyPodiumFeature(context(server, timer), ReportRules(9, 7, 7), zone, 3, Store(emptyMap())).start()
        assertTrue(server.messages.isEmpty())
    }
}
