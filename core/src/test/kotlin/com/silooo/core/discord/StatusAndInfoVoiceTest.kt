package com.silooo.core.discord

import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Le statut qui alterne et le vocal « N en ligne » renommé dans le quota de Discord. */
class StatusAndInfoVoiceTest {
    private val fake = FakeDiscordServer()
    private val timer = FakeTimer()

    private fun advance(duration: Duration) = runBlocking { timer.advance(duration) }

    @Test
    @DisplayName("Le statut alterne la ligne de jeu et le compte des membres, toutes les 60 s")
    fun alternatesTheStatus() {
        fake.members = 3
        val feature = StatusFeature(BotContext(fake, ServerIds.NONE, timer, ServerPhase.ATTENTE), StatusRules(alternateSeconds = 60))
        runBlocking { feature.start() }
        assertEquals(listOf("Joue à play.silooo.com"), fake.statuses)
        advance(Duration.ofSeconds(60))
        assertEquals("Regarde 3 entrepreneurs", fake.statuses.last())
        fake.members = 1
        advance(Duration.ofSeconds(120))
        assertEquals(listOf("Joue à play.silooo.com", "Regarde 3 entrepreneurs", "Joue à play.silooo.com", "Regarde 1 entrepreneur"), fake.statuses)
    }

    @Test
    @DisplayName("Le vocal « N en ligne » suit les joueurs en jeu, deux renommages par dix minutes au plus, jamais pour rien")
    fun renamesWithinTheBudget() {
        var online = 0
        val channel = runBlocking {
            val category = fake.createCategory(CategorySpec("Infos", null))
            fake.createChannel(category, ChannelSpec("👥 0 en ligne", ChannelKind.VOCAL, null, emptySet(), null, true))
        }
        val ids = ServerIds(emptyMap(), mapOf(InfoVoiceFeature.ONLINE to channel))
        val feature = InfoVoiceFeature(BotContext(fake, ids, timer, ServerPhase.ATTENTE), InfoVoiceRules(renamesMax = 2, windowMinutes = 10)) { online }
        runBlocking { feature.start() }
        assertTrue(fake.renames.isEmpty(), "zéro en ligne : le nom du plan reste")

        online = 4
        advance(Duration.ofMinutes(5))
        assertEquals(listOf(channel to "👥 4 en ligne"), fake.renames)
        online = 6
        advance(Duration.ofMinutes(5))
        assertEquals("👥 6 en ligne", fake.renames.last().second)
        online = 9
        advance(Duration.ofMinutes(5))
        assertEquals(2, fake.renames.size, "deux renommages en dix minutes : le troisième attend")
        advance(Duration.ofMinutes(5))
        assertEquals("👥 9 en ligne", fake.renames.last().second)
        advance(Duration.ofMinutes(30))
        assertEquals(3, fake.renames.size, "le nombre n'a pas changé : pas de renommage")
        assertEquals("👥 9 en ligne", fake.channels.single().name)
    }

    @Test
    @DisplayName("Sans le vocal sur le serveur, la fonction ne fait rien")
    fun staysQuietWithoutTheChannel() {
        val feature = InfoVoiceFeature(BotContext(fake, ServerIds.NONE, timer, ServerPhase.ATTENTE), InfoVoiceRules(2, 10)) { 5 }
        runBlocking { feature.start() }
        advance(Duration.ofHours(1))
        assertTrue(fake.renames.isEmpty())
        assertEquals(0, timer.pending)
    }
}
