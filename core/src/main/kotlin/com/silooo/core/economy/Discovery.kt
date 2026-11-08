package com.silooo.core.economy

import com.silooo.core.api.FactoryState
import com.silooo.core.session.FactoryRecord

/**
 * Les usines révélées pour une entreprise qui a les lignes [factories] et l'époque [era] ([EconomyEngine.discoveredFactories]) :
 * le niveau d'entreprise est la somme des niveaux de ses lignes, et ses usines `ACTIVE` ouvrent les clés `apres-<usine>`.
 * Une ligne que usines.json ne connaît pas (une usine retirée du jeu) ne compte pas.
 */
fun EconomyEngine.discoveredFor(factories: List<FactoryRecord>, era: Int): List<String> {
    val known = factoryIds().toSet()
    val rows = factories.filter { it.factoryId in known }
    val active = rows.filter { it.state == FactoryState.ACTIVE }.mapTo(HashSet()) { it.factoryId }
    return discoveredFactories(rows.associate { it.factoryId to it.level }, era, active)
}
