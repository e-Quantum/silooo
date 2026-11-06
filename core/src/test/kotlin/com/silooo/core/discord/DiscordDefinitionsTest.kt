package com.silooo.core.discord

import com.silooo.core.TestDefinitions
import com.silooo.core.definitions.DefinitionsException
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Les chiffres du Discord : lus dans definitions/, cohérents avec le plan du serveur, et la formule des niveaux. */
class DiscordDefinitionsTest {
    private val loaded = DiscordDefinitions.load(TestDefinitions.directory)
    private val plan = ServerPlan.fromClasspath()

    @Test
    @DisplayName("Le fichier du dépôt se lit et colle au plan du serveur")
    fun repositoryFileMatchesThePlan() {
        assertEquals(emptyList<String>(), loaded.problems(plan))
        assertEquals(loaded, DiscordDefinitions.loadChecked(TestDefinitions.directory, plan))
        assertEquals("Europe/Paris", loaded.zone.id)
        assertEquals(listOf(1, 5, 10, 25, 50), loaded.profiles.prestigeTiers.map { it.minimum })
        assertEquals("#c8962e", loaded.profiles.prestigeColor)
        assertEquals("prestige-50", loaded.profiles.roleDefinitions().highest(50))
    }

    @Test fun `prestige tiers must be increasing and point to roles with the palette color`() {
        val invalid = loaded.copy(profiles = loaded.profiles.copy(
            prestigeTiers = listOf(GamePrestigeTier(5, "prestige-5"), GamePrestigeTier(1, "missing-role")),
        ))
        val problems = invalid.problems(plan)
        assertTrue(problems.any { "strictement croissants" in it })
        assertTrue(problems.any { "missing-role" in it })
    }

    @Test
    @DisplayName("Les niveaux suivent la formule base × n + croissance × n × (n - 1) / 2")
    fun levelsFollowTheFormula() {
        val definitions = loaded.copy(xp = loaded.xp.copy(levelBase = 100, levelGrowth = 50))
        assertEquals(0L, definitions.pointsForLevel(0))
        assertEquals(100L, definitions.pointsForLevel(1))
        assertEquals(250L, definitions.pointsForLevel(2))
        assertEquals(1000L, definitions.pointsForLevel(5))
        assertEquals(0, definitions.levelFor(99))
        assertEquals(1, definitions.levelFor(100))
        assertEquals(4, definitions.levelFor(999))
        assertEquals(5, definitions.levelFor(1000))
    }

    @Test
    @DisplayName("Le délai du bonjour suit la plateforme, et prend le plus long sans plateforme")
    fun helloDelayFollowsThePlatform() {
        val hello = HelloSeconds(console = 12, telephone = 8, pc = 4)
        assertEquals(12, hello.forPlatform("console"))
        assertEquals(8, hello.forPlatform("telephone"))
        assertEquals(4, hello.forPlatform("pc"))
        assertEquals(12, hello.forPlatform(null))
        assertEquals(12, hello.forPlatform("montre"))
    }

    @Test
    @DisplayName("Un rôle ou un salon absent du plan, des niveaux qui ne montent pas, un fuseau inconnu : nommés un par un")
    fun problemsNameEachMismatch() {
        val broken = loaded.copy(
            timeZone = "Lune/Cratere",
            xp = loaded.xp.copy(
                pointsMin = 30,
                pointsMax = 20,
                channels = listOf("nulle-part"),
                roles = listOf(LevelRole(10, "apprenti"), LevelRole(5, "inconnu")),
            ),
            reactions = loaded.reactions.copy(channels = listOf("ailleurs")),
        )
        val problems = broken.problems(plan)
        assertEquals(6, problems.size, problems.toString())
        assertTrue(problems.any { "Lune/Cratere" in it })
        assertTrue(problems.any { "pointsMax" in it })
        assertTrue(problems.any { "le niveau 5 devrait dépasser 10" in it })
        assertTrue(problems.any { "« inconnu »" in it })
        assertTrue(problems.any { "« nulle-part »" in it })
        assertTrue(problems.any { "« ailleurs »" in it })
        val e = assertThrows(DefinitionsException::class.java) {
            val directory = Files.createTempDirectory("silooo-discord-definitions")
            Files.writeString(directory.resolve(DiscordDefinitions.FILE), TestDefinitions.text(DiscordDefinitions.FILE).replace("\"apprenti\"", "\"inconnu\""))
            DiscordDefinitions.loadChecked(directory, plan)
        }
        assertTrue("« inconnu »" in checkNotNull(e.message))
    }

    @Test
    @DisplayName("Un fichier absent ou abîmé est une erreur de définitions, qui nomme le fichier et la clé")
    fun missingOrBrokenFile() {
        val empty = Files.createTempDirectory("silooo-discord-definitions")
        val missing = assertThrows(DefinitionsException::class.java) { DiscordDefinitions.load(empty) }
        assertTrue(DiscordDefinitions.FILE in checkNotNull(missing.message))
        val broken = assertThrows(DefinitionsException::class.java) { DiscordDefinitions.parse("{ \"version\": 1 }") }
        assertTrue("clé manquante" in checkNotNull(broken.message), broken.message)
        assertTrue("« commentaire »" in checkNotNull(broken.message), broken.message)
        val unknown = assertThrows(DefinitionsException::class.java) {
            DiscordDefinitions.parse(TestDefinitions.text(DiscordDefinitions.FILE).replace("\"version\": 1", "\"version\": 1, \"inconnu\": 2"))
        }
        assertTrue("inconnu" in checkNotNull(unknown.message), unknown.message)
    }
}
