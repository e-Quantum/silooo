package com.silooo.core.definitions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * La forme de definitions/vote.json (schéma : definitions/schemas/vote.schema.json). Voir [EconomyDefinitions].
 * Tous les chiffres des votes et de la Vote party sont ici et nulle part ailleurs ; ils sont **à valider par Kr**.
 * Les Lingots versés sont toujours hors prestige.
 */
@Serializable
data class VoteDefinitions(
    val version: Int,
    @SerialName("commentaire") val comment: String,
    @SerialName("recompense") val reward: VoteReward,
    @SerialName("serie") val streak: VoteStreak,
    @SerialName("voteParty") val party: VoteParty,
    /** Les sites de vote connus ; un service inconnu est accepté tel qu'il s'annonce. */
    @SerialName("sites") val sites: List<VoteSiteDefinition>,
) {
    /**
     * L'identifiant de site que `server_votes.site` garde pour un vote venu du service [serviceName] : l'`id` du site connu
     * dont l'`id` ou le `nom` correspond (casse ignorée), ou le nom tel qu'il s'annonce quand aucun ne correspond.
     */
    fun siteFor(serviceName: String): String {
        val name = serviceName.trim()
        return sites.firstOrNull { it.id.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) }?.id ?: name
    }
}

/** Ce que donne un vote reçu. */
@Serializable
data class VoteReward(
    /** Les Lingots versés au joueur pour chaque vote. */
    @SerialName("lingotsParVote") val lingotsPerVote: Long,
)

/** Le bonus de série : des jours UTC de vote de suite. */
@Serializable
data class VoteStreak(
    /** Le nombre de jours de suite qui donne le bonus au vote de ce jour-là, puis à chaque multiple. */
    @SerialName("jours") val days: Int,
    /** Les Lingots de plus pour le vote qui boucle la série. */
    @SerialName("lingotsEnPlus") val bonusLingots: Long,
)

/** La Vote party : un objectif de votes pour tout le serveur dans un jour UTC. */
@Serializable
data class VoteParty(
    /** Le nombre de votes du jour qui déclenche la fête. */
    @SerialName("votesParJour") val votesPerDay: Int,
    /** Les Lingots que reçoit chaque joueur présent quand la fête a lieu. */
    @SerialName("lingotsParJoueur") val lingotsPerPlayer: Long,
)

/** Un site de vote connu. */
@Serializable
data class VoteSiteDefinition(
    /** L'identifiant que `server_votes.site` garde : minuscules et tirets, 32 caractères au plus. */
    val id: String,
    /** Le nom tel que le service de vote l'annonce (`serviceName`). */
    @SerialName("nom") val name: String,
)
