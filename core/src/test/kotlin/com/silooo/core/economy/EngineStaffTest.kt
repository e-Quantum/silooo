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
 * Les formules du personnel du [EconomyEngine] : bonus, salaires, net d'une équipe, prix d'embauche, postes et plafond
 * hors ligne d'un chef. Tous les chiffres sortent de `definitions/personnel.json` ; seuls les calculs sont écrits ici, et ce que
 * rend une économie sans personne doit rester le même, bit pour bit.
 */
class EngineStaffTest {
    private val definitions = TestDefinitions.loaded
    private val personnel = definitions.personnel
    private val engine = EconomyEngine(definitions)
    private val factoryId = definitions.economy.prestige.courseStart.factoryId
    private val level = 40

    private fun crew(vararg members: Pair<StaffRole, StaffRarity>): List<CrewMember> = members.map { CrewMember(it.first, it.second) }

    @Test
    @DisplayName("Le bonus d'une rareté et le salaire d'un rôle sont ceux de personnel.json, le salaire fois le facteur du rôle")
    fun bonusAndSalaryFollowTheDefinitions() {
        for (rarity in StaffRarity.entries) {
            assertEquals(personnel.rarity(rarity).productionBonusPercent, engine.staffBonusPercent(rarity))
            for (role in StaffRole.entries) {
                assertEquals(personnel.rarity(rarity).salaryPercent * personnel.role(role).salaryFactor, engine.staffSalaryPercent(role, rarity))
            }
        }
    }

    @Test
    @DisplayName("Un membre rapporte toujours plus qu'il ne coûte, et plus la rareté monte plus le bonus monte")
    fun everyoneEarnsMoreThanTheyCost() {
        for (role in StaffRole.entries) for (rarity in StaffRarity.entries) {
            assertTrue(engine.staffBonusPercent(rarity) > engine.staffSalaryPercent(role, rarity), "$role $rarity coûte plus qu'il ne rapporte")
        }
        val bonuses = StaffRarity.entries.map { engine.staffBonusPercent(it) }
        assertEquals(bonuses.sorted(), bonuses, "les raretés se suivent du plus petit bonus au plus grand")
    }

    @Test
    @DisplayName("Sans personne, le revenu est celui d'avant, bit pour bit : liste vide, absente ou équipe vide d'une usine")
    fun noStaffChangesNothing() {
        val levels = engine.startingLevels() + (engine.factoryIds().last() to 12)
        val plain = engine.baseRate(levels)
        assertEquals(plain, engine.baseRate(levels, emptyMap()))
        assertEquals(plain, engine.baseRate(levels, levels.keys.associateWith { emptyList() }))
        assertEquals(engine.factoryRate(factoryId, level), engine.factoryRate(factoryId, level, emptyList()))
        val multiplier = engine.globalMultiplier(3.0, 2)
        assertEquals(engine.ratePerHour(levels, multiplier), engine.ratePerHour(levels, multiplier, emptyMap()))
    }

    @Test
    @DisplayName("Une équipe change la production de son usine du net de ses membres, en pourcent de la production sans équipe")
    fun crewChangesTheFactoryByItsNet() {
        val team = crew(StaffRole.SALARIE to StaffRarity.RARE)
        val net = engine.staffBonusPercent(StaffRarity.RARE) - engine.staffSalaryPercent(StaffRole.SALARIE, StaffRarity.RARE)
        val alone = engine.factoryRate(factoryId, level)

        assertEquals(net, engine.crewNetPercent(team))
        assertGolden(alone * (1.0 + net / 100.0), engine.factoryRate(factoryId, level, team), "production avec un salarié rare")
        assertGolden(alone * (1.0 + net / 100.0), engine.baseRate(mapOf(factoryId to level), mapOf(factoryId to team)), "base avec un salarié rare")
        assertTrue(engine.factoryRate(factoryId, level, team) > alone)
    }

    @Test
    @DisplayName("Deux membres rapportent la somme de ce que chacun rapporterait seul : les bonus s'additionnent, ils ne se multiplient pas")
    fun bonusesAddUp() {
        val first = StaffRole.SALARIE to StaffRarity.COMMUN
        val second = StaffRole.CHEF to StaffRarity.EPIQUE
        val both = engine.crewNetPercent(crew(first, second))

        assertGolden(engine.crewNetPercent(crew(first)) + engine.crewNetPercent(crew(second)), both, "net des deux")
        val alone = engine.factoryRate(factoryId, level)
        assertGolden(alone * (1.0 + both / 100.0), engine.factoryRate(factoryId, level, crew(first, second)), "production avec les deux")
    }

    @Test
    @DisplayName("Le revenu net est le multiplicateur fois la base avec équipes ; une usine sans équipe garde sa production")
    fun ratePerHourIsNet() {
        val levels = mapOf(factoryId to level, engine.factoryIds().last() to 7)
        val team = mapOf(factoryId to crew(StaffRole.SALARIE to StaffRarity.EPIQUE))
        val multiplier = engine.globalMultiplier(2.0, 1)

        assertEquals(multiplier * engine.baseRate(levels, team), engine.ratePerHour(levels, multiplier, team))
        val withoutTeam = engine.baseRate(levels)
        val expected = withoutTeam + engine.factoryRate(factoryId, level) * engine.crewNetPercent(team.getValue(factoryId)) / 100.0
        assertGolden(expected, engine.baseRate(levels, team), "seule l'usine de l'équipe change")
    }

