package com.silooo.core.discord

import java.time.Instant

/** [SpoilStore] en mémoire, avec les mêmes règles que le SQL : le numéro est le dernier plus un, le courant est le dernier publié. */
class InMemorySpoilStore : SpoilStore {
    val spoils = mutableListOf<Spoil>()

    override suspend fun add(text: String, image: ImageFile?, nextThreshold: Int, createdBy: String, at: Instant): Int {
        val number = (spoils.maxOfOrNull { it.number } ?: 0) + 1
        spoils += Spoil(number, text, image, nextThreshold, createdBy, at)
        return number
    }

    override suspend fun current(): Spoil? = spoils.filter { it.publishedAt != null }.maxByOrNull { it.number }

    override suspend fun nextPending(): Spoil? = spoils.filter { it.publishedAt == null }.minByOrNull { it.number }

    override suspend fun lastPublished(limit: Int): List<Spoil> =
        spoils.filter { it.publishedAt != null }.sortedByDescending { it.number }.take(limit)

    override suspend fun published(number: Int, messageId: String, at: Instant) = update(number) { it.copy(messageId = messageId, publishedAt = at) }

    override suspend fun reactions(number: Int, count: Int) = update(number) { it.copy(reactions = count) }

    private fun update(number: Int, change: (Spoil) -> Spoil) {
        val index = spoils.indexOfFirst { it.number == number }
        if (index >= 0) spoils[index] = change(spoils[index])
    }
}
