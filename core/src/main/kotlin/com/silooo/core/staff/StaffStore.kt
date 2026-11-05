package com.silooo.core.staff

import com.silooo.core.session.StaffRecord
import java.sql.Connection
import java.time.LocalDate

/** Une place de la liste du jour déjà embauchée (`agency_candidates` avec `hired_staff_id`), telle qu'elle a été proposée. */
data class HiredSlot(val candidate: Candidate, val staffId: Long)

/**
 * Les accès SQL du personnel et de l'agence, derrière une interface : les tests de route tournent avec un faux en mémoire,
 * le SQL avec la vraie base (Testcontainers, `StaffSqlTest`). La lecture du personnel pour le moteur d'économie passe par
 * `SessionStore.loadStaff` ; le Donar de l'entreprise s'écrit par `updateCompany` ; l'événement par `CompanyStore.publish`.
 *
 * Toute écriture d'ici suit l'écriture de l'entreprise (`updateCompany`, verrou optimiste sur `companies.version`) : c'est
 * la version qui protège ces lignes d'une action concurrente.
 *
 * La liste du jour n'est PAS gardée en base : elle se retire du jour et de l'entreprise (`AgencyDraw`). `agency_candidates`
 * ne garde que les places embauchées, avec le candidat tel qu'il a été proposé.
 */
interface StaffStore {
    /** Les places de la liste du jour [day] déjà embauchées, par place croissante. */
    fun hiredSlots(connection: Connection, companyId: Long, day: LocalDate): List<HiredSlot>

    /** Le membre [staffId] de l'entreprise [companyId], ou `null` s'il n'existe pas ou appartient à une autre entreprise. */
    fun find(connection: Connection, companyId: Long, staffId: Long): StaffRecord?

    /**
     * Ajoute le membre issu de [candidate] à l'entreprise, affecté à [factoryId] (ou sans usine), avec le gain de
     * production de sa rareté ([bonusPercent], gardé à titre d'information : le moteur lit le bonus dans `personnel.json`).
     * Rend son identifiant.
     */
    fun insert(connection: Connection, companyId: Long, candidate: Candidate, factoryId: String?, bonusPercent: Double): Long

    /** Note que la place [Candidate.slot] de la liste du jour [day] est embauchée, sous l'identifiant [staffId]. */
    fun recordHire(connection: Connection, companyId: Long, day: LocalDate, candidate: Candidate, bonusPercent: Double, staffId: Long)

    /** Change l'usine du membre [staffId] de l'entreprise [companyId]. */
    fun assign(connection: Connection, companyId: Long, staffId: Long, factoryId: String)

    /** Renvoie le membre : sa ligne disparaît, et sa place dans la liste du jour (s'il y en a une) se libère. */
    fun delete(connection: Connection, companyId: Long, staffId: Long)
}
