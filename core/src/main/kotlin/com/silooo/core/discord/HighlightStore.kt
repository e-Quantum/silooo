package com.silooo.core.discord

import com.silooo.core.db.Database

/** Un message recopié dans `#meilleurs-moments` : l'original ([messageId], [channelId], [authorId]) et sa copie. */
data class Highlight(val messageId: String, val channelId: String, val authorId: String, val repostMessageId: String)

/** Les meilleurs moments déjà recopiés (`discord_highlights`, migration V10) : un message ne l'est qu'une fois. */
interface HighlightStore {
    /** Le message d'origine [messageId] tel qu'il a été recopié, ou `null` s'il ne l'a jamais été. */
    suspend fun find(messageId: String): Highlight?

    /** Note la copie ; une copie déjà notée pour le même message d'origine reste telle quelle. */
    suspend fun save(highlight: Highlight)
}

/**
 * Le SQL de [HighlightStore], en minuscules : le garde anti-IA lit les chaînes de ce paquet. Testé sur MariaDB par
 * Testcontainers (`HighlightSqlTest`).
 */
class JdbcHighlightStore(private val database: Database) : HighlightStore {
    override suspend fun find(messageId: String): Highlight? =
        database.transaction { connection ->
            connection.prepareStatement(
                "select message_id, channel_id, author_id, repost_message_id from discord_highlights where message_id = ?",
            ).use {
                it.setString(1, messageId)
                it.executeQuery().use { rows ->
                    if (rows.next()) Highlight(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4)) else null
                }
            }
        }

    override suspend fun save(highlight: Highlight) {
        database.transaction { connection ->
            connection.prepareStatement(
                "insert ignore into discord_highlights (message_id, channel_id, author_id, repost_message_id) values (?, ?, ?, ?)",
            ).use {
                it.setString(1, highlight.messageId)
                it.setString(2, highlight.channelId)
                it.setString(3, highlight.authorId)
                it.setString(4, highlight.repostMessageId)
                it.executeUpdate()
            }
        }
    }
}
