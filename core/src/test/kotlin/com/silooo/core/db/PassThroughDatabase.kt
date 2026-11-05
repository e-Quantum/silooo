package com.silooo.core.db

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection

/**
 * Un [Database] pour les tests de logique, sans base : chaque transaction exécute son bloc tout de suite avec
 * une [Connection] qui ne sait rien faire, et compte les validations et les annulations. Les faux dépôts
 * (en mémoire) n'ouvrent jamais la connexion ; si un code s'en sert quand même, le test échoue au lieu de
 * passer à vide.
 *
 * Ce faux ne simule PAS l'annulation d'une transaction : ce que la base annule vraiment (atomicité du solde
 * et du journal, du marqueur et de l'action) se teste sur MariaDB, dans les tests `*SqlTest`.
 */
class PassThroughDatabase : Database {
    var transactions = 0
        private set
    var commits = 0
        private set
    var rollbacks = 0
        private set

    private val connection: Connection = Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
        InvocationHandler { _, method, _ ->
            throw UnsupportedOperationException("PassThroughDatabase : la connexion n'est pas utilisable (${method.name})")
        },
    ) as Connection

    override suspend fun <T> transaction(block: (Connection) -> T): T {
        transactions++
        val result = try {
            block(connection)
        } catch (e: Throwable) {
            rollbacks++
            throw e
        }
        commits++
        return result
    }

    override fun close() = Unit
}
