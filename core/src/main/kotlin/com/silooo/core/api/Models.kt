package com.silooo.core.api

import com.silooo.core.definitions.BuildingBonusType
import com.silooo.core.definitions.StaffRarity
import com.silooo.core.definitions.StaffRole
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
    val incidentKind: String? = null,
    val stoppedNoPower: Boolean = false,
)

/**
 * Schéma `Staff` : un candidat du jour (`getCandidates`) ou un membre du personnel embauché (`CompanyState.staff`).
 * Les champs que le schéma ne déclare pas nullables ne s'écrivent pas quand ils n'ont pas de valeur ; `factoryId`
 * est nullable dans le contrat et s'écrit `null`. Tous les chiffres viennent du service : l'add-on les affiche.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Staff(
    /** N'existe qu'une fois embauché. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val staffId: Long? = null,
    /** N'existe que pour un candidat du jour (1 à 5). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val slot: Int? = null,
    val name: String,
    val skinId: String,
    val role: StaffRole,
    val rarity: StaffRarity,
    /** Gain de production de la rareté, en pourcent de la production de l'usine où le membre travaille. */
    val bonusPercent: Double,
    /** Salaire du membre (rareté et rôle), en pourcent de la production de l'usine où il travaille. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val salaryPercent: Double? = null,
    /** Ce que le salaire coûte à l'entreprise par heure de jeu, maintenant ; zéro sans usine active. Membre embauché seulement. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val salaryPerHour: Double? = null,
    /** Le prix d'embauche, en Donar, d'un candidat encore libre. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val hireCost: Double? = null,
    /** L'usine où le membre est affecté ; nul tant qu'il n'en a pas. */
    val factoryId: String? = null,
    /** Vrai si un logement de la ville l'héberge : son salaire est réduit, `salaryPercent` et `salaryPerHour` le disent déjà. Membre embauché seulement. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val housed: Boolean? = null,
)

/** Schéma `CityState.nextZone` : la prochaine zone à acheter, avec ce qu'elle demande. Tout vient de `ville.json` et `economie.json`. */
@Serializable
data class NextZone(val zoneIndex: Int, val priceLingots: Long, val mairieLevelRequired: Int, val activeFactoriesRequired: Int)

/** Schéma `CityBuilding.nextLevel` : ce que coûte et demande le niveau suivant d'un bâtiment. */
@Serializable
data class NextBuildingLevel(val priceLingots: Long, val mairieLevelRequired: Int)

/**
 * Schéma `CityBuilding` : un bâtiment de la ville. [bonusType] et [bonus] disent ce qu'il donne à son niveau (nuls pour la mairie et
 * les routes), [nextLevel] ce que coûte le niveau suivant (nul au dernier niveau). Nulls écrits `null`, comme le contrat les déclare.
 */
@Serializable
data class CityBuilding(
    val buildingId: Long,
    val buildingType: String,
    val level: Int,
    val maxLevel: Int,
    val zoneIndex: Int,
    val x: Int,
    val z: Int,
    val rotation: Int,
    val bonusType: BuildingBonusType? = null,
    val bonus: Double? = null,
    val nextLevel: NextBuildingLevel? = null,
)

/** Schéma `CityBonuses` : ce que les bâtiments donnent ensemble, plafonds appliqués. */
@Serializable
data class CityBonuses(
    val housingPlaces: Int,
    val housedStaff: Int,
    val donarProductionPercent: Double,
    val deliverySpeedPercent: Double,
)

/** Schéma `CityState` : la ville de l'entreprise telle que le service la calcule ; l'add-on l'affiche. */
@Serializable
data class CityState(
    val mairieLevel: Int,
    /** La prochaine zone à acheter ; nulle quand toutes sont à l'entreprise. */
    val nextZone: NextZone? = null,
    val buildings: List<CityBuilding>,
    val bonuses: CityBonuses,
)

/**
 * Schéma `Member` : un membre de l'entreprise. [sharePercent] est sa part entière du partage des Lingots au prestige ; les parts de
 * tous les membres font 100. [name] est son gamertag.
 */
@Serializable
data class Member(val xuid: String, val name: String, val role: Role, val sharePercent: Int)

/** Schéma `CompanyState.island` : l'origine de l'archipel de l'entreprise, en blocs (`archipelago_slots`). */
@Serializable
data class Island(val x: Int, val z: Int)

/** Schéma `CompanyState`. [city] n'est écrite que si le service a calculé la ville (le contrat ne la rend pas obligatoire). */
@OptIn(ExperimentalSerializationApi::class)
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
    @EncodeDefault(EncodeDefault.Mode.NEVER) val city: CityState? = null,
    val staff: List<Staff> = emptyList(),
    /** Le PDG d'abord, puis les associés dans l'ordre d'arrivée. */
    val members: List<Member> = emptyList(),
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

/** Schéma `WorldOrder` : la commande mondiale du jour. [mine] est la part déjà livrée par le joueur demandé (0 sans joueur). */
@Serializable
data class WorldOrder(
    val day: String,
    val resourceId: String,
    val target: Double,
    val delivered: Double,
    val rewardLingots: Long,
    val completed: Boolean,
    val mine: Double,
)

/**
 * Schéma `VotesToday` : les votes du jour (UTC) pour le serveur et la Vote party. Les chiffres (objectif, Lingots, jours de série)
 * viennent de `definitions/vote.json` : l'add-on les affiche sans en connaître aucun. [mine] est la part du joueur demandé (zéro sans joueur).
 */
@Serializable
data class VotesToday(
    val day: String,
    val votes: Int,
    val goal: Int,
    val partyReached: Boolean,
    val rewardLingots: Long,
    val partyRewardLingots: Long,
    val streakGoalDays: Int,
    val streakBonusLingots: Long,
    val mine: PlayerVotes,
)

/** Schéma `PlayerVotes` : les votes du joueur aujourd'hui, et sa série de jours de vote de suite. */
@Serializable
data class PlayerVotes(val votes: Int, val streak: Int)

/** Réponse 200 de `deliverOrder` : la commande à jour, puis l'état de l'entreprise et les soldes de celui qui livre. */
@Serializable
data class OrderDelivered(val order: WorldOrder, val state: CompanyState, val balances: Balances)

/** Schéma `Finding` : une trouvaille du Carnet, la ressource et le moment où le joueur l'a faite. */
@Serializable
data class Finding(val id: String, val foundAt: String)

/** Schéma `FindingBook` : le Carnet du joueur, le nombre de trouvailles que le jeu propose et la prime de chacune. */
@Serializable
data class FindingBook(val found: List<Finding>, val total: Int, val rewardLingots: Long)

/**
 * Un élément de la réponse de `catalog` : un article de la boutique, son prix en Gemmes, et ce que le joueur demandé en est.
 * [owned] : il le possède (le grade à vie, ou l'abonnement qui court). [available] : il peut l'acheter maintenant (pour un grade,
 * le seul premier qu'il n'a pas encore ; pour un abonnement, ce qu'il demande est réuni).
 */
@Serializable
data class CatalogItem(val itemId: String, val gemmes: Long, val owned: Boolean, val available: Boolean)
