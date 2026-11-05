package com.silooo.core.discord

/** [BotMessageStore] en mémoire, avec les mêmes règles que le SQL : une clé n'a qu'un message, le dernier enregistré. */
class InMemoryBotMessageStore : BotMessageStore {
    val refs = mutableMapOf<String, BotMessageRef>()

    override suspend fun find(key: String): BotMessageRef? = refs[key]

    override suspend fun save(key: String, channelId: String, messageId: String) {
        refs[key] = BotMessageRef(channelId, messageId)
    }
}
