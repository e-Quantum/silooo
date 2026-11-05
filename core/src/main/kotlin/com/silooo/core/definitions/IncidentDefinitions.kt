package com.silooo.core.definitions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * La forme de definitions/incidents.json (schéma : definitions/schemas/incidents.schema.json). Voir [EconomyDefinitions].
 * Tous les chiffres des incidents d'usine et de l'électricité sont ici et nulle part ailleurs ; ils sont **à valider par Kr**.
 */
@Serializable
data class IncidentsDefinitions(
    val version: Int,
    @SerialName("frequence") val frequency: IncidentFrequency,
    /** Les sortes d'incident, dans l'ordre du fichier : le tirage s'y réfère par la position. */
    @SerialName("sortes") val kinds: List<IncidentKindDefinition>,
    @SerialName("recompense") val reward: IncidentReward,
    @SerialName("electricite") val electricity: ElectricityDefinition,
)

/** La cadence des incidents : le temps se découpe en créneaux, et chaque créneau où un joueur est en ligne a un tirage. */
@Serializable
data class IncidentFrequency(
    /** Durée d'un créneau, en minutes. Elle divise une journée (contrôle de [DefinitionsChecks]). */
    @SerialName("creneauMinutes") val slotMinutes: Int,
    /** La chance qu'un incident survienne à un créneau, en pourcent. */
    @SerialName("chancePourcent") val chancePercent: Double,
)

@Serializable
data class IncidentKindDefinition(
    /** La sorte, telle que `factory_incidents.kind` la garde (16 caractères au plus) : elle ne change plus. */
    val id: String,
    /** Le facteur sur la production de l'usine tant que l'incident dure : strictement entre 0 et 1. */
    @SerialName("ralentissement") val slowdown: Double,
    /** Le poids de la sorte dans le tirage. */
    @SerialName("poids") val weight: Int,
)

/** La petite récompense du mini-jeu de réparation. */
@Serializable
data class IncidentReward(
    /** Le nombre de minutes du revenu de l'usine (sans ralentissement) que la réparation rapporte, en Donar. */
    @SerialName("minutesDeRevenu") val revenueMinutes: Double,
)

/** Ce que l'électricité demande aux usines modernes. */
@Serializable
data class ElectricityDefinition(
    /** À partir de cette époque de l'entreprise, une usine qui dépend du courant s'arrête sans Centrale active. */
    @SerialName("aPartirDeEpoque") val fromEra: Int,
    /** L'usine de `usines.json` qui produit le courant. */
    @SerialName("usineCentrale") val powerPlantId: String,
)
