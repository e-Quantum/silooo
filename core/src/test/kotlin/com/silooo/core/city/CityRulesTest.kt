package com.silooo.core.city

import com.silooo.core.TestDefinitions
import com.silooo.core.definitions.BuildingBonusType
import com.silooo.core.definitions.CityDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les règles de la ville, sans base ni HTTP : [CityRules] sur les `definitions/` du dépôt. Les chiffres attendus sortent des
 * définitions (`ville.json`, `economie.json`) lues dans le test, jamais d'un littéral : changer le JSON ne casse pas ces tests tant
 * que les règles tiennent.
 */
class CityRulesTest {
    private val definitions = TestDefinitions.loaded
    private val city = definitions.city
    private val rules = CityRules(definitions)
    private val zoneBlocks = city.zones.sizeChunks * city.grid.blocksPerChunk
    private val step = city.grid.plotBlocks()

    private var nextId = 0L

    private fun record(type: String, level: Int = 1, zoneIndex: Int = 1, x: Int = 0, z: Int = 0) =
        CityBuildingRecord(++nextId, type, level, zoneIndex, x, z, 0)

    private fun top(type: String): Int = city.building(type)!!.maxLevel()

    // --- le quadrillage

    @Test
    @DisplayName("Chaque parcelle de l'île tombe dans la zone que ville.json nomme, et chaque zone compte le même nombre de parcelles")
    fun everyPlotHasItsZone() {
        val perSide = zoneBlocks / step
        val counts = HashMap<Int, Int>()
        for (x in 0 until city.zones.columns * zoneBlocks step step) {
            for (z in 0 until city.zones.columns * zoneBlocks step step) {
                val zone = rules.zoneOfPlot(x, z)
                assertNotNull(zone, "($x, $z)")
                val expected = city.zones.order.indexOfFirst { it.column == x / zoneBlocks && it.row == z / zoneBlocks } + 1
                assertEquals(expected, zone, "($x, $z)")
                counts.merge(zone!!, 1, Int::plus)
            }
        }
        assertEquals((1..rules.zoneCount).toSet(), counts.keys)
        assertTrue(counts.values.all { it == perSide * perSide }, "$counts")
    }

    @Test
    @DisplayName("Une parcelle hors de l'île, négative ou qui ne tombe pas sur la grille n'a pas de zone")
    fun plotsOutsideTheGrid() {
        val island = city.zones.columns * zoneBlocks
        for ((x, z) in listOf(-step to 0, 0 to -step, island to 0, 0 to island, island to island, 1 to 0, 0 to 1, step - 1 to step, Int.MAX_VALUE to 0, Int.MIN_VALUE to 0)) {
            assertNull(rules.zoneOfPlot(x, z), "($x, $z)")
        }
        assertNotNull(rules.zoneOfPlot(0, 0))
        assertNotNull(rules.zoneOfPlot(island - step, island - step))
    }

    @Test
    @DisplayName("La mairie de départ est dans la zone 1")
    fun startPlotIsInTheFirstZone() {
        assertEquals(1, rules.zoneOfPlot(rules.startPlot.x, rules.startPlot.z))
        assertEquals(city.townHall.startPlot, rules.startPlot)
    }

    // --- les zones

    @Test
    @DisplayName("Le prix et le niveau de mairie de chaque zone viennent des définitions ; la première seule demande des usines actives")
    fun zonesAskWhatTheDefinitionsSay() {
        assertEquals(definitions.economy.city.zonePricesLingots.size, rules.zoneCount)
        for (index in 1..rules.zoneCount) {
            assertEquals(definitions.economy.city.zonePricesLingots[index - 1], rules.zonePrice(index))
            assertEquals(city.zones.order[index - 1].townHallLevelRequired, rules.zone(index).townHallLevelRequired)
            assertEquals(if (index == 1) city.zones.first.activeFactoriesMin else 0, rules.activeFactoriesRequired(index))
        }
    }

    @Test
    @DisplayName("La prochaine zone est la suivante de la plus haute possédée, et il n'y en a plus quand elles sont toutes à l'entreprise")
    fun nextZone() {
        assertEquals(1, rules.nextZoneIndex(emptyList()))
        assertEquals(2, rules.nextZoneIndex(listOf(1)))
        assertEquals(rules.zoneCount, rules.nextZoneIndex((1 until rules.zoneCount).toList()))
        assertNull(rules.nextZoneIndex((1..rules.zoneCount).toList()))
    }

    @Test
    @DisplayName("La ville d'une entreprise neuve : mairie niveau 1, la première zone à acheter, rien d'autre")
    fun emptyCity() {
        val state = rules.state(mairieLevel = 1, owned = emptyList(), buildings = emptyList(), staffCount = 3)
        assertEquals(1, state.mairieLevel)
        val next = state.nextZone!!
        assertEquals(1, next.zoneIndex)
        assertEquals(rules.zonePrice(1), next.priceLingots)
        assertEquals(rules.zone(1).townHallLevelRequired, next.mairieLevelRequired)
        assertEquals(city.zones.first.activeFactoriesMin, next.activeFactoriesRequired)
        assertTrue(state.buildings.isEmpty())
        assertEquals(0, state.bonuses.housingPlaces)
        assertEquals(0, state.bonuses.housedStaff)
        assertEquals(0.0, state.bonuses.donarProductionPercent)
        assertEquals(0.0, state.bonuses.deliverySpeedPercent)
    }

