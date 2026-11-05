package com.silooo.core.city

import com.silooo.core.db.Xuid
import java.sql.Connection

/** Une ligne de `city_buildings` : un bâtiment posé, au niveau où il est, sur la parcelle dont le coin est `(x, z)`. */
data class CityBuildingRecord(
    val id: Long,
    val buildingType: String,
    val level: Int,
    val zoneIndex: Int,
    val x: Int,
    val z: Int,
    val rotation: Int,
)

/**
 * Les accès SQL de la ville (`city_zones`, `city_buildings`) et le paiement en Lingots, derrière une interface : les tests de
 * route tournent avec un faux en mémoire, le SQL avec la vraie base (Testcontainers, `CitySqlTest`). Les zones possédées se lisent
 * par `SessionStore.loadZones`, le niveau de la mairie par `companies.mairie_level` ; l'événement passe par `CompanyStore.publish`.
 *
 * Toute écriture d'ici suit l'écriture de l'entreprise (`writeProduction`, verrou optimiste sur `companies.version`) : c'est la
 * version qui protège ces lignes d'une action concurrente.
 */
interface CityStore {
    /** Les bâtiments de l'entreprise [companyId], par identifiant croissant : l'ordre fixe aussi l'ordre des sommes de bonus. */
    fun loadBuildings(connection: Connection, companyId: Long): List<CityBuildingRecord>

    /** Le bâtiment [buildingId] de l'entreprise [companyId], ou `null` s'il n'existe pas ou appartient à une autre entreprise. */
    fun findBuilding(connection: Connection, companyId: Long, buildingId: Long): CityBuildingRecord?

    /** Note que [boughtBy] a acheté la zone [zoneIndex] de l'entreprise pour [lingotsPaid] Lingots. */
    fun insertZone(connection: Connection, companyId: Long, zoneIndex: Int, boughtBy: Xuid, lingotsPaid: Long)

    /** Pose un bâtiment et rend son identifiant. */
    fun insertBuilding(connection: Connection, companyId: Long, buildingType: String, level: Int, zoneIndex: Int, x: Int, z: Int, rotation: Int): Long

    /** Change le niveau du bâtiment [buildingId] de l'entreprise [companyId]. */
    fun setBuildingLevel(connection: Connection, companyId: Long, buildingId: Long, level: Int)

    /**
     * Retire [amount] Lingots à [xuid] : `player_balances` et une ligne de `currency_ledger`, dans la transaction de [connection]
     * (voir `creditBalance`). [reason] tient en 32 caractères, [refId] en 64. Lève `InsufficientBalance` si le solde ne couvre pas
     * la dépense, avant toute écriture. Rend le solde après la dépense.
     */
    fun spendLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long
}
