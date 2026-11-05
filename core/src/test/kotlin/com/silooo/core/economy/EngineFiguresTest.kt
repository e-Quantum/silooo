package com.silooo.core.economy

import com.silooo.core.GoldenFiles
import com.silooo.core.api.Balances
import com.silooo.core.api.FactoryState
import com.silooo.core.api.Role
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.double
import com.silooo.core.economy.EngineFixture.Companion.KR
import com.silooo.core.economy.EngineFixture.Companion.OTHER
import com.silooo.core.economy.EngineFixture.Companion.STRANGER
import com.silooo.core.int
import com.silooo.core.objects
import com.silooo.core.session.FactoryFigures
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.Membership
import com.silooo.core.session.SessionService
import com.silooo.core.string
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les chiffres de `join` que [EngineFigures] tire du [EconomyEngine] : revenu, multiplicateur, seuil, prix et palier
 * de chaque usine. Aucun chiffre du jeu n'est écrit ici : tout vient de `definitions/` et du moteur, dont on
 * vérifie seulement que le service recopie les sorties, avec les bonnes entrées.
 */
class EngineFiguresTest {
    private val fx = EngineFixture()
    private val engine = fx.engine
    private val definitions = fx.definitions
    private val starting = fx.starting
    private val prestige = definitions.economy.prestige

    @Test
    @DisplayName("Un join neuf : la Carrière au niveau 1, le revenu et le seuil du moteur, le prix et le palier suivants")
    fun freshJoin() {
        val service = SessionService(PassThroughDatabase(), fx.store, fx.figures, fx.bootstrap)

        val response = runBlocking { service.join(KR, "Kr") }
        val state = response.state

        assertTrue(response.firstJoin)
        assertEquals(0.0, response.offlineCollected, "rien à encaisser à la création")
        val factory = state.factories.single()
        assertEquals(starting.factoryId, factory.factoryId)
        assertEquals(FactoryState.ACTIVE, factory.state)
        assertEquals(0, factory.restoreStep)
        assertEquals(starting.level, factory.level)

        val noMultiplier = engine.globalMultiplier(0.0, 0)
        assertEquals(noMultiplier, state.multiplier)
        assertEquals(engine.ratePerHour(engine.startingLevels(), noMultiplier), state.ratePerHour)
        assertTrue(state.ratePerHour > 0.0)
        assertEquals(engine.prestigeThreshold(1), state.threshold)
        assertEquals(prestige.thresholds.first().donar, state.threshold)

        assertEquals(state.ratePerHour, factory.ratePerHour, "une seule usine produit : son revenu est celui de l'entreprise")
        assertEquals(engine.nextLevelCost(starting.factoryId, starting.level), factory.nextCost)
        assertEquals(engine.nextMilestone(starting.level), factory.nextMilestone)
        assertTrue(factory.nextMilestone > factory.level)
        assertEquals(engine.doublings(factory.level) + 1, engine.doublings(factory.nextMilestone), "le palier suivant double la production")
        assertFalse(factory.stoppedNoPower)
    }

    @Test
    @DisplayName("Le multiplicateur suit la moyenne des Lingots des membres, pas ceux d'un autre joueur, puis les étoiles")
    fun multiplierFollowsLingotsAndStars() {
        val company = fx.newCompany(ownerLingots = 4)
        fx.addMember(company.id, OTHER, lingots = 8)
        fx.store.balances[STRANGER] = Balances(1_000_000, 0, 0, 0)
        val average = 6.0

        val plain = fx.figuresOf(company.id)
        assertGolden(1.0 + prestige.bonusPerHeldLingot * average, plain.multiplier, "multiplicateur des Lingots")
        assertEquals(engine.globalMultiplier(average, 0), plain.multiplier)
        assertTrue(plain.multiplier > engine.globalMultiplier(0.0, 0))
        assertEquals(engine.ratePerHour(engine.startingLevels(), plain.multiplier), plain.ratePerHour)

        fx.update(fx.store.loadCompany(fx.connection, company.id).copy(stars = 2))
        val starred = fx.figuresOf(company.id)
        assertEquals(engine.globalMultiplier(average, 2), starred.multiplier)
        assertGolden(plain.multiplier * Math.pow(prestige.productionPerStar, 2.0), starred.multiplier, "multiplicateur avec deux étoiles")
        assertGolden(plain.ratePerHour * Math.pow(prestige.productionPerStar, 2.0), starred.ratePerHour, "revenu avec deux étoiles")
    }

    @Test
    @DisplayName("Un membre sans ligne de soldes compte pour zéro Lingot dans la moyenne")
    fun memberWithoutBalancesCountsForZero() {
        val company = fx.newCompany(ownerLingots = 10)
        fx.store.members[OTHER] = Membership(company.id, Role.ASSOCIE)

        assertEquals(engine.globalMultiplier(5.0, 0), fx.figuresOf(company.id).multiplier)
    }

