package com.silooo.core.discord

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.Database
import com.silooo.core.discord.jda.JdaMessages
import net.dv8tion.jda.api.Permission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Le vrai catalogue de production passe par le vrai convertisseur JDA, sans démarrer de fonction ni de connexion. */
class CommandCatalogTest {
    @Test
    @DisplayName("Les commandes assemblées en production se sérialisent avec JDA avant toute connexion")
    fun productionCatalogueConvertsWithJdaOffline() {
        val context = BotContext(
            forbiddenDiscordDependency<DiscordServer>(), ServerIds.NONE,
            forbiddenDiscordDependency<Timer>(), ServerPhase.ATTENTE,
        )
        val db = forbiddenDiscordDependency<Database>()
        val game = Dependencies(db, "test-key", definitions = TestDefinitions.loaded)
        val features = BotFeatures.build(TestDefinitions.directory, db, game = game, onlineCount = {
            throw AssertionError("Le catalogue ne lit pas le nombre de joueurs")
        })(context)
        val commands = features.flatMap { it.commands }
        assertEquals(setOf("spoil", "annonce", "niveau", "giveaway", "lier", "delier", "entreprise", "usine", "classement", "vote", "rappels", "bureau", "sondage", "suggestion"), commands.map { it.name }.toSet())
        assertEquals(14, commands.size, "aucun nom dupliqué dans le catalogue")

        for (spec in commands) {
            val command = JdaMessages.command(spec)
            val serialized = command.toData()
            assertEquals(spec.name, serialized.getString("name"))
            assertEquals(spec.options.map { it.name }, command.options.map { it.name })
            assertEquals(spec.options.map { it.required }, command.options.map { it.isRequired })
            assertEquals(spec.options.map { it.choices }, command.options.map { option -> option.choices.map { it.asString } })
            if (spec.name !in setOf("spoil", "annonce", "giveaway", "sondage", "suggestion")) {
                assertEquals(false, spec.staffOnly)
                assertNull(command.defaultPermissions.permissionsRaw)
            } else {
                assertEquals(true, spec.staffOnly)
                assertEquals(Permission.MANAGE_SERVER.rawValue, command.defaultPermissions.permissionsRaw)
            }
        }
        assertEquals(
            listOf("titre", "lot", "duree", "quantite", "cosmetique", "etape"),
            commands.single { it.name == "giveaway" }.options.map { it.name },
        )
    }
}
