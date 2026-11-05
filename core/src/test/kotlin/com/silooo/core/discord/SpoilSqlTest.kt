package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `discord_spoils` sur la vraie MariaDB : le dépôt, l'ordre de publication, le compte, l'image. Ignoré sans Docker : la CI le joue. */
class SpoilSqlTest {
    private val store get() = JdbcSpoilStore(MariaDbSupport.database)
    private val start = Instant.parse("2026-11-05T12:00:00Z")

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    @Test
    @DisplayName("Les spoils prennent les numéros 1, 2, 3 ; le courant est le dernier publié, le suivant le plus petit numéro en attente")
    fun numbersAndOrder() = runBlocking {
        assertNull(store.current())
        assertNull(store.nextPending())
        assertEquals(listOf<Spoil>(), store.lastPublished(3))

        assertEquals(1, store.add("Un.", null, 150, "42", start))
        assertEquals(2, store.add("Deux.", null, 200, "42", start + Duration.ofMinutes(1)))
        assertEquals(3, store.add("Trois.", null, 250, "43", start + Duration.ofMinutes(2)))
        assertNull(store.current(), "rien n'est publié")
        assertEquals(Spoil(1, "Un.", null, 150, "42", start), store.nextPending())

        store.published(1, "9001", start + Duration.ofHours(1))
        store.reactions(1, 87)
        val first = checkNotNull(store.current())
        assertEquals(Spoil(1, "Un.", null, 150, "42", start, "9001", start + Duration.ofHours(1), 87), first)
        assertEquals(2, store.nextPending()?.number)

        store.published(2, "9002", start + Duration.ofHours(2))
        store.published(3, "9003", start + Duration.ofHours(3))
        store.reactions(2, 95)
        assertEquals(3, store.current()?.number)
        assertEquals(listOf(3, 2, 1), store.lastPublished(3).map { it.number })
        assertEquals(listOf(3, 2), store.lastPublished(2).map { it.number })
        assertEquals(listOf(0, 95, 87), store.lastPublished(5).map { it.reactions })
        assertNull(store.nextPending())
        assertEquals(3L, MariaDbSupport.count("discord_spoils"))
    }

    @Test
    @DisplayName("L'image d'un spoil, jusqu'à ses octets, revient telle qu'elle est partie ; un spoil sans image n'en a pas")
    fun keepsTheImage() = runBlocking {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        store.add("Avec image.", ImageFile("chantier.png", bytes), 10, "42", start)
        store.add("Sans image.", null, 10, "42", start)
        store.published(1, "9001", start)
        store.published(2, "9002", start)
        val spoils = store.lastPublished(2).reversed()
        assertEquals("chantier.png", spoils[0].image?.name)
        assertArrayEquals(bytes, spoils[0].image?.bytes)
        assertNull(spoils[1].image)
    }
}
