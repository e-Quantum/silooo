package com.silooo.core

import com.silooo.core.discord.BotMode
import com.silooo.core.discord.ServerPhase
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Les variables du bot Discord (ADR 0007) : éteint par défaut, exigeant dès qu'il est demandé, jamais bavard sur le jeton. */
class ConfigDiscordTest {
    private val base = mapOf(
        Config.DB_URL to "jdbc:mariadb://localhost:3306/silooo",
        Config.DB_USER to "u",
        Config.DB_PASSWORD to "p",
        Config.API_KEY to "k",
        Config.DEFINITIONS_DIR to System.getProperty("silooo.definitions.dir"),
    )

    private fun service(extra: Map<String, String>) = Config.service(base + extra, Path.of("."))

    private fun refused(extra: Map<String, String>): String =
        assertThrows(ConfigException::class.java) { service(extra) }.message.orEmpty()

    @Test
    @DisplayName("Sans la variable, ou avec non, le bot n'existe pas")
    fun off() {
        assertNull(service(emptyMap()).bot)
        assertNull(service(mapOf(Config.DISCORD_ACTIF to "non")).bot)
        assertNull(service(mapOf(Config.DISCORD_ACTIF to "non", Config.DISCORD_TOKEN to "jeton")).bot)
        assertTrue(service(emptyMap()).toString().endsWith("bot=non)"))
    }

    @Test
    @DisplayName("Demandé, le bot exige le jeton et l'identifiant numérique du serveur")
    fun requirements() {
        val problems = refused(mapOf(Config.DISCORD_ACTIF to "a-blanc"))
        assertTrue(problems.contains(Config.DISCORD_TOKEN), problems)
        assertTrue(problems.contains(Config.DISCORD_SERVEUR), problems)
        val notNumeric = refused(mapOf(Config.DISCORD_ACTIF to "oui", Config.DISCORD_TOKEN to "jeton", Config.DISCORD_SERVEUR to "silooo"))
        assertTrue(notNumeric.contains("identifiant numérique"), notNumeric)
    }

    @Test
    @DisplayName("Un mode ou une phase inconnus arrêtent le démarrage")
    fun unknownWords() {
        assertTrue(refused(mapOf(Config.DISCORD_ACTIF to "peut-être")).contains(Config.DISCORD_ACTIF))
        val phase = refused(mapOf(Config.DISCORD_ACTIF to "oui", Config.DISCORD_TOKEN to "jeton", Config.DISCORD_SERVEUR to "1", Config.DISCORD_PHASE to "alpha"))
        assertTrue(phase.contains(Config.DISCORD_PHASE), phase)
    }

    @Test
    @DisplayName("Posées, les variables donnent le bot, en phase d'attente par défaut, et le jeton ne s'affiche jamais")
    fun present() {
        val config = service(mapOf(Config.DISCORD_ACTIF to "oui", Config.DISCORD_TOKEN to " jeton-secret ", Config.DISCORD_SERVEUR to " 123456789 "))
        val bot = checkNotNull(config.bot)
        assertEquals(BotMode.OUI, bot.mode)
        assertEquals("123456789", bot.guildId)
        assertEquals(ServerPhase.ATTENTE, bot.phase)
        assertEquals("jeton-secret", bot.token.reveal())
        assertFalse(bot.dryRun)
        assertFalse(config.toString().contains("jeton-secret"))
        assertTrue(config.toString().contains("mode=oui"))

        val dry = service(mapOf(Config.DISCORD_ACTIF to "A-BLANC", Config.DISCORD_TOKEN to "jeton", Config.DISCORD_SERVEUR to "1", Config.DISCORD_PHASE to "beta")).bot
        assertNotNull(dry)
        assertTrue(dry!!.dryRun)
        assertEquals(ServerPhase.BETA, dry.phase)
    }
}
