package com.silooo.core.simulation

import com.silooo.core.TestDefinitions
import com.silooo.core.definitions.Definitions
import com.silooo.core.economy.EconomyEngine

/**
 * La partie rejouée une seule fois pour tous les tests de ce package : le simulateur est déterministe, et neuf
 * courses coûtent quelques centaines de milliers d'évaluations du moteur.
 */
object SimulationFixture {
    val definitions: Definitions get() = TestDefinitions.loaded
    val engine: EconomyEngine by lazy { EconomyEngine(definitions) }
    val profile: PlayerProfile by lazy { PlayerProfile.fromGolden() }
    val targets: TargetSet by lazy { TargetSet.fromGolden(definitions) }

    /** Les courses qu'il faut jouer pour mesurer toutes les cibles du tableur. */
    val run: SimulationRun by lazy { GameSimulator(definitions, profile, engine).run(targets.coursesNeeded) }
}
