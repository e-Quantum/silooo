package com.silooo.core.simulation

import com.silooo.core.definitions.Definitions
import com.silooo.core.economy.EconomyEngine

/*
 * Le simulateur : il fait jouer le joueur type du tableur, de la première course à la dernière, et note quand
 * chaque chose arrive. Il ne connaît aucune formule du jeu : prix, production, paliers ×2, découverte, remise en état,
 * seuils, Lingots, époques et étoiles viennent tous d'EconomyEngine et de definitions/. Ne sont à lui que les règles
 * du joueur type (PlayerProfile) et la façon dont il choisit ses achats (voir GameSimulator).
 *
 * C'est du code de test : le service ne s'en sert pas, et l'image Docker ne l'embarque pas. Mode d'emploi, stratégie
 * et lecture d'un échec : docs/economie.md, section « Le simulateur ».
 */

/** Un achat du joueur type : une usine montée jusqu'à un palier, ou rachetée et remise en état. */
data class Purchase(
    /** Le rang de l'achat depuis le début de la partie, à partir de 1 (colonne A de la feuille Simulation). */
    val rank: Int,
    val course: Int,
    val factoryId: String,
    val fromLevel: Int,
    val toLevel: Int,
    /** Vrai pour le premier achat d'une usine dans la course : rachat, puis les quatre étapes de remise en état. */
    val buyout: Boolean,
    val price: Double,
    val incomeBefore: Double,
    val incomeAfter: Double,
    val waitHours: Double,
    /** L'heure de jeu dans la course, et depuis le tout début. */
    val courseHour: Double,
    val cumulativeHour: Double,
    /** Les Donar gagnés dans la course, attente de cet achat comprise. */
    val donarEarned: Double,
)

/** Une usine révélée sur la carte. Une usine révélée le reste d'une course à l'autre. */
data class Discovery(val course: Int, val cumulativeHour: Double, val factoryId: String)

/** Une course, du départ au prestige. */
data class CourseResult(
    val course: Int,
    val era: Int,
    val stars: Int,
    val threshold: Double,
    val startHour: Double,
    val duration: Double,
    val endHour: Double,
    /** Les Lingots cumulés au départ, la part gardée pour le multiplicateur, et le multiplicateur de la course. */
    val lingotsBefore: Double,
    val lingotsKept: Double,
    val multiplier: Double,
    val lingotsAtPrestige: Long,
    val incomeAtEnd: Double,
    val purchases: List<Purchase>,
    val discoveries: List<Discovery>,
    /** Le niveau de chaque usine remise en état à la fin de la course. */
    val levelsAtEnd: Map<String, Int>,
)

/** Une zone de la ville payée en Lingots, à l'heure de jeu où le budget de ville la couvre. */
data class CityZonePurchase(val zone: Int, val priceLingots: Long, val course: Int, val cumulativeHour: Double)

/** Une partie rejouée : les courses dans l'ordre, et les zones de ville payées pendant ces courses. */
class SimulationRun(val courses: List<CourseResult>, val zones: List<CityZonePurchase>) {
    val purchases: List<Purchase> = courses.flatMap { it.purchases }

    fun courseOrNull(number: Int): CourseResult? = courses.getOrNull(number - 1)

    fun course(number: Int): CourseResult =
        courseOrNull(number) ?: error("La partie rejouée n'a pas de course $number (elle en a ${courses.size})")

    /**
     * L'heure de jeu cumulée à laquelle `factoryId` atteint `level` dans la course `course`, ou `null` si la course
     * s'arrête avant (ou si la course n'est pas jouée). Un rachat qui amène l'usine plus haut que `level` compte.
     */
    fun hourFactoryReaches(course: Int, factoryId: String, level: Int): Double? =
        courseOrNull(course)?.purchases?.firstOrNull { it.factoryId == factoryId && it.toLevel >= level }?.cumulativeHour
}