    @Test
    @DisplayName("Toutes les zones achetées : plus de nextZone")
    fun everyZoneOwned() {
        val state = rules.state(rules.zoneCount, (1..rules.zoneCount).toList(), emptyList(), 0)
        assertNull(state.nextZone)
    }

    // --- les bâtiments

    @Test
    @DisplayName("Un bâtiment montre son niveau, son bonus et le niveau suivant ; au dernier niveau il n'y a plus de nextLevel")
    fun buildingView() {
        val house = city.building("maison")!!
        val first = rules.view(record("maison", 1, x = 16))!!
        assertEquals(1, first.level)
        assertEquals(house.maxLevel(), first.maxLevel)
        assertEquals(BuildingBonusType.LOGEMENTS, first.bonusType)
        assertEquals(house.level(1).bonus, first.bonus)
        assertEquals(house.level(2).priceLingots, first.nextLevel!!.priceLingots)
        assertEquals(house.level(2).townHallLevelRequired, first.nextLevel.mairieLevelRequired)

        val last = rules.view(record("maison", house.maxLevel()))!!
        assertEquals(house.level(house.maxLevel()).bonus, last.bonus)
        assertNull(last.nextLevel)

        val road = rules.view(record("route"))!!
        assertNull(road.bonusType)
        assertNull(road.bonus)
        assertNull(rules.view(record("palais")), "un type qui a quitté ville.json n'est pas montré")
    }

    @Test
    @DisplayName("Les bonus d'un même type s'additionnent, les places de logement sont entières, un niveau hors catalogue compte comme le dernier")
    fun bonusesAddUp() {
        val house = city.building("maison")!!
        val commerce = city.building("commerce")!!
        val garage = city.building("garage")!!
        val buildings = listOf(
            record("maison", 1), record("maison", house.maxLevel()), record("maison", 99),
            record("commerce", 1), record("commerce", commerce.maxLevel()),
            record("garage", 2),
            record("mairie", 5), record("route", 1), record("palais", 3),
        )
        val bonuses = rules.bonuses(buildings, staffCount = 100)
        val places = (house.level(1).bonus!! + house.level(house.maxLevel()).bonus!! * 2)
        assertEquals(places.toInt(), bonuses.housingPlaces)
        assertEquals(commerce.level(1).bonus!! + commerce.level(commerce.maxLevel()).bonus!!, bonuses.donarProductionPercent)
        assertEquals(garage.level(2).bonus!!, bonuses.deliverySpeedPercent)
    }

    @Test
    @DisplayName("Chaque type de bonus est plafonné à la valeur de ville.json")
    fun capsApply() {
        val many = 1000
        val houses = List(many) { record("immeuble", top("immeuble")) }
        val shops = List(many) { record("commerce", top("commerce")) }
        val garages = List(many) { record("garage", top("garage")) }
        val bonuses = rules.bonuses(houses + shops + garages, staffCount = many)
        assertEquals(city.cap(BuildingBonusType.LOGEMENTS).toInt(), bonuses.housingPlaces)
        assertEquals(city.cap(BuildingBonusType.PRODUCTION_DONAR), bonuses.donarProductionPercent)
        assertEquals(city.cap(BuildingBonusType.LIVRAISON_VITESSE), bonuses.deliverySpeedPercent)
        assertEquals(bonuses.housingPlaces, bonuses.housedStaff, "des logements pour tout le monde : autant de logés que de places")
    }

    @Test
    @DisplayName("Les logés sont au plus les places et au plus le personnel embauché")
    fun housedStaff() {
        val houses = List(3) { record("maison", 1) }
        val places = rules.bonuses(houses, 0).housingPlaces
        assertTrue(places > 0)
        assertEquals(0, rules.bonuses(houses, 0).housedStaff)
        assertEquals(1, rules.bonuses(houses, 1).housedStaff)
        assertEquals(places, rules.bonuses(houses, places + 5).housedStaff)
        assertEquals(places - 1, rules.bonuses(houses, places - 1).housedStaff)
    }

    @Test
    @DisplayName("Sans bâtiment à bonus, tout est à zéro : le calcul est celui d'avant la ville")
    fun noBonusBuildingMeansZero() {
        val inert = listOf(record("mairie", 3), record("route"), record("route", 2))
        val bonuses = rules.bonuses(inert, staffCount = 5)
        assertEquals(0, bonuses.housingPlaces)
        assertEquals(0, bonuses.housedStaff)
        assertEquals(0.0, bonuses.donarProductionPercent)
        assertEquals(0.0, bonuses.deliverySpeedPercent)
        assertEquals(rules.bonuses(emptyList(), 5), bonuses)
    }

    @Test
    @DisplayName("Aucun bonus de la ville ne donne de Lingots : le catalogue n'a que des types sans monnaie")
    fun noBonusGivesLingots() {
        assertEquals(setOf("LOGEMENTS", "PRODUCTION_DONAR", "LIVRAISON_VITESSE"), BuildingBonusType.entries.map { it.name }.toSet())
        assertFalse(city.buildings.any { it.bonusType?.name?.contains("LINGOT") == true })
        assertEquals(CityDefinitions.TOWN_HALL_ID, rules.townHall.id)
        assertFalse(rules.townHall.placeable)
    }
}
