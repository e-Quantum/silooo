package com.silooo.core.lobby

import com.silooo.core.TestDefinitions
import com.silooo.core.db.Xuid
import com.silooo.core.definitions.DefinitionsException
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Les règles de la salle d'attente sans base : semaine, plafond, rangs, une ligne par XUID. */
class LobbyServiceTest {
    private val rules = LobbyDefinitions.load(TestDefinitions.directory)
    /** Le faux magasin ne lit jamais la connexion : celle-ci échoue si on la touche. */
    private val fake: Connection = java.lang.reflect.Proxy.newProxyInstance(
        Connection::class.java.classLoader, arrayOf(Connection::class.java),
    ) { _, method, _ -> error("connexion lue par ${method.name}") } as Connection

    private fun service(store: LobbyStore = InMemoryLobbyStore()) = LobbyService(rules, store) { emptyList() }

    private fun xuid(n: Long) = Xuid.parse(n.toString())!!

    @Test fun `La semaine part du lundi a minuit, heure de Paris`() {
        // Dimanche 8 novembre 2026 à 23 h 30 UTC : déjà lundi 0 h 30 à Paris.
        assertEquals(LocalDate.parse("2026-11-09"), service().weekOf(Instant.parse("2026-11-08T23:30:00Z")))
        assertEquals(LocalDate.parse("2026-11-02"), service().weekOf(Instant.parse("2026-11-08T22:30:00Z")))
    }

    @Test fun `Une course trop rapide pour le plafond est refusee, une seconde au moins`() {
        val max = rules.parcours.maxPerSecond
        assertTrue(service().plausible((max * 10).toInt(), 10))
        assertFalse(service().plausible((max * 10).toInt() + 1, 10))
        assertTrue(service().plausible(max.toInt(), 0))
    }

    @Test fun `Un joueur egale une ligne, meme apres un changement de gamertag`() {
        val store = InMemoryLobbyStore()
        val lobby = service(store)
        val now = Instant.parse("2026-11-07T12:00:00Z")
        lobby.run(fake, xuid(1), "Ana", 12, now)
        lobby.run(fake, xuid(1), "Ana Bedrock", 5, now.plusSeconds(60))
        val result = lobby.run(fake, xuid(2), "Kr", 12, now.plusSeconds(120))
        assertEquals(listOf(LobbyEntry(1, "Ana Bedrock", 12), LobbyEntry(1, "Kr", 12)), result.board.allTime)
        assertEquals(1, result.rank)
        assertFalse(lobby.run(fake, xuid(1), "Ana Bedrock", 12, now.plusSeconds(180)).newRecord)
        assertTrue(lobby.run(fake, xuid(1), "Ana Bedrock", 13, now.plusSeconds(240)).newRecord)
    }

    @Test fun `Les egalites partagent le rang`() {
        val ranked = LobbyService.ranked(listOf(LobbyScore("a", 9), LobbyScore("b", 9), LobbyScore("c", 4)))
        assertEquals(listOf(1, 1, 3), ranked.map { it.rank })
    }

    @Test fun `Un fuseau inconnu arrete la lecture du fichier`() {
        val text = java.nio.file.Files.readString(TestDefinitions.directory.resolve(LobbyDefinitions.FILE))
        assertThrows(DefinitionsException::class.java) { LobbyDefinitions.parse(text.replace("Europe/Paris", "Ailleurs/Nulle-part")) }
    }
}
