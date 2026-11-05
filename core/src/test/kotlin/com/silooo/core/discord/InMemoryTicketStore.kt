package com.silooo.core.discord

import java.time.Instant

/** [TicketStore] en mémoire, avec les mêmes règles que le SQL : le numéro est le dernier plus un, un ticket fermé ne se referme pas. */
class InMemoryTicketStore : TicketStore {
    val tickets = mutableListOf<Ticket>()

    override suspend fun open(userId: String, kind: String, channelId: String, at: Instant): Int {
        val id = (tickets.maxOfOrNull { it.id } ?: 0) + 1
        tickets += Ticket(id, userId, kind, channelId, at)
        return id
    }

    override suspend fun channelCreated(id: Int, channelId: String) {
        replace(id) { it.copy(channelId = channelId) }
    }

    override suspend fun byId(id: Int): Ticket? = tickets.firstOrNull { it.id == id }

    override suspend fun openOf(userId: String): Ticket? =
        tickets.filter { it.userId == userId && it.closedAt == null }.maxByOrNull { it.id }

    override suspend fun byChannel(channelId: String): Ticket? = tickets.filter { it.channelId == channelId }.maxByOrNull { it.id }

    override suspend fun openOnes(): List<Ticket> = tickets.filter { it.closedAt == null }.sortedBy { it.id }

    override suspend fun close(id: Int, by: String, at: Instant) {
        replace(id) { if (it.closedAt == null) it.copy(closedAt = at, closedBy = by) else it }
    }

    private fun replace(id: Int, change: (Ticket) -> Ticket) {
        val index = tickets.indexOfFirst { it.id == id }
        if (index >= 0) tickets[index] = change(tickets[index])
    }
}
