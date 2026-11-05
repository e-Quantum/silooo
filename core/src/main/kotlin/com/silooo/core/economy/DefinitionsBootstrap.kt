package com.silooo.core.economy

import com.silooo.core.api.FactoryState
import com.silooo.core.definitions.Definitions
import com.silooo.core.session.CompanyBootstrap
import com.silooo.core.session.SlotOrigin
import com.silooo.core.session.StartingFactory

/**
 * Ce que la création d'une entreprise tire de `definitions/` : les usines de départ (`prestige.departDeCourse`,
 * par le [EconomyEngine]), le plafond hors ligne de départ (`horsLigne.plafondHeures`) et la grille des
 * archipels (`monde.json`). Aucun chiffre n'est écrit ici.
 */
class DefinitionsBootstrap(private val engine: EconomyEngine, definitions: Definitions) : CompanyBootstrap {
    private val grid = definitions.world.archipelago
    private val offlineCapHours = definitions.economy.offline.capHours

    /** Les usines de départ de la course, toutes `ACTIVE` : la première usine n'est pas à remettre en état. */
    override fun startingFactories(): List<StartingFactory> =
        engine.startingLevels().map { (factoryId, level) -> StartingFactory(factoryId, FactoryState.ACTIVE, level) }

    override fun startingOfflineCapHours(): Double = offlineCapHours

    /**
     * La grille de `monde.json` : la case [slotIndex] est dans la colonne `slotIndex % casesParRangee` et la rangée
     * `slotIndex / casesParRangee`, chacune multipliée par `ecartEntreCases`, à partir de l'origine. Deux cases
     * ont donc toujours des origines distinctes (`uq_slots_origin`) et au moins `ecartEntreCases` blocs d'écart
     * sur un des deux axes.
     */
    override fun slotOrigin(slotIndex: Int): SlotOrigin {
        require(slotIndex >= 0) { "Une case d'archipel se numérote depuis 0 : $slotIndex" }
        val column = slotIndex % grid.slotsPerRow
        val row = slotIndex / grid.slotsPerRow
        return SlotOrigin(
            x = Math.toIntExact(grid.origin.x + column.toLong() * grid.slotSpacing),
            z = Math.toIntExact(grid.origin.z + row.toLong() * grid.slotSpacing),
        )
    }
}
