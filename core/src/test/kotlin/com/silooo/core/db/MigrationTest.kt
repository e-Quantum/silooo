package com.silooo.core.db

import com.silooo.core.DatabaseConfig
import java.sql.DriverManager
import org.flywaydb.core.api.exception.FlywayValidateException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.testcontainers.DockerClientFactory
import org.testcontainers.mariadb.MariaDBContainer

/**
 * Les migrations rejouées sur la même MariaDB que la production (12.3.3 sur SileneMC), dans un
 * conteneur Testcontainers.
 *
 * Sans Docker (le PC de Kr, par exemple), ces tests sont IGNORÉS avec un message : jamais réussis à
 * vide. Ils tournent dans la CI GitHub, qui a Docker.
 */
class MigrationTest {

    @Test
    @DisplayName("Flyway applique V1 sur MariaDB 12.3.3 et la base compte 32 tables")
    fun v1CreatesAllTables() = withMariaDb { config ->
        // On s'arrête à la version 1 : quand V2 arrivera, ce test comptera toujours les tables de V1.
        assertEquals(1, Migrations.migrate(config, target = "1"))
        assertEquals(TABLES_IN_V1, countTables(config))
    }

    @Test
    @DisplayName("Le démarrage normal refuse une base sans migration, puis l'accepte une fois migrée")
    fun validateFailsUntilMigrated() = withMariaDb { config ->
        assertThrows<FlywayValidateException>("base vide : V1 manque") { Migrations.validate(config) }
        Migrations.migrate(config)
        assertDoesNotThrow { Migrations.validate(config) }
        assertEquals(0, Migrations.migrate(config), "rejouer migrate n'applique rien de plus")
    }

    private fun withMariaDb(test: (DatabaseConfig) -> Unit) {
        assumeTrue(
            DockerClientFactory.instance().isDockerAvailable,
            "Docker absent : test de migration IGNORÉ (il tourne dans la CI GitHub)",
        )
        MariaDBContainer("mariadb:12.3.3").withDatabaseName("silooo").use { container ->
            container.start()
            test(DatabaseConfig(container.jdbcUrl, container.username, container.password))
        }
    }

    private fun countTables(config: DatabaseConfig): Int =
        DriverManager.getConnection(config.url, config.user, config.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' " +
                        "AND table_name <> 'flyway_schema_history'",
                ).use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private companion object {
        /** Les tables de V1__init.sql (docs/base-de-donnees.md les regroupe par domaine). */
        const val TABLES_IN_V1 = 32
    }
}
