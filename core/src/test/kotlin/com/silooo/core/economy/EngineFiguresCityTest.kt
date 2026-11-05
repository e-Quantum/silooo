package com.silooo.core.economy

import com.silooo.core.api.FactoryState
import com.silooo.core.city.CityFigures
import com.silooo.core.city.CityRules
import com.silooo.core.city.InMemoryCityStore
import com.silooo.core.definitions.StaffRarity
import com.silooo.core.definitions.StaffRole
import com.silooo.core.economy.EngineFixture.Companion.hours
import com.silooo.core.session.CompanyFigures
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.Regime
import com.silooo.core.session.StaffRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La ville dans les chiffres que [EngineFigures] rend : le bonus de production sur le multiplicateur, le logement qui baisse le
 * salaire des premiers membres du personnel, et la règle qui tient tout ensemble : sans bâtiment à bonus, ou sans source de ville,
 * rien ne change, bit pour bit. Les valeurs attendues sortent du moteur et de `definitions/ville.json`, jamais d'un littéral.
 */
class EngineFiguresCityTest {
    private val fx = EngineFixture()
    private val engine = fx.engine
    private val city = fx.definitions.city
    private val cityStore = InMemoryCityStore(fx.store)
    private val withCity = EngineFigures(engine, fx.store, fx.clock, CityFigures(CityRules(fx.definitions), fx.store, cityStore))
    private val starter = fx.starting.factoryId
    private val second = fx.otherFactories().first()
    private var nextId = 0L
    private var nextPlot = 0

    private fun member(factoryId: String?, role: StaffRole = StaffRole.SALARIE, rarity: StaffRarity = StaffRarity.COMMUN): StaffRecord {
        nextId += 1
        return StaffRecord(nextId, factoryId, "Membre $nextId", "ouvrier-casque", role, rarity)
    }

    private fun hire(company: CompanyRecord, vararg members: StaffRecord) {
        fx.store.staff.getOrPut(company.id) { mutableListOf() } += members
    }

    /** Pose un bâtiment sur la prochaine parcelle libre de la zone 1 : sa place n'intéresse pas les chiffres. */
    private fun build(company: CompanyRecord, type: String, level: Int) {
        cityStore.seedBuilding(company.id, type, level, zoneIndex = 1, x = nextPlot * 16, z = 0)
        nextPlot += 1
    }

    private fun bonus(type: String, level: Int): Double = city.building(type)!!.level(level).bonus!!

    private fun plainFigures(company: CompanyRecord): CompanyFigures = fx.figuresOf(company.id)

    private fun cityFigures(company: CompanyRecord): CompanyFigures =
        withCity.forCompany(fx.connection, fx.store.loadCompany(fx.connection, company.id), fx.store.loadFactories(fx.connection, company.id))

    private fun pending(figures: EngineFigures, company: CompanyRecord, regime: Regime) =
        figures.pendingProduction(fx.connection, fx.store.loadCompany(fx.connection, company.id), fx.store.loadFactories(fx.connection, company.id), regime)

    /** Une entreprise avec la Carrière de départ et une seconde usine active, au niveau 30. */
    private fun twoFactories(): CompanyRecord {
        val company = fx.newCompany()
        fx.putFactory(company.id, FactoryRecord(second, FactoryState.ACTIVE, 0, 30, null))
        return company
    }

    // --- sans bonus : rien ne change

    @Test
    @DisplayName("Une entreprise sans bâtiment, ou avec seulement la mairie, des routes et des maisons sans personnel : les chiffres d'avant, bit pour bit")
    fun noBonusBuildingChangesNothing() {
        val company = twoFactories()
        val plain = plainFigures(company)

        for (step in 0..3) {
            when (step) {
                1 -> build(company, "mairie", 1)
                2 -> repeat(3) { build(company, "route", 1) }
                3 -> build(company, "maison", 1)
            }
            val figures = cityFigures(company)
            assertEquals(plain.multiplier, figures.multiplier, "étape $step : multiplicateur")
            assertEquals(plain.ratePerHour, figures.ratePerHour, "étape $step : revenu")
            for (factory in listOf(starter, second)) {
                assertEquals(plain.forFactory(factory).ratePerHour, figures.forFactory(factory).ratePerHour, "étape $step : $factory")
                assertEquals(plain.forFactory(factory).nominalRatePerHour, figures.forFactory(factory).nominalRatePerHour, "étape $step : nominal $factory")
            }
        }

        fx.clock.advance(hours(7.5))
        for (regime in Regime.entries) {
            val before = pending(fx.figures, company, regime)
            val after = pending(withCity, company, regime)
            assertEquals(before.earned, after.earned, "régime $regime : la production versée")
            assertEquals(before.countedHours, after.countedHours, "régime $regime : les heures comptées")
            assertEquals(before.at, after.at, "régime $regime : l'instant")
        }
    }

