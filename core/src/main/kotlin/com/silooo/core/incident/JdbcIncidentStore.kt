package com.silooo.core.incident

import java.sql.Connection
import java.sql.Statement
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Le SQL des incidents d'usine sur `factory_incidents` (V1). Testé sur MariaDB par Testcontainers (`IncidentSqlTest`), jamais
 * avec un faux. Les dates sont celles de l'horloge du service, en UTC, à la milliseconde que la colonne garde.
 */
class JdbcIncidentStore : IncidentStore {

    override fun find(connection: Connection, companyId: Long, incidentId: Long): IncidentRecord? =
        connection.prepareStatement(
            "SELECT id, company_id, factory_id, kind, slowdown, started_at, resolved_at FROM factory_incidents WHERE id = ? AND company_id = ?",
        ).use {
            it.setLong(1, incidentId)
            it.setLong(2, companyId)
            it.executeQuery().use { rows ->
                if (!rows.next()) {
                    null
                } else {
                    IncidentRecord(
                        id = rows.getLong("id"),
                        companyId = rows.getLong("company_id"),
                        factoryId = rows.getString("factory_id"),
                        kind = rows.getString("kind"),
                        slowdown = rows.getDouble("slowdown"),
                        startedAt = checkNotNull(rows.getObject("started_at", LocalDateTime::class.java)) { "started_at : date absente" }
                            .toInstant(ZoneOffset.UTC),
                        resolvedAt = rows.getObject("resolved_at", LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC),
                    )
                }
            }
        }

    override fun open(connection: Connection, companyId: Long, factoryId: String, kind: String, slowdown: Double, at: Instant): Long =
        connection.prepareStatement(
            "INSERT INTO factory_incidents (company_id, factory_id, kind, slowdown, started_at) VALUES (?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setLong(1, companyId)
            it.setString(2, factoryId)
            it.setString(3, kind)
            it.setDouble(4, slowdown)
            it.setObject(5, LocalDateTime.ofInstant(at, ZoneOffset.UTC))
            it.executeUpdate()
            it.generatedKeys.use { keys ->
                check(keys.next()) { "factory_incidents : pas d'identifiant rendu" }
                keys.getLong(1)
            }
        }

    override fun close(connection: Connection, companyId: Long, incidentId: Long, at: Instant): Boolean =
        connection.prepareStatement("UPDATE factory_incidents SET resolved_at = ? WHERE id = ? AND company_id = ? AND resolved_at IS NULL").use {
            it.setObject(1, LocalDateTime.ofInstant(at, ZoneOffset.UTC))
            it.setLong(2, incidentId)
            it.setLong(3, companyId)
            it.executeUpdate() == 1
        }

    override fun startedSince(connection: Connection, companyId: Long, since: Instant): Boolean =
        connection.prepareStatement("SELECT 1 FROM factory_incidents WHERE company_id = ? AND started_at >= ? LIMIT 1").use {
            it.setLong(1, companyId)
            it.setObject(2, LocalDateTime.ofInstant(since, ZoneOffset.UTC))
            it.executeQuery().use { rows -> rows.next() }
        }
}
