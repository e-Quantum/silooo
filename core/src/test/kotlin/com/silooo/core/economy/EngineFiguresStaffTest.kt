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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le personnel dans les chiffres que [EngineFigures] rend : le revenu net (bonus moins salaires), l'état de chaque membre, et la
 * production versée en ligne comme hors ligne, avec le plafond relevé d'un chef. Les valeurs attendues sortent du moteur et de
 * `definitions/` ; sans personnel, tout reste ce que les autres tests figent.
 */
class EngineFiguresStaffTest {
    private val fx = EngineFixture()
    private val engine = fx.engine
    private val offline = fx.definitions.economy.offline
    private val starter = fx.starting.factoryId
    private val second = fx.otherFactories().first()
    private var nextId = 0L

    private fun member(factoryId: String?, role: StaffRole = StaffRole.SALARIE, rarity: StaffRarity = StaffRarity.COMMUN): StaffRecord {
        nextId += 1
        return StaffRecord(nextId, factoryId, "Membre $nextId", "ouvrier-casque", role, rarity)
    }

    private fun hire(company: CompanyRecord, vararg members: StaffRecord) {
        fx.store.staff.getOrPut(company.id) { mutableListOf() } += members
    }

    private fun net(vararg members: StaffRecord): Double =
        engine.crewNetPercent(members.map { CrewMember(it.role, it.rarity) })

    private fun pending(company: CompanyRecord, regime: Regime) =
        fx.figures.pendingProduction(fx.connection, company, fx.store.loadFactories(fx.connection, company.id), regime)

    /** Une entreprise avec la Carrière de départ et une seconde usine active au niveau [level]. */
    private fun twoFactories(level: Int = 30): CompanyRecord {
        val company = fx.newCompany()
        fx.putFactory(company.id, FactoryRecord(second, FactoryState.ACTIVE, 0, level, null))
        return company
    }

    @Test
    @DisplayName("Sans personnel, ou avec un membre sans usine : le revenu de l'entreprise est celui d'avant, bit pour bit")
    fun noStaffNoChange() {
        val company = twoFactories()
        val before = fx.figuresOf(company.id)

        hire(company, member(factoryId = null, rarity = StaffRarity.EPIQUE))
        val after = fx.figuresOf(company.id)

        assertEquals(before.ratePerHour, after.ratePerHour)
        assertEquals(before.forFactory(starter).ratePerHour, after.forFactory(starter).ratePerHour)
        assertEquals(before.forFactory(second).ratePerHour, after.forFactory(second).ratePerHour)
        val view = after.staff.single()
        assertEquals(0.0, view.salaryPerHour, "sans usine, pas de salaire")
        assertEquals(null, view.factoryId)
    }

    @Test
    @DisplayName("Un membre dans une usine active : le revenu de l'usine et de l'entreprise suivent le net, l'autre usine ne bouge pas")
    fun netRateOfTheFactory() {
        val company = twoFactories()
        val plain = fx.figuresOf(company.id)
        val worker = member(starter, rarity = StaffRarity.RARE)
        hire(company, worker)
        val staffed = fx.figuresOf(company.id)
        val factor = 1.0 + net(worker) / 100.0

        assertGolden(plain.forFactory(starter).ratePerHour * factor, staffed.forFactory(starter).ratePerHour, "usine de l'équipe")
        assertEquals(plain.forFactory(second).ratePerHour, staffed.forFactory(second).ratePerHour, "l'autre usine n'a pas d'équipe")
        assertGolden(
            plain.ratePerHour + plain.forFactory(starter).ratePerHour * net(worker) / 100.0,
            staffed.ratePerHour,
            "revenu de l'entreprise",
        )
        assertGolden(staffed.forFactory(starter).ratePerHour + staffed.forFactory(second).ratePerHour, staffed.ratePerHour, "la somme des usines redonne le revenu")
        assertTrue(staffed.ratePerHour > plain.ratePerHour)
    }

