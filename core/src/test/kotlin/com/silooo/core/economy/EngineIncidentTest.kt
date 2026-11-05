package com.silooo.core.economy

import com.silooo.core.TestDefinitions
import com.silooo.core.definitions.StaffRarity
import com.silooo.core.definitions.StaffRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les formules des incidents et de l'électricité du [EconomyEngine] : le revenu d'une usine ralentie, les usines qui s'arrêtent
 * faute de courant, la récompense d'une réparation. Les chiffres sortent de `definitions/incidents.json` et de `usines.json` ; seuls
 * les calculs sont écrits ici, et une économie sans incident doit rester la même, bit pour bit.
 */
class EngineIncidentTest {
    private val definitions = TestDefinitions.loaded
    private val incidents = definitions.incidents
    private val engine = EconomyEngine(definitions)

    private val powerPlant = incidents.electricity.powerPlantId
    private val fromEra = incidents.electricity.fromEra
    private val electric = definitions.factories.factories.filter { it.needsElectricity }.map { it.id }
    private val plain = definitions.factories.factories.filter { !it.needsElectricity && it.id != powerPlant }.map { it.id }

    private fun crew(vararg members: Pair<StaffRole, StaffRarity>): List<CrewMember> = members.map { CrewMember(it.first, it.second) }

    @Test
    @DisplayName("Un facteur de 1 rend exactement la production sans incident, bit pour bit, avec ou sans équipe")
    fun noSlowdownChangesNothing() {
        val id = plain.first()
        val team = crew(StaffRole.SALARIE to StaffRarity.RARE, StaffRole.CHEF to StaffRarity.COMMUN)
        assertEquals(engine.factoryRate(id, 17), engine.factoryRate(id, 17, emptyList(), 1.0))
        assertEquals(engine.factoryRate(id, 17, team), engine.factoryRate(id, 17, team, 1.0))

        val levels = engine.startingLevels() + (plain.last() to 12) + (plain.first() to 9)
        val crews = mapOf(plain.first() to team)
        val base = engine.baseRate(levels, crews)
        assertEquals(base, engine.baseRate(levels, crews, emptyMap()))
        assertEquals(base, engine.baseRate(levels, crews, levels.keys.associateWith { 1.0 }), "un facteur de 1 partout")
        val multiplier = engine.globalMultiplier(3.0, 2)
        assertEquals(engine.ratePerHour(levels, multiplier, crews), engine.ratePerHour(levels, multiplier, crews, emptyMap()))
    }

    @Test
    @DisplayName("Un incident multiplie la production de l'usine, équipe comprise, par son facteur")
    fun slowdownMultipliesTheFactory() {
        val id = plain.first()
        val team = crew(StaffRole.SALARIE to StaffRarity.EPIQUE)
        assertEquals(engine.factoryRate(id, 25) / 2.0, engine.factoryRate(id, 25, emptyList(), 0.5))
        assertEquals(engine.factoryRate(id, 25, team) * 0.6, engine.factoryRate(id, 25, team, 0.6))
        assertTrue(engine.factoryRate(id, 25, team, 0.6) < engine.factoryRate(id, 25, team))
        assertTrue(engine.factoryRate(id, 25, team, 0.6) > 0.0, "un incident ralentit, il n'arrête pas")
    }

