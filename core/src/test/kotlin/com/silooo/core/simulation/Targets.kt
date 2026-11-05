package com.silooo.core.simulation

import com.silooo.core.GoldenFiles
import com.silooo.core.definitions.Definitions
import com.silooo.core.double
import com.silooo.core.int
import com.silooo.core.obj
import com.silooo.core.objects
import com.silooo.core.string
import java.util.Locale

/*
 * Les treize cibles du tableur et leur mesure sur une partie simulée.
 *
 * Les cibles et leurs fourchettes ne sont écrites nulle part dans le code : elles viennent de golden/synthese.json
 * (feuille Synthèse, colonnes A à D), que tools/tableur/extraire.py tire du tableur
 * docs/references/silooo-equilibrage-v2.xlsx. Ce fichier-ci ne fait que les lire, les mesurer et juger.
 */

/** Ce que le simulateur mesure pour une cible. Une cible du tableur renvoie à l'un de ces quatre événements. */
sealed interface Measure {
    /** L'heure de jeu cumulée où `factoryId` atteint `level` dans la course `course` (Synthèse : « achat n° N de Simulation »). */
    data class LevelReached(val course: Int, val factoryId: String, val level: Int) : Measure

    /** L'heure de jeu cumulée où le budget de ville paie la zone `zone` (Synthèse : « cityZone », feuille Ville). */
    data class CityZone(val zone: Int) : Measure

    /** L'heure de jeu cumulée de la fin de la course `course`, prestige compris. */
    data class CourseEnd(val course: Int) : Measure

    /** La durée de la course `course`, en heures de jeu. */
    data class CourseDuration(val course: Int) : Measure
}

/** Une cible : une étape du jeu, sa mesure, et la fourchette en heures de jeu où le tableur veut la voir (bornes comprises). */
data class Target(
    val id: String,
    val stage: String,
    val measure: Measure,
    val min: Double,
    val max: Double,
    /** La valeur que le tableur calcule pour son joueur type (Synthèse, colonne B). */
    val sheetValue: Double,
    /** Les cellules de la fourchette dans la feuille, par exemple « Synthèse!C5:D5 ». */
    val sheetCells: String,
)

enum class Verdict(val label: String) {
    TOO_SHORT("Trop court"),
    IN_TARGET("Dans la cible"),
    TOO_LONG("Trop long"),

    /** La partie simulée ne va pas jusqu'à l'événement : rien à comparer. */
    NOT_REACHED("Jamais atteint"),
}

data class TargetResult(val target: Target, val measured: Double?) {
    /** Le verdict du tableur (Synthèse, colonne E) : sous le minimum, au-dessus du maximum, ou dans la fourchette. */
    val verdict: Verdict = when {
        measured == null || measured.isNaN() -> Verdict.NOT_REACHED
        measured < target.min -> Verdict.TOO_SHORT
        measured > target.max -> Verdict.TOO_LONG
        else -> Verdict.IN_TARGET
    }

    val inTarget: Boolean get() = verdict == Verdict.IN_TARGET

    /** De combien la mesure sort de la fourchette, en heures : négatif sous le minimum, positif au-dessus du maximum, nul dedans. */
    val gap: Double = when (verdict) {
        Verdict.TOO_SHORT -> measured!! - target.min
        Verdict.TOO_LONG -> measured!! - target.max
        else -> 0.0
    }

    fun line(): String {
        val measuredText = if (measured == null) "jamais atteint" else "${hours(measured)} (${minutes(measured)})"
        val gapText = if (gap == 0.0) "" else " (${signed(gap)} h)"
        return "%-14s %-52s mesuré %-22s fourchette [%s ; %s] h  %s%s  %s".format(
            target.id,
            target.stage,
            measuredText,
            number(target.min),
            number(target.max),
            verdict.label,
            gapText,
            target.sheetCells,
        )
    }

    private fun hours(value: Double) = String.format(Locale.FRANCE, "%.3f h", value)
    private fun minutes(value: Double) = String.format(Locale.FRANCE, "%.1f min", value * MINUTES_PER_HOUR)
    private fun signed(value: Double) = String.format(Locale.FRANCE, "%+.3f", value)
    private fun number(value: Double) = String.format(Locale.FRANCE, "%.3f", value).trimEnd('0').trimEnd(',')

    private companion object {
        /** Une heure : constante de la nature. */
        const val MINUTES_PER_HOUR = 60.0
    }
}

