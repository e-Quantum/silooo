package com.silooo.core.discord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CommandSpecTest {
    private val optional = CommandOption("facultatif", "Une option facultative")
    private val required = CommandOption("obligatoire", "Une option obligatoire", required = true)

    @Test
    @DisplayName("Une option obligatoire après une facultative est refusée avec le nom de la commande et de l'option")
    fun requiredAfterOptionalIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CommandSpec("essai", "Une commande", listOf(optional, required))
        }
        assertTrue(error.message.orEmpty().contains("/essai"))
        assertTrue(error.message.orEmpty().contains("obligatoire"))
    }

    @Test
    @DisplayName("La copie d'une commande vérifie aussi l'ordre de ses options")
    fun copyCannotIntroduceAnInvalidOrder() {
        val command = CommandSpec("essai", "Une commande", listOf(required, optional))
        assertThrows(IllegalArgumentException::class.java) { command.copy(options = listOf(optional, required)) }
    }

    @Test
    @DisplayName("Aucune option, toutes obligatoires, toutes facultatives ou obligatoires puis facultatives sont acceptées")
    fun validOrdersArePreserved() {
        for (options in listOf(emptyList(), listOf(required), listOf(optional), listOf(required, optional))) {
            assertEquals(options, CommandSpec("essai", "Une commande", options).options)
        }
    }
}
