package com.silooo.core.discord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class BoundedQueueTest {
    @Test
    @DisplayName("Pleine, la file abandonne le plus ancien et le compte")
    fun dropsOldest() {
        val queue = BoundedQueue<Int>(3)
        (1..5).forEach { queue.offer(it) }
        assertEquals(3, queue.size)
        assertEquals(2, queue.dropped)
        assertEquals(listOf(3, 4, 5), generateSequence { queue.poll() }.toList())
        assertNull(queue.poll())
        assertEquals(0, queue.size)
    }

    @Test
    @DisplayName("Une capacité nulle est refusée")
    fun capacity() {
        assertThrows(IllegalArgumentException::class.java) { BoundedQueue<Int>(0) }
    }
}
