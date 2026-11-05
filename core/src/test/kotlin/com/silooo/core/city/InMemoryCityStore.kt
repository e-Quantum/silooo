package com.silooo.core.city

import com.silooo.core.api.Balances
import com.silooo.core.db.Currency
import com.silooo.core.db.InsufficientBalance
import com.silooo.core.db.Xuid
import com.silooo.core.definitions.CityDefinitions
import com.silooo.core.session.InMemorySessionStore
import java.sql.Connection

/**
 * Le faux en mémoire de [CityStore], pour les tests des routes de la ville sans base. Les zones possédées vivent dans le faux
 * [InMemorySessionStore] (`zones`), que le moteur lit par `loadZones` ; les bâtiments sont ici, comme `city_buildings`. Les
 * identifiants se suivent sans jamais se réutiliser, comme `AUTO_INCREMENT`, et une parcelle n'a qu'un bâtiment, comme la clé
 * unique de V6. Les Lingots se retirent du solde du faux dépôt, avec le refus que `creditBalance` lève quand il ne suffit pas.
 *
 * La connexion de test ne change pas le faux dépôt de l'entreprise : le niveau de la mairie, que le vrai service écrit dans
 * `companies.mairie_level` par `updateCompany`, est recopié ici quand le bâtiment mairie monte, pour que l'action suivante d'un test
 * le retrouve. L'`UPDATE` lui-même se lit dans les écritures notées de la connexion. Le SQL se teste sur MariaDB (`CitySqlTest`).
 */
class InMemoryCityStore(private val sessions: InMemorySessionStore) : CityStore {
    /** Un retrait de Lingots : une ligne de `currency_ledger`. */
    data class Spent(val xuid: Xuid, val amount: Long, val reason: String, val refId: String)

    /** Une écriture de la ville, dans l'ordre : ce que le service a demandé. */
    sealed interface Write {
        data class ZoneBought(val companyId: Long, val zoneIndex: Int, val boughtBy: Xuid, val lingotsPaid: Long) : Write
        data class BuildingPlaced(val companyId: Long, val record: CityBuildingRecord) : Write
        data class BuildingLeveled(val companyId: Long, val buildingId: Long, val level: Int) : Write
    }

    val buildings = LinkedHashMap<Long, MutableList<CityBuildingRecord>>()
    val spent = mutableListOf<Spent>()
    val writes = mutableListOf<Write>()
    private var lastId = 0L

    /** Pose un bâtiment directement, sans payer ni noter d'écriture : l'état de départ d'un test. Rend son identifiant. */
    fun seedBuilding(companyId: Long, buildingType: String, level: Int, zoneIndex: Int, x: Int, z: Int, rotation: Int = 0): Long {
        val id = nextId()
        buildings.getOrPut(companyId) { mutableListOf() } += CityBuildingRecord(id, buildingType, level, zoneIndex, x, z, rotation)
        return id
    }

    /** Rend à l'entreprise les zones [owned] sans les payer : l'état de départ d'un test. */
    fun seedZones(companyId: Long, owned: List<Int>) {
        sessions.zones[companyId] = owned.sorted()
    }

    private fun nextId(): Long {
        lastId = maxOf(lastId, buildings.values.flatten().maxOfOrNull { it.id } ?: 0L) + 1
        return lastId
    }

    override fun loadBuildings(connection: Connection, companyId: Long): List<CityBuildingRecord> =
        buildings[companyId].orEmpty().sortedBy { it.id }

    override fun findBuilding(connection: Connection, companyId: Long, buildingId: Long): CityBuildingRecord? =
        buildings[companyId].orEmpty().firstOrNull { it.id == buildingId }

    override fun insertZone(connection: Connection, companyId: Long, zoneIndex: Int, boughtBy: Xuid, lingotsPaid: Long) {
        val owned = sessions.zones[companyId].orEmpty()
        check(zoneIndex !in owned) { "city_zones : la zone $zoneIndex est déjà à l'entreprise $companyId" }
        sessions.zones[companyId] = (owned + zoneIndex).sorted()
        writes += Write.ZoneBought(companyId, zoneIndex, boughtBy, lingotsPaid)
    }

    override fun insertBuilding(connection: Connection, companyId: Long, buildingType: String, level: Int, zoneIndex: Int, x: Int, z: Int, rotation: Int): Long {
        check(buildings[companyId].orEmpty().none { it.x == x && it.z == z }) { "city_buildings : la parcelle ($x, $z) a déjà un bâtiment (ux_buildings_plot)" }
        val id = nextId()
        val record = CityBuildingRecord(id, buildingType, level, zoneIndex, x, z, rotation)
        buildings.getOrPut(companyId) { mutableListOf() } += record
        writes += Write.BuildingPlaced(companyId, record)
        return id
    }

    override fun setBuildingLevel(connection: Connection, companyId: Long, buildingId: Long, level: Int) {
        val list = buildings.getValue(companyId)
        val index = list.indexOfFirst { it.id == buildingId }
        check(index >= 0) { "city_buildings : bâtiment $buildingId absent de l'entreprise $companyId" }
        list[index] = list[index].copy(level = level)
        if (list[index].buildingType == CityDefinitions.TOWN_HALL_ID) {
            sessions.companies[companyId] = sessions.companies.getValue(companyId).copy(mairieLevel = level)
        }
        writes += Write.BuildingLeveled(companyId, buildingId, level)
    }

    override fun spendLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long {
        require(amount >= 0L) { "Une dépense n'est pas négative : $amount" }
        val before = sessions.balances.getValue(xuid)
        if (amount > before.lingots) throw InsufficientBalance(Currency.LINGOTS, amount - before.lingots)
        sessions.balances[xuid] = Balances(before.lingots - amount, before.gemmes, before.jetons, before.cles)
        spent += Spent(xuid, amount, reason, refId)
        return before.lingots - amount
    }
}
