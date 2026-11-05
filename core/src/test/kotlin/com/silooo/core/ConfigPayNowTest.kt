package com.silooo.core

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `SILOOO_PAYNOW_WEBHOOK_SECRET` : facultative, rognée, et jamais affichée. */
class ConfigPayNowTest {
    private val base = mapOf(
        Config.DB_URL to "jdbc:mariadb://localhost:3306/silooo",
        Config.DB_USER to "u",
        Config.DB_PASSWORD to "p",
        Config.API_KEY to "k",
        Config.DEFINITIONS_DIR to System.getProperty("silooo.definitions.dir"),
    )

    private fun service(extra: Map<String, String>) = Config.service(base + extra, Path.of("."))

    @Test
    @DisplayName("Sans la variable, ou vide, il n'y a pas de secret : le webhook répondra 503")
    fun absent() {
        assertNull(service(emptyMap()).payNowWebhookSecret)
        assertNull(service(mapOf(Config.PAYNOW_WEBHOOK_SECRET to "   ")).payNowWebhookSecret)
    }

    @Test
    @DisplayName("Posée, elle est lue sans espace aux bords et ne s'affiche jamais dans la configuration")
    fun present() {
        val config = service(mapOf(Config.PAYNOW_WEBHOOK_SECRET to "  s3cr3t-paynow  "))
        assertEquals("s3cr3t-paynow", config.payNowWebhookSecret)
        assertFalse(config.toString().contains("s3cr3t-paynow"))
    }
}
