package com.silooo.core.definitions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * La forme de definitions/boutique.json (schéma : definitions/schemas/boutique.schema.json). Voir
 * [EconomyDefinitions]. Les packs de Gemmes, les grades, les abonnements, les cosmétiques et le boost
 * collectif : tous les prix du jeu en Gemmes vivent ici et nulle part ailleurs.
 */
@Serializable
data class ShopDefinitions(
    val version: Int,
    @SerialName("commentaire") val comment: String,
    @SerialName("packsGemmes") val gemPacks: List<GemPackDefinition>,
    val grades: List<GradeDefinition>,
    @SerialName("regleGrades") val gradeRule: String,
    @SerialName("abonnements") val subscriptions: List<SubscriptionDefinition>,
    @SerialName("cosmetiques") val cosmetics: List<CosmeticDefinition>,
    @SerialName("boostCollectif") val collectiveBoost: CollectiveBoostDefinition,
) {
    companion object {
        /** L'identifiant de l'article « boost collectif » dans `buyItem` : le boost n'est pas une liste, il n'a pas d'entrée à lui dans le fichier. */
        const val BOOST_ID = "boost-collectif"

        /** Le titre que le site donne aux pionniers : le service l'écrit dans `player_cosmetics` à la connexion. */
        const val PIONEER_TITLE_ID = "pionnier"
    }
}

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
    /** Le nom que les joueurs voient. À valider par Kr. */
    @SerialName("nom") val name: String,
    @SerialName("gemmes") val gems: Long,
    @SerialName("jours") val days: Int,
    @SerialName("bonusDonarPourcent") val donarBonusPercent: Int? = null,
    @SerialName("gemmesRendues") val gemsReturned: Long? = null,
    @SerialName("reserveA") val reservedForGradeId: String? = null,
)

/** Les rayons de la boutique de cosmétiques. */
@Serializable
enum class CosmeticCategory {
    @SerialName("skinUsine")
    FACTORY_SKIN,

    @SerialName("skinVehicule")
    VEHICLE_SKIN,

    @SerialName("pet")
    PET,

    @SerialName("decor")
    DECOR,

    @SerialName("effetPrestige")
    PRESTIGE_EFFECT,

    @SerialName("titre")
    TITLE,
}

/**
 * Un cosmétique. Un cosmétique non achetable (`purchasable` faux, prix 0) n'est pas vendu : le jeu ou le site le
 * donne, comme le titre Pionnier.
 */
@Serializable
data class CosmeticDefinition(
    /** Aussi la valeur de `player_cosmetics.cosmetic_id`. */
    val id: String,
    /** Le nom que les joueurs voient. À valider par Kr. */
    @SerialName("nom") val name: String,
    @SerialName("categorie") val category: CosmeticCategory,
    @SerialName("prixGemmes") val gems: Long,
    @SerialName("achetable") val purchasable: Boolean,
)

/** Le boost collectif : la production de tout le serveur monte de [bonusPercent] pendant [durationMinutes] minutes. */
@Serializable
data class CollectiveBoostDefinition(
    @SerialName("prixGemmes") val gems: Long,
    @SerialName("bonusPourcent") val bonusPercent: Int,
    @SerialName("dureeMinutes") val durationMinutes: Long,
)