    @Test
    @DisplayName("Le membre rendu porte ses pourcents et ce que son salaire coûte par heure maintenant, calculés par le moteur")
    fun staffView() {
        val company = twoFactories()
        val chef = member(second, StaffRole.CHEF, StaffRarity.EPIQUE)
        hire(company, chef)
        val figures = fx.figuresOf(company.id)
        val view = figures.staff.single()

        assertEquals(chef.id, view.staffId)
        assertEquals(chef.name, view.name)
        assertEquals(chef.skinId, view.skinId)
        assertEquals(StaffRole.CHEF, view.role)
        assertEquals(StaffRarity.EPIQUE, view.rarity)
        assertEquals(second, view.factoryId)
        assertEquals(engine.staffBonusPercent(StaffRarity.EPIQUE), view.bonusPercent)
        assertEquals(engine.staffSalaryPercent(StaffRole.CHEF, StaffRarity.EPIQUE), view.salaryPercent)
        val level = fx.store.loadFactories(fx.connection, company.id).single { it.factoryId == second }.level
        assertEquals(engine.salaryPerHour(second, level, StaffRole.CHEF, StaffRarity.EPIQUE, figures.multiplier), view.salaryPerHour)
        assertEquals(null, view.slot, "un membre embauché n'a pas de place de liste")
        assertEquals(null, view.hireCost, "ni de prix d'embauche")
    }

    @Test
    @DisplayName("Un membre dans une usine en ruine ne rapporte rien et ne coûte rien")
    fun staffInAStoppedFactoryDoesNothing() {
        val company = fx.newCompany()
        fx.putFactory(company.id, FactoryRecord(second, FactoryState.EN_RUINE, 0, 0, null))
        val plain = fx.figuresOf(company.id)

        hire(company, member(second, StaffRole.CHEF, StaffRarity.EPIQUE))
        val staffed = fx.figuresOf(company.id)

        assertEquals(plain.ratePerHour, staffed.ratePerHour)
        assertEquals(0.0, staffed.staff.single().salaryPerHour)
        fx.clock.advance(hours(5.0))
        for (regime in Regime.entries) {
            val withStaff = pending(company, regime)
            fx.store.staff.clear()
            val without = pending(company, regime)
            hire(company, member(second, StaffRole.CHEF, StaffRarity.EPIQUE))
            assertEquals(without.earned, withStaff.earned, "régime $regime : une usine en ruine ne profite pas du chef")
            assertEquals(without.countedHours, withStaff.countedHours, "régime $regime : ni de son plafond")
        }
    }

    @Test
    @DisplayName("En ligne, la production versée est le revenu net plein : le bonus en plus, le salaire en moins, heure après heure")
    fun onlineProductionIsNet() {
        val company = fx.newCompany()
        fx.clock.advance(hours(3.0))
        val plain = pending(company, Regime.ONLINE)
        val worker = member(starter, rarity = StaffRarity.RARE)
        hire(company, worker)
        val staffed = pending(company, Regime.ONLINE)

        val rate = fx.figuresOf(company.id).ratePerHour
        assertEquals(engine.onlineEarnings(rate, 3.0), staffed.earned)
        assertGolden(plain.earned * (1.0 + net(worker) / 100.0), staffed.earned, "production en ligne avec un salarié")
        assertEquals(plain.countedHours, staffed.countedHours)

        // Le salaire est bien une sortie d'argent : bonus brut moins salaire, chacun au revenu de l'usine sans équipe.
        val bonus = plain.earned * engine.staffBonusPercent(worker.rarity) / 100.0
        val salary = plain.earned * engine.staffSalaryPercent(worker.role, worker.rarity) / 100.0
        assertGolden(plain.earned + bonus - salary, staffed.earned, "bonus moins salaire")
        assertTrue(salary > 0.0)
    }