    @Test
    @DisplayName("Le premier achat du tableur : multiplicateur 1,05 et, avec le hors ligne du joueur type, revenu 6552")
    fun firstPurchaseOfTheSheet() {
        val first = GoldenFiles.read("simulation").objects("purchases").first()
        val settings = PlayerSettings.fromGolden()
        val factoryName = definitions.factories.factories.first { it.id == starting.factoryId }.name
        assertEquals(factoryName, first.string("factory"), "la première ligne du tableur achète la Carrière")
        assertEquals(starting.level, first.int("levelBefore"))

        // Le joueur type garde une part des Lingots de la mission de bienvenue : en jeu, des Lingots entiers.
        val kept = settings.keptShare * definitions.economy.lingotsOutsidePrestige.welcomeMission
        assertEquals(Math.rint(kept), kept, "le joueur type garde un nombre entier de Lingots")
        val figures = fx.figuresOf(fx.newCompany(ownerLingots = kept.toLong()).id)

        assertGolden(first.double("globalMultiplier"), figures.multiplier, "multiplicateur du premier achat")
        val withOffline = figures.ratePerHour * engine.dailyOfflineMultiplier(settings.playHoursPerDay)
        assertGolden(first.double("incomeBefore"), withOffline, "revenu avant le premier achat")
    }

    @Test
    @DisplayName("Le seuil : celui de la liste tant qu'il y en a un, puis le dernier fois le facteur, toujours fini")
    fun thresholdBeyondTheListedCourses() {
        val listed = prestige.thresholds
        val company = fx.newCompany()

        fun thresholdAt(course: Int): Double {
            fx.update(fx.store.loadCompany(fx.connection, company.id).copy(courseNumber = course))
            return fx.figuresOf(company.id).threshold
        }

        assertEquals(listed.first().donar, thresholdAt(1))
        assertEquals(listed.last().donar, thresholdAt(listed.size))
        val last = listed.last().donar
        assertGolden(last * prestige.nextThresholdFactor, thresholdAt(listed.size + 1), "seuil de la course suivante")
        assertGolden(last * Math.pow(prestige.nextThresholdFactor, 3.0), thresholdAt(listed.size + 3), "seuil trois courses plus loin")
        assertTrue(thresholdAt(listed.size + 1) > thresholdAt(listed.size))
        for (course in listOf(listed.size + 1, listed.size + 3, 1000, 100_000)) {
            val threshold = thresholdAt(course)
            assertEquals(engine.prestigeThreshold(course), threshold, "seuil de la course $course")
            assertTrue(threshold.isFinite(), "le seuil de la course $course reste un nombre")
        }
    }

    @Test
    @DisplayName("Seules les usines ACTIVE produisent ; les autres gardent leur prix et leur palier ; la somme des usines est le revenu")
    fun onlyActiveFactoriesProduce() {
        val others = fx.otherFactories()
        assertTrue(others.size >= 3, "usines.json doit compter trois autres usines pour ce test")
        val (active, ruined, restoring) = others
        val company = fx.newCompany(ownerLingots = 3)
        fx.update(fx.store.loadCompany(fx.connection, company.id).copy(stars = 1))
        fx.putFactory(company.id, FactoryRecord(active, FactoryState.ACTIVE, 0, 7, null))
        fx.putFactory(company.id, FactoryRecord(ruined, FactoryState.EN_RUINE, 0, 9, null))
        fx.putFactory(company.id, FactoryRecord(restoring, FactoryState.REMISE_EN_ETAT, 2, 4, null))

        val figures = fx.figuresOf(company.id)
        val levels = mapOf(starting.factoryId to starting.level, active to 7, ruined to 9, restoring to 4)

        val producing = mapOf(starting.factoryId to starting.level, active to 7)
        assertEquals(engine.ratePerHour(producing, figures.multiplier), figures.ratePerHour)
        for ((id, level) in producing) {
            assertGolden(figures.multiplier * engine.factoryRate(id, level), figures.forFactory(id).ratePerHour, "revenu de $id")
        }
        assertEquals(0.0, figures.forFactory(ruined).ratePerHour, "une usine en ruine ne produit pas")
        assertEquals(0.0, figures.forFactory(restoring).ratePerHour, "une usine en remise en état ne produit pas")
        assertGolden(figures.ratePerHour, levels.keys.sumOf { figures.forFactory(it).ratePerHour }, "somme des usines")

        for ((id, level) in levels) {
            assertEquals(engine.nextLevelCost(id, level), figures.forFactory(id).nextCost, "prix suivant de $id")
            assertEquals(engine.nextMilestone(level), figures.forFactory(id).nextMilestone, "palier suivant de $id")
            assertFalse(figures.forFactory(id).stoppedNoPower, "aucune usine ne s'arrête faute de courant pour l'instant ($id)")
        }
    }

    @Test
    @DisplayName("Une usine de la base qui n'est plus dans usines.json ne fait tomber ni le revenu ni join")
    fun unknownFactoryIsIgnored() {
        val ghost = "usine-disparue"
        assertFalse(ghost in engine.factoryIds())
        val company = fx.newCompany()
        val before = fx.figuresOf(company.id)

        fx.putFactory(company.id, FactoryRecord(ghost, FactoryState.ACTIVE, 0, 50, null))
        val after = fx.figuresOf(company.id)

        assertEquals(before.ratePerHour, after.ratePerHour)
        assertEquals(before.multiplier, after.multiplier)
        assertEquals(FactoryFigures(0.0, 0.0, 0), after.forFactory(ghost))
        assertEquals(before.forFactory(starting.factoryId), after.forFactory(starting.factoryId))
    }
}
