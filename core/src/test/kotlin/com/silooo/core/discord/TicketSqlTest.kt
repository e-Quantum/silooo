package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `discord_tickets` sur la vraie MariaDB : l'ouverture, le salon, la recherche, la fermeture. Ignoré sans Docker : la CI le joue. */
class TicketSqlTest {
    private val store get() = JdbcTicketStore(MariaDbSupport.database)
    private val start = Instant.parse("2026-11-05T12:00:00Z")

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    @Test
    @DisplayName("Un ticket prend les numéros 1, 2, 3 ; le salon s'écrit après la ligne ; il se relit par numéro et par salon")
    fun opensAndFindsATicket() = runBlocking {
        assertNull(store.byId(1))
        assertEquals(1, store.open("42", "achat", "", start))
        assertEquals(2, store.open("43", "compte", "", start + Duration.ofMinutes(1)))
        assertEquals(Ticket(1, "42", "achat", "", start), store.byId(1))

        store.channelCreated(1, "9001")
        store.channelCreated(2, "9002")
        assertEquals(Ticket(1, "42", "achat", "9001", start), store.byId(1))
        assertEquals(Ticket(2, "43", "compte", "9002", start + Duration.ofMinutes(1)), store.byChannel("9002"))
        assertNull(store.byChannel("9999"))
        assertEquals(2L, MariaDbSupport.count("discord_tickets"))
    }

    @Test
    @DisplayName("Le ticket ouvert d'un membre est le seul sans date de fermeture ; fermé, il disparaît des ouverts mais reste lisible")
    fun openOnesAndClosing() = runBlocking {
        val first = store.open("42", "achat", "", start)
        val second = store.open("43", "sanction", "", start + Duration.ofMinutes(1))
        store.channelCreated(first, "9001")
        store.channelCreated(second, "9002")

        assertEquals(first, store.openOf("42")?.id)
        assertNull(store.openOf("44"))
        assertEquals(listOf(first, second), store.openOnes().map { it.id })

        store.close(first, "77", start + Duration.ofHours(1))
        assertNull(store.openOf("42"))
        assertEquals(listOf(second), store.openOnes().map { it.id })
        assertEquals(Ticket(first, "42", "achat", "9001", start, start + Duration.ofHours(1), "77"), store.byId(first))

        store.close(first, "88", start + Duration.ofHours(2))
        assertEquals(Ticket(first, "42", "achat", "9001", start, start + Duration.ofHours(1), "77"), store.byChannel("9001"), "un ticket fermé ne se referme pas")

        val again = store.open("42", "compte", "", start + Duration.ofDays(1))
        assertEquals(again, store.openOf("42")?.id, "un membre peut rouvrir un ticket après la fermeture")
    }
}
