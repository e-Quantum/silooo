package com.silooo.core.discord

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * [XpStore] en mémoire, avec les mêmes règles que le SQL : [records] tient la place de `discord_chat_xp`, [messages] celle
 * de `discord_xp_messages`, jamais sous zéro, la semaine ne change que par [resetWeek].
 */
class InMemoryXpStore : XpStore {
    val records = mutableMapOf<String, XpRecord>()

    /** Les messages qui ont compté, par identifiant : qui, combien et quand. */
    val messages = linkedMapOf<String, Counted>()

    data class Counted(val userId: String, val points: Int, val at: Instant)

    override suspend fun record(userId: String): XpRecord? = records[userId]

    override suspend fun count(userId: String, messageId: String, points: Int, at: Instant, weekStart: LocalDate): XpRecord {
        check(messageId !in messages) { "la clé primaire refuse un second compte pour le message $messageId" }
        val known = records[userId]
        val updated = if (known == null) {
            XpRecord(userId, points, 0, points, weekStart, at)
        } else {
            known.copy(points = known.points + points, weekPoints = known.weekPoints + points, lastCountedAt = at)
        }
        records[userId] = updated
        messages[messageId] = Counted(userId, points, at)
        return updated
    }

    override suspend fun uncount(messageId: String): Int? {
        val counted = messages.remove(messageId) ?: return null
        records[counted.userId]?.let {
            records[counted.userId] = it.copy(points = maxOf(it.points - counted.points, 0), weekPoints = maxOf(it.weekPoints - counted.points, 0))
        }
        return counted.points
    }

    override suspend fun levelReached(userId: String, level: Int) {
        records[userId]?.let { records[userId] = it.copy(level = level) }
    }

    override suspend fun weekTop(limit: Int): List<XpRecord> =
        records.values.filter { it.weekPoints > 0 }.sortedWith(compareBy({ -it.weekPoints }, { it.userId })).take(limit)

    override suspend fun resetWeek(weekStart: LocalDate) {
        for ((userId, record) in records.toMap()) records[userId] = record.copy(weekPoints = 0, weekStart = weekStart)
        val limit = weekStart.atStartOfDay().toInstant(ZoneOffset.UTC)
        messages.entries.removeAll { it.value.at.isBefore(limit) }
    }

    override suspend fun oldestWeekStart(): LocalDate? = records.values.minOfOrNull { it.weekStart }
}
