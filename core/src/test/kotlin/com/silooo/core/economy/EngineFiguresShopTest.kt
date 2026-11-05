package com.silooo.core.economy

import com.silooo.core.api.FactoryState
import com.silooo.core.economy.EngineFixture.Companion.hours
import com.silooo.core.session.CompanyFigures
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.Regime
import com.silooo.core.shop.InMemoryShopStore
import com.silooo.core.shop.ShopFigures
import com.silooo.core.shop.ShopRules
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La boutique dans les chiffres que [EngineFigures] rend : le bonus de Donar du plus haut grade à vie du PDG et de son Pilier, sur le
 * multiplicateur global de l'entreprise, et la règle qui tient tout ensemble : sans grade ni Pilier, ou sans source de boutique, rien ne
 * change, bit pour bit. Les valeurs attendues sortent du moteur et de `definitions/boutique.json`, jamais d'un littéral.
 */
class EngineFiguresShopTest {
    private val fx = EngineFixture()
    private val engine = fx.engine
    private val rules = ShopRules(fx.definitions)
    private val shopStore = InMemoryShopStore(fx.store)
    private val withShop = EngineFigures(engine, fx.store, fx.clock, shop = ShopFigures(rules, shopStore, fx.clock))
    private val pilier = rules.subscriptions.single { it.id == ShopRules.PILIER_ID }
    private val second = fx.otherFactories().first()
    private val kr = EngineFixture.KR
    private val other = EngineFixture.OTHER

    /** Une entreprise de [EngineFixture.KR] (le PDG) avec la Carrière de départ et une seconde usine active, au niveau 30. */
    private fun twoFactories(): CompanyRecord {
        val company = fx.newCompany()
        fx.putFactory(company.id, FactoryRecord(second, FactoryState.ACTIVE, 0, 30, null))
        return company
    }

    private fun plainFigures(company: CompanyRecord): CompanyFigures = fx.figuresOf(company.id)

    private fun shopFigures(company: CompanyRecord): CompanyFigures =
        withShop.forCompany(fx.connection, fx.store.loadCompany(fx.connection, company.id), fx.store.loadFactories(fx.connection, company.id))

    private fun pending(figures: EngineFigures, company: CompanyRecord, regime: Regime) =
        figures.pendingProduction(fx.connection, fx.store.loadCompany(fx.connection, company.id), fx.store.loadFactories(fx.connection, company.id), regime)

    private fun gradePercent(count: Int): Int = rules.grades[count - 1].donarBonusPercent

    private fun factor(percent: Int): Double = 1.0 + percent / 100.0

    private fun ranks(count: Int) = rules.grades.take(count).forEach { shopStore.seedRank(kr, it.id) }

    // --- sans bonus : rien ne change

    @Test
    @DisplayName("Sans grade ni Pilier, avec une source de boutique : les chiffres d'avant, bit pour bit, revenu, usines et production versée")
    fun noGradeNoPilierChangesNothing() {
        val company = twoFactories()
        val plain = plainFigures(company)

        val figures = shopFigures(company)
        assertEquals(plain.multiplier, figures.multiplier)
        assertEquals(plain.ratePerHour, figures.ratePerHour)
        for (factory in listOf(fx.starting.factoryId, second)) {
            assertEquals(plain.forFactory(factory).ratePerHour, figures.forFactory(factory).ratePerHour, factory)
            assertEquals(plain.forFactory(factory).nominalRatePerHour, figures.forFactory(factory).nominalRatePerHour, factory)
        }

        fx.clock.advance(hours(7.5))
        for (regime in Regime.entries) {
            val before = pending(fx.figures, company, regime)
            val after = pending(withShop, company, regime)
            assertEquals(before.earned, after.earned, "régime $regime : la production versée")
            assertEquals(before.countedHours, after.countedHours, "régime $regime : les heures comptées")
            assertEquals(before.at, after.at, "régime $regime : l'instant")
        }
    }

