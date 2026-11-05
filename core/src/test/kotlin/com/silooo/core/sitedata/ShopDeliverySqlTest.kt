package com.silooo.core.sitedata

import com.silooo.core.db.MariaDbSupport
import java.sql.Connection
import java.sql.SQLException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** L'écriture du webhook PayNow dans `shop_deliveries` sur MariaDB 12.3.3. Ignoré sans Docker, joué dans la CI. */
class ShopDeliverySqlTest {
    private val store = JdbcShopDeliveryStore()

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    private fun <T> inTransaction(block: (Connection) -> T): T = runBlocking { MariaDbSupport.database.transaction(block) }

    private fun insert(orderId: String, gamertag: String = "Kr Test", gemmes: Long = 500) = inTransaction { store.insert(it, orderId, gamertag, gemmes) }

    @Test
    @DisplayName("Insertion : la ligne porte la commande, le pseudo et les Gemmes ; xuid et delivered_at restent nuls, created_at est posée")
    fun insertion() {
        assertTrue(insert("PN-1001"))
        assertEquals("PN-1001", MariaDbSupport.string("SELECT order_id FROM shop_deliveries"))
        assertEquals("Kr Test", MariaDbSupport.string("SELECT gamertag FROM shop_deliveries"))
        assertEquals(500L, MariaDbSupport.long("SELECT gemmes FROM shop_deliveries"))
        assertNull(MariaDbSupport.string("SELECT xuid FROM shop_deliveries"))
        assertNull(MariaDbSupport.string("SELECT delivered_at FROM shop_deliveries"))
        assertNotNull(MariaDbSupport.string("SELECT created_at FROM shop_deliveries"))
    }

    @Test
    @DisplayName("Doublon : le même order_id rend faux et n'écrit pas de seconde ligne, ni ne change la première")
    fun duplicateIsIgnored() {
        assertTrue(insert("PN-1001", "Kr Test", 500))
        assertFalse(insert("PN-1001", "Autre", 9))
        assertEquals(1L, MariaDbSupport.count("shop_deliveries"))
        assertEquals("Kr Test", MariaDbSupport.string("SELECT gamertag FROM shop_deliveries"))
        assertEquals(500L, MariaDbSupport.long("SELECT gemmes FROM shop_deliveries"))
        // Un doublon n'empêche pas la commande suivante.
        assertTrue(insert("PN-1002"))
        assertEquals(2L, MariaDbSupport.count("shop_deliveries"))
    }

    @Test
    @DisplayName("Gemmes nulles ou négatives : la contrainte gemmes > 0 refuse, et l'erreur remonte (ce n'est pas un doublon)")
    fun gemsMustBePositive() {
        for (gemmes in listOf(0L, -5L)) {
            assertThrows(SQLException::class.java) { insert("PN-BAD", gemmes = gemmes) }
        }
        assertEquals(0L, MariaDbSupport.count("shop_deliveries"))
    }
}