    @Test
    @DisplayName("Hors ligne, le salaire se paie aussi : le revenu net passe par le plafond et l'efficacité, comme le revenu sans équipe")
    fun offlineProductionIsNet() {
        val company = fx.newCompany()
        val away = offline.capHours / 2
        fx.clock.advance(hours(away))
        val plain = pending(company, Regime.OFFLINE)
        val worker = member(starter, rarity = StaffRarity.EPIQUE)
        hire(company, worker)
        val staffed = pending(company, Regime.OFFLINE)

        val rate = fx.figuresOf(company.id).ratePerHour
        assertEquals(engine.offlineEarnings(rate, away, company.offlineCapHours), staffed.earned)
        assertGolden(plain.earned * (1.0 + net(worker) / 100.0), staffed.earned, "production hors ligne avec un salarié")
        val salary = plain.earned * engine.staffSalaryPercent(worker.role, worker.rarity) / 100.0
        val bonus = plain.earned * engine.staffBonusPercent(worker.rarity) / 100.0
        assertGolden(plain.earned + bonus - salary, staffed.earned, "bonus moins salaire hors ligne")
        assertEquals(plain.countedHours, staffed.countedHours, "sans chef, le plafond est celui de l'entreprise")
    }

    @Test
    @DisplayName("Un chef d'équipe relève le plafond hors ligne de son usine seulement : l'autre usine reste au plafond de l'entreprise")
    fun leaderRaisesTheCapOfItsFactory() {
        val company = twoFactories()
        val chef = member(starter, StaffRole.CHEF)
        hire(company, chef)
        val cap = company.offlineCapHours
        val away = cap + engine.leaderCapHours(cap) // bien au-delà des deux plafonds
        fx.clock.advance(hours(away))

        val production = pending(company, Regime.OFFLINE)
        val factories = fx.store.loadFactories(fx.connection, company.id)
        val levels = factories.associate { it.factoryId to it.level }
        val multiplier = fx.figuresOf(company.id).multiplier
        val crews = mapOf(starter to listOf(CrewMember(chef.role, chef.rarity)))

        val expected = engine.offlineEarningsWithLeaders(
            othersRatePerHour = engine.ratePerHour(levels.filterKeys { it == second }, multiplier, crews),
            ledRatePerHour = engine.ratePerHour(levels.filterKeys { it == starter }, multiplier, crews),
            awayHours = away,
            capHours = cap,
        )
        assertEquals(expected, production.earned)
        assertEquals(engine.offlineHours(away, engine.leaderCapHours(cap)), production.countedHours, "les heures comptées sont celles du plafond relevé")
        assertTrue(production.countedHours > cap)

        val withoutChef = fx.store.staff.let { it.clear(); pending(company, Regime.OFFLINE) }
        assertTrue(production.earned > withoutChef.earned, "le chef fait gagner les heures du relèvement")
        assertEquals(engine.offlineHours(away, cap), withoutChef.countedHours)
    }

    @Test
    @DisplayName("Un chef n'y change rien tant que l'absence tient dans le plafond de l'entreprise : le net seulement")
    fun leaderChangesNothingForAShortAbsence() {
        val company = fx.newCompany()
        val away = company.offlineCapHours / 2
        fx.clock.advance(hours(away))
        val plain = pending(company, Regime.OFFLINE)

        val chef = member(starter, StaffRole.CHEF, StaffRarity.RARE)
        hire(company, chef)
        val led = pending(company, Regime.OFFLINE)

        assertGolden(plain.earned * (1.0 + net(chef) / 100.0), led.earned, "absence courte avec un chef")
        assertEquals(plain.countedHours, led.countedHours)
    }

    @Test
    @DisplayName("Le revenu hors ligne sans chef reste exactement la formule de toujours, même avec des salariés")
    fun withoutLeaderTheFormulaIsTheOldOne() {
        val company = twoFactories()
        hire(company, member(starter, rarity = StaffRarity.RARE), member(second, rarity = StaffRarity.COMMUN))
        fx.clock.advance(hours(company.offlineCapHours + 7))

        val production = pending(company, Regime.OFFLINE)
        val rate = fx.figuresOf(company.id).ratePerHour

        assertEquals(engine.offlineEarnings(rate, company.offlineCapHours + 7, company.offlineCapHours), production.earned)
        assertEquals(engine.offlineHours(company.offlineCapHours + 7, company.offlineCapHours), production.countedHours)
    }
}
