package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `discord_bot_messages` sur la vraie MariaDB : un message par clé, remplacé s'il est reposté. Ignoré sans Docker : la CI le joue. */
class BotMessageSqlTest {
    private val store get() = JdbcBotMessageStore(MariaDbSupport.database)

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    @Test
    @DisplayName("Une clé inconnue ne rend rien ; un message enregistré se retrouve par sa clé")
    fun savesAndFinds() = runBlocking {
        assertNull(store.find("tickets-menu"))
        store.save("tickets-menu", "9001", "5001")
        store.save("monte-charge", "9002", "5002")
        assertEquals(BotMessageRef("9001", "5001"), store.find("tickets-menu"))
        assertEquals(BotMessageRef("9002", "5002"), store.find("monte-charge"))
        assertEquals(2L, MariaDbSupport.count("discord_bot_messages"))
    }

    @Test
    @DisplayName("Enregistrer une clé connue remplace le message sans ajouter de ligne")
    fun saveReplaces() = runBlocking {
        store.save("tickets-menu", "9001", "5001")
        store.save("tickets-menu", "9003", "5003")
        assertEquals(BotMessageRef("9003", "5003"), store.find("tickets-menu"))
        assertEquals(1L, MariaDbSupport.count("discord_bot_messages"))
    }
}
