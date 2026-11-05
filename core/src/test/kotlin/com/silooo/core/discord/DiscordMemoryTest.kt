package com.silooo.core.discord

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.EnumSet
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.requests.GatewayIntent
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La mesure mémoire de l'ADR 0007 : ce que coûte JDA au service, hors connexion. Le chiffre va dans
 * `build/mesure-memoire-discord.txt`, pour la passation. La mesure connectée (le bot sur le serveur de test) se fait à
 * la main, avec le jeton de Kr ; le budget de l'ADR est de 96 Mo au-delà du service seul.
 */
class DiscordMemoryTest {
    @Test
    @DisplayName("Charger JDA sans se connecter reste très en dessous du budget de l'ADR 0007")
    fun classLoadingStaysUnderBudget() {
        val before = used()
        val builder = JDABuilder.createLight("jeton-de-mesure", EnumSet.noneOf(GatewayIntent::class.java))
        assertNotNull(builder)
        val after = used()
        val heapKo = (after.first - before.first) / 1024
        val nonHeapKo = (after.second - before.second) / 1024
        val lines = listOf(
            "Mesure mémoire du bot Discord (ADR 0007), JVM ${System.getProperty("java.version")}.",
            "Chargement des classes de JDA, sans connexion : tas ${heapKo} Ko, hors tas (classes) ${nonHeapKo} Ko.",
            "Mesure connectée (bot de test, phase attente, à blanc) : à faire à la main avec le jeton de Kr.",
        )
        Files.createDirectories(BUILD)
        Files.writeString(BUILD.resolve("mesure-memoire-discord.txt"), lines.joinToString("\n") + "\n")
        assertTrue(heapKo + nonHeapKo < BUDGET_KO, "JDA coûte ${heapKo + nonHeapKo} Ko au chargement, au-delà du budget")
    }

    private fun used(): Pair<Long, Long> {
        System.gc()
        Thread.sleep(100)
        val bean = ManagementFactory.getMemoryMXBean()
        return bean.heapMemoryUsage.used to bean.nonHeapMemoryUsage.used
    }

    private companion object {
        val BUILD: Path = Path.of("build")
        const val BUDGET_KO = 96L * 1024
    }
}
