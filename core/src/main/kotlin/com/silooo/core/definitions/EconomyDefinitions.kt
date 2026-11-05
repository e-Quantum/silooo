package com.silooo.core.definitions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * La forme de definitions/economie.json, clé par clé (schéma : definitions/schemas/economie.schema.json).
 *
 * Les identifiants sont en anglais (ADR 0005), les clés du fichier restent en français : Kr les édite.
 * Aucune propriété n'a de valeur par défaut, exprès : une clé absente du fichier fait échouer le
 * chargement au démarrage au lieu de laisser le moteur tourner sur un chiffre inventé. Une clé en
 * trop échoue aussi : le fichier et cette classe disent la même chose.
 */

@Serializable
data class EconomyDefinitions(
    val version: Int,
    @SerialName("niveaux") val levels: LevelsDefinition,
    @SerialName("horsLigne") val offline: OfflineDefinition,
    val prestige: PrestigeDefinition,
    @SerialName("lingotsHorsPrestige") val lingotsOutsidePrestige: LingotsOutsidePrestigeDefinition,
    @SerialName("ville") val city: CityDefinition,
    val notation: NotationDefinition,
)

@Serializable
data class LevelsDefinition(
    /** Chaque niveau coûte ce multiple du précédent. */
    @SerialName("croissancePrix") val priceGrowth: Double,
    /** Texte de la formule, pour les humains : le moteur ne le lit pas. */
    @SerialName("formulePrix") val priceFormula: String,
    @SerialName("paliersX2") val doublingMilestones: DoublingMilestonesDefinition,
)

@Serializable
data class DoublingMilestonesDefinition(
    /** Niveaux où la production double, avant le pas régulier. */
    @SerialName("fixes") val fixed: List<Int>,
    /** Puis la production double à chaque multiple de ce nombre de niveaux (lecture du tableur : 100, 200…). */
    @SerialName("ensuiteTous") val thenEvery: Int,
    @SerialName("commentaire") val comment: String,
)

@Serializable
data class OfflineDefinition(
    /** Plafond de départ, en heures, de la production hors ligne. */
    @SerialName("plafondHeures") val capHours: Double,
    /** Part de la production en ligne obtenue hors ligne. */
    @SerialName("efficacite") val efficiency: Double,
)

@Serializable
data class PrestigeDefinition(
    /** Un seuil par course, dans l'ordre : la course 1 d'abord. */
    @SerialName("seuils") val thresholds: List<PrestigeThresholdDefinition>,
    /** Texte, pour les humains : les seuils au-delà de la dernière course listée. Le moteur lit [nextThresholdFactor]. */
    @SerialName("etoilesSuivantes") val nextStars: String,
    /**
     * Au-delà de la dernière course listée dans [thresholds], chaque seuil vaut le précédent multiplié par ce
     * facteur : la lecture chiffrée de [nextStars]. À valider par Kr.
     */
    @SerialName("facteurSeuilSuivant") val nextThresholdFactor: Double,
    @SerialName("conversionLingots") val lingotsConversion: LingotsConversionDefinition,
    /** Facteur de production gagné à chaque étoile. */
    @SerialName("productionParEtoile") val productionPerStar: Double,
    /** Bonus de production (fraction) pour chaque Lingot détenu. */
    @SerialName("bonusParLingotDetenu") val bonusPerHeldLingot: Double,
    @SerialName("departDeCourse") val courseStart: CourseStartDefinition,
)

@Serializable
data class PrestigeThresholdDefinition(
    val course: Int,
    @SerialName("fin") val end: String,
    /** Donar gagnés dans la course pour avoir le droit de prestige. */
    val donar: Double,
)

@Serializable
data class LingotsConversionDefinition(
    val coefficient: Double,
    @SerialName("diviseurReference") val referenceDivisor: Double,
    /** Texte de la formule, pour les humains : le moteur ne le lit pas. */
    @SerialName("formule") val formula: String,
)

/** L'usine déjà posée au début de chaque course, et son niveau. Les autres repartent à zéro. */
@Serializable
data class CourseStartDefinition(
    @SerialName("usine") val factoryId: String,
    @SerialName("niveau") val level: Int,
)

@Serializable
data class LingotsOutsidePrestigeDefinition(
    @SerialName("missionBienvenue") val welcomeMission: Long,
    @SerialName("moyenneParHeureDeJeu") val averagePerPlayHour: Double,
)

@Serializable
data class CityDefinition(
    /** Le prix en Lingots de chaque zone, dans l'ordre. */
    @SerialName("zonesPrixLingots") val zonePricesLingots: List<Long>,
)

@Serializable
data class NotationDefinition(
    @SerialName("apresMd") val afterMd: String,
    @SerialName("limiteDouble") val doubleLimit: String,
)
