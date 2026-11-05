package com.silooo.core.vote

import com.silooo.core.TestDefinitions
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Les règles des votes sur des jours, sans base ni horloge. Les chiffres viennent de `definitions/vote.json`. */
class VoteRulesTest {
    private val vote = TestDefinitions.loaded.vote
    private val today = LocalDate.of(2026, 11, 5)

    private fun days(vararg back: Long): Set<LocalDate> = back.map { today.minusDays(it) }.toSet()

    @Test
    @DisplayName("La série qui finit un jour compte les jours de vote de suite, en remontant, et s'arrête au premier jour sans vote")
    fun streakCountsConsecutiveDays() {
        assertEquals(0, VoteRules.streakEndingOn(emptySet(), today))
        assertEquals(1, VoteRules.streakEndingOn(days(0), today))
        assertEquals(3, VoteRules.streakEndingOn(days(0, 1, 2), today))
        assertEquals(2, VoteRules.streakEndingOn(days(0, 1, 3, 4, 5), today), "un jour sans vote coupe la série")
        assertEquals(0, VoteRules.streakEndingOn(days(1, 2), today), "pas de vote ce jour-là : la série qui finit ce jour-là est vide")
        assertEquals(2, VoteRules.streakEndingOn(days(1, 2, 3), today.minusDays(2)), "on peut demander la série d'un jour passé")
    }

    @Test
    @DisplayName("La série affichée reste en vie tant que le joueur a voté hier et pas encore aujourd'hui")
    fun currentStreakStaysAliveUntilTheDayEnds() {
        assertEquals(0, VoteRules.currentStreak(emptySet(), today))
        assertEquals(2, VoteRules.currentStreak(days(0, 1), today))
        assertEquals(2, VoteRules.currentStreak(days(1, 2), today), "hier mais pas aujourd'hui : la série de hier vit encore")
        assertEquals(0, VoteRules.currentStreak(days(2, 3), today), "avant-hier seulement : la série est perdue")
    }

    @Test
    @DisplayName("Le bonus de série est dû à serie.jours jours de suite, puis à chaque multiple, et pas avant")
    fun bonusIsDueOnEveryMultiple() {
        val goal = vote.streak.days
        for (streak in 0 until goal) assertFalse(VoteRules.streakBonusDue(streak, vote), "série de $streak jours")
        assertTrue(VoteRules.streakBonusDue(goal, vote))
        assertFalse(VoteRules.streakBonusDue(goal + 1, vote))
        assertTrue(VoteRules.streakBonusDue(2 * goal, vote))
        assertTrue(VoteRules.streakBonusDue(3 * goal, vote))
    }

    @Test
    @DisplayName("Les Lingots d'un vote sont la récompense de base, plus le bonus quand il est dû")
    fun lingotsAddTheBonusWhenDue() {
        assertEquals(vote.reward.lingotsPerVote, VoteRules.lingotsFor(false, vote))
        assertEquals(vote.reward.lingotsPerVote + vote.streak.bonusLingots, VoteRules.lingotsFor(true, vote))
    }

    @Test
    @DisplayName("La Vote party part au vote dont le rang est exactement l'objectif, jamais avant ni après")
    fun partyStartsOnTheGoalRankOnly() {
        val goal = vote.party.votesPerDay
        assertFalse(VoteRules.startsParty(goal - 1, vote))
        assertTrue(VoteRules.startsParty(goal, vote))
        assertFalse(VoteRules.startsParty(goal + 1, vote))
        assertFalse(VoteRules.partyReached(goal - 1, vote))
        assertTrue(VoteRules.partyReached(goal, vote))
        assertTrue(VoteRules.partyReached(goal + 5, vote))
    }
}
