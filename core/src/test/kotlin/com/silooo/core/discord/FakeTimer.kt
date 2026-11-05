package com.silooo.core.discord

import java.time.Duration
import java.time.Instant

/** Le temps des tests : il n'avance que par [advance], qui joue les actions dues dans l'ordre. */
class FakeTimer(start: Instant = Instant.parse("2026-11-05T10:00:00Z")) : Timer {
    private class Entry(var due: Instant, val period: Duration?, val action: suspend () -> Unit) {
        var cancelled = false
    }

    private val entries = mutableListOf<Entry>()
    private var current = start

    /** Les actions programmées encore vivantes. */
    val pending: Int get() = entries.count { !it.cancelled }

    override fun now(): Instant = current

    override fun after(delay: Duration, action: suspend () -> Unit): Scheduled {
        val entry = Entry(current + delay, null, action)
        entries += entry
        return Scheduled { entry.cancelled = true }
    }

    override fun every(period: Duration, action: suspend () -> Unit): Scheduled {
        val entry = Entry(current + period, period, action)
        entries += entry
        return Scheduled { entry.cancelled = true }
    }

    suspend fun advance(duration: Duration) {
        val target = current + duration
        while (true) {
            val next = entries.filter { !it.cancelled && !it.due.isAfter(target) }.minByOrNull { it.due } ?: break
            current = next.due
            if (next.period == null) entries.remove(next) else next.due = next.due + next.period
            next.action()
        }
        entries.removeAll { it.cancelled }
        current = target
    }
}

/** Attend qu'une condition tienne, au plus [timeoutMs] ; sinon le test échoue avec [what]. */
fun awaitUntil(what: String = "la condition", timeoutMs: Long = 2000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > end) throw AssertionError("$what n'est pas arrivée en $timeoutMs ms")
        Thread.sleep(5)
    }
}
