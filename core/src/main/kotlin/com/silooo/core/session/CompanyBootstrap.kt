package com.silooo.core.session

import com.silooo.core.api.FactoryState

/** Une usine que l'entreprise possède dès sa création (la Carrière : `decouverte: "depart"` dans `definitions/usines.json`). */
data class StartingFactory(
    val factoryId: String,
    val state: FactoryState,
    val level: Int,
    val restoreStep: Int = 0,
)

/** Où une case de l'archipel se trouve dans le monde : `archipelago_slots.origin_x` et `origin_z`. */
data class SlotOrigin(val x: Int, val z: Int)

/**
 * Tout ce que la création d'une entreprise tire de `definitions/`. Le code des routes de session ne lit pas
 * `definitions/` et n'écrit aucun identifiant ni chiffre d'usine : il demande ici.
 *
 * L'implémentation du service est `com.silooo.core.economy.DefinitionsBootstrap` (usines possédées au départ,
 * plafond hors ligne de départ, grille des îles de `monde.json`) ; les tests passent la leur à
 * `Dependencies(companyBootstrap = ...)`.
 */
interface CompanyBootstrap {
    /** Les usines possédées au départ, avec leur état et leur niveau. */
    fun startingFactories(): List<StartingFactory>

    /**
     * Le plafond hors ligne, en heures, d'une entreprise neuve : `companies.offline_cap_hours`. Le plafond de
     * départ est un chiffre du jeu (`horsLigne.plafondHeures`) ; la colonne le reprend à la création pour que
     * `definitions/` reste la seule source, puis les améliorations l'étendent.
     */
    fun startingOfflineCapHours(): Double

    /**
     * Où poser une case d'archipel NEUVE, d'indice [slotIndex] (les cases libres déjà dans
     * `archipelago_slots` servent d'abord). L'écart entre deux îles est un chiffre du jeu : il vient des définitions.
     */
    fun slotOrigin(slotIndex: Int): SlotOrigin
}
