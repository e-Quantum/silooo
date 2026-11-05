package com.silooo.core.incident

import java.sql.Connection
import java.time.Instant

/** Une ligne de `factory_incidents` : un incident d'usine, ouvert tant que [resolvedAt] est `null`. */
data class IncidentRecord(
    val id: Long,
    val companyId: Long,
    val factoryId: String,
    /** La sorte de `incidents.json`, telle qu'elle était à l'ouverture. */
    val kind: String,
    /** Le facteur de production de l'usine tant que l'incident dure, tel qu'il était à l'ouverture. */
    val slowdown: Double,
    val startedAt: Instant,
    val resolvedAt: Instant?,
)

/**
 * Les accès SQL des incidents d'usine, derrière une interface : les tests de route tournent avec un faux en mémoire, le SQL
 * avec la vraie base (Testcontainers, `IncidentSqlTest`). La lecture des usines (avec leur incident ouvert) passe par
 * `SessionStore.loadFactories` ; le Donar de l'entreprise s'écrit par `updateCompany` ; l'événement par `CompanyStore.publish`.
 *
 * Toute écriture d'ici suit l'écriture de l'entreprise (`updateCompany`, verrou optimiste sur `companies.version`) : c'est la
 * version qui protège ces lignes d'une action concurrente. Aucune migration : `factory_incidents` de V1 suffit.
 */
interface IncidentStore {
    /** L'incident [incidentId] de l'entreprise [companyId], ouvert ou clos ; `null` s'il n'existe pas ou appartient à une autre entreprise. */
    fun find(connection: Connection, companyId: Long, incidentId: Long): IncidentRecord?

    /** Ouvre un incident de sorte [kind] sur l'usine [factoryId], à l'instant [at]. Rend son identifiant. */
    fun open(connection: Connection, companyId: Long, factoryId: String, kind: String, slowdown: Double, at: Instant): Long

    /** Clôt l'incident [incidentId] à l'instant [at]. Rend faux s'il n'était plus ouvert. */
    fun close(connection: Connection, companyId: Long, incidentId: Long, at: Instant): Boolean

    /** Vrai si l'entreprise a ouvert un incident à l'instant [since] ou après (clos ou non). */
    fun startedSince(connection: Connection, companyId: Long, since: Instant): Boolean
}
