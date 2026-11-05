package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `discord_giveaways`, `discord_giveaway_entries` et `discord_prizes` sur la vraie MariaDB. Ignoré sans Docker : la CI le joue. */
class GiveawaySqlTest {
    private val store get() = JdbcGiveawayStore(MariaDbSupport.database)
    private val start = Instant.parse("2026-11-05T12:00:00Z")

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    @Test
    @DisplayName("Un giveaway s'écrit, reçoit sa carte, se lit parmi les ouverts dans l'ordre de fin, et seules les cartes postées comptent dans le mois")
    fun createsAndListsOpenGiveaways() = runBlocking {
        assertEquals(listOf<Giveaway>(), store.open())

        val first = store.create("Un", "gemmes", 500, null, "300", start + Duration.ofDays(7), "42", start)
        val second = store.create("Deux", "cosmetique", 0, "ciel-ancien", "300", start + Duration.ofDays(3), "43", start + Duration.ofHours(1))
        assertEquals(1, first)
        assertEquals(2, second)
        assertEquals(0, store.countCreatedBetween(start - Duration.ofDays(1), start + Duration.ofDays(1)), "sans carte, rien ne compte")

        store.carded(first, "9001")
        store.carded(second, "9002")
        assertEquals(2, store.countCreatedBetween(start, start + Duration.ofHours(2)))
        assertEquals(1, store.countCreatedBetween(start, start + Duration.ofHours(1)), "la borne de fin est exclue")
        assertEquals(1, store.countCreatedBetween(start + Duration.ofHours(1), start + Duration.ofHours(2)), "la borne de début est comprise")
        assertEquals(0, store.countCreatedBetween(start + Duration.ofDays(1), start + Duration.ofDays(2)))

        assertEquals(
            listOf(
                Giveaway(2, "Deux", "cosmetique", 0, "ciel-ancien", "300", "9002", start + Duration.ofDays(3), null, null, "43", start + Duration.ofHours(1)),
                Giveaway(1, "Un", "gemmes", 500, null, "300", "9001", start + Duration.ofDays(7), null, null, "42", start),
            ),
            store.open(),
        )
        assertEquals(2L, MariaDbSupport.count("discord_giveaways"))
    }

    @Test
    @DisplayName("Un membre ne s'inscrit qu'une fois, les inscrits reviennent dans l'ordre, un giveaway inconnu n'inscrit personne")
    fun entersOnce() = runBlocking {
        val id = store.create("Un", "lingots", 2000, null, "300", start + Duration.ofDays(1), "42", start)
        assertTrue(store.enter(id, "7"))
        assertFalse(store.enter(id, "7"))
        assertTrue(store.enter(id, "8"))
        assertEquals(listOf("7", "8"), store.entries(id))
        assertEquals(listOf<String>(), store.entries(99))
        assertFalse(store.enter(99, "7"))
        assertEquals(2L, MariaDbSupport.count("discord_giveaway_entries"))
    }

    @Test
    @DisplayName("Le tirage clôt le giveaway une seule fois, avec ou sans gagnant ; le lot reste sur l'identifiant Discord, pas encore crédité")
    fun drawsOnceAndKeepsThePrize() = runBlocking {
        val first = store.create("Un", "gemmes", 500, null, "300", start + Duration.ofDays(1), "42", start)
        val second = store.create("Deux", "cosmetique", 0, "ciel-ancien", "300", start + Duration.ofDays(2), "42", start)

        store.drawn(first, "8", start + Duration.ofDays(1))
        store.drawn(first, "7", start + Duration.ofDays(1) + Duration.ofMinutes(1))
        assertEquals(listOf(second), store.open().map { it.id })
        assertEquals("8", MariaDbSupport.string("select winner_id from discord_giveaways where id = ?", first), "le premier tirage reste")

        store.drawn(second, null, start + Duration.ofDays(2))
        assertEquals(listOf<Giveaway>(), store.open())
        assertEquals(null, MariaDbSupport.string("select winner_id from discord_giveaways where id = ?", second))

        store.addPrize("8", first, "gemmes", 500, null, start + Duration.ofDays(1))
        assertEquals(1L, MariaDbSupport.count("discord_prizes"))
        assertEquals("8", MariaDbSupport.string("select discord_user_id from discord_prizes where giveaway_id = ?", first))
        assertEquals(500L, MariaDbSupport.long("select prize_amount from discord_prizes where giveaway_id = ?", first))
        assertEquals("gemmes", MariaDbSupport.string("select prize_kind from discord_prizes where giveaway_id = ?", first))
        assertEquals(1L, MariaDbSupport.long("select count(*) from discord_prizes where delivered_at is null and giveaway_id = ?", first))
    }
}