    @Test
    @DisplayName("Du personnel mais aucun logement (la mairie et une route seulement) : salaires et revenu d'avant, bit pour bit")
    fun noHousingPlaceChangesNothing() {
        val company = twoFactories()
        hire(company, member(starter, StaffRole.CHEF, StaffRarity.RARE), member(second))
        build(company, "mairie", 1)
        build(company, "route", 1)
        val plain = plainFigures(company)

        val figures = cityFigures(company)
        assertEquals(plain.ratePerHour, figures.ratePerHour)
        for ((before, after) in plain.staff.zip(figures.staff)) {
            assertEquals(before.salaryPercent, after.salaryPercent)
            assertEquals(before.salaryPerHour, after.salaryPerHour)
            assertEquals(false, after.housed, "pas de logement : personne n'est logé")
        }
    }

    @Test
    @DisplayName("Sans source de ville, la réponse d'avant : ni city ni housed ; avec une source, la ville est là")
    fun cityIsAbsentWithoutASource() {
        val company = twoFactories()
        hire(company, member(starter))

        val plain = plainFigures(company)
        assertNull(plain.city)
        assertNull(plain.staff.single().housed)

        val figures = cityFigures(company)
        assertNotNull(figures.city)
        assertEquals(1, figures.city!!.mairieLevel)
        assertEquals(false, figures.staff.single().housed)
    }

    // --- le bonus de production

    @Test
    @DisplayName("Un commerce multiplie le multiplicateur global : le revenu de l'entreprise et de chaque usine suit, la somme des usines redonne le total")
    fun donarBonusMultipliesTheGlobalMultiplier() {
        val company = twoFactories()
        val plain = plainFigures(company)
        build(company, "commerce", 2)
        val pct = bonus("commerce", 2)
        assertTrue(pct > 0.0)

        val figures = cityFigures(company)
        assertEquals(engine.withCityBonus(plain.multiplier, pct), figures.multiplier)
        val factor = 1.0 + pct / 100.0
        assertGolden(plain.ratePerHour * factor, figures.ratePerHour, "revenu de l'entreprise")
        for (factory in listOf(starter, second)) {
            assertGolden(plain.forFactory(factory).ratePerHour * factor, figures.forFactory(factory).ratePerHour, factory)
        }
        assertGolden(figures.forFactory(starter).ratePerHour + figures.forFactory(second).ratePerHour, figures.ratePerHour, "la somme des usines")
        assertEquals(pct, figures.city!!.bonuses.donarProductionPercent)
    }

    @Test
    @DisplayName("Les commerces s'additionnent jusqu'au plafond de ville.json, pas au-delà")
    fun donarBonusIsCapped() {
        val company = twoFactories()
        val plain = plainFigures(company)
        val cap = city.cap(com.silooo.core.definitions.BuildingBonusType.PRODUCTION_DONAR)
        repeat(50) { build(company, "commerce", city.building("commerce")!!.maxLevel()) }

        val figures = cityFigures(company)
        assertEquals(cap, figures.city!!.bonuses.donarProductionPercent)
        assertEquals(engine.withCityBonus(plain.multiplier, cap), figures.multiplier)
    }

    @Test
    @DisplayName("En ligne comme hors ligne, la production versée porte le bonus de la ville")
    fun donarBonusIsPaid() {
        val company = twoFactories()
        build(company, "commerce", 1)
        val pct = bonus("commerce", 1)
        fx.clock.advance(hours(4.0))

        val factor = 1.0 + pct / 100.0
        for (regime in Regime.entries) {
            val plain = pending(fx.figures, company, regime)
            val boosted = pending(withCity, company, regime)
            assertGolden(plain.earned * factor, boosted.earned, "régime $regime")
            assertEquals(plain.countedHours, boosted.countedHours, "régime $regime : les heures ne changent pas")
        }
    }

    // --- le logement