/**
 * Fait jouer le joueur type, une course après l'autre, en régime en ligne.
 *
 * **Le temps** est celui du tableur : des heures de jeu. Le joueur est connecté, donc ce qu'il gagne se paie au revenu
 * plein ([EconomyEngine.onlineEarnings]). Les heures où il est absent le reste de sa journée ne sont pas jouées une par
 * une : elles entrent dans le revenu par heure de jeu, par le multiplicateur de joueur type
 * ([EconomyEngine.dailyOfflineMultiplier], cellule B8 du tableur). C'est ce qui rend les temps du simulateur
 * comparables aux cibles, qui sont des heures de jeu.
 *
 * **La course** commence avec les usines de [EconomyEngine.startingLevels] ; les autres sont en ruine. Le multiplicateur
 * ([EconomyEngine.globalMultiplier]) est figé au départ, comme dans le tableur : il compte la part gardée des Lingots
 * cumulés (bienvenue, 1 par heure de jeu, prestiges) et les étoiles de la course. Elle finit par un prestige au
 * moment exact où les Donar gagnés atteignent le seuil ([EconomyEngine.canPrestige]).
 *
 * **La stratégie d'achat** est celle du tableur. Il ne la fixe pas par une règle mais par une liste d'achats
 * (colonnes C et D de la feuille Simulation) que son auteur décrit ainsi : « un joueur qui achète toujours ce qui rapporte
 * le plus vite ». La règle ci-dessous, retrouvée en la confrontant aux 965 achats de la feuille, les redonne tous, dans
 * le même ordre :
 *  1. Le joueur ne vise que les paliers de [PlayerProfile.ladder] : pour chaque usine, le prochain palier au-dessus de
 *     son niveau. Une usine en ruine n'a qu'un achat : le rachat, au prix du niveau où la remise en état la laisse
 *     ([EconomyEngine.restoredLevel]).
 *  2. Il choisit l'achat qui minimise le temps qu'il lui faut pour avoir le prix **plus** le temps que l'achat met à se
 *     rembourser : `prix / revenu + prix / gain de revenu`. À égalité parfaite, l'usine qui vient la première dans
 *     usines.json.
 *  3. Il attend d'avoir exactement le prix, achète, et recommence. Il ne garde aucun Donar : son solde est nul avant
 *     chaque achat.
 *  4. Dès que les Donar gagnés atteignent le seuil, il fait son prestige, avant d'avoir fini d'attendre le prochain
 *     achat. Il prestige donc toujours au seuil, jamais au-dessus.
 *
 * **Découverte et remise en état.** Une usine n'est achetable qu'une fois révélée ([EconomyEngine.isDiscovered], clé
 * `decouverte`) ; le rachat de la première fois paie le niveau de départ de la remise en état, puis les quatre étapes
 * se font d'un coup, sans prix ni durée (`definitions/` n'en chiffre aucun, docs/economie.md, « La remise en état »).
 * Au prestige, les usines repassent en ruine mais restent révélées, comme dans le service.
 *
 * **La ville.** Les zones se paient avec la part des Lingots que le joueur ne garde pas, dans l'ordre, dès que le budget
 * les couvre ([CityZonePurchase]).
 */