    @Test
    @DisplayName("Le salaire par heure est le pourcent de salaire de la production de l'usine sans équipe, au multiplicateur du moment")
    fun salaryPerHour() {
        val multiplier = 1.5
        val expected = multiplier * engine.factoryRate(factoryId, level) * engine.staffSalaryPercent(StaffRole.CHEF, StaffRarity.RARE) / 100.0
        assertGolden(expected, engine.salaryPerHour(factoryId, level, StaffRole.CHEF, StaffRarity.RARE, multiplier), "salaire d'un chef rare")
        assertTrue(engine.salaryPerHour(factoryId, level, StaffRole.CHEF, StaffRarity.RARE, multiplier) > 0.0)
        assertEquals(0.0, engine.salaryPerHour(factoryId, 0, StaffRole.CHEF, StaffRarity.RARE, multiplier), "une usine à zéro ne paie personne")
    }

    @Test
    @DisplayName("Le prix d'embauche est des heures de revenu fois le facteur du rôle, jamais un chiffre fixe, jamais négatif")
    fun hireCost() {
        val revenue = 1234.5
        for (role in StaffRole.entries) for (rarity in StaffRarity.entries) {
            val hours = personnel.rarity(rarity).hireCostHours * personnel.role(role).costFactor
            assertEquals(revenue * hours, engine.hireCost(role, rarity, revenue))
            assertEquals(0.0, engine.hireCost(role, rarity, 0.0), "sans revenu, l'embauche ne coûte rien")
            assertEquals(2 * engine.hireCost(role, rarity, revenue), engine.hireCost(role, rarity, 2 * revenue), "le prix suit le revenu")
        }
        assertThrows(IllegalArgumentException::class.java) { engine.hireCost(StaffRole.SALARIE, StaffRarity.COMMUN, -1.0) }
        val costs = StaffRarity.entries.map { engine.hireCost(StaffRole.SALARIE, it, revenue) }
        assertEquals(costs.sorted(), costs, "plus la rareté monte, plus l'embauche coûte")
    }

    @Test
    @DisplayName("Postes : un salarié de base plus un tous les N niveaux, un chef par usine, quel que soit le niveau")
    fun posts() {
        val posts = personnel.posts
        assertEquals(posts.baseEmployees, engine.staffPosts(StaffRole.SALARIE, 0))
        assertEquals(posts.baseEmployees, engine.staffPosts(StaffRole.SALARIE, posts.levelsPerPost - 1))
        assertEquals(posts.baseEmployees + 1, engine.staffPosts(StaffRole.SALARIE, posts.levelsPerPost))
        assertEquals(posts.baseEmployees + 3, engine.staffPosts(StaffRole.SALARIE, 3 * posts.levelsPerPost + 1))
        for (factoryLevel in listOf(0, 1, 100, 1000)) assertEquals(posts.leadersPerFactory, engine.staffPosts(StaffRole.CHEF, factoryLevel))
        assertThrows(IllegalArgumentException::class.java) { engine.staffPosts(StaffRole.SALARIE, -1) }
    }

    @Test
    @DisplayName("Le plafond hors ligne d'un chef est celui de l'entreprise plus les heures de personnel.json")
    fun leaderCap() {
        val cap = definitions.economy.offline.capHours
        assertEquals(cap + personnel.teamLeader.offlineCapBonusHours, engine.leaderCapHours(cap))
        assertTrue(engine.leaderCapHours(cap) > cap)
    }

    @Test
    @DisplayName("Hors ligne avec chef : sans usine à chef, la formule de toujours ; avec, l'usine du chef compte jusqu'au plafond relevé")
    fun offlineWithLeaders() {
        val cap = definitions.economy.offline.capHours
        val others = 100.0
        val led = 60.0
        val away = cap + personnel.teamLeader.offlineCapBonusHours / 2

        assertEquals(engine.offlineEarnings(others, away, cap), engine.offlineEarningsWithLeaders(others, 0.0, away, cap), "aucune usine à chef : la formule d'avant, bit pour bit")
        val expected = engine.offlineEarnings(others, away, cap) + engine.offlineEarnings(led, away, engine.leaderCapHours(cap))
        assertEquals(expected, engine.offlineEarningsWithLeaders(others, led, away, cap))
        assertTrue(engine.offlineEarningsWithLeaders(others, led, away, cap) > engine.offlineEarnings(others + led, away, cap), "le chef fait gagner les heures du relèvement")

        val short = cap / 2
        assertGolden(engine.offlineEarnings(others + led, short, cap), engine.offlineEarningsWithLeaders(others, led, short, cap), "absence courte : le chef ne change rien")
    }
}