/** Les cibles du tableur, avec d'où elles viennent. */
class TargetSet(val provenance: String, val targets: List<Target>) {
    /** Le nombre de courses à jouer pour atteindre tous les événements des cibles. */
    val coursesNeeded: Int = targets.maxOf {
        when (val measure = it.measure) {
            is Measure.LevelReached -> measure.course
            is Measure.CityZone -> 1
            is Measure.CourseEnd -> measure.course
            is Measure.CourseDuration -> measure.course
        }
    }

    fun measure(run: SimulationRun): List<TargetResult> = targets.map { TargetResult(it, measure(run, it.measure)) }

    private fun measure(run: SimulationRun, measure: Measure): Double? = when (measure) {
        is Measure.LevelReached -> run.hourFactoryReaches(measure.course, measure.factoryId, measure.level)
        is Measure.CityZone -> run.zones.firstOrNull { it.zone == measure.zone }?.cumulativeHour
        is Measure.CourseEnd -> run.courseOrNull(measure.course)?.endHour
        is Measure.CourseDuration -> run.courseOrNull(measure.course)?.duration
    }

    companion object {
        /**
         * Lit golden/synthese.json. Une cible qui renvoie à un achat de la feuille Simulation (`link.simulationPurchase`)
         * est traduite en « telle usine atteint tel niveau dans telle course » d'après la ligne de simulation.json : le
         * simulateur décide lui-même de l'ordre de ses achats, il ne reprend que l'événement que le tableur mesurait.
         */
        fun fromGolden(definitions: Definitions): TargetSet {
            val synthese = GoldenFiles.read("synthese")
            val source = synthese.obj("source")
            val plan = GoldenFiles.read("simulation").objects("purchases")
            val idByName = definitions.factories.factories.associate { it.name to it.id }
            val targets = synthese.objects("targets").map { target ->
                val link = target.obj("link")
                val measure = when {
                    "simulationPurchase" in link -> {
                        val wanted = link.int("simulationPurchase")
                        val row = plan.firstOrNull { it.int("n") == wanted } ?: error("synthese.json vise l'achat $wanted, absent de simulation.json")
                        Measure.LevelReached(
                            course = row.int("course"),
                            factoryId = idByName[row.string("factory")] ?: error("Usine inconnue de usines.json : ${row.string("factory")}"),
                            level = row.int("targetLevel"),
                        )
                    }
                    "cityZone" in link -> Measure.CityZone(link.int("cityZone"))
                    "course" in link -> when (val kind = target.string("measure")) {
                        "cumulativeHour" -> Measure.CourseEnd(link.int("course"))
                        "courseDuration" -> Measure.CourseDuration(link.int("course"))
                        else -> error("Mesure inconnue dans synthese.json : $kind")
                    }
                    else -> error("Lien inconnu dans synthese.json : $link")
                }
                val row = target.int("sheetRow")
                Target(
                    id = target.string("id"),
                    stage = target.string("stage"),
                    measure = measure,
                    min = target.double("targetMin"),
                    max = target.double("targetMax"),
                    sheetValue = target.double("computed"),
                    sheetCells = "Synthèse!C$row:D$row",
                )
            }
            val provenance = "${source.string("file")} (sha256 ${source.string("sha256").take(SHA_PREFIX)}…), lu dans golden/synthese.json"
            return TargetSet(provenance, targets)
        }

        private const val SHA_PREFIX = 12
    }
}

/** Le compte rendu des treize cibles, en une table lisible : c'est ce qu'on lit quand le test échoue. */
fun report(provenance: String, results: List<TargetResult>, run: SimulationRun): String = buildString {
    val failing = results.filterNot { it.inTarget }
    appendLine("${failing.size} cible(s) sur ${results.size} hors de leur fourchette. Cibles : $provenance.")
    for (result in results) appendLine((if (result.inTarget) "   " else " ! ") + result.line())
    appendLine("Courses simulées (durée en heures de jeu) :")
    for (course in run.courses) {
        appendLine(
            "   course %d : époque %d, %d étoile(s), %s achats, %s h, fin à %s h".format(
                course.course,
                course.era,
                course.stars,
                course.purchases.size,
                String.format(Locale.FRANCE, "%.3f", course.duration),
                String.format(Locale.FRANCE, "%.3f", course.endHour),
            ),
        )
    }
    append("Lecture d'un échec : docs/economie.md, section « Le simulateur ».")
}
