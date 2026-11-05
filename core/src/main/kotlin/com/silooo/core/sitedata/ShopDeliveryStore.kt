package com.silooo.core.sitedata

import java.sql.Connection
import java.sql.SQLException

/**
 * L'écriture du site dans `shop_deliveries` : une ligne par commande de Gemmes payée. Le site n'écrit que la ligne (`order_id`, `gamertag`,
 * `gemmes`) ; `xuid` et `delivered_at` restent nuls, c'est le relevé du jeu (`DeliveryRelief`) qui les remplit en créditant le joueur.
 */
interface ShopDeliveryStore {
    /** Écrit la commande. Vrai si la ligne est neuve, faux si cet `order_id` existait déjà (un rejeu de PayNow : rien n'est écrit). */
    fun insert(connection: Connection, orderId: String, gamertag: String, gemmes: Long): Boolean
}

/** Le SQL de [ShopDeliveryStore]. Testé sur MariaDB par Testcontainers (`ShopDeliverySqlTest`). */
class JdbcShopDeliveryStore : ShopDeliveryStore {
    override fun insert(connection: Connection, orderId: String, gamertag: String, gemmes: Long): Boolean =
        try {
            // Un INSERT simple : c'est la clé primaire qui refuse le rejeu, sans lecture avant. InnoDB n'annule que cette instruction.
            connection.prepareStatement("INSERT INTO shop_deliveries (order_id, gamertag, gemmes) VALUES (?, ?, ?)").use {
                it.setString(1, orderId)
                it.setString(2, gamertag)
                it.setLong(3, gemmes)
                it.executeUpdate() == 1
            }
        } catch (e: SQLException) {
            // Seul le doublon de clé est un rejeu. La contrainte `gemmes > 0` lève aussi une violation d'intégrité (autre code) : elle remonte.
            if (e.errorCode == DUPLICATE_ENTRY) false else throw e
        }

    private companion object {
        /** `ER_DUP_ENTRY`. */
        const val DUPLICATE_ENTRY = 1062
    }
}
