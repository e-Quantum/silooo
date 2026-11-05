package com.silooo.core.definitions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * La forme de definitions/personnel.json (schéma : definitions/schemas/personnel.schema.json). Voir [EconomyDefinitions].
 * Tous les chiffres du personnel sont ici et nulle part ailleurs ; ils sont **à valider par Kr**.
 */
@Serializable
data class PersonnelDefinitions(
    val version: Int,
    @SerialName("agence") val agency: AgencyDefinition,
    /** Les trois raretés, par code. */
    @SerialName("raretes") val rarities: Map<StaffRarity, RarityDefinition>,
    @SerialName("roles") val roles: Map<StaffRole, RoleDefinition>,
    @SerialName("postes") val posts: PostsDefinition,
    @SerialName("chefEquipe") val teamLeader: TeamLeaderDefinition,
) {
    /** La rareté [rarity] ; le constructeur de [Definitions] a vérifié que les trois y sont. */
    fun rarity(rarity: StaffRarity): RarityDefinition = rarities.getValue(rarity)

    fun role(role: StaffRole): RoleDefinition = roles.getValue(role)
}

/** Le personnel d'usine que le jeu sait faire embaucher. `DIRECTEUR` du contrat n'est pas encore modélisé. */
@Serializable
enum class StaffRole {
    @SerialName("SALARIE")
    SALARIE,

    @SerialName("CHEF")
    CHEF,
}

/**
 * Les raretés du personnel, de la plus courante à la plus rare : l'ordre de déclaration est celui du tirage. `EPIQUE`
 * est la troisième, que le game design appelle « légendaire » (nom affiché à confirmer par Kr).
 */
@Serializable
enum class StaffRarity {
    @SerialName("COMMUN")
    COMMUN,

    @SerialName("RARE")
    RARE,

    @SerialName("EPIQUE")
    EPIQUE,
}

@Serializable
data class AgencyDefinition(
    /** Candidats proposés chaque jour. */
    @SerialName("candidatsParJour") val candidatesPerDay: Int,
    /** Le rôle de chaque place de la liste du jour, dans l'ordre des places (la première place est la 1). */
    @SerialName("rolesParPlace") val rolesPerSlot: List<StaffRole>,
    /** Les prénoms tirés. */
    @SerialName("noms") val names: List<String>,
    /** Les apparences (`skinId`) tirées, par rôle. */
    @SerialName("apparences") val appearances: Map<StaffRole, List<String>>,
)

@Serializable
data class RarityDefinition(
    /** Le nom que les joueurs voient pour cette rareté (la clé du fichier reste le code). À valider par Kr. */
    @SerialName("nom") val name: String,
    /** Part des candidats de cette rareté, en pourcent. */
    @SerialName("repartitionPourcent") val sharePercent: Double,
    /** Gain de production de l'usine où le membre travaille, en pourcent de sa production sans lui. */
    @SerialName("bonusProductionPourcent") val productionBonusPercent: Double,
    /** Salaire par heure, en pourcent de la production de l'usine sans le membre, avant le facteur du rôle. */
    @SerialName("salairePourcent") val salaryPercent: Double,
    /** Prix d'embauche en heures de revenu de l'entreprise, avant le facteur du rôle. */
    @SerialName("coutHeuresDeRevenu") val hireCostHours: Double,
)

@Serializable
data class RoleDefinition(
    @SerialName("coutFacteur") val costFactor: Double,
    @SerialName("salaireFacteur") val salaryFactor: Double,
)

@Serializable
data class PostsDefinition(
    /** Postes de salarié d'une usine active, quel que soit son niveau. */
    @SerialName("salariesBase") val baseEmployees: Int,
    /** Un poste de salarié de plus tous les N niveaux. */
    @SerialName("niveauxParPoste") val levelsPerPost: Int,
    /** Postes de chef d'équipe d'une usine active. */
    @SerialName("chefsParUsine") val leadersPerFactory: Int,
)

@Serializable
data class TeamLeaderDefinition(
    /** Heures ajoutées au plafond hors ligne d'une usine qui a un chef d'équipe. */
    @SerialName("plafondHorsLigneBonusHeures") val offlineCapBonusHours: Double,
)
