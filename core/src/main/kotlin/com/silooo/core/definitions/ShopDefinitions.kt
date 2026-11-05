package com.silooo.core.definitions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * La forme de definitions/boutique.json (schéma : definitions/schemas/boutique.schema.json). Voir
 * [EconomyDefinitions]. Le moteur d'économie n'en lit rien en phase 1 : la boutique est chargée avec
 * les autres fichiers pour que le démarrage échoue net si elle est abîmée.
 */
@Serializable
data class ShopDefinitions(
    val version: Int,
    @SerialName("commentaire") val comment: String,
    @SerialName("packsGemmes") val gemPacks: List<GemPackDefinition>,
    val grades: List<GradeDefinition>,
    @SerialName("regleGrades") val gradeRule: String,
    @SerialName("abonnements") val subscriptions: List<SubscriptionDefinition>,
)

@Serializable
data class GemPackDefinition(
    @SerialName("gemmes") val gems: Long,
    /** Prix en euros tel que le joueur le lit : virgule décimale, deux décimales (« 4,99 »). */
    @SerialName("euros") val euros: String,
    @SerialName("bonusPourcent") val bonusPercent: Int,
)

@Serializable
data class GradeDefinition(
    val id: String,
    @SerialName("nom") val name: String,
    @SerialName("gemmes") val gems: Long,
    @SerialName("bonusDonarPourcent") val donarBonusPercent: Int,
)

/** Un abonnement porte soit un bonus de Donar (avec les Gemmes rendues), soit une réserve à un grade. */
@Serializable
data class SubscriptionDefinition(
    val id: String,
    @SerialName("gemmes") val gems: Long,
    @SerialName("jours") val days: Int,
    @SerialName("bonusDonarPourcent") val donarBonusPercent: Int? = null,
    @SerialName("gemmesRendues") val gemsReturned: Long? = null,
    @SerialName("reserveA") val reservedForGradeId: String? = null,
)
