package com.silooo.core.discord

import java.time.Instant
import java.time.LocalDate

/**
 * [HelpStore] en mémoire, avec les mêmes règles que le SQL : [marks] tient la place de `discord_help_marks` (un seul
 * marquage par sujet), [months] celle de `discord_help`.
 */
class InMemoryHelpStore : HelpStore {
    data class Mark(val resolverId: String, val markedBy: String, val at: Instant)

    val marks = linkedMapOf<String, Mark>()
    val months = sortedMapOf<LocalDate, MutableMap<String, Int>>()

    override suspend fun isResolved(threadId: String): Boolean = threadId in marks

    override suspend fun mark(threadId: String, resolverId: String, markedBy: String, at: Instant, monthStart: LocalDate): Boolean {
        if (threadId in marks) return false
        marks[threadId] = Mark(resolverId, markedBy, at)
        val month = months.getOrPut(monthStart) { mutableMapOf() }
        month[resolverId] = (month[resolverId] ?: 0) + 1
        return true
    }

    override suspend fun top(monthStart: LocalDate, limit: Int): List<HelpCount> =
        months[monthStart].orEmpty().filter { it.value > 0 }.map { HelpCount(it.key, it.value) }
            .sortedWith(compareBy({ -it.answers }, { it.userId })).take(limit)
}
