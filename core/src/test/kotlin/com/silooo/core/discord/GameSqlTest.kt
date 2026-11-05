package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `discord_games` et `discord_records` sur la vraie MariaDB : l'état de chaque jeu, son record, remplacés sans ajouter de ligne. Ignoré sans Docker : la CI le joue. */
class GameSqlTest {
    private val store get() = JdbcGameStore(MariaDbSupport.database)
    private val start = Instant.parse("2026-11-05T17:10:00Z")

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    @Test
    @DisplayName("Un jeu jamais écrit repart de zéro ; l'état écrit se relit, un membre vide comme un membre connu")
    fun savesAndReadsTheState() = runBlocking {
        assertEquals(GameState.START, store.state("comptage"))

        store.saveState("comptage", GameState(57, "7"), start)
        assertEquals(GameState(57, "7"), store.state("comptage"))
        store.saveState("comptage", GameState.START, start + Duration.ofMinutes(1))
        assertEquals(GameState(0, null), store.state("comptage"))
        assertEquals(1L, MariaDbSupport.count("discord_games"), "la même ligne est remplacée")
    }

    @Test
    @DisplayName("Chaque jeu a son état, et le moment du geste est gardé")
    fun gamesAreIndependent() = runBlocking {
        store.saveState("comptage", GameState(57, "7"), start)
        store.saveState("monte-charge", GameState(12, "8"), start + Duration.ofMinutes(1))

        assertEquals(GameState(57, "7"), store.state("comptage"))
        assertEquals(GameState(12, "8"), store.state("monte-charge"))
        assertEquals(2L, MariaDbSupport.count("discord_games"))
        assertEquals(1L, MariaDbSupport.long("select count(*) from discord_games where game = ? and updated_at = ?", "monte-charge", LocalDateTime.of(2026, 11, 5, 17, 11)))
    }

    @Test
    @DisplayName("Un nombre de dix-huit chiffres tient dans l'état")
    fun keepsBigNumbers() = runBlocking {
        store.saveState("comptage", GameState(999_999_999_999_999_999L, "7"), start)
        assertEquals(GameState(999_999_999_999_999_999L, "7"), store.state("comptage"))
    }

    @Test
    @DisplayName("Un jeu sans record n'en rend pas ; le record écrit se relit avec son auteur et son moment, puis il est remplacé")
    fun savesAndReadsTheRecord() = runBlocking {
        assertNull(store.record("comptage"))

        store.saveRecord("comptage", GameRecord(120, "9", start))
        assertEquals(GameRecord(120, "9", start), store.record("comptage"))
        store.saveRecord("comptage", GameRecord(121, "7", start + Duration.ofDays(1)))
        assertEquals(GameRecord(121, "7", start + Duration.ofDays(1)), store.record("comptage"))
        assertEquals(1L, MariaDbSupport.count("discord_records"))
    }

    @Test
    @DisplayName("Un record sans auteur connu se relit sans auteur, et les records des deux jeux restent séparés")
    fun recordsStaySeparate() = runBlocking {
        store.saveRecord("monte-charge", GameRecord(37, null, start))
        store.saveRecord("comptage", GameRecord(120, "9", start))

        assertEquals(GameRecord(37, null, start), store.record("monte-charge"))
        assertEquals(GameRecord(120, "9", start), store.record("comptage"))
        assertEquals(2L, MariaDbSupport.count("discord_records"))
    }
}
