package com.silooo.core.server

import com.silooo.core.MutableClock
import com.silooo.core.db.Xuid
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `ServerPresence.isOnline` : la source de présence de `join` (le régime en ligne ou hors ligne de la période).
 * Les routes du signal de vie sont dans `ApiTest`.
 */
class ServerPresenceTest {
    private val clock = MutableClock(Instant.parse("2026-11-05T12:00:00Z"))
    private val presence = ServerPresence(clock)
    private val kr = Xuid.parse("2535428000000001")!!
    private val other = Xuid.parse("2535428000000002")!!

    @Test
    @DisplayName("Sans aucun signal de vie, personne n'est en ligne")
    fun nobodyBeforeTheFirstBeat() {
        assertFalse(presence.isOnline(kr))
    }

    @Test
    @DisplayName("Un joueur nommé par le dernier signal de vie est en ligne, un autre non")
    fun listedPlayerIsOnline() {
        presence.record("serveur-1", listOf(kr.text))

        assertTrue(presence.isOnline(kr))
        assertFalse(presence.isOnline(other))
    }

    @Test
    @DisplayName("Le signal de vie compte jusqu'à 30 secondes, pas au-delà : un serveur qui ne bat plus n'a plus de joueurs en ligne")
    fun staleBeatIsIgnored() {
        presence.record("serveur-1", listOf(kr.text))

        clock.advance(ServerPresence.DEFAULT_MAX_BEAT_AGE)
        assertTrue(presence.isOnline(kr), "pile à la limite, il compte encore")

        clock.advance(Duration.ofMillis(1))
        assertFalse(presence.isOnline(kr))
    }

    @Test
    @DisplayName("Le dernier signal de vie d'un serveur remplace le précédent : un joueur qui n'y figure plus est parti")
    fun newestBeatReplacesThePrevious() {
        presence.record("serveur-1", listOf(kr.text, other.text))
        presence.record("serveur-1", listOf(other.text))

        assertFalse(presence.isOnline(kr))
        assertTrue(presence.isOnline(other))
    }

    @Test
    @DisplayName("Avec deux serveurs, un joueur est en ligne dès qu'un signal de vie frais le nomme, et plus quand seul le périmé le nomme")
    fun anyFreshServerCounts() {
        presence.record("serveur-1", listOf(kr.text))
        clock.advance(Duration.ofSeconds(20))
        presence.record("serveur-2", listOf(other.text))
        clock.advance(Duration.ofSeconds(20))

        assertFalse(presence.isOnline(kr), "le signal de serveur-1 a 40 secondes")
        assertTrue(presence.isOnline(other), "le signal de serveur-2 en a 20")
    }

    @Test
    @DisplayName("La durée de péremption se règle à la construction")
    fun maxBeatAgeIsConfigurable() {
        val strict = ServerPresence(clock, maxBeatAge = Duration.ofSeconds(5))
        strict.record("serveur-1", listOf(kr.text))

        clock.advance(Duration.ofSeconds(6))

        assertFalse(strict.isOnline(kr))
    }
}
