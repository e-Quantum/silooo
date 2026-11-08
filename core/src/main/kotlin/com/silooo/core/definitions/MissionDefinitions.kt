package com.silooo.core.definitions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** La forme de definitions/missions.json (schéma : definitions/schemas/missions.schema.json). Voir [EconomyDefinitions]. */
@Serializable
data class MissionsDefinitions(
    val version: Int,
    /** Les missions dans l'ordre où un joueur les fait : la mission en cours est la première qu'il n'a pas terminée. */
    @SerialName("missions") val missions: List<MissionDefinition>,
)

/**
 * Une mission du catalogue. Elle ne porte pas ses textes (l'add-on les tient) ni le nombre de Lingots : [reward] est le
 * chemin, dans economie.json, du nombre à verser. `Definitions.missionRewards` le résout au chargement.
 */
@Serializable
data class MissionDefinition(
    /** Clé du suivi des joueurs (`player_missions.mission_id`, 48 caractères au plus) : elle ne change plus une fois la mission jouée. */
    val id: String,
    @SerialName("evenement") val event: MissionEvent,
    /** Nombre d'actions à faire pour terminer la mission. */
    @SerialName("cible") val target: Int,
    /** Chemin dans economie.json, par exemple `lingotsHorsPrestige.missionBienvenue`. */
    @SerialName("recompense") val reward: String,
)

/** Les actions du joueur que le service sait compter pour une mission. Une valeur nouvelle s'ajoute ici et dans le schéma. */
@Serializable
enum class MissionEvent {
    /** Un encaissement qui rapporte du Donar. */
    @SerialName("collect")
    COLLECT,

    /** Un achat de niveaux d'usine, quel qu'en soit le nombre (1, 10, palier ou max). */
    @SerialName("achatNiveaux")
    BUY_LEVELS,

    /** Une étape de remise en état d'usine. */
    @SerialName("etapeRemiseEnEtat")
    RESTORE_STEP,

    /** L'achat d'une zone de la ville. */
    @SerialName("achatZone")
    BUY_ZONE,
}
