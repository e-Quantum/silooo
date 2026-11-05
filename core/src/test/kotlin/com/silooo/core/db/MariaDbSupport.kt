package com.silooo.core.db

import com.silooo.core.DatabaseConfig
import java.math.BigInteger
import java.sql.Connection
import java.sql.DriverManager
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.mariadb.MariaDBContainer

/**
 * Une MariaDB 12.3.3 (celle de SileneMC) pour tous les tests `*SqlTest`, démarrée une seule fois par JVM de
 * test et migrée par Flyway. Chaque test appelle [prepare] : il saute si Docker manque (message clair,
 * jamais de réussite à vide) et remet la base à zéro sinon.
 *
 * Sur le PC de Kr il n'y a pas Docker : ces tests sont IGNORÉS. Ils tournent dans la CI GitHub.
 */
object MariaDbSupport {
    private const val IMAGE = "mariadb:12.3.3"

    val dockerAvailable: Boolean by lazy {
        try {
            DockerClientFactory.instance().isDockerAvailable
        } catch (e: Throwable) {
            false
        }
    }

    private val container: MariaDBContainer by lazy {
        MariaDBContainer(IMAGE).withDatabaseName("silooo").also { it.start() }
    }

    val config: DatabaseConfig by lazy {
        val started = DatabaseConfig(container.jdbcUrl, container.username, container.password)
        Migrations.migrate(started)
        started
    }

    /** Le pool de l'application (8 connexions, validation explicite) branché sur le conteneur. */
    val database: HikariDatabase by lazy {
        HikariDatabase.connect(config).also { pool ->
            Runtime.getRuntime().addShutdownHook(Thread { runCatching { pool.close() } })
        }
    }

    /** À appeler en tête de chaque test SQL : saute sans Docker, vide les tables sinon. */
    fun prepare() {
        assumeTrue(dockerAvailable, "Docker absent : test SQL IGNORÉ (il tourne dans la CI GitHub)")
        reset()
    }

    /** Une connexion à part, en validation automatique : pour poser un état de départ ou le relire pendant un test. */
    fun <T> raw(block: (Connection) -> T): T =
        DriverManager.getConnection(config.url, config.user, config.password).use { connection ->
            connection.autoCommit = true
            block(connection)
        }

    private fun reset() = raw { connection ->
        val tables = connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT table_name FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'",
            ).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
        connection.createStatement().use { statement ->
            statement.execute("SET FOREIGN_KEY_CHECKS = 0")
            tables.forEach { statement.execute("TRUNCATE TABLE `$it`") }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1")
        }
    }

    // --- petits outils pour poser et relire des lignes

    fun update(sql: String, vararg params: Any?): Int = raw { connection ->
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    fun longs(sql: String, vararg params: Any?): List<Long> = raw { connection ->
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getLong(1)) } }
        }
    }

    fun long(sql: String, vararg params: Any?): Long = longs(sql, *params).single()

    fun strings(sql: String, vararg params: Any?): List<String?> = raw { connection ->
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    fun string(sql: String, vararg params: Any?): String? = strings(sql, *params).single()

    fun count(table: String): Long = long("SELECT COUNT(*) FROM `$table`")

    /** Un joueur de test : la ligne `players` suffit aux clés étrangères des soldes et du journal. */
    fun insertPlayer(xuid: String, gamertag: String = "J$xuid") {
        val number = BigInteger(xuid)
        update(
            "INSERT INTO players (xuid, floodgate_uuid, gamertag) VALUES (?, ?, ?)",
            number,
            java.util.UUID(0L, number.toLong()).toString(),
            gamertag,
        )
    }

    /** Une case d'archipel et une entreprise, posées à la main : renvoie l'identifiant de l'entreprise. */
    fun insertCompany(name: String = "Entreprise de test", slotIndex: Int = 0): Long {
        update("INSERT INTO archipelago_slots (slot_index, origin_x, origin_z, allocated_at) VALUES (?, ?, 0, UTC_TIMESTAMP(3))", slotIndex, slotIndex)
        update("INSERT INTO companies (name, slot_index) VALUES (?, ?)", name, slotIndex)
        return long("SELECT id FROM companies WHERE name = ?", name)
    }
}
