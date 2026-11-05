package com.silooo.core.discord

/** [HighlightStore] en mémoire, avec les mêmes règles que le SQL : une copie par message d'origine, la première gardée. */
class InMemoryHighlightStore : HighlightStore {
    val highlights = linkedMapOf<String, Highlight>()

    override suspend fun find(messageId: String): Highlight? = highlights[messageId]

    override suspend fun save(highlight: Highlight) {
        highlights.putIfAbsent(highlight.messageId, highlight)
    }
}
