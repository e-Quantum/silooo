package com.silooo.core.incident

import com.silooo.core.session.InMemorySessionStore
import java.sql.Connection
import java.time.Instant

/**
 * Le faux en mémoire de [IncidentStore], pour les tests des incidents sans base. L'incident ouvert d'une usine vit dans le
 * faux [InMemorySessionStore] (`FactoryRecord.openIncidentId`, `incidentSlowdown`, `incidentKind`), que le moteur d'économie lit
 * par `loadFactories` : ouvrir ou clore ici change donc l'usine, comme la jointure SQL le fait. Les identifiants se suivent sans
 * jamais se réutiliser, comme `AUTO_INCREMENT`. Le SQL lui-même se teste sur MariaDB (`IncidentSqlTest`).
 */
class InMemoryIncidentStore(private val sessions: InMemorySessionStore) : IncidentStore {
    val rows = mutableListOf<IncidentRecord>()
    private var lastId = 0L

    override fun find(connection: Connection, companyId: Long, incidentId: Long): IncidentRecord? =
        rows.firstOrNull { it.id == incidentId && it.companyId == companyId }

    override fun open(connection: Connection, companyId: Long, factoryId: String, kind: String, slowdown: Double, at: Instant): Long {
        check(rows.none { it.companyId == companyId && it.factoryId == factoryId && it.resolvedAt == null }) {
            "factory_incidents : $factoryId a déjà un incident ouvert"
        }
        lastId = maxOf(lastId, rows.maxOfOrNull { it.id } ?: 0L) + 1
        rows += IncidentRecord(lastId, companyId, factoryId, kind, slowdown, at, resolvedAt = null)
        refreshFactory(companyId, factoryId)
        return lastId
    }

    override fun close(connection: Connection, companyId: Long, incidentId: Long, at: Instant): Boolean {
        val index = rows.indexOfFirst { it.id == incidentId && it.companyId == companyId && it.resolvedAt == null }
        if (index < 0) return false
        rows[index] = rows[index].copy(resolvedAt = at)
        refreshFactory(companyId, rows[index].factoryId)
        return true
    }

    override fun startedSince(connection: Connection, companyId: Long, since: Instant): Boolean =
        rows.any { it.companyId == companyId && !it.startedAt.isBefore(since) }

    /** Pose un incident déjà ouvert (état de départ d'un test), sans rien publier. Rend son identifiant. */
    fun seedOpen(companyId: Long, factoryId: String, kind: String, slowdown: Double, at: Instant): Long = open(UNUSED_CONNECTION, companyId, factoryId, kind, slowdown, at)

    /** Recopie l'incident ouvert de l'usine sur son `FactoryRecord`, comme `loadFactories` le lit : le plus petit identifiant ouvert. */
    private fun refreshFactory(companyId: Long, factoryId: String) {
        val list = sessions.factories.getValue(companyId)
        val index = list.indexOfFirst { it.factoryId == factoryId }
        check(index >= 0) { "usine $factoryId absente de l'entreprise $companyId" }
        val open = rows.filter { it.companyId == companyId && it.factoryId == factoryId && it.resolvedAt == null }.minByOrNull { it.id }
        list[index] = list[index].copy(
            openIncidentId = open?.id,
            incidentSlowdown = open?.slowdown ?: 1.0,
            incidentKind = open?.kind,
        )
    }

    private companion object {
        /** Le faux n'écrit pas dans [Connection] : n'importe laquelle convient, ce n'est jamais touché. */
        val UNUSED_CONNECTION: Connection = com.silooo.core.db.RecordingConnection().connection
    }
}