    @Test
    @DisplayName("Les logements hébergent les premiers membres par identifiant ; un membre sans usine prend une place comme un autre")
    fun housingTakesTheFirstMembersById() {
        val company = twoFactories()
        build(company, "maison", 1)
        build(company, "maison", 1)
        val places = Math.floor(bonus("maison", 1) * 2).toInt()
        assertTrue(places >= 1)
        val members = buildList {
            add(member(factoryId = null, role = StaffRole.SALARIE))
            repeat(places + 1) { add(member(starter, StaffRole.SALARIE, StaffRarity.COMMUN)) }
        }
        hire(company, *members.toTypedArray())

        val figures = cityFigures(company)
        assertEquals(places, figures.city!!.bonuses.housingPlaces)
        assertEquals(places, figures.city.bonuses.housedStaff)
        val byId = figures.staff.associateBy { it.staffId }
        for ((index, record) in members.withIndex()) {
            assertEquals(index < places, byId.getValue(record.id).housed, "membre ${record.id}")
        }
        assertFalse(byId.getValue(members.last().id).housed!!, "le dernier embauché n'a pas de place")
    }

    @Test
    @DisplayName("Un membre logé coûte moins de salaire : le pourcent, le coût par heure et le revenu net de l'usine le disent")
    fun housedMemberCostsLess() {
        val company = twoFactories()
        val worker = member(starter, StaffRole.CHEF, StaffRarity.EPIQUE)
        hire(company, worker)
        val plain = plainFigures(company)
        build(company, "maison", 1)

        val figures = cityFigures(company)
        val view = figures.staff.single()
        assertEquals(true, view.housed)
        assertEquals(engine.staffSalaryPercent(StaffRole.CHEF, StaffRarity.EPIQUE, true), view.salaryPercent)
        assertTrue(view.salaryPercent!! < plain.staff.single().salaryPercent!!)
        assertEquals(engine.staffSalaryPercent(StaffRole.CHEF, StaffRarity.EPIQUE) * (1.0 - city.housing.salaryDiscountPercent / 100.0), view.salaryPercent)
        val level = fx.store.loadFactories(fx.connection, company.id).single { it.factoryId == starter }.level
        assertEquals(engine.salaryPerHour(starter, level, StaffRole.CHEF, StaffRarity.EPIQUE, figures.multiplier, housed = true), view.salaryPerHour)
        assertTrue(view.salaryPerHour!! < plain.staff.single().salaryPerHour!!)

        val netHoused = engine.crewNetPercent(listOf(CrewMember(StaffRole.CHEF, StaffRarity.EPIQUE, housed = true)))
        val netPlain = engine.crewNetPercent(listOf(CrewMember(StaffRole.CHEF, StaffRarity.EPIQUE)))
        assertTrue(netHoused > netPlain)
        assertGolden(plain.forFactory(starter).ratePerHour / (1.0 + netPlain / 100.0) * (1.0 + netHoused / 100.0), figures.forFactory(starter).ratePerHour, "usine de l'équipe")
        assertEquals(plain.forFactory(second).ratePerHour, figures.forFactory(second).ratePerHour, "l'autre usine n'a pas d'équipe")
        assertTrue(figures.ratePerHour > plain.ratePerHour)
    }

    @Test
    @DisplayName("Le logement ne touche que les salaires : il ne change ni le multiplicateur, ni les Lingots de personne")
    fun housingGivesNothingElse() {
        val company = twoFactories()
        hire(company, member(starter))
        val plain = plainFigures(company)
        val lingotsBefore = fx.store.balances.toMap()
        repeat(3) { build(company, "immeuble", 1) }

        val figures = cityFigures(company)
        assertEquals(plain.multiplier, figures.multiplier)
        assertEquals(0.0, figures.city!!.bonuses.donarProductionPercent)
        assertEquals(lingotsBefore, fx.store.balances.toMap())
    }

    @Test
    @DisplayName("Un membre dans une usine en ruine, même logé, ne coûte rien et ne change pas le revenu")
    fun housedMemberInARuinedFactoryCostsNothing() {
        val company = fx.newCompany()
        fx.putFactory(company.id, FactoryRecord(second, FactoryState.EN_RUINE, 0, 0, null))
        hire(company, member(second, StaffRole.CHEF, StaffRarity.EPIQUE))
        build(company, "maison", 1)
        val plain = plainFigures(company)

        val figures = cityFigures(company)
        assertEquals(plain.ratePerHour, figures.ratePerHour)
        assertEquals(0.0, figures.staff.single().salaryPerHour)
        assertEquals(true, figures.staff.single().housed)
    }
}
