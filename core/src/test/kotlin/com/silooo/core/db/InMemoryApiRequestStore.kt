package com.silooo.core.db

import java.sql.Connection
import java.time.Duration
import java.time.Instant

/**
 * Le faux en mémoire de [ApiRequestStore], pour les tests de la logique d'[com.silooo.core.api.Idempotency].
 * Même contrat que [JdbcApiRequestStore] ; le SQL, lui, se teste sur MariaDB (`ApiRequestSqlTest`).
 */
class InMemoryApiRequestStore(var now: Instant = Instant.parse("2026-11-05T12:00:00Z")) : ApiRequestStore {

    private sealed interface Row {
        val endpoint: String
        val since: Instant
    }

    private class Pending(override val endpoint: String, override val since: Instant, val owner: String) : Row
    private class Done(override val endpoint: String, override val since: Instant, val response: StoredResponse) : Row

    private val rows = LinkedHashMap<String, Row>()

    val size: Int get() = synchronized(rows) { rows.size }

    fun has(requestId: String): Boolean = synchronized(rows) { requestId in rows }

    fun isPending(requestId: String): Boolean = synchronized(rows) { rows[requestId] is Pending }

    override fun claim(connection: Connection, requestId: String, endpoint: String, owner: String, staleAfter: Duration): Claim =
        synchronized(rows) {
            val row = rows[requestId]
            when {
                row == null -> {
                    rows[requestId] = Pending(endpoint, now, owner)
                    Claim.Acquired
                }
                row.endpoint != endpoint -> Claim.OtherEndpoint
                row is Done -> Claim.Replay(row.response)
                row.since.isBefore(now.minus(staleAfter)) -> {
                    rows[requestId] = Pending(endpoint, now, owner)
                    Claim.Acquired
                }
                else -> Claim.InProgress
            }
        }

    override fun complete(connection: Connection, requestId: String, owner: String, response: StoredResponse): Boolean =
        synchronized(rows) {
            val row = rows[requestId]
            if (row is Pending && row.owner == owner) {
                rows[requestId] = Done(row.endpoint, row.since, response)
                true
            } else {
                false
            }
        }

    override fun release(connection: Connection, requestId: String, owner: String) {
        synchronized(rows) {
            val row = rows[requestId]
            if (row is Pending && row.owner == owner) rows.remove(requestId)
        }
    }

    override fun purge(connection: Connection, keep: Duration, batch: Int): Int =
        synchronized(rows) {
            val limit = now.minus(keep)
            val old = rows.filterValues { it.since.isBefore(limit) }.keys.take(batch)
            old.forEach(rows::remove)
            old.size
        }
}

/** Un [ApiRequestStore] dont aucune méthode ne doit servir : pour prouver qu'une route n'appelle pas l'idempotence. */
object NeverUsedApiRequestStore : ApiRequestStore {
    private fun unused(): Nothing = throw AssertionError("api_requests ne devrait pas servir pour cette route")

    override fun claim(connection: Connection, requestId: String, endpoint: String, owner: String, staleAfter: Duration): Claim = unused()
    override fun complete(connection: Connection, requestId: String, owner: String, response: StoredResponse): Boolean = unused()
    override fun release(connection: Connection, requestId: String, owner: String) = unused()
    override fun purge(connection: Connection, keep: Duration, batch: Int): Int = unused()
}
