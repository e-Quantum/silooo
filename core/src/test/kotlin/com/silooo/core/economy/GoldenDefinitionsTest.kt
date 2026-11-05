package com.silooo.core.economy

import com.silooo.core.GoldenFiles
import com.silooo.core.array
import com.silooo.core.double
import com.silooo.core.int
import com.silooo.core.long
import com.silooo.core.obj
import com.silooo.core.objects
import com.silooo.core.string
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * definitions/ contre le tableur : chaque chiffre que le jeu lit dans les JSON doit être celui du tableur
 * d'équilibrage (golden/parametres.json, courses.json, ville.json). Si Kr change un chiffre dans definitions/
 * sans changer le tableur, ce test le dit : les deux doivent bouger ensemble, ou le test doré est à régénérer.
 */
class GoldenDefinitionsTest {
    private val definitions = TypicalPlayer.definitions
    private val engine = TypicalPlayer.engine
    private val parametres = GoldenFiles.read("parametres")
    private val settings = parametres.obj("settings")

    private fun setting(key: String): Double = settings.obj(key).double("value")

    @Test
    @DisplayName("Les réglages du tableur (Paramètres B4 à B17) sont ceux de economie.json")
    fun settingsMatchEconomy() {
        val economy = definitions.economy
        assertEquals(setting("priceGrowthPerLevel"), economy.levels.priceGrowth, "croissance du prix par niveau")
        assertEquals(setting("offlineCapHours"), economy.offline.capHours, "plafond hors ligne")
        assertEquals(setting("offlineEfficiency"), economy.offline.efficiency, "efficacité hors ligne")
        assertEquals(setting("productionBonusPerLingotKept"), economy.prestige.bonusPerHeldLingot, "bonus par Lingot détenu")
        assertEquals(setting("lingotsConversionCoefficient"), economy.prestige.lingotsConversion.coefficient, "coefficient de conversion")
        assertEquals(setting("lingotsConversionDivisor"), economy.prestige.lingotsConversion.referenceDivisor, "diviseur de référence")
        assertEquals(setting("welcomeLingots"), economy.lingotsOutsidePrestige.welcomeMission.toDouble(), "Lingots de bienvenue")
        assertEquals(setting("missionLingotsPerPlayHour"), economy.lingotsOutsidePrestige.averagePerPlayHour, "Lingots de missions par heure de jeu")
        assertEquals(setting("productionPerStar"), economy.prestige.productionPerStar, "production par étoile")
        assertEquals(setting("startingCarriereLevel"), economy.prestige.courseStart.level.toDouble(), "niveau de départ de la Carrière")
    }

    @Test
    @DisplayName("L'usine de départ de chaque course est la Carrière, comme dans le tableur")
    fun courseStartFactory() {
        val start = definitions.economy.prestige.courseStart
        val carriere = parametres.array("factories").first().jsonObject
        assertEquals("Carrière", carriere.string("name"))
        assertEquals(carriere.string("name"), definitions.factories.factories.single { it.id == start.factoryId }.name)
        assertEquals(mapOf(start.factoryId to start.level), engine.startingLevels().filterValues { it > 0 })
    }

    @Test
    @DisplayName("Les 15 usines : même ordre, même nom, même époque, mêmes prix et productions que le tableur")
    fun factoriesMatchTheSheet() {
        val sheet = parametres.array("factories").map { it.jsonObject }
        val ours = definitions.factories.factories
        assertEquals(15, sheet.size)
        assertEquals(sheet.size, ours.size)
        for ((expected, factory) in sheet.zip(ours)) {
            assertEquals(expected.string("name"), factory.name)
            assertEquals(expected.int("era"), factory.era, "époque de ${factory.name}")
            assertEquals(expected.double("level1Price"), factory.level1Price, "prix du niveau 1 de ${factory.name}")
            assertEquals(expected.double("productionPerLevel"), factory.productionPerLevel, "production par niveau de ${factory.name}")
        }
        assertEquals(TypicalPlayer.parametresFactoryNames(), ours.map { it.name })
    }

    @Test
    @DisplayName("Les seuils de prestige de economie.json sont ceux de la feuille Courses")
    fun thresholdsMatchCourses() {
        val courses = GoldenFiles.read("courses").objects("courses")
        assertEquals(courses.size, definitions.economy.prestige.thresholds.size)
        for ((expected, threshold) in courses.zip(definitions.economy.prestige.thresholds)) {
            val n = expected.int("course")
            assertEquals(n, threshold.course)
            assertEquals(expected.double("prestigeThresholdDonar"), threshold.donar, "seuil de la course $n")
            assertEquals(expected.double("prestigeThresholdDonar"), engine.prestigeThreshold(n), "seuil du moteur à la course $n")
        }
    }

    @Test
    @DisplayName("Les 16 prix de zones de ville de economie.json sont ceux de la feuille Ville")
    fun cityPricesMatchCity() {
        val zones = GoldenFiles.read("ville").objects("zones")
        assertEquals(zones.map { it.long("priceLingots") }, definitions.economy.city.zonePricesLingots)
    }

    @Test
    @DisplayName("Le multiplicateur hors ligne du joueur type (Paramètres!B8) est celui que le moteur calcule")
    fun offlineMultiplierMatchesTheSheet() {
        assertGolden(setting("offlineMultiplier"), engine.dailyOfflineMultiplier(setting("playHoursPerDay")), "multiplicateur hors ligne")
    }
}
