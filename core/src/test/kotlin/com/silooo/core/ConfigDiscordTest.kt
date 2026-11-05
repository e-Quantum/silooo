package com.silooo.core

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Les deux variables des messages Discord du serveur : l'adresse du webhook (un secret) et l'intervalle du suiveur. */
class ConfigDiscordTest {
    private val complete = mapOf(
        Config.DB_URL to "jdbc:mariadb://mariadb:3306/silooo",
        Config.DB_USER to "silooo",
        Config.DB_PASSWORD to "mot-de-passe-de-test",
        Config.API_KEY to "cle-de-test",
    )
    private val address = "https://exemple.invalid/api/webhooks/1234/jeton-de-test"

    @Test
    @DisplayName("Sans variable, les messages Discord sont éteints (pas d'adresse) et le suiveur passe toutes les 15 secondes")
    fun defaults() {
        val config = Config.service(complete)

        assertNull(config.discordWebhook)
        assertEquals(Duration.ofSeconds(Config.DEFAULT_DISCORD_POLL_SECONDS), config.discordPoll)
        assertEquals(Duration.ofSeconds(15), config.discordPoll)
        assertNull(Config.service(complete + (Config.DISCORD_WEBHOOK to "   ")).discordWebhook, "une variable vide vaut une variable absente")
        assertEquals(Duration.ofSeconds(15), Config.service(complete + (Config.DISCORD_POLL_SECONDS to "  ")).discordPoll)
    }

    @Test
    @DisplayName("Une adresse https:// est reprise, avec les espaces autour retirés")
    fun theAddressIsRead() {
        val config = Config.service(complete + (Config.DISCORD_WEBHOOK to "  $address  "))

        assertEquals(java.net.URI(address), checkNotNull(config.discordWebhook).uri)
    }

    @Test
    @DisplayName("Une adresse qui ne commence pas par https:// est refusée, et le message ne reprend pas sa valeur (c'est un secret)")
    fun aBadAddressIsRefusedWithoutEchoingIt() {
        for (bad in listOf("http://exemple.invalid/hook-secret", "exemple.invalid/hook-secret", "jeton-secret-sans-adresse", "https://")) {
            val error = assertThrows<ConfigException>("« $bad » devrait être refusée") {
                Config.service(complete + (Config.DISCORD_WEBHOOK to bad))
            }
            val message = error.message!!
            assertTrue(message.contains(Config.DISCORD_WEBHOOK), message)
            assertFalse(message.contains("hook-secret"), message)
            assertFalse(message.contains("jeton-secret"), message)
            assertFalse(message.contains("exemple.invalid"), message)
        }
    }

    @Test
    @DisplayName("La variable règle le nombre de secondes ; un intervalle absurde est refusé avec le nom de la variable")
    fun theIntervalIsChecked() {
        assertEquals(Duration.ofSeconds(5), Config.service(complete + (Config.DISCORD_POLL_SECONDS to "5")).discordPoll)
        assertEquals(Duration.ofSeconds(1), Config.service(complete + (Config.DISCORD_POLL_SECONDS to " 1 ")).discordPoll)
        assertEquals(
            Duration.ofSeconds(Config.MAX_DISCORD_POLL_SECONDS),
            Config.service(complete + (Config.DISCORD_POLL_SECONDS to Config.MAX_DISCORD_POLL_SECONDS.toString())).discordPoll,
        )
        for (bad in listOf("0", "-5", "abc", "1.5", (Config.MAX_DISCORD_POLL_SECONDS + 1).toString(), "99999999999999999999")) {
            val error = assertThrows<ConfigException>("« $bad » devrait être refusé") {
                Config.service(complete + (Config.DISCORD_POLL_SECONDS to bad))
            }
            assertTrue(error.message!!.contains(Config.DISCORD_POLL_SECONDS), error.message)
        }
    }

    @Test
    @DisplayName("Les problèmes Discord s'ajoutent aux autres dans un seul message")
    fun problemsAreReportedTogether() {
        val error = assertThrows<ConfigException> {
            Config.service(mapOf(Config.DISCORD_WEBHOOK to "pas-une-adresse", Config.DISCORD_POLL_SECONDS to "zéro"))
        }

        for (name in listOf(Config.DB_URL, Config.API_KEY, Config.DISCORD_WEBHOOK, Config.DISCORD_POLL_SECONDS)) {
            assertTrue(error.message!!.contains(name), "Le message devrait citer $name : ${error.message}")
        }
    }

    @Test
    @DisplayName("La description de la configuration dit si l'adresse est posée, jamais l'adresse elle-même")
    fun theDescriptionNeverShowsTheAddress() {
        val without = Config.service(complete).toString()
        assertTrue(without.contains("discordWebhook=absente"), without)
        assertTrue(without.contains("discordPoll=PT15S"), without)

        val with = Config.service(complete + (Config.DISCORD_WEBHOOK to address)).toString()
        assertTrue(with.contains("discordWebhook=***"), with)
        assertFalse(with.contains("exemple.invalid"), with)
        assertFalse(with.contains("jeton-de-test"), with)
    }
}