class GameSimulator(
    private val definitions: Definitions,
    private val profile: PlayerProfile,
    private val engine: EconomyEngine = EconomyEngine(definitions),
) {
    private val lingotRules = definitions.economy.lingotsOutsidePrestige
    private val offlineBoost: Double = engine.dailyOfflineMultiplier(profile.playHoursPerDay)

    /** Rejoue les `courses` premières courses. Une même entrée donne toujours la même partie. */
    fun run(courses: Int): SimulationRun {
        require(courses >= 1) { "Une partie compte au moins une course : $courses" }
        val results = ArrayList<CourseResult>(courses)
        val discovered = LinkedHashSet<String>()
        var startHour = 0.0
        var previousPrestigeLingots = 0.0
        var rank = 0
        for (course in 1..courses) {
            val result = playCourse(course, startHour, previousPrestigeLingots, discovered, rank)
            results += result
            rank += result.purchases.size
            startHour += result.duration
            previousPrestigeLingots += result.lingotsAtPrestige.toDouble()
        }
        return SimulationRun(results, cityZones(results))
    }

    /** Un achat que le joueur envisage : l'usine, le niveau où il l'amène, ce que ça coûte et ce que ça rapporte. */
    private class Offer(
        val factoryId: String,
        val fromLevel: Int,
        val toLevel: Int,
        val buyout: Boolean,
        val price: Double,
        val levelsAfter: Map<String, Int>,
        val incomeAfter: Double,
    )

    private fun playCourse(
        course: Int,
        startHour: Double,
        previousPrestigeLingots: Double,
        discovered: MutableSet<String>,
        ranked: Int,
    ): CourseResult {
        val era = engine.eraAt(course)
        val stars = engine.starsAt(course)
        val threshold = engine.prestigeThreshold(course)
        val lingotsBefore = lingotRules.welcomeMission + lingotRules.averagePerPlayHour * startHour + previousPrestigeLingots
        val kept = profile.keptLingotsShare * lingotsBefore
        val multiplier = engine.globalMultiplier(kept, stars)
        val scale = multiplier * offlineBoost

        var levels: Map<String, Int> = engine.startingLevels()
        var income = engine.ratePerHour(levels, scale)
        var earned = 0.0
        var hour = 0.0
        val purchases = ArrayList<Purchase>()
        val discoveries = ArrayList<Discovery>()

        fun reveal() {
            val companyLevel = engine.companyLevel(levels)
            for (id in engine.factoryIds()) {
                if (id !in discovered && engine.isDiscovered(id, companyLevel, era, levels.keys)) {
                    discovered += id
                    discoveries += Discovery(course, startHour + hour, id)
                }
            }
        }
        reveal()

        while (true) {
            val offer = bestOffer(course, hour, levels, discovered, income, scale)
            // Le solde est nul avant chaque achat : le joueur attend d'avoir exactement le prix.
            val wait = offer.price / income
            val earnedAfter = earned + engine.onlineEarnings(income, wait)
            if (engine.canPrestige(course, earnedAfter)) break

            earned = earnedAfter
            val incomeBefore = income
            hour += wait
            levels = offer.levelsAfter
            income = offer.incomeAfter
            purchases += Purchase(
                rank = ranked + purchases.size + 1,
                course = course,
                factoryId = offer.factoryId,
                fromLevel = offer.fromLevel,
                toLevel = offer.toLevel,
                buyout = offer.buyout,
                price = offer.price,
                incomeBefore = incomeBefore,
                incomeAfter = income,
                waitHours = wait,
                courseHour = hour,
                cumulativeHour = startHour + hour,
                donarEarned = earned,
            )
            reveal()
        }

        // Le prestige au seuil : le temps qu'il reste à gagner, au revenu d'après le dernier achat.
        val duration = hour + (threshold - earned) / income
        return CourseResult(
            course = course,
            era = era,
            stars = stars,
            threshold = threshold,
            startHour = startHour,
            duration = duration,
            endHour = startHour + duration,
            lingotsBefore = lingotsBefore,
            lingotsKept = kept,
            multiplier = multiplier,
            lingotsAtPrestige = engine.prestigeLingots(course, threshold),
            incomeAtEnd = income,
            purchases = purchases,
            discoveries = discoveries,
            levelsAtEnd = levels,
        )
    }

    /** L'achat qui minimise `prix / revenu + prix / gain de revenu` parmi ceux que le joueur peut envisager. */
    private fun bestOffer(
        course: Int,
        hour: Double,
        levels: Map<String, Int>,
        discovered: Set<String>,
        income: Double,
        scale: Double,
    ): Offer {
        var best: Offer? = null
        var bestScore = Double.POSITIVE_INFINITY
        for (id in engine.factoryIds()) {
            if (id !in discovered) continue
            val offer = offerFor(id, levels, scale) ?: continue
            val score = offer.price / income + offer.price / (offer.incomeAfter - income)
            if (score < bestScore) {
                best = offer
                bestScore = score
            }
        }
        return best ?: throw IllegalStateException(
            "Course $course, heure ${"%.3f".format(hour)} de la course : le joueur type n'a plus de palier à acheter avant le seuil. " +
                "Son échelle (${profile.ladder}) est épuisée ; ajoute des paliers à la feuille Simulation du tableur.",
        )
    }

    private fun offerFor(factoryId: String, levels: Map<String, Int>, scale: Double): Offer? {
        val level = levels[factoryId]
        val buyout = level == null
        val fromLevel = level ?: 0
        val toLevel = if (buyout) engine.restoredLevel(0) else profile.ladder.firstOrNull { it > fromLevel } ?: return null
        val levelsAfter = levels + (factoryId to toLevel)
        return Offer(
            factoryId = factoryId,
            fromLevel = fromLevel,
            toLevel = toLevel,
            buyout = buyout,
            price = engine.levelsCost(factoryId, fromLevel, toLevel - fromLevel),
            levelsAfter = levelsAfter,
            incomeAfter = engine.ratePerHour(levelsAfter, scale),
        )
    }

    /**
     * Les zones de la ville que le budget de ville couvre pendant les courses jouées. Le budget est la part non gardée
     * des Lingots cumulés : il croît de `moyenneParHeureDeJeu` Lingots par heure de jeu dans une course et saute des
     * Lingots du prestige à la fin (feuille Ville). Une zone ne s'achète pas avant la mission de bienvenue.
     */
    private fun cityZones(courses: List<CourseResult>): List<CityZonePurchase> {
        val spendable = 1.0 - profile.keptLingotsShare
        val prestigeUpTo = courses.runningFold(0.0) { sum, course -> sum + course.lingotsAtPrestige.toDouble() }
        val zones = ArrayList<CityZonePurchase>()
        var cumulativePrice = 0L
        for ((index, price) in definitions.economy.city.zonePricesLingots.withIndex()) {
            cumulativePrice += price
            val cumulative = cumulativePrice.toDouble()
            // La première course dont le budget, juste après son prestige, couvre le prix cumulé des zones.
            val course = courses.firstOrNull { c ->
                spendable * (lingotRules.welcomeMission + lingotRules.averagePerPlayHour * c.endHour + prestigeUpTo[c.course]) >= cumulative
            } ?: break
            val prestigeBefore = prestigeUpTo[course.course - 1]
            val linear = if (lingotRules.averagePerPlayHour > 0.0) {
                (cumulative / spendable - lingotRules.welcomeMission - prestigeBefore) / lingotRules.averagePerPlayHour
            } else {
                Double.POSITIVE_INFINITY
            }
            val hour = minOf(course.endHour, maxOf(course.startHour, linear))
            zones += CityZonePurchase(index + 1, price, course.course, maxOf(profile.welcomeMissionHour, hour))
        }
        return zones
    }
}
