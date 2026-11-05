package com.silooo.core.discord

import com.silooo.core.db.Database

/** Où vit un message du bot qui se modifie sur place : son salon et son identifiant Discord. */
data class BotMessageRef(val channelId: String, val messageId: String)

/**
 * Les messages du bot qui vivent (`discord_bot_messages`, migration V10) : le menu des tickets, les tableaux. Chacun a
 * une clé que le code lui donne ; après un redémarrage le bot le retrouve par elle et le modifie, il ne le reposte pas.
 * Chaque appel est une transaction à lui.
 */
interface BotMessageStore {
    /** Le message de clé [key], ou `null` s'il n'a jamais été posté. */
    suspend fun find(key: String): BotMessageRef?

    /** Note que le message de clé [key] est [messageId] dans [channelId] ; remplace l'ancien s'il y en avait un. */
    suspend fun save(key: String, channelId: String, messageId: String)
}

/**
 * Le SQL de [BotMessageStore] sur `discord_bot_messages`, en minuscules : le garde anti-IA lit les chaînes de ce paquet.
 * Testé sur MariaDB par Testcontainers (`BotMessageSqlTest`).
 */
class JdbcBotMessageStore(private val database: Database) : BotMessageStore {
    override suspend fun find(key: String): BotMessageRef? =
        database.transaction { connection ->
            connection.prepareStatement("select channel_id, message_id from discord_bot_messages where message_key = ?").use {
                it.setString(1, key)
                it.executeQuery().use { rows -> if (rows.next()) BotMessageRef(rows.getString(1), rows.getString(2)) else null }
            }
        }

    override suspend fun save(key: String, channelId: String, messageId: String) {
        database.transaction { connection ->
            connection.prepareStatement(
                "insert into discord_bot_messages (message_key, channel_id, message_id) values (?, ?, ?) " +
                    "on duplicate key update channel_id = values(channel_id), message_id = values(message_id), updated_at = utc_timestamp(3)",
            ).use {
                it.setString(1, key)
                it.setString(2, channelId)
                it.setString(3, messageId)
                it.executeUpdate()
            }
        }
    }
}
