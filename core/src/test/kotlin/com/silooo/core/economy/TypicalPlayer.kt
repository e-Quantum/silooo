package com.silooo.core.economy

import com.silooo.core.GoldenFiles
import com.silooo.core.TestDefinitions
import com.silooo.core.array
import com.silooo.core.definitions.Definitions
import com.silooo.core.double
import com.silooo.core.int
import com.silooo.core.obj
import com.silooo.core.objects
import com.silooo.core.string
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * Le joueur type du tableur d'équilibrage, rejoué avec le moteur.
 *
 * Ce fichier est du code de test : il fait ce que fait la feuille Simulation (acheter dans l'ordre
 * saisi, attendre d'avoir le prix, prestiger au seuil) et appelle le moteur pour chaque règle du jeu
 * (prix, production, multiplicateurs, seuil, Lingots). Les seuls chiffres qu'il lit hors de
 * definitions/ sont les trois hypothèses du joueur type (heures de jeu par jour, part des Lingots
 * gardés, heure de la mission de bienvenue), prises dans parametres.json : ce ne sont pas des règles du jeu.
 * Les formules et l'ordre des opérations suivent les colonnes de la feuille, pour que l'écart restant
 * soit celui de l'arithmétique et pas celui d'un autre calcul.
 */

/** Les trois hypothèses du joueur type du tableur (Paramètres!B7, B10, B14), lues dans parametres.json. */
data class PlayerSettings(val playHoursPerDay: Double, val keptShare: Double, val welcomeMissionHour: Double) {
    companion object {
        fun fromGolden(): PlayerSettings {
            val settings = GoldenFiles.read("parametres").obj("settings")
            return PlayerSettings(
                playHoursPerDay = settings.obj("playHoursPerDay").double("value"),
                keptShare = settings.obj("lingotsKeptShare").double("value"),
                welcomeMissionHour = settings.obj("welcomeMissionHour").double("value"),
            )
        }
    }
}

/** Un achat saisi dans la feuille Simulation (colonnes A à D) : le test le lit, il ne le décide pas. */
data class PlannedPurchase(val n: Int, val course: Int, val factoryName: String, val targetLevel: Int)

/** Une ligne de la feuille Simulation, telle que le moteur la recalcule. */
data class ReplayRow(
    val n: Int,
    val course: Int,
    val factoryName: String,
    val targetLevel: Int,
    val newCourse: Boolean,
    val courseMultiplier: Double,
    val levelBefore: Int,
    val price: Double,
    val incomeBefore: Double,
    val balanceBefore: Double,
    val waitHours: Double,
    val balanceAfter: Double,
    val donarEarned: Double,
    val courseHour: Double,
    val cumulativeHour: Double,
    val beforePrestige: Boolean,
    val incomeAfter: Double,
    val courseStartHour: Double,
    val durationIfPrestigeFollows: Double,
    /** Niveau de chaque usine après l'achat, par nom, usines à zéro omises. */
    val levelsAfter: Map<String, Int>,
)

/** Une ligne de la feuille Courses, telle que le moteur la recalcule. */
data class ReplayCourse(
    val course: Int,
    val era: Int,
    val stars: Int,
    val threshold: Double,
    val startHour: Double,
    val lingotsEarnedBefore: Double,
    val lingotsKept: Double,
    val courseMultiplier: Double,
    val purchasesMade: Int,
    val duration: Double,
    val endHour: Double,
    val conversionReference: Double,
    val lingotsAtPrestigeExact: Double,
    val lingotsAtPrestige: Long,
    val incomeAtEnd: Double,
)

class ReplayResult(val rows: List<ReplayRow>, val courses: List<ReplayCourse>)

/** Une zone de la feuille Ville. */
data class ReplayZone(
    val zone: Int,
    val priceLingots: Long,
    val cumulativePriceLingots: Long,
    val affordableInCourse: Int,
    val affordableAtHour: Double,
    val realDays: Double,
)

object TypicalPlayer {
    val definitions: Definitions get() = TestDefinitions.loaded
    val engine: EconomyEngine by lazy { EconomyEngine(definitions) }
    val settings: PlayerSettings by lazy { PlayerSettings.fromGolden() }

    /** Les 965 achats saisis dans simulation.json, dans l'ordre. */
    val plan: List<PlannedPurchase> by lazy {
        GoldenFiles.read("simulation").objects("purchases").map {
            PlannedPurchase(it.int("n"), it.int("course"), it.string("factory"), it.int("targetLevel"))
        }
    }

    /** Le joueur type rejoué de bout en bout, une fois pour tous les tests. */
    val replay: ReplayResult by lazy { play(plan) }

    private val idByName: Map<String, String> by lazy { definitions.factories.factories.associate { it.name to it.id } }
    private val nameById: Map<String, String> by lazy { definitions.factories.factories.associate { it.id to it.name } }

    fun factoryId(name: String): String = idByName[name] ?: error("Usine inconnue de usines.json : $name")

    /**
     * Rejoue `plan` course par course. Une course se termine quand les Donar gagnés atteignent son seuil ;
     * la dernière ligne de chaque course du plan est l'achat que le joueur n'a pas le temps de faire.
     */
    fun play(plan: List<PlannedPurchase>): ReplayResult {
        val lingotRules = definitions.economy.lingotsOutsidePrestige
        val offlineBoost = engine.dailyOfflineMultiplier(settings.playHoursPerDay)

        val rows = ArrayList<ReplayRow>(plan.size)
        val courses = ArrayList<ReplayCourse>()
        var startHour = 0.0
        var previousPrestigeLingots = 0.0

        for ((course, steps) in plan.groupBy { it.course }.toSortedMap()) {
            val threshold = engine.prestigeThreshold(course)
            val lingotsBefore = lingotRules.welcomeMission + lingotRules.averagePerPlayHour * startHour + previousPrestigeLingots
            val kept = settings.keptShare * lingotsBefore
            val courseMultiplier = engine.globalMultiplier(kept, engine.starsAt(course))
            val scale = courseMultiplier * offlineBoost

            var levels: Map<String, Int> = engine.startingLevels()
            var income = engine.ratePerHour(levels, scale)
            var balance = 0.0
            var earned = 0.0
            var hour = 0.0
            var duration = Double.NaN
            var made = 0
            var ended = false

            for ((index, step) in steps.withIndex()) {
                check(!ended) { "Le plan a des achats après la fin de la course $course (achat ${step.n})" }
                val factoryId = factoryId(step.factoryName)
                val levelBefore = levels[factoryId] ?: 0
                val price = engine.levelsCost(factoryId, levelBefore, step.targetLevel - levelBefore)
                val wait = maxOf(0.0, (price - balance) / income)
                val gained = income * wait
                // Le joueur type attend d'avoir le prix : s'il reste moins que le bruit de calcul, son solde est nul.
                val residual = balance + gained - price
                val balanceAfter = if (Math.abs(residual) <= BALANCE_NOISE * price) 0.0 else residual
                val earnedAfter = earned + gained
                val hourAfter = hour + wait
                val levelsAfter = levels + (factoryId to step.targetLevel)
                val incomeAfter = engine.ratePerHour(levelsAfter, scale)
                val beforePrestige = earnedAfter < threshold
                val durationIfPrestigeFollows = when {
                    beforePrestige -> hourAfter + (threshold - earnedAfter) / incomeAfter
                    index == 0 -> threshold / income
                    else -> duration
                }
                rows += ReplayRow(
                    n = step.n,
                    course = course,
                    factoryName = step.factoryName,
                    targetLevel = step.targetLevel,
                    newCourse = index == 0,
                    courseMultiplier = courseMultiplier,
                    levelBefore = levelBefore,
                    price = price,
                    incomeBefore = income,
                    balanceBefore = balance,
                    waitHours = wait,
                    balanceAfter = balanceAfter,
                    donarEarned = earnedAfter,
                    courseHour = hourAfter,
                    cumulativeHour = startHour + hourAfter,
                    beforePrestige = beforePrestige,
                    incomeAfter = incomeAfter,
                    courseStartHour = startHour,
                    durationIfPrestigeFollows = durationIfPrestigeFollows,
                    levelsAfter = levelsAfter.filterValues { it > 0 }.mapKeys { nameById.getValue(it.key) },
                )
                if (beforePrestige) {
                    levels = levelsAfter
                    income = incomeAfter
                    balance = balanceAfter
                    earned = earnedAfter
                    hour = hourAfter
                    duration = durationIfPrestigeFollows
                    made++
                } else {
                    ended = true
                }
            }
            check(ended) { "Le plan s'arrête avant la fin de la course $course : paliers épuisés" }

            val lingotsExact = engine.prestigeLingotsExact(course, threshold)
            val lingots = engine.prestigeLingots(course, threshold)
            courses += ReplayCourse(
                course = course,
                era = engine.eraAt(course),
                stars = engine.starsAt(course),
                threshold = threshold,
                startHour = startHour,
                lingotsEarnedBefore = lingotsBefore,
                lingotsKept = kept,
                courseMultiplier = courseMultiplier,
                purchasesMade = made,
                duration = duration,
                endHour = startHour + duration,
                conversionReference = threshold / definitions.economy.prestige.lingotsConversion.referenceDivisor,
                lingotsAtPrestigeExact = lingotsExact,
                lingotsAtPrestige = lingots,
                incomeAtEnd = income,
            )
            startHour += duration
            previousPrestigeLingots += lingots.toDouble()
        }
        return ReplayResult(rows, courses)
    }

    /**
     * Le budget ville (en Lingots) juste après le prestige de chaque course : la part des Lingots que le joueur
     * type ne garde pas pour le multiplicateur (feuille Ville, colonne K).
     */
    fun cityBudgetsJustAfter(courses: List<ReplayCourse>): List<Double> {
        val lingotRules = definitions.economy.lingotsOutsidePrestige
        var cumulativePrestige = 0.0
        return courses.map {
            cumulativePrestige += it.lingotsAtPrestige.toDouble()
            (1.0 - settings.keptShare) * (lingotRules.welcomeMission + lingotRules.averagePerPlayHour * it.endHour + cumulativePrestige)
        }
    }

    /**
     * Les zones de la ville et l'heure où le joueur type peut payer chacune (feuille Ville, colonnes A à F).
     * Le budget ville croît en ligne droite pendant une course (1 Lingot par heure de jeu) et saute de la
     * valeur du prestige à la fin ; au-delà de la dernière course du plan, on prolonge au rythme de la
     * dernière course.
     */
    fun cityZones(courses: List<ReplayCourse>): List<ReplayZone> {
        val lingotRules = definitions.economy.lingotsOutsidePrestige
        val keep = settings.keptShare
        val budgets = cityBudgetsJustAfter(courses)
        val endHours = courses.map { it.endHour }
        val cumulativePrestige = courses.runningFold(0.0) { sum, c -> sum + c.lingotsAtPrestige.toDouble() }.drop(1)
        val last = courses.last()

        var cumulativePrice = 0L
        return definitions.economy.city.zonePricesLingots.mapIndexed { index, price ->
            cumulativePrice += price
            val cumulative = cumulativePrice.toDouble()
            // MATCH(C - 0,000001 ; budgets ; 1) + 1 : la première course dont le budget couvre le prix cumulé.
            val course = budgets.count { it <= cumulative - CITY_MATCH_MARGIN } + 1
            val hour = if (course > courses.size) {
                val perCourseHour = last.lingotsAtPrestige.toDouble() / last.duration
                endHours.last() + (cumulative / (1.0 - keep) - lingotRules.welcomeMission -
                    lingotRules.averagePerPlayHour * endHours.last() - cumulativePrestige.last()) /
                    (lingotRules.averagePerPlayHour + perCourseHour)
            } else {
                val courseStart = if (course == 1) 0.0 else endHours[course - 2]
                val prestigeBefore = if (course == 1) 0.0 else cumulativePrestige[course - 2]
                val linear = (cumulative / (1.0 - keep) - lingotRules.welcomeMission - prestigeBefore) / lingotRules.averagePerPlayHour
                minOf(endHours[course - 1], maxOf(courseStart, linear))
            }
            val affordableAt = maxOf(settings.welcomeMissionHour, hour)
            ReplayZone(index + 1, price, cumulativePrice, course, affordableAt, affordableAt / settings.playHoursPerDay)
        }
    }

    /** L'écart au sens de la feuille : `MATCH` cherche « au plus égal à prix - 0,000001 ». */
    private const val CITY_MATCH_MARGIN = 0.000001

    /**
     * Sous cette part du prix, ce qui reste en caisse après l'achat est du bruit de calcul, pas de l'argent :
     * le joueur type a attendu d'avoir exactement le prix. Excel fait la même remise à zéro (son solde est 0
     * partout, sauf un résidu de -1 Donar sur un prix de 5,7e15, aux achats 550 et 551), mais le moteur n'a pas
     * de solde : c'est une règle du joueur type, et elle est loin au-dessus de l'arrondi (1e-16) et loin
     * sous tout achat réel.
     */
    private const val BALANCE_NOISE = 1e-12

    /** Les noms d'usines du tableur, dans l'ordre de usines.json. */
    fun parametresFactoryNames(): List<String> =
        GoldenFiles.read("parametres").array("factories").map { it.jsonObject.getValue("name").jsonPrimitive.content }
}
