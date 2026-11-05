package com.silooo.core.orders

import com.silooo.core.definitions.Definitions
import java.time.LocalDate

/**
 * Ce que dit la commande mondiale d'un jour donné, avant que quiconque ait livré : la même pour tout le serveur, et
 * tirée de la seule date. Aucun tirage au sort, aucune ligne en base : deux services qui tournent le même jour
 * annoncent la même commande, et l'add-on peut la lire sans qu'elle soit écrite.
 *
 * La ressource du jour est la `(date en jours depuis 1970) mod (nombre de ressources)`-ième des ressources du jeu
 * ([Definitions.resources], les produits de `usines.json` dans l'ordre du fichier) : le serveur les parcourt toutes, une
 * par jour, dans le même ordre. L'objectif et la prime viennent de `economie.json` (`commandeDuJour`,
 * `lingotsHorsPrestige.commandeDuJour`). Cette règle est **à valider par Kr** : le game design dit « une par jour »,
 * pas comment on la choisit.
 */
class OrderPlan(private val definitions: Definitions) {
    /** La commande d'un jour : la ressource à livrer, l'objectif en unités et la prime en Lingots hors prestige. */
    data class Planned(val day: LocalDate, val resourceId: String, val targetUnits: Long, val rewardLingots: Long)

    fun forDay(day: LocalDate): Planned {
        val resources = definitions.resources
        val resource = resources[Math.floorMod(day.toEpochDay(), resources.size.toLong()).toInt()]
        return Planned(
            day = day,
            resourceId = resource,
            targetUnits = definitions.economy.dailyOrder.targetUnits,
            rewardLingots = definitions.economy.lingotsOutsidePrestige.dailyOrder,
        )
    }
}