    @Test
    @DisplayName("Le moteur : zéro et zéro rendent le multiplicateur lui-même ; un bonus négatif est refusé")
    fun engineWithoutBonus() {
        for (multiplier in listOf(1.0, 3.7, 123_456.789)) {
            assertEquals(multiplier, engine.withShopBonuses(multiplier, 0, 0))
        }
        assertThrows(IllegalArgumentException::class.java) { engine.withShopBonuses(2.0, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { engine.withShopBonuses(2.0, 0, -1) }
    }

    // --- le grade

    @Test
    @DisplayName("Le grade du PDG multiplie le multiplicateur global : le revenu de l'entreprise et de chaque usine suit, la somme des usines redonne le total")
    fun gradeMultipliesTheGlobalMultiplier() {
        val company = twoFactories()
        val plain = plainFigures(company)
        ranks(1)
        val pct = gradePercent(1)
        assertTrue(pct > 0)

        val figures = shopFigures(company)
        assertEquals(engine.withShopBonuses(plain.multiplier, pct, 0), figures.multiplier)
        assertGolden(plain.multiplier * factor(pct), figures.multiplier, "multiplicateur")
        assertGolden(plain.ratePerHour * factor(pct), figures.ratePerHour, "revenu de l'entreprise")
        for (factory in listOf(fx.starting.factoryId, second)) {
            assertGolden(plain.forFactory(factory).ratePerHour * factor(pct), figures.forFactory(factory).ratePerHour, factory)
        }
        assertGolden(
            figures.forFactory(fx.starting.factoryId).ratePerHour + figures.forFactory(second).ratePerHour,
            figures.ratePerHour,
            "la somme des usines",
        )
    }

    @Test
    @DisplayName("Seul le plus haut grade compte : à chaque marche le multiplicateur est celui de ce grade, jamais la somme des grades")
    fun onlyTheHighestGradeCounts() {
        val company = twoFactories()
        val plain = plainFigures(company)

        for (count in 1..rules.grades.size) {
            shopStore.seedRank(kr, rules.grades[count - 1].id)
            val figures = shopFigures(company)
            assertEquals(engine.withShopBonuses(plain.multiplier, gradePercent(count), 0), figures.multiplier, "avec $count grade(s)")
        }
        val every = rules.grades.sumOf { it.donarBonusPercent }
        assertTrue(shopFigures(company).multiplier < plain.multiplier * factor(every), "pas la somme des bonus")
        assertTrue(shopFigures(company).multiplier > plain.multiplier)
    }

    // --- le Pilier

    @Test
    @DisplayName("Le Pilier compte tant qu'il court : actif, le multiplicateur monte ; à sa fin, il retombe aux chiffres d'avant")
    fun pilierCountsWhileRunning() {
        val company = twoFactories()
        val plain = plainFigures(company)
        val bonus = pilier.donarBonusPercent
        assertTrue(bonus > 0)
        shopStore.seedSubscription(kr, pilier.id, fx.clock.instant(), fx.clock.instant().plus(Duration.ofDays(pilier.days.toLong())))

        assertEquals(engine.withShopBonuses(plain.multiplier, 0, bonus), shopFigures(company).multiplier)

        fx.clock.advance(Duration.ofDays(pilier.days.toLong()))
        val after = plainFigures(company)
        assertEquals(after.multiplier, shopFigures(company).multiplier, "abonnement fini : rien de plus")
    }

    @Test
    @DisplayName("Le Perso ne change aucun chiffre, même actif")
    fun persoChangesNothing() {
        val company = twoFactories()
        val plain = plainFigures(company)
        val perso = rules.subscriptions.single { it.requiredGradeId != null }
        shopStore.seedSubscription(kr, perso.id, fx.clock.instant(), fx.clock.instant().plus(Duration.ofDays(perso.days.toLong())))

        assertEquals(plain.multiplier, shopFigures(company).multiplier)
    }

    @Test
    @DisplayName("Grade et Pilier se combinent comme le bonus de la ville : le multiplicateur est multiplié par chacun")
    fun gradeAndPilierCombine() {
        val company = twoFactories()
        val plain = plainFigures(company)
        ranks(rules.grades.size)
        shopStore.seedSubscription(kr, pilier.id, fx.clock.instant(), fx.clock.instant().plus(Duration.ofDays(pilier.days.toLong())))
        val grade = gradePercent(rules.grades.size)

        val figures = shopFigures(company)

        assertEquals(engine.withShopBonuses(plain.multiplier, grade, pilier.donarBonusPercent), figures.multiplier)
        assertGolden(plain.multiplier * factor(grade) * factor(pilier.donarBonusPercent), figures.multiplier, "multiplicateur")
    }

    // --- le PDG

    @Test
    @DisplayName("C'est le grade du PDG qui compte : celui d'un associé ne change rien, celui du PDG profite à toute l'entreprise")
    fun theCeoIsTheOneThatCounts() {
        val company = twoFactories()
        fx.addMember(company.id, other, lingots = 0)
        val plain = plainFigures(company)

        // Un associé au grade le plus haut, un PDG sans rien : aucun bonus.
        rules.grades.forEach { shopStore.seedRank(other, it.id) }
        assertEquals(plain.multiplier, shopFigures(company).multiplier)

        // Le PDG achète à son tour le premier grade : seul le sien compte, pas celui de l'associé.
        shopStore.seedRank(kr, rules.grades[0].id)
        assertEquals(engine.withShopBonuses(plain.multiplier, gradePercent(1), 0), shopFigures(company).multiplier)
        assertNotEquals(engine.withShopBonuses(plain.multiplier, gradePercent(rules.grades.size), 0), shopFigures(company).multiplier)
    }

    @Test
    @DisplayName("Un autre PDG, une autre entreprise : le grade de Kr ne touche pas l'entreprise d'un autre")
    fun anotherCompanyIsUnaffected() {
        val krs = twoFactories()
        val others = fx.newCompany(owner = other)
        ranks(rules.grades.size)

        assertEquals(plainFigures(others).multiplier, shopFigures(others).multiplier)
        assertNotEquals(plainFigures(krs).multiplier, shopFigures(krs).multiplier)
    }

    // --- la production versée

    @Test
    @DisplayName("En ligne comme hors ligne, la production versée porte le bonus de grade et de Pilier")
    fun bonusIsPaid() {
        val company = twoFactories()
        ranks(1)
        shopStore.seedSubscription(kr, pilier.id, fx.clock.instant(), fx.clock.instant().plus(Duration.ofDays(pilier.days.toLong())))
        fx.clock.advance(hours(4.0))

        val expected = factor(gradePercent(1)) * factor(pilier.donarBonusPercent)
        for (regime in Regime.entries) {
            val plain = pending(fx.figures, company, regime)
            val boosted = pending(withShop, company, regime)
            assertGolden(plain.earned * expected, boosted.earned, "régime $regime")
            assertEquals(plain.countedHours, boosted.countedHours, "régime $regime : les heures ne changent pas")
        }
    }
}
