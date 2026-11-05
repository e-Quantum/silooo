package com.silooo.core

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** La durée de garde de `api_requests` (SILOOO_API_REQUESTS_RETENTION_HOURS). */
class ConfigRetentionTest {
    private val complete = mapOf(
        Config.DB_URL to "jdbc:mariadb://mariadb:3306/silooo",
        Config.DB_USER to "silooo",
        Config.DB_PASSWORD to "mot-de-passe-de-test",
        Config.API_KEY to "cle-de-test",
    )

    @Test
    @DisplayName("Sans variable, les réponses sont gardées 24 heures")
    fun defaultIsTwentyFourHours() {
        assertEquals(Duration.ofHours(24), Config.service(complete).apiRequestRetention)
        assertEquals(Duration.ofHours(24), Config.service(complete + (Config.API_REQUESTS_RETENTION_HOURS to "  ")).apiRequestRetention)
    }

    @Test
    @DisplayName("La variable règle le nombre d'heures")
    fun variableSetsTheHours() {
        assertEquals(Duration.ofHours(72), Config.service(complete + (Config.API_REQUESTS_RETENTION_HOURS to "72")).apiRequestRetention)
        assertEquals(Duration.ofHours(1), Config.service(complete + (Config.API_REQUESTS_RETENTION_HOURS to " 1 ")).apiRequestRetention)
        assertEquals(
            Duration.ofHours(Config.MAX_API_REQUESTS_RETENTION_HOURS),
            Config.service(complete + (Config.API_REQUESTS_RETENTION_HOURS to Config.MAX_API_REQUESTS_RETENTION_HOURS.toString())).apiRequestRetention,
        )
    }

    @Test
    @DisplayName("Une durée absurde est refusée, avec le nom de la variable dans le message")
    fun absurdValuesAreRefused() {
        for (bad in listOf("0", "-5", "abc", "1.5", "8761", "99999999999999999999")) {
            val error = assertThrows<ConfigException>("« $bad » devrait être refusé") {
                Config.service(complete + (Config.API_REQUESTS_RETENTION_HOURS to bad))
            }
            assertTrue(error.message!!.contains(Config.API_REQUESTS_RETENTION_HOURS), error.message)
        }
    }

    @Test
    @DisplayName("Le problème de durée s'ajoute aux autres dans un seul message")
    fun retentionProblemIsReportedWithTheOthers() {
        val error = assertThrows<ConfigException> {
            Config.service(mapOf(Config.API_REQUESTS_RETENTION_HOURS to "zéro"))
        }
        for (name in listOf(Config.DB_URL, Config.API_KEY, Config.API_REQUESTS_RETENTION_HOURS)) {
            assertTrue(error.message!!.contains(name), "Le message devrait citer $name : ${error.message}")
        }
    }
}
