package com.silooo.core.city

import com.silooo.core.api.CityBonuses
import com.silooo.core.api.CityBuilding
import com.silooo.core.api.CityState
import com.silooo.core.api.NextBuildingLevel
import com.silooo.core.api.NextZone
import com.silooo.core.definitions.BuildingBonusType
import com.silooo.core.definitions.BuildingDefinition
import com.silooo.core.definitions.CityDefinitions
import com.silooo.core.definitions.Definitions
import com.silooo.core.definitions.PlotDefinition
import com.silooo.core.definitions.ZoneDefinition
import kotlin.math.floor

/**
 * Les règles de la ville, sans base ni HTTP : où tombe une parcelle, combien coûte et demande une zone, ce que donnent les
 * bâtiments ensemble. Aucun chiffre du jeu n'est écrit ici : le quadrillage, les zones, les prix, les niveaux, les bonus et les
 * plafonds viennent de `definitions/ville.json` et `definitions/economie.json` (`ville.zonesPrixLingots`). Mêmes entrées, mêmes
 * sorties ; [CityService] décide et écrit, [CityFigures] montre.
 *
 * Les règles que les définitions ne disent pas et qu'on a tranchées au plus simple (**à valider par Kr**) :
 *  - un bâtiment occupe une parcelle (un chunk) et une parcelle n'a qu'un bâtiment ;
 *  - les zones s'achètent dans l'ordre de `zones.ordre`, de la 1 à la dernière ;
 *  - les logements hébergent les premiers membres du personnel, par identifiant ; un membre logé coûte moins de salaire ;
 *  - les bonus d'un même type s'additionnent, puis se plafonnent ; un bonus ne donne jamais de Lingots.
 */
class CityRules(definitions: Definitions) {
    private val city = definitions.city
    private val zonePrices: List<Long> = definitions.economy.city.zonePricesLingots

    /** Le nombre de zones de l'île : le plus haut `zoneIndex` que `buyZone` accepte. */
    val zoneCount: Int = city.zones.order.size

    /** Blocs sur le côté d'une parcelle : le pas des coordonnées d'un bâtiment. */
    val plotBlocks: Int = city.grid.plotBlocks()

    private val zoneBlocks: Int = city.zones.sizeChunks * city.grid.blocksPerChunk
    private val islandBlocks: Int = city.zones.columns * zoneBlocks
    private val zoneByCell: Map<Pair<Int, Int>, Int> =
        city.zones.order.withIndex().associate { (index, zone) -> (zone.column to zone.row) to index + 1 }

    /** Le bâtiment [id] du catalogue, ou `null`. */
    fun building(id: String): BuildingDefinition? = city.building(id)

    /** La mairie du catalogue : `Definitions` a vérifié qu'elle existe. */
    val townHall: BuildingDefinition = checkNotNull(city.building(CityDefinitions.TOWN_HALL_ID)) { "ville.json : la mairie manque" }

    /** La parcelle de la mairie de départ (`ville.json : mairie.emplacementDepart`), posée avec la première zone. */
    val startPlot: PlotDefinition = city.townHall.startPlot

    /** Les Lingots que coûte la zone [zoneIndex] (de 1 à [zoneCount]), `economie.json : ville.zonesPrixLingots`. */
    fun zonePrice(zoneIndex: Int): Long = zonePrices[zoneIndex - 1]

    /** Ce que la zone [zoneIndex] demande : le niveau de mairie (`zones.ordre`). */
    fun zone(zoneIndex: Int): ZoneDefinition = city.zones.order[zoneIndex - 1]

    /** Les usines actives qu'il faut avoir pour acheter la zone [zoneIndex] : un minimum pour la première, rien pour les autres. */
    fun activeFactoriesRequired(zoneIndex: Int): Int = if (zoneIndex == 1) city.zones.first.activeFactoriesMin else 0

    /** Le numéro de la prochaine zone à acheter quand l'entreprise a les zones [owned], ou `null` si elle les a toutes. */
    fun nextZoneIndex(owned: Collection<Int>): Int? = ((owned.maxOrNull() ?: 0) + 1).takeIf { it <= zoneCount }

    /**
     * La zone où tombe la parcelle dont le coin est `(x, z)`, ou `null` si la parcelle n'est pas sur l'île ou ne tombe pas sur la
     * grille des parcelles (un multiple de [plotBlocks]).
     */
    fun zoneOfPlot(x: Int, z: Int): Int? {
        if (x < 0 || z < 0 || x >= islandBlocks || z >= islandBlocks) return null
        if (x % plotBlocks != 0 || z % plotBlocks != 0) return null
        return zoneByCell[(x / zoneBlocks) to (z / zoneBlocks)]
    }

    /** Le plafond du bonus [type]. */
    fun cap(type: BuildingBonusType): Double = city.cap(type)

    /**
     * Ce que les bâtiments donnent ensemble : la somme des bonus de chaque type, plafonnée (`plafonds`). Les places de logement
     * sont entières (la partie entière de la somme) ; [staffCount] membres embauchés en occupent au plus autant. Un bâtiment dont
     * le type a quitté `ville.json` ne donne rien, et un niveau au-delà du dernier compte comme le dernier.
     */
    fun bonuses(buildings: List<CityBuildingRecord>, staffCount: Int): CityBonuses {
        val places = floor(total(BuildingBonusType.LOGEMENTS, buildings)).toInt()
        return CityBonuses(
            housingPlaces = places,
            housedStaff = minOf(places, staffCount),
            donarProductionPercent = total(BuildingBonusType.PRODUCTION_DONAR, buildings),
            deliverySpeedPercent = total(BuildingBonusType.LIVRAISON_VITESSE, buildings),
        )
    }

    private fun total(type: BuildingBonusType, buildings: List<CityBuildingRecord>): Double {
        var sum = 0.0
        for (record in buildings) {
            val definition = building(record.buildingType) ?: continue
            if (definition.bonusType == type) sum += definition.level(record.level.coerceIn(1, definition.maxLevel())).bonus ?: 0.0
        }
        return minOf(sum, cap(type))
    }

    /** Un bâtiment tel que le contrat le montre, ou `null` si son type a quitté `ville.json`. */
    fun view(record: CityBuildingRecord): CityBuilding? {
        val definition = building(record.buildingType) ?: return null
        val level = record.level.coerceIn(1, definition.maxLevel())
        val next = if (level < definition.maxLevel()) {
            definition.level(level + 1).let { NextBuildingLevel(it.priceLingots, it.townHallLevelRequired) }
        } else {
            null
        }
        return CityBuilding(
            buildingId = record.id,
            buildingType = definition.id,
            level = level,
            maxLevel = definition.maxLevel(),
            zoneIndex = record.zoneIndex,
            x = record.x,
            z = record.z,
            rotation = record.rotation,
            bonusType = definition.bonusType,
            bonus = definition.level(level).bonus,
            nextLevel = next,
        )
    }

    /** La ville de l'entreprise : niveau de mairie, prochaine zone, bâtiments et bonus. */
    fun state(mairieLevel: Int, owned: Collection<Int>, buildings: List<CityBuildingRecord>, staffCount: Int): CityState =
        CityState(
            mairieLevel = mairieLevel,
            nextZone = nextZoneIndex(owned)?.let { NextZone(it, zonePrice(it), zone(it).townHallLevelRequired, activeFactoriesRequired(it)) },
            buildings = buildings.mapNotNull(::view),
            bonuses = bonuses(buildings, staffCount),
        )
}
