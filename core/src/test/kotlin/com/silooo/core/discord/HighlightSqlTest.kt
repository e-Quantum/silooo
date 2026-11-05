package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `discord_highlights` sur la vraie MariaDB : retrouver une copie, n'en garder qu'une. Ignoré sans Docker : la CI le joue. */
class HighlightSqlTest {
    private val store get() = JdbcHighlightStore(MariaDbSupport.database)

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    @Test
    @DisplayName("Une copie se retrouve par le message d'origine ; un message jamais copié est inconnu")
    fun findsACopy() = runBlocking {
        assertNull(store.find("m1"))

        store.save(Highlight("m1", "c1", "7", "r1"))
        assertEquals(Highlight("m1", "c1", "7", "r1"), store.find("m1"))
        assertNull(store.find("m2"))
        assertEquals(1L, MariaDbSupport.count("discord_highlights"))
    }

    @Test
    @DisplayName("Une seconde copie du même message ne remplace pas la première et ne casse rien")
    fun keepsTheFirstCopy() = runBlocking {
        store.save(Highlight("m1", "c1", "7", "r1"))
        store.save(Highlight("m1", "c1", "7", "r2"))

        assertEquals("r1", store.find("m1")?.repostMessageId)
        assertEquals(1L, MariaDbSupport.count("discord_highlights"))
    }
}
