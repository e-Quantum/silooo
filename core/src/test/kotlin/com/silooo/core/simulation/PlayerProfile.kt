package com.silooo.core.simulation

import com.silooo.core.GoldenFiles
import com.silooo.core.double
import com.silooo.core.int
import com.silooo.core.obj
import com.silooo.core.objects

/**
 * Les hypothèses du joueur type du tableur. Ce ne sont pas des règles du jeu (elles n'ont pas de clé dans
 * `definitions/`) : elles disent comment joue le joueur que le simulateur fait jouer.
 *
 * @property playHoursPerDay heures de jeu par jour (Paramètres!B7). Les autres heures de la journée rapportent hors
 * ligne : elles entrent dans le revenu par [com.silooo.core.economy.EconomyEngine.dailyOfflineMultiplier] (B8).
 * @property keptLingotsShare part des Lingots que le joueur garde pour le multiplicateur (B10). Le reste est son budget
 * de ville.
 * @property welcomeMissionHour heure de jeu où la mission de bienvenue de la mairie paie les premiers Lingots (B14) :
 * aucune zone de ville ne s'achète avant.
 * @property ladder l'échelle des paliers que le joueur vise, du plus bas au plus haut : à chaque achat, il monte une
 * usine jusqu'au palier suivant de cette échelle, jamais entre deux (la feuille Simulation : « Scierie jusqu'au niveau 25 »).
 */
data class PlayerProfile(
    val playHoursPerDay: Double,
    val keptLingotsShare: Double,
    val welcomeMissionHour: Double,
    val ladder: List<Int>,
) {
    init {
        require(playHoursPerDay > 0.0) { "Les heures de jeu par jour sont positives : $playHoursPerDay" }
        require(keptLingotsShare in 0.0..1.0) { "La part de Lingots gardés est entre 0 et 1 : $keptLingotsShare" }
        require(welcomeMissionHour >= 0.0) { "L'heure de la mission de bienvenue n'est pas négative : $welcomeMissionHour" }
        require(ladder.isNotEmpty() && ladder.all { it >= 1 }) { "L'échelle de paliers est faite de niveaux à partir de 1 : $ladder" }
        require(ladder == ladder.distinct().sorted()) { "L'échelle de paliers est strictement croissante : $ladder" }
    }

    companion object {
        /**
         * Le joueur type du tableur, lu dans les données dorées (`golden/`) :
         *  - les trois hypothèses dans `parametres.json` (B7, B10, B14) ;
         *  - l'échelle de paliers dans `simulation.json`, colonne D (« Niveau visé ») : les niveaux que la feuille vise,
         *    une fois chacun. Le tableur ne la déclare nulle part ailleurs, et une échelle copiée à la main dériverait.
         */
        fun fromGolden(): PlayerProfile {
            val settings = GoldenFiles.read("parametres").obj("settings")
            return PlayerProfile(
                playHoursPerDay = settings.obj("playHoursPerDay").double("value"),
                keptLingotsShare = settings.obj("lingotsKeptShare").double("value"),
                welcomeMissionHour = settings.obj("welcomeMissionHour").double("value"),
                ladder = GoldenFiles.read("simulation").objects("purchases").map { it.int("targetLevel") }.distinct().sorted(),
            )
        }
    }
}
