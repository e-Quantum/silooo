package com.silooo.core

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** L'intervalle du relevé des livraisons de Gemmes (SILOOO_DELIVERY_POLL_SECONDS). */
class ConfigDeliveryPollTest {
    private val complete = mapOf(
        Config.DB_URL to "jdbc:mariadb://mariadb:3306/silooo",
        Config.DB_USER to "silooo",
        Config.DB_PASSWORD to "mot-de-passe-de-test",
        Config.API_KEY to "cle-de-test",
    )

    @Test
    @DisplayName("Sans variable, le relevé passe toutes les 30 secondes")
    fun defaultIsThirtySeconds() {
        assertEquals(Duration.ofSeconds(Config.DEFAULT_DELIVERY_POLL_SECONDS), Config.service(complete).deliveryPoll)
        assertEquals(Duration.ofSeconds(30), Config.service(complete).deliveryPoll)
        assertEquals(Duration.ofSeconds(30), Config.service(complete + (Config.DELIVERY_POLL_SECONDS to "  ")).deliveryPoll)
    }

    @Test
    @DisplayName("La variable règle le nombre de secondes")
    fun variableSetsTheSeconds() {
        assertEquals(Duration.ofSeconds(5), Config.service(complete + (Config.DELIVERY_POLL_SECONDS to "5")).deliveryPoll)
        assertEquals(Duration.ofSeconds(1), Config.service(complete + (Config.DELIVERY_POLL_SECONDS to " 1 ")).deliveryPoll)
        assertEquals(
            Duration.ofSeconds(Config.MAX_DELIVERY_POLL_SECONDS),
            Config.service(complete + (Config.DELIVERY_POLL_SECONDS to Config.MAX_DELIVERY_POLL_SECONDS.toString())).deliveryPoll,
        )
    }

    @Test
    @DisplayName("Un intervalle absurde est refusé, avec le nom de la variable dans le message")
    fun absurdValuesAreRefused() {
        for (bad in listOf("0", "-5", "abc", "1.5", (Config.MAX_DELIVERY_POLL_SECONDS + 1).toString(), "99999999999999999999")) {
            val error = assertThrows<ConfigException>("« $bad » devrait être refusé") {
                Config.service(complete + (Config.DELIVERY_POLL_SECONDS to bad))
            }
            assertTrue(error.message!!.contains(Config.DELIVERY_POLL_SECONDS), error.message)
        }
    }

    @Test
    @DisplayName("Le problème d'intervalle s'ajoute aux autres dans un seul message")
    fun problemIsReportedWithTheOthers() {
        val error = assertThrows<ConfigException> {
            Config.service(mapOf(Config.DELIVERY_POLL_SECONDS to "zéro"))
        }
        for (name in listOf(Config.DB_URL, Config.API_KEY, Config.DELIVERY_POLL_SECONDS)) {
            assertTrue(error.message!!.contains(name), "Le message devrait citer $name : ${error.message}")
        }
    }

    @Test
    @DisplayName("L'intervalle n'est pas un secret : il figure dans la description de la configuration, le mot de passe non")
    fun theIntervalIsShownButNoSecret() {
        val text = Config.service(complete).toString()
        assertTrue(text.contains("deliveryPoll=PT30S"), text)
        assertFalse(text.contains("mot-de-passe-de-test"), text)
        assertFalse(text.contains("cle-de-test"), text)
    }
}
