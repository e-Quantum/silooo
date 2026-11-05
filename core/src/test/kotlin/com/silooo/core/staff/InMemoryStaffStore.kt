package com.silooo.core.staff

import com.silooo.core.session.InMemorySessionStore
import com.silooo.core.session.StaffRecord
import java.sql.Connection
import java.time.LocalDate

/**
 * Le faux en mémoire de [StaffStore], pour les tests des routes du personnel sans base. Le personnel vit dans le faux
 * [InMemorySessionStore] (`staff`), que le moteur d'économie lit par `loadStaff` ; les places embauchées de la liste du jour
 * sont ici, comme `agency_candidates`. Les identifiants se suivent sans jamais se réutiliser, comme `AUTO_INCREMENT`. Le SQL
 * lui-même se teste sur MariaDB (`StaffSqlTest`).
 */
class InMemoryStaffStore(private val sessions: InMemorySessionStore) : StaffStore {
    /** `agency_candidates` : une place embauchée d'un jour d'une entreprise. */
    data class AgencyRow(val companyId: Long, val day: LocalDate, val candidate: Candidate, val bonusPercent: Double, val staffId: Long)

    /** Une écriture de `staff`, dans l'ordre : ce que le service a demandé. */
    sealed interface Write {
        data class Inserted(val companyId: Long, val staffId: Long, val factoryId: String?, val candidate: Candidate, val bonusPercent: Double) : Write
        data class Assigned(val companyId: Long, val staffId: Long, val factoryId: String) : Write
        data class Deleted(val companyId: Long, val staffId: Long) : Write
    }

    val agency = mutableListOf<AgencyRow>()
    val writes = mutableListOf<Write>()
    private var lastId = 0L

    override fun hiredSlots(connection: Connection, companyId: Long, day: LocalDate): List<HiredSlot> =
        agency.filter { it.companyId == companyId && it.day == day }
            .sortedBy { it.candidate.slot }
            .map { HiredSlot(it.candidate, it.staffId) }

    override fun find(connection: Connection, companyId: Long, staffId: Long): StaffRecord? =
        sessions.staff[companyId].orEmpty().firstOrNull { it.id == staffId }

    override fun insert(connection: Connection, companyId: Long, candidate: Candidate, factoryId: String?, bonusPercent: Double): Long {
        val seeded = sessions.staff.values.flatten().maxOfOrNull { it.id } ?: 0L
        lastId = maxOf(lastId, seeded) + 1
        sessions.staff.getOrPut(companyId) { mutableListOf() } +=
            StaffRecord(lastId, factoryId, candidate.name, candidate.skinId, candidate.role, candidate.rarity)
        writes += Write.Inserted(companyId, lastId, factoryId, candidate, bonusPercent)
        return lastId
    }

    override fun recordHire(connection: Connection, companyId: Long, day: LocalDate, candidate: Candidate, bonusPercent: Double, staffId: Long) {
        check(agency.none { it.companyId == companyId && it.day == day && it.candidate.slot == candidate.slot }) {
            "agency_candidates : la place ${candidate.slot} est déjà embauchée le $day"
        }
        agency += AgencyRow(companyId, day, candidate, bonusPercent, staffId)
    }

    override fun assign(connection: Connection, companyId: Long, staffId: Long, factoryId: String) {
        val list = sessions.staff.getValue(companyId)
        val index = list.indexOfFirst { it.id == staffId }
        check(index >= 0) { "staff : membre $staffId absent de l'entreprise $companyId" }
        list[index] = list[index].copy(factoryId = factoryId)
        writes += Write.Assigned(companyId, staffId, factoryId)
    }

    override fun delete(connection: Connection, companyId: Long, staffId: Long) {
        agency.removeAll { it.companyId == companyId && it.staffId == staffId }
        check(sessions.staff.getValue(companyId).removeAll { it.id == staffId }) { "staff : membre $staffId absent de l'entreprise $companyId" }
        writes += Write.Deleted(companyId, staffId)
    }
}
