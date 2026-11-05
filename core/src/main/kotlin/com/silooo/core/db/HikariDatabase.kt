package com.silooo.core.db

import com.silooo.core.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** La vraie base : un pool HikariCP sur la MariaDB de SileneMC (base `silooo`). */
class HikariDatabase private constructor(private val pool: HikariDataSource) : Database {

    override suspend fun <T> transaction(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        pool.connection.use { connection ->
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (e: Throwable) {
                // Une erreur de retour arrière ne doit pas cacher l'erreur d'origine.
                try {
                    connection.rollback()
                } catch (rollbackError: Exception) {
                    e.addSuppressed(rollbackError)
                }
                throw e
            }
        }
    }

    override fun close() = pool.close()

    companion object {
        /**
         * Ouvre le pool. Si la base ne répond pas, la création échoue tout de suite (comportement
         * par défaut de HikariCP) : le service ne démarre pas à moitié, le compose le relancera.
         */
        fun connect(config: DatabaseConfig): HikariDatabase {
            val hikari = HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                poolName = "silooo-core"
                // Les transactions sont explicites : voir Database.transaction.
                isAutoCommit = false
                // Le conteneur est limité en mémoire, et la base est partagée avec SileneMC.
                maximumPoolSize = 8
            }
            return HikariDatabase(HikariDataSource(hikari))
        }
    }
}
