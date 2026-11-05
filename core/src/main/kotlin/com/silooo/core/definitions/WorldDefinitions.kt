package com.silooo.core.definitions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** La forme de definitions/monde.json (schéma : definitions/schemas/monde.schema.json). Voir [EconomyDefinitions]. */
@Serializable
data class WorldDefinitions(
    val version: Int,
    @SerialName("archipel") val archipelago: ArchipelagoDefinition,
    @SerialName("entreprise") val company: CompanyDefinition,
)

/** L'entreprise partagée (bloc `entreprise` de monde.json) : la taille d'une entreprise et la durée d'une invitation. */
@Serializable
data class CompanyDefinition(
    /** Nombre de membres au plus d'une entreprise, PDG compris. */
    @SerialName("membresMax") val maxMembers: Int,
    /** Durée de validité d'une invitation, en heures. */
    @SerialName("invitationHeures") val invitationHours: Int,
)

/**
 * La grille des archipels dans le monde unique : une case par entreprise (`archipelago_slots`). La case
 * d'indice `n` est dans la colonne `n % casesParRangee` et la rangée `n / casesParRangee`.
 */
@Serializable
data class ArchipelagoDefinition(
    /** Distance en blocs entre les origines de deux cases voisines, sur x comme sur z. */
    @SerialName("ecartEntreCases") val slotSpacing: Int,
    /** Nombre de cases d'une rangée (le long de x) avant de passer à la rangée suivante (le long de z). */
    @SerialName("casesParRangee") val slotsPerRow: Int,
    /** Coordonnées en blocs de la case d'indice 0. */
    @SerialName("origine") val origin: WorldPointDefinition,
)

@Serializable
data class WorldPointDefinition(val x: Int, val z: Int)
