package com.silooo.core.economy

import com.silooo.core.api.FactoryState
import com.silooo.core.definitions.StaffRarity
import com.silooo.core.definitions.StaffRole
import com.silooo.core.economy.EngineFixture.Companion.hours
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.Regime
import com.silooo.core.session.StaffRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * L'électricité et les incidents dans les chiffres que [EngineFigures] rend : une usine électrique à l'arrêt sans Centrale (revenu et
 * salaires à zéro), une usine ralentie par un incident ouvert (en ligne seulement), et la production versée à chaque régime. Les valeurs
 * attendues sortent du moteur et de `definitions/` ; sans incident et avant l'époque de l'électricité, tout reste ce que les autres
 * tests figent.
 */
class EngineFiguresIncidentTest {
    private val fx = EngineFixture()
    private val engine = fx.engine
    private val electricity = fx.definitions.incidents.electricity
    private val starter = fx.starting.factoryId
    private val powerPlant = electricity.powerPlantId
    private val electric = fx.definitions.factories.factories.first { it.needsElectricity }.id
    private val plain = fx.definitions.factories.factories.first { !it.needsElectricity && it.id != starter && it.id != powerPlant }.id
    private var nextId = 0L

    /** Une entreprise de l'époque [era], avec la Carrière de départ, une usine ordinaire et une usine électrique actives au niveau 10. */
    private fun company(era: Int): CompanyRecord {
        val company = fx.update(fx.newCompany().copy(era = era, courseNumber = era))
        fx.putFactory(company.id, FactoryRecord(plain, FactoryState.ACTIVE, 0, 10, null))
        fx.putFactory(company.id, FactoryRecord(electric, FactoryState.ACTIVE, 0, 10, null))
        return company
    }

    private fun putPowerPlant(company: CompanyRecord, state: FactoryState = FactoryState.ACTIVE, level: Int = 1) =
        fx.putFactory(company.id, FactoryRecord(powerPlant, state, 0, level, null))

    private fun openIncident(company: CompanyRecord, factoryId: String, slowdown: Double) {
        val level = fx.store.loadFactories(fx.connection, company.id).single { it.factoryId == factoryId }.level
        fx.putFactory(company.id, FactoryRecord(factoryId, FactoryState.ACTIVE, 0, level, openIncidentId = 7, incidentSlowdown = slowdown, incidentKind = "panne"))
    }

    private fun hire(company: CompanyRecord, factoryId: String): StaffRecord {
        nextId += 1
        val member = StaffRecord(nextId, factoryId, "Membre $nextId", "ouvrier-casque", StaffRole.SALARIE, StaffRarity.RARE)
        fx.store.staff.getOrPut(company.id) { mutableListOf() } += member
        return member
    }

    private fun pending(company: CompanyRecord, regime: Regime) =
        fx.figures.pendingProduction(fx.connection, company, fx.store.loadFactories(fx.connection, company.id), regime)

    @Test
    @DisplayName("Avant l'époque de l'électricité, une usine électrique produit sans Centrale")
    fun noOutageBeforeTheEra() {
        val company = company(electricity.fromEra - 1)
        val figures = fx.figuresOf(company.id)

        assertFalse(figures.forFactory(electric).stoppedNoPower)
        assertEquals(figures.multiplier * engine.factoryRate(electric, 10), figures.forFactory(electric).ratePerHour)
        assertFalse(figures.forFactory(starter).stoppedNoPower)
    }

    @Test
    @DisplayName("À l'époque de l'électricité, sans Centrale : l'usine électrique est à l'arrêt, son revenu est nul, les autres usines et la somme des usines suivent")
    fun anElectricFactoryStopsWithoutAPowerPlant() {
        val company = company(electricity.fromEra)
        val figures = fx.figuresOf(company.id)

        val stopped = figures.forFactory(electric)
        assertTrue(stopped.stoppedNoPower)
        assertEquals(0.0, stopped.ratePerHour)
        assertEquals(0.0, stopped.nominalRatePerHour, "à l'arrêt, il n'y a pas de revenu sans ralentissement non plus")
        assertTrue(stopped.nextCost > 0.0, "le prix du prochain niveau, lui, s'affiche toujours")
        assertFalse(figures.forFactory(plain).stoppedNoPower)
        assertFalse(figures.forFactory(starter).stoppedNoPower)
        assertTrue(figures.forFactory(plain).ratePerHour > 0.0)
        assertGolden(figures.forFactory(starter).ratePerHour + figures.forFactory(plain).ratePerHour, figures.ratePerHour, "le revenu est celui des usines qui tournent")
    }

    @Test
    @DisplayName("Une Centrale ACTIVE de niveau 1 au moins rallume l'usine électrique ; en ruine, en remise en état ou au niveau 0, elle ne compte pas")
    fun onlyARunningPowerPlantKeepsTheLightsOn() {
        val company = company(electricity.fromEra)
        assertTrue(fx.figuresOf(company.id).forFactory(electric).stoppedNoPower)

        putPowerPlant(company, FactoryState.EN_RUINE, level = 0)
        assertTrue(fx.figuresOf(company.id).forFactory(electric).stoppedNoPower, "une Centrale en ruine ne produit pas de courant")
        putPowerPlant(company, FactoryState.REMISE_EN_ETAT, level = 3)
        assertTrue(fx.figuresOf(company.id).forFactory(electric).stoppedNoPower, "ni une Centrale en remise en état")
        putPowerPlant(company, FactoryState.ACTIVE, level = 0)
        assertTrue(fx.figuresOf(company.id).forFactory(electric).stoppedNoPower, "ni une Centrale de niveau 0")

        putPowerPlant(company, FactoryState.ACTIVE, level = 1)
        val lit = fx.figuresOf(company.id)
        assertFalse(lit.forFactory(electric).stoppedNoPower)
        assertEquals(lit.multiplier * engine.factoryRate(electric, 10), lit.forFactory(electric).ratePerHour)
        assertEquals(lit.forFactory(electric).ratePerHour, lit.forFactory(electric).nominalRatePerHour)
        assertFalse(lit.forFactory(powerPlant).stoppedNoPower, "la Centrale ne dépend pas de l'électricité")
    }

