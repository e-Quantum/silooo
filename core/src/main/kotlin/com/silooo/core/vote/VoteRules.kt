package com.silooo.core.vote

import com.silooo.core.definitions.VoteDefinitions
import java.time.LocalDate

/**
 * Les règles des votes, sans base ni horloge : des fonctions pures sur des jours UTC. Les chiffres (jours de la série, Lingots,
 * objectif de la Vote party) viennent de [VoteDefinitions], jamais d'ici. Toutes ces règles sont **à valider par Kr**.
 */
object VoteRules {
    /**
     * La série qui finit à [through] : le nombre de jours de suite, en remontant depuis [through] inclus, où le joueur a voté
     * ([votedDays]). 0 si le joueur n'a pas voté ce jour-là.
     */
    fun streakEndingOn(votedDays: Set<LocalDate>, through: LocalDate): Int {
        var length = 0
        var day = through
        while (day in votedDays) {
            length++
            day = day.minusDays(1)
        }
        return length
    }

    /**
     * La série du joueur à la date [today], celle que `todayVotes` montre : si le joueur a voté aujourd'hui, la série qui finit
     * aujourd'hui ; sinon celle qui finissait hier, encore en vie jusqu'à la fin de la journée ; sinon 0.
     */
    fun currentStreak(votedDays: Set<LocalDate>, today: LocalDate): Int =
        if (today in votedDays) streakEndingOn(votedDays, today) else streakEndingOn(votedDays, today.minusDays(1))

    /**
     * Vrai si une série de [streak] jours donne le bonus au vote du jour : [streak] atteint `serie.jours` ou l'un de ses multiples
     * (7, 14, 21 jours avec 7). Le bonus ne revient que sur le premier vote du jour du joueur (voir [VoteService]).
     */
    fun streakBonusDue(streak: Int, definitions: VoteDefinitions): Boolean =
        streak >= definitions.streak.days && streak % definitions.streak.days == 0

    /** Les Lingots d'un vote : la récompense de base, plus le bonus de série quand il est dû. */
    fun lingotsFor(bonusDue: Boolean, definitions: VoteDefinitions): Long =
        definitions.reward.lingotsPerVote + if (bonusDue) definitions.streak.bonusLingots else 0L

    /**
     * Vrai si ce vote est celui qui déclenche la Vote party : son rang dans la journée ([votesToday], lui compris) est exactement
     * l'objectif `voteParty.votesParJour`. Les votes suivants du jour comptent mais ne la redéclenchent pas : un seul vote a ce rang.
     */
    fun startsParty(votesToday: Int, definitions: VoteDefinitions): Boolean = votesToday == definitions.party.votesPerDay

    /** Vrai si la Vote party a eu lieu aujourd'hui : l'objectif est atteint. */
    fun partyReached(votesToday: Int, definitions: VoteDefinitions): Boolean = votesToday >= definitions.party.votesPerDay
}
