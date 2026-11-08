package com.silooo.core.discord

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.db.Database
import com.silooo.core.discord.jda.JdaMessages
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
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
        assertEquals(setOf("spoil", "annonce", "niveau", "giveaway", "lier", "delier", "entreprise", "usine", "classement", "vote", "rappels", "bureau", "sondage", "suggestion", "gestion", "avertir", "timeout", "retablir", "dossier", "expulser", "bannir", "debannir", "Signaler", "ticket"), commands.map { it.name }.toSet())
        assertEquals(24, commands.size, "aucun nom dupliqué dans le catalogue")

        for (spec in commands) {
            val command = JdaMessages.command(spec)
            val serialized = command.toData()
            assertEquals(spec.name, serialized.getString("name"))
            if (spec.kind == CommandKind.SLASH) {
                val slash = command as SlashCommandData
                assertEquals(spec.options.map { it.name }, slash.options.map { it.name })
                assertEquals(spec.options.map { it.required }, slash.options.map { it.isRequired })
                assertEquals(spec.options.map { it.choices }, slash.options.map { option -> option.choices.map { it.asString } })
                for ((option, actual) in spec.options.zip(slash.options)) {
                    if (option.kind == OptionKind.MEMBRE) assertEquals(net.dv8tion.jda.api.interactions.commands.OptionType.USER, actual.type)
                }
            } else {
                assertEquals(3, serialized.getInt("type"))
                assertEquals("Signaler", spec.name)
            }
            val expected = when (spec.name) {
                "spoil", "annonce", "giveaway", "sondage", "suggestion" -> Permission.MANAGE_SERVER.rawValue
                "gestion", "avertir", "timeout", "retablir", "dossier" -> Permission.MODERATE_MEMBERS.rawValue
                "expulser" -> Permission.KICK_MEMBERS.rawValue
                "bannir", "debannir" -> Permission.BAN_MEMBERS.rawValue
                else -> null
            }
            assertEquals(expected != null, spec.staffOnly)
            assertEquals(expected, command.defaultPermissions.permissionsRaw)

        }
        assertEquals(
            listOf("titre", "lot", "duree", "quantite", "cosmetique", "etape"),
            commands.single { it.name == "giveaway" }.options.map { it.name },
        )
    }
}
