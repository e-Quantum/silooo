package com.silooo.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Les schémas de l'OpenAPI qui servent à plusieurs routes (`components/schemas`), avec le nom du
 * contrat. Les XUID sont des chaînes dans le JSON (règle 6 du socle). Un champ ajouté ici s'ajoute
 * d'abord à l'OpenAPI.
 */

/** Schéma `ActionBase` : tout appel qui modifie quelque chose le porte (corps, ou paramètres pour un `DELETE`). */
@Serializable
data class ActionBase(val requestId: String, val actor: String)

/** Schéma `Balances` : les monnaies personnelles, des entiers. */
@Serializable
data class Balances(val lingots: Long, val gemmes: Long, val jetons: Long, val cles: Long)

/** États d'une usine (`Factory.state`, `company_factories.state`). */
@Serializable
enum class FactoryState { EN_RUINE, REMISE_EN_ETAT, ACTIVE }

/** Rôles dans une entreprise (`JoinResponse.role`, `company_members.role`). */
@Serializable
enum class Role { PDG, ASSOCIE }

/** Schéma `Factory`. Les chiffres (`ratePerHour`, `nextCost`, `nextMilestone`) viennent du moteur d'économie. */
@Serializable
data class Factory(
    val factoryId: String,
    val state: FactoryState,
    val restoreStep: Int,
    val level: Int,
    val ratePerHour: Double,
    val nextCost: Double,
    val nextMilestone: Int,
    val incidentId: Long? = null,
    val stoppedNoPower: Boolean = false,
)

/** Schéma `CompanyState.island` : l'origine de l'archipel de l'entreprise, en blocs (`archipelago_slots`). */
@Serializable
data class Island(val x: Int, val z: Int)

/** Schéma `CompanyState`. */
@Serializable
data class CompanyState(
    val companyId: Long,
    val name: String,
    val era: Int,
    val stars: Int,
    val courseNumber: Int,
    val donar: Double,
    val donarEarned: Double,
    val ratePerHour: Double,
    val multiplier: Double,
    val threshold: Double,
    val factories: List<Factory>,
    val zonesOwned: List<Int>,
    val island: Island,
    val version: Int,
)

/** Schéma `PrestigePreview.shares[]` : les Lingots qu'un membre recevrait. */
@Serializable
data class PrestigeShare(val xuid: String, val lingots: Long)

/**
 * Schéma `PrestigePreview` : ce que rapporterait un prestige maintenant. `next` vaut `EPOQUE_2`, `EPOQUE_3`,
 * `EPOQUE_4` ou `ETOILE` ; il reste une chaîne, l'énumération de l'OpenAPI suivant les époques d'`usines.json`.
 */
@Serializable
data class PrestigePreview(
    val allowed: Boolean,
    val lingotsTotal: Long,
    val lingotsIfDoubled: Long,
    val shares: List<PrestigeShare>,
    val next: String,
)

/** Schéma `JoinResponse`. */
@Serializable
data class JoinResponse(
    val xuid: String,
    val firstJoin: Boolean,
    val tutorialStep: Int,
    val companyId: Long,
    val role: Role,
    val rank: String?,
    val pilierUntil: String?,
    val balances: Balances,
    val state: CompanyState,
    val offlineCollected: Double,
)

/** Réponse `CompanyUpdated` de l'OpenAPI : l'état de l'entreprise après l'action, et les soldes de celui qui agit. */
@Serializable
data class CompanyUpdated(val state: CompanyState, val balances: Balances)

/** Schéma `GameEvent`. `kind` reste une chaîne : un type écrit par une version plus récente ne doit pas casser la lecture. */
@Serializable
data class GameEvent(val id: Long, val xuid: String?, val kind: String, val payload: JsonObject)

/** La réponse 200 de `pollEvents`. */
@Serializable
data class EventsPage(val cursor: Long, val events: List<GameEvent>)

/** Schéma `Mission` : la mission en cours du joueur. Les textes sont ceux de l'add-on, retrouvés par [id]. */
@Serializable
data class Mission(val id: String, val progress: Int, val target: Int, val rewardLingots: Long)

/** Schéma `MissionState` : [current] est la première mission pas terminée (`null` quand toutes le sont), [completed] les terminées. */
@Serializable
data class MissionState(val current: Mission?, val completed: List<String>)
