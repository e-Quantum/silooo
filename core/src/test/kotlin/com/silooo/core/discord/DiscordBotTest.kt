package com.silooo.core.discord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Le bot : ce qui tombe tombe seul, sans lever, et le jeton ne s'affiche jamais. */
class DiscordBotTest {
    private val config = BotConfig(BotMode.OUI, BotToken("jeton-secret-de-test"), "123456789", ServerPhase.ATTENTE)

    @Test
    @DisplayName("Une connexion refusée rend null sans lever")
    fun refusedConnection() {
        val bot = DiscordBot(config, { throw IllegalStateException("401 pour jeton-secret-de-test") })
        assertNull(bot.runGuarded())
        bot.close()
    }

    @Test
    @DisplayName("Un plan illisible arrête le bot avant la connexion")
    fun unreadablePlan() {
        val bot = DiscordBot(config, { fail("le bot ne doit pas se connecter") }, loadPlan = { throw PlanException("plan cassé") })
        assertNull(bot.runGuarded())
    }

    @Test
    @DisplayName("Avec une connexion, le plan s'applique, le journal reçoit le compte rendu, et close coupe la connexion")
    fun appliesPlanAndCloses() {
        val fake = FakeDiscordServer()
        var closed = false
        val connection = object : DiscordConnection {
            override val server: DiscordServer = fake
            override fun close() {
                closed = true
            }
        }
        val bot = DiscordBot(config, { connection })
        val report = checkNotNull(bot.runGuarded())
        assertEquals(emptyList<String>(), report.failures)
        assertEquals(1, fake.messages.size)
        assertFalse(closed)
        bot.close()
        assertTrue(closed)
        bot.close()
    }

    @Test
    @DisplayName("Le jeton ne s'affiche ni dans la configuration ni dans un message d'erreur masqué")
    fun tokenNeverShows() {
        val token = BotToken("jeton-secret-de-test")
        assertEquals("***", token.toString())
        assertEquals("erreur 401 avec *** dedans", token.mask("erreur 401 avec jeton-secret-de-test dedans"))
        assertEquals("", token.mask(null))
        assertFalse(config.toString().contains("jeton-secret"))
        assertTrue(config.toString().contains("mode=oui"))
        assertFalse(config.dryRun)
        assertTrue(config.copy(mode = BotMode.A_BLANC).dryRun)
    }

    @Test
    @DisplayName("Les mots des variables se lisent sans tenir compte de la casse ni des espaces")
    fun words() {
        assertEquals(BotMode.A_BLANC, BotMode.parse(" A-Blanc "))
        assertNull(BotMode.parse("peut-être"))
        assertEquals(ServerPhase.OUVERT, ServerPhase.parse("Ouvert"))
        assertNull(ServerPhase.parse(""))
    }
}
