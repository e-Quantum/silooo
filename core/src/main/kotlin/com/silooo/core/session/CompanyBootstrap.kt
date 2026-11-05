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
 * La direction branche l'implémentation qui lit les définitions (usines possédées au départ, écart entre
 * les îles de la grille) et se passe à `Dependencies(companyBootstrap = ...)`. D'ici là, [EmptyBootstrap].
 */
interface CompanyBootstrap {
    /** Les usines possédées au départ, avec leur état et leur niveau. */
    fun startingFactories(): List<StartingFactory>

    /**
     * Où poser une case d'archipel NEUVE, d'indice [slotIndex] (les cases libres déjà dans
     * `archipelago_slots` servent d'abord). L'écart entre deux îles est un chiffre du jeu : il vient des définitions.
     */
    fun slotOrigin(slotIndex: Int): SlotOrigin
}

/**
 * L'implémentation par défaut : aucune usine au départ, et une case d'archipel d'indice N posée en (N, 0). C'est
 * le minimum pour que les coordonnées restent uniques (`uq_slots_origin`), PAS une grille de jeu : les îles
 * se chevaucheraient dans le monde. À remplacer avant d'accueillir de vrais joueurs.
 */
object EmptyBootstrap : CompanyBootstrap {
    override fun startingFactories(): List<StartingFactory> = emptyList()

    override fun slotOrigin(slotIndex: Int): SlotOrigin = SlotOrigin(slotIndex, 0)
}
