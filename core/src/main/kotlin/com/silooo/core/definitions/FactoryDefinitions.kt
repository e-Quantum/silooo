package com.silooo.core.definitions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** La forme de definitions/usines.json (schéma : definitions/schemas/usines.schema.json). Voir [EconomyDefinitions]. */
@Serializable
data class FactoriesDefinitions(
    val version: Int,
    val source: String,
    @SerialName("unites") val units: FactoryUnitsDefinition,
    @SerialName("usines") val factories: List<FactoryDefinition>,
)

@Serializable
data class FactoryUnitsDefinition(
    @SerialName("prixNiveau1") val level1Price: String,
    @SerialName("productionParNiveau") val productionPerLevel: String,
)

@Serializable
data class FactoryDefinition(
    /** L'identifiant que la base garde (`company_factories.factory_id`). Il ne change jamais. */
    val id: String,
    @SerialName("nom") val name: String,
    @SerialName("epoque") val era: Int,
    @SerialName("produit") val products: List<String>,
    @SerialName("entrees") val inputs: List<String>,
    @SerialName("decouverte") val discovery: String,
    /** Prix du niveau 1, en Donar. */
    @SerialName("prixNiveau1") val level1Price: Double,
    /** Production par niveau et par heure de jeu, en Donar, salaires déduits, avant multiplicateurs. */
    @SerialName("productionParNiveau") val productionPerLevel: Double,
    @SerialName("electricite") val needsElectricity: Boolean,
)
