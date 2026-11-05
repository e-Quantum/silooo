package com.silooo.core.definitions

import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * La forme de definitions/pass.json (schéma : definitions/schemas/pass.schema.json). Voir [EconomyDefinitions].
 * Tous les chiffres du pass de saison sont ici et nulle part ailleurs ; ils sont **à valider par Kr**. La piste
 * premium ne donne que des cosmétiques : la forme du fichier et les contrôles de [Definitions] l'imposent.
 */
@Serializable
data class PassDefinitions(
    val version: Int,
    @SerialName("commentaire") val comment: String,
    /** La saison en cours : une seule à la fois, on en ouvre une autre en remplaçant le fichier. */
    @SerialName("saison") val season: PassSeasonDefinition,
    @SerialName("premium") val premium: PassPremiumDefinition,
    @SerialName("points") val points: PassPointsDefinition,
    /** Les paliers, du premier au dernier : `tier` vaut 1, 2, 3… et `points` croît strictement. */
    @SerialName("paliers") val tiers: List<PassTierDefinition>,
) {
    /** Le palier numéro [tier] (à partir de 1), ou `null` s'il n'existe pas. */
    fun tier(tier: Int): PassTierDefinition? = tiers.getOrNull(tier - 1)?.takeIf { it.tier == tier }
}

/** La saison : son identifiant (`season_pass.season_id`), son nom et ses jours UTC de début et de fin, compris. */
@Serializable
data class PassSeasonDefinition(
    val id: String,
    @SerialName("nom") val name: String,
    @SerialName("debut") val start: String,
    @SerialName("fin") val end: String,
) {
    /** Le premier jour de la saison. [Definitions] a déjà refusé une date qui n'existe pas. */
    val startDay: LocalDate get() = LocalDate.parse(start)

    /** Le dernier jour de la saison. */
    val endDay: LocalDate get() = LocalDate.parse(end)

    /** La saison court le [day] (jour UTC) : du premier au dernier jour, compris. */
    fun covers(day: LocalDate): Boolean = !day.isBefore(startDay) && !day.isAfter(endDay)
}

/** La piste premium, achetée en Gemmes. */
@Serializable
data class PassPremiumDefinition(
    /** Le prix de la piste premium pour la saison. */
    @SerialName("gemmes") val gems: Long,
)

/** Les points de chaque action. */
@Serializable
data class PassPointsDefinition(
    /** Pour chaque associé de l'entreprise qui fait un prestige. */
    @SerialName("parPrestige") val perPrestige: Int,
    /** Pour chaque joueur qui a livré à la commande du jour quand elle se termine. */
    @SerialName("parCommande") val perOrder: Int,
    /** Pour une mission terminée. */
    @SerialName("parMission") val perMission: Int,
)

/** Un palier : le total de points à atteindre et, par piste, au plus une récompense. */
@Serializable
data class PassTierDefinition(
    @SerialName("palier") val tier: Int,
    /** Le total de points du joueur à atteindre. */
    val points: Int,
    @SerialName("gratuit") val free: PassFreeRewardDefinition? = null,
    @SerialName("premium") val premium: PassPremiumRewardDefinition? = null,
)

/** La récompense de la piste gratuite : des Lingots hors prestige. */
@Serializable
data class PassFreeRewardDefinition(
    val lingots: Long,
)

/** La récompense de la piste premium : un cosmétique, jamais de monnaie. */
@Serializable
data class PassPremiumRewardDefinition(
    @SerialName("cosmetique") val cosmeticId: String,
)
