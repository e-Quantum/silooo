package com.silooo.core.discord

import java.time.Instant
import java.time.LocalDate

/**
 * [ReactionStore] en mémoire, avec les mêmes règles que le SQL : [weeks] tient la place de `discord_reactions`, [marks]
 * celle de `discord_reaction_marks` (une marque par message et par membre).
 */
class InMemoryReactionStore : ReactionStore {
    val weeks = sortedMapOf<LocalDate, MutableMap<String, Int>>()
    val marks = linkedMapOf<Pair<String, String>, Instant>()

    /** Combien de fois [top] a été lu : un tableau qui ne change pas ne relit rien. */
    var reads = 0

    override suspend fun add(messageId: String, userId: String, at: Instant, weekStart: LocalDate): Boolean {
        if ((messageId to userId) in marks) return false
        marks[messageId to userId] = at
        val week = weeks.getOrPut(weekStart) { mutableMapOf() }
        week[userId] = (week[userId] ?: 0) + 1
        return true
    }

    override suspend fun top(weekStart: LocalDate, limit: Int): List<ReactionCount> {
        reads++
        return weeks[weekStart].orEmpty().filter { it.value > 0 }.map { ReactionCount(it.key, it.value) }
            .sortedWith(compareBy({ -it.given }, { it.userId })).take(limit)
    }

    override suspend fun oldestWeekStart(): LocalDate? = weeks.keys.firstOrNull()

    override suspend fun purge(weekStart: LocalDate, markedBefore: Instant) {
        weeks.keys.removeAll { it.isBefore(weekStart) }
        marks.entries.removeAll { it.value.isBefore(markedBefore) }
    }
}