    @Test
    @DisplayName("Le revenu de l'entreprise ne ralentit que l'usine de l'incident : les autres et les usines à zéro ne bougent pas")
    fun baseRateSlowsOnlyTheIncidentFactory() {
        val slowed = plain[0]
        val other = plain[1]
        val levels = mapOf(slowed to 20, other to 14)
        val full = engine.baseRate(levels)
        val partial = engine.baseRate(levels, emptyMap(), mapOf(slowed to 0.5))

        val expected = engine.factoryRate(slowed, 20) * 0.5 + engine.factoryRate(other, 14)
        assertGolden(expected, partial, "une usine sur deux ralentie")
        assertTrue(partial < full)
        // Un incident sur une usine sans niveau, ou sur une usine absente, ne change rien.
        assertEquals(full, engine.baseRate(levels, emptyMap(), mapOf(plain[2] to 0.5)))
        // Les équipes comptent dans la production ralentie.
        val crews = mapOf(slowed to crew(StaffRole.SALARIE to StaffRarity.RARE))
        val withCrew = engine.baseRate(levels, crews, mapOf(slowed to 0.5))
        assertGolden(engine.factoryRate(slowed, 20, crews.getValue(slowed)) * 0.5 + engine.factoryRate(other, 14), withCrew, "avec équipe")
        // ratePerHour est baseRate fois le multiplicateur.
        val multiplier = engine.globalMultiplier(2.0, 1)
        assertEquals(multiplier * partial, engine.ratePerHour(levels, multiplier, emptyMap(), mapOf(slowed to 0.5)))
    }

    @Test
    @DisplayName("Avant l'époque de l'électricité, rien ne s'arrête, même sans Centrale")
    fun noOutageBeforeTheEra() {
        val producing = electric.associateWith { 5 } + plain.associateWith { 5 }
        for (era in 1 until fromEra) assertEquals(emptySet<String>(), engine.stoppedNoPower(era, producing), "époque $era")
    }

    @Test
    @DisplayName("À partir de l'époque de l'électricité, sans Centrale qui produise, les usines électriques s'arrêtent et elles seules")
    fun electricFactoriesStopWithoutAPowerPlant() {
        assertTrue(electric.isNotEmpty(), "usines.json a au moins une usine qui dépend de l'électricité")
        val producing = electric.associateWith { 5 } + plain.associateWith { 5 }
        for (era in fromEra..fromEra + 2) {
            assertEquals(electric.toSet(), engine.stoppedNoPower(era, producing), "époque $era")
        }
        // Sans usine électrique en production, personne ne s'arrête.
        assertEquals(emptySet<String>(), engine.stoppedNoPower(fromEra, plain.associateWith { 5 }))
        // La Centrale elle-même ne dépend pas de l'électricité : seule, elle ne s'arrête pas.
        assertEquals(emptySet<String>(), engine.stoppedNoPower(fromEra, mapOf(powerPlant to 3)))
        // Une usine que usines.json ne connaît pas ne compte pas.
        assertEquals(emptySet<String>(), engine.stoppedNoPower(fromEra, mapOf("usine-inconnue" to 4)))
    }

    @Test
    @DisplayName("Une Centrale qui produit, au niveau 1 au moins, rallume toutes les usines électriques ; au niveau 0 elle ne compte pas")
    fun aPowerPlantKeepsTheLightsOn() {
        val producing = electric.associateWith { 5 } + plain.associateWith { 5 }
        assertEquals(emptySet<String>(), engine.stoppedNoPower(fromEra, producing + (powerPlant to 1)))
        assertEquals(emptySet<String>(), engine.stoppedNoPower(fromEra, producing + (powerPlant to 40)))
        assertEquals(electric.toSet(), engine.stoppedNoPower(fromEra, producing + (powerPlant to 0)))
    }

    @Test
    @DisplayName("La récompense d'une réparation est minutesDeRevenu minutes du revenu de l'usine sans ralentissement ; sans revenu, rien")
    fun rewardIsMinutesOfRevenue() {
        val minutes = incidents.reward.revenueMinutes
        assertEquals(60.0 * minutes / 60.0, engine.incidentReward(60.0))
        assertEquals(0.0, engine.incidentReward(0.0))
        val rate = engine.factoryRate(plain.first(), 30) * 4.0
        assertGolden(rate * minutes / 60.0, engine.incidentReward(rate), "récompense")
        assertTrue(engine.incidentReward(rate * 2) > engine.incidentReward(rate))
        assertThrows(IllegalArgumentException::class.java) { engine.incidentReward(-1.0) }
    }
}