    @Test
    @DisplayName("Une usine à l'arrêt faute de courant ne coûte aucun salaire ; une usine qui tourne paie le sien comme avant")
    fun aStoppedFactoryCostsNoSalary() {
        val company = company(electricity.fromEra)
        val inStopped = hire(company, electric)
        val inRunning = hire(company, plain)
        val figures = fx.figuresOf(company.id)

        assertEquals(0.0, figures.staff.single { it.staffId == inStopped.id }.salaryPerHour)
        assertTrue(figures.staff.single { it.staffId == inRunning.id }.salaryPerHour!! > 0.0)
        // L'équipe de l'usine à l'arrêt ne change pas le revenu de l'entreprise : elle ne rapporte rien et ne coûte rien.
        fx.store.staff.getValue(company.id).removeAll { it.id == inStopped.id }
        assertEquals(fx.figuresOf(company.id).ratePerHour, figures.ratePerHour)

        // Avec une Centrale, l'équipe de l'usine électrique travaille et son salaire reprend.
        hire(company, electric).also { putPowerPlant(company) }
        val lit = fx.figuresOf(company.id)
        assertTrue(lit.staff.single { it.factoryId == electric }.salaryPerHour!! > 0.0)
    }

    @Test
    @DisplayName("Un incident ouvert ralentit le revenu affiché de son usine et de l'entreprise, sans toucher aux autres usines ; le revenu sans incident reste lisible")
    fun anIncidentSlowsTheDisplayedRate() {
        val company = company(electricity.fromEra - 1)
        val before = fx.figuresOf(company.id)

        openIncident(company, plain, 0.5)
        val after = fx.figuresOf(company.id)

        assertGolden(before.forFactory(plain).ratePerHour * 0.5, after.forFactory(plain).ratePerHour, "usine ralentie")
        assertEquals(before.forFactory(plain).ratePerHour, after.forFactory(plain).nominalRatePerHour, "le revenu sans ralentissement ne bouge pas")
        assertEquals(before.forFactory(starter).ratePerHour, after.forFactory(starter).ratePerHour)
        assertEquals(before.forFactory(electric).ratePerHour, after.forFactory(electric).ratePerHour)
        assertGolden(
            after.forFactory(starter).ratePerHour + after.forFactory(plain).ratePerHour + after.forFactory(electric).ratePerHour,
            after.ratePerHour,
            "la somme des usines redonne le revenu",
        )
        assertTrue(after.ratePerHour < before.ratePerHour)
        assertTrue(after.ratePerHour > 0.0, "un incident ralentit, il n'arrête pas")
        assertFalse(after.forFactory(plain).stoppedNoPower)
    }

    @Test
    @DisplayName("Un incident ralentit aussi le salaire de l'équipe de l'usine : le net reste la même part de la production")
    fun anIncidentSlowsTheSalaryToo() {
        val company = company(electricity.fromEra - 1)
        val member = hire(company, plain)
        val before = fx.figuresOf(company.id)

        openIncident(company, plain, 0.5)
        val after = fx.figuresOf(company.id)

        assertGolden(before.staff.single { it.staffId == member.id }.salaryPerHour!! * 0.5, after.staff.single { it.staffId == member.id }.salaryPerHour!!, "salaire ralenti")
        assertGolden(before.forFactory(plain).ratePerHour * 0.5, after.forFactory(plain).ratePerHour, "revenu net ralenti")
    }

    @Test
    @DisplayName("La production versée en ligne suit le revenu ralenti ; hors ligne, elle suit le revenu sans incident, puisqu'aucun incident ne s'ouvre pendant une absence")
    fun onlinePaysSlowedOfflinePaysNominal() {
        val company = company(electricity.fromEra - 1)
        val nominal = fx.figuresOf(company.id).ratePerHour
        openIncident(company, plain, 0.5)
        val slowed = fx.figuresOf(company.id).ratePerHour
        assertTrue(slowed < nominal)

        fx.clock.advance(hours(3.0))
        assertEquals(engine.onlineEarnings(slowed, 3.0), pending(company, Regime.ONLINE).earned)
        assertEquals(engine.offlineEarnings(nominal, 3.0, company.offlineCapHours), pending(company, Regime.OFFLINE).earned)
    }

    @Test
    @DisplayName("Une usine à l'arrêt faute de courant ne produit rien à aucun régime, en ligne comme hors ligne")
    fun aStoppedFactoryProducesNothingAtAnyRegime() {
        val company = company(electricity.fromEra)
        val runningRate = fx.figuresOf(company.id).ratePerHour

        fx.clock.advance(hours(3.0))
        assertEquals(engine.onlineEarnings(runningRate, 3.0), pending(company, Regime.ONLINE).earned)
        assertEquals(engine.offlineEarnings(runningRate, 3.0, company.offlineCapHours), pending(company, Regime.OFFLINE).earned)

        // Avec une Centrale, l'usine électrique produit de nouveau, aux deux régimes.
        putPowerPlant(company)
        val litRate = fx.figuresOf(company.id).ratePerHour
        assertTrue(litRate > runningRate)
        assertEquals(engine.onlineEarnings(litRate, 3.0), pending(company, Regime.ONLINE).earned)
        assertEquals(engine.offlineEarnings(litRate, 3.0, company.offlineCapHours), pending(company, Regime.OFFLINE).earned)
    }
}
