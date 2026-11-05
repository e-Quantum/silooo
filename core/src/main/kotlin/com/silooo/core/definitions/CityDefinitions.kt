package com.silooo.core.definitions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * La forme de definitions/ville.json (schéma : definitions/schemas/ville.schema.json) : le quadrillage de l'île principale, les zones
 * dans l'ordre où on les achète, la mairie, le logement des salariés, les plafonds de bonus et le catalogue des bâtiments. Voir
 * [EconomyDefinitions]. Les prix des zones restent dans economie.json (`ville.zonesPrixLingots`). Tous les chiffres de la ville sont
 * ici et nulle part ailleurs ; ils sont **à valider par Kr**.
 */
@Serializable
data class CityDefinitions(
    val version: Int,
    @SerialName("commentaire") val comment: String,
    @SerialName("ile") val grid: CityGridDefinition,
    @SerialName("zones") val zones: CityZonesDefinition,
    @SerialName("mairie") val townHall: CityTownHallDefinition,
    @SerialName("logement") val housing: CityHousingDefinition,
    /** Le plus que les bâtiments donnent ensemble pour chaque type de bonus : `LOGEMENTS` en places, les autres en pourcent. */
    @SerialName("plafonds") val caps: Map<BuildingBonusType, Double>,
    @SerialName("batiments") val buildings: List<BuildingDefinition>,
) {
    /** Le bâtiment [id], ou `null` s'il n'est pas dans le catalogue. */
    fun building(id: String): BuildingDefinition? = buildings.firstOrNull { it.id == id }

    /** Le plafond du bonus [type] ; le constructeur de [Definitions] a vérifié que chaque type en a un. */
    fun cap(type: BuildingBonusType): Double = caps.getValue(type)

    companion object {
        /** L'identifiant de la mairie dans le catalogue : la seule dont les niveaux ouvrent les zones. */
        const val TOWN_HALL_ID = "mairie"
    }
}

@Serializable
data class CityGridDefinition(
    /** Blocs sur le côté d'un chunk (16 dans Minecraft). */
    @SerialName("blocsParChunk") val blocksPerChunk: Int,
    /** Chunks sur le côté d'une parcelle : un bâtiment en occupe une. */
    @SerialName("parcelleChunks") val plotChunks: Int,
) {
    /** Blocs sur le côté d'une parcelle. */
    fun plotBlocks(): Int = blocksPerChunk * plotChunks
}

@Serializable
data class CityZonesDefinition(
    /** Zones sur un côté de l'île (4 : seize zones). */
    @SerialName("colonnes") val columns: Int,
    /** Chunks sur le côté d'une zone. */
    @SerialName("tailleChunks") val sizeChunks: Int,
    @SerialName("premiere") val first: FirstZoneDefinition,
    /** Les zones dans l'ordre où on les achète : la première entrée est la zone 1. */
    @SerialName("ordre") val order: List<ZoneDefinition>,
)

@Serializable
data class FirstZoneDefinition(
    /** Les usines actives qu'il faut avoir pour acheter la première zone. */
    @SerialName("usinesActivesMin") val activeFactoriesMin: Int,
)

@Serializable
data class ZoneDefinition(
    /** Colonne de la zone, depuis 0 au plus petit x. */
    @SerialName("colonne") val column: Int,
    /** Rangée de la zone, depuis 0 au plus petit z. */
    @SerialName("rangee") val row: Int,
    /** Le niveau de mairie qu'il faut avoir pour acheter la zone. */
    @SerialName("niveauMairieRequis") val townHallLevelRequired: Int,
)

@Serializable
data class CityTownHallDefinition(
    /** Où la mairie est posée, toute seule, à l'achat de la première zone. */
    @SerialName("emplacementDepart") val startPlot: PlotDefinition,
)

/** Une parcelle : son coin en blocs depuis le coin de l'île principale, et l'orientation du bâtiment. */
@Serializable
data class PlotDefinition(
    val x: Int,
    val z: Int,
    val rotation: Int,
)

@Serializable
data class CityHousingDefinition(
    /** Ce qu'un salarié logé coûte de moins : sa part du salaire en moins, en pourcent. */
    @SerialName("reductionSalairePourcent") val salaryDiscountPercent: Double,
)

/**
 * Ce qu'un bâtiment donne. [LOGEMENTS] : des places pour loger des salariés (leur salaire baisse). [PRODUCTION_DONAR] : un pourcentage de
 * production de Donar en plus, pour toute l'entreprise. [LIVRAISON_VITESSE] : un pourcentage de vitesse en plus pour les camions de
 * livraison. Jamais de Lingots (game-design.md, « Des bonus visibles »).
 */
@Serializable
enum class BuildingBonusType {
    @SerialName("LOGEMENTS")
    LOGEMENTS,

    @SerialName("PRODUCTION_DONAR")
    PRODUCTION_DONAR,

    @SerialName("LIVRAISON_VITESSE")
    LIVRAISON_VITESSE,
}

@Serializable
data class BuildingDefinition(
    /** Kebab-case, 32 caractères au plus (`city_buildings.building_type`). */
    val id: String,
    /** Vrai si le joueur le pose lui-même ; faux pour la mairie. */
    @SerialName("posable") val placeable: Boolean,
    /** Combien d'exemplaires une entreprise peut en avoir. */
    @SerialName("quantiteMax") val maxCount: Int,
    /** Ce que le bâtiment donne ; `null` : rien, c'est un décor (la route, la mairie). */
    @SerialName("typeBonus") val bonusType: BuildingBonusType? = null,
    /** Un niveau par entrée : la première est le niveau 1. */
    @SerialName("niveaux") val levels: List<BuildingLevelDefinition>,
) {
    /** Le niveau le plus haut du bâtiment. */
    fun maxLevel(): Int = levels.size

    /** Le niveau [level] (à partir de 1) ; une erreur de programmation s'il n'existe pas. */
    fun level(level: Int): BuildingLevelDefinition = levels[level - 1]
}

@Serializable
data class BuildingLevelDefinition(
    /** Les Lingots pour poser le bâtiment (niveau 1) ou pour passer à ce niveau. */
    @SerialName("prixLingots") val priceLingots: Long,
    /** Le niveau de mairie qu'il faut avoir pour le poser ou passer à ce niveau. */
    @SerialName("niveauMairieRequis") val townHallLevelRequired: Int,
    /** Le bonus total du bâtiment à ce niveau, dans l'unité de son type ; `null` si le bâtiment n'a pas de type de bonus. */
    val bonus: Double? = null,
)
