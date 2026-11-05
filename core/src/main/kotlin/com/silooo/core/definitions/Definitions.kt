package com.silooo.core.definitions

import kotlin.math.floor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Un fichier de definitions/ manque, est abîmé, ou dit quelque chose d'impossible. Le message dit lequel et où. */
class DefinitionsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Tout ce que dit definitions/ : l'économie, les usines, la boutique, le monde, les missions, le personnel, les incidents, la ville, les votes. Chargé une fois au
 * démarrage ([DefinitionsLoader]) puis passé aux services ; jamais relu pendant que le service tourne.
 *
 * Une instance est toujours cohérente : le constructeur refuse les chiffres impossibles (croissance de
 * prix sous 1, usine de départ inconnue, seuils qui ne se suivent pas, récompense de mission introuvable…). Le
 * moteur peut donc s'appuyer sur ces garanties sans les revérifier.
 */
class Definitions(
    val economy: EconomyDefinitions,
    val factories: FactoriesDefinitions,
    val shop: ShopDefinitions,
    val world: WorldDefinitions,
    val missions: MissionsDefinitions,
    val personnel: PersonnelDefinitions,
    val incidents: IncidentsDefinitions,
    val city: CityDefinitions,
    val vote: VoteDefinitions,
) {
    /**
     * Les Lingots de chaque mission, par identifiant : le nombre que son `recompense` désigne dans economie.json. Le
     * chiffre reste dans economie.json ; la mission n'en garde que le chemin.
     */
    val missionRewards: Map<String, Long> = missions.missions.mapNotNull { mission ->
        DefinitionsChecks.economyInteger(economy, mission.reward)?.let { mission.id to it }
    }.toMap()

    /** Les ressources du jeu : chaque produit de usines.json, une fois, dans l'ordre du fichier. La commande du jour et le Carnet en tirent. */
    val resources: List<String> = factories.factories.flatMap { it.products }.distinct()

    init {
        val problems = DefinitionsChecks.problems(economy, factories, world, missions, personnel, incidents, city, vote)
        if (problems.isNotEmpty()) {
            throw DefinitionsException("Définitions incohérentes :\n" + problems.joinToString("\n") { "  - $it" })
        }
    }
}

/** Les contrôles de sens que la forme du JSON ne garantit pas. Les messages citent la clé du fichier. */
internal object DefinitionsChecks {
    /** La longueur de `player_missions.mission_id`. */
    const val MISSION_ID_MAX_LENGTH = 48

    /** La longueur de `factory_incidents.kind`. */
    const val INCIDENT_KIND_MAX_LENGTH = 16

    /** Les minutes d'une journée : une constante de la nature, pas un chiffre d'équilibrage. */
    const val MINUTES_PER_DAY = 24 * 60

    /** La longueur de `world_orders.resource_id` et de `player_findings.finding_id`. */
    const val RESOURCE_ID_MAX_LENGTH = 32

    /** La longueur de `city_buildings.building_type`. */
    const val BUILDING_ID_MAX_LENGTH = 32

    /** Le plus de zones que le contrat (`buyZone`, chemin `zoneIndex`) et `city_zones.zone_index` connaissent. */
    const val ZONES_MAX = 16

    private val MISSION_ID = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val economyTree = Json { encodeDefaults = true }

    /**
     * Le nombre entier positif ou nul que [path] (« lingotsHorsPrestige.missionBienvenue ») désigne dans economie.json,
     * ou `null` s'il n'y a rien à ce chemin, ou autre chose qu'un nombre entier. Un nombre à virgule qui tombe juste
     * (`1.0`) compte, comme pour le vérificateur de `tools/`.
     */
    fun economyInteger(economy: EconomyDefinitions, path: String): Long? {
        var node: JsonElement = economyTree.encodeToJsonElement(EconomyDefinitions.serializer(), economy)
        for (key in path.split('.')) node = (node as? JsonObject)?.get(key) ?: return null
        val number = (node as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
        number.longOrNull?.let { return it.takeIf { value -> value >= 0 } }
        val whole = number.doubleOrNull?.takeIf { it.isFinite() && it >= 0.0 && it == floor(it) && it < Long.MAX_VALUE.toDouble() }
        return whole?.toLong()
    }

    /** La plus grande place de la liste du jour que le contrat (`hire`, chemin `slot`) connaît. */
    const val AGENCY_SLOTS_MAX = 5

    /** Les longueurs de `staff.name` et `staff.skin_id`. */
    const val STAFF_NAME_MAX_LENGTH = 32
    const val STAFF_SKIN_MAX_LENGTH = 48

    private const val PERCENT_TOLERANCE = 1e-9

    fun problems(
        economy: EconomyDefinitions,
        factories: FactoriesDefinitions,
        world: WorldDefinitions,
        missions: MissionsDefinitions,
        personnel: PersonnelDefinitions,
        incidents: IncidentsDefinitions,
        city: CityDefinitions,
        vote: VoteDefinitions,
    ): List<String> {
        val problems = mutableListOf<String>()
        fun check(ok: Boolean, message: () -> String) {
            if (!ok) problems += message()
        }

        val levels = economy.levels
        check(levels.priceGrowth.isFinite() && levels.priceGrowth > 1.0) { "economie.json : niveaux.croissancePrix doit être un nombre au-dessus de 1" }
        check(levels.doublingMilestones.fixed.all { it >= 1 }) { "economie.json : niveaux.paliersX2.fixes ne contient que des niveaux à partir de 1" }
        check(levels.doublingMilestones.thenEvery >= 1) { "economie.json : niveaux.paliersX2.ensuiteTous doit être au moins 1" }

        val offline = economy.offline
        check(offline.capHours.isFinite() && offline.capHours > 0.0) { "economie.json : horsLigne.plafondHeures doit être positif" }
        check(offline.efficiency > 0.0 && offline.efficiency <= 1.0) { "economie.json : horsLigne.efficacite doit être entre 0 (exclu) et 1" }

        val prestige = economy.prestige
        check(prestige.thresholds.isNotEmpty()) { "economie.json : prestige.seuils ne doit pas être vide" }
        prestige.thresholds.forEachIndexed { index, threshold ->
            check(threshold.course == index + 1) {
                "economie.json : prestige.seuils[$index].course vaut ${threshold.course}, attendu ${index + 1} (les courses se suivent depuis 1)"
            }
            check(threshold.donar.isFinite() && threshold.donar > 0.0) { "economie.json : prestige.seuils[$index].donar doit être positif" }
        }
        check(prestige.nextThresholdFactor.isFinite() && prestige.nextThresholdFactor > 1.0) {
            "economie.json : prestige.facteurSeuilSuivant doit être un nombre au-dessus de 1"
        }
        check(prestige.lingotsConversion.coefficient > 0.0) { "economie.json : prestige.conversionLingots.coefficient doit être positif" }
        check(prestige.lingotsConversion.referenceDivisor > 0.0) { "economie.json : prestige.conversionLingots.diviseurReference doit être positif" }
        check(prestige.productionPerStar.isFinite() && prestige.productionPerStar > 0.0) { "economie.json : prestige.productionParEtoile doit être positif" }
        check(prestige.bonusPerHeldLingot.isFinite() && prestige.bonusPerHeldLingot >= 0.0) { "economie.json : prestige.bonusParLingotDetenu ne peut pas être négatif" }
        check(prestige.courseStart.level >= 1) { "economie.json : prestige.departDeCourse.niveau doit être au moins 1" }

        val outside = economy.lingotsOutsidePrestige
        check(outside.welcomeMission >= 0) { "economie.json : lingotsHorsPrestige.missionBienvenue ne peut pas être négatif" }
        check(outside.averagePerPlayHour.isFinite() && outside.averagePerPlayHour >= 0.0) {
            "economie.json : lingotsHorsPrestige.moyenneParHeureDeJeu ne peut pas être négatif"
        }

        check(outside.dailyOrder >= 0) { "economie.json : lingotsHorsPrestige.commandeDuJour ne peut pas être négatif" }
        check(outside.finding >= 0) { "economie.json : lingotsHorsPrestige.trouvaille ne peut pas être négatif" }
        val dailyOrder = economy.dailyOrder
        check(dailyOrder.targetUnits >= 1) { "economie.json : commandeDuJour.objectifUnites doit être au moins 1" }
        check(dailyOrder.minutesOfIncomePerUnit.isFinite() && dailyOrder.minutesOfIncomePerUnit > 0.0) {
            "economie.json : commandeDuJour.minutesDeProductionParUnite doit être positif"
        }

        check(economy.city.zonePricesLingots.isNotEmpty() && economy.city.zonePricesLingots.all { it >= 1 }) {
            "economie.json : ville.zonesPrixLingots doit lister des prix d'au moins 1 Lingot"
        }

        check(factories.factories.isNotEmpty()) { "usines.json : usines ne doit pas être vide" }
        val ids = factories.factories.map { it.id }
        check(ids.size == ids.toSet().size) { "usines.json : deux usines ont le même id" }
        factories.factories.forEach { factory ->
            check(factory.era >= 1) { "usines.json : ${factory.id}.epoque doit être au moins 1" }
            check(factory.level1Price.isFinite() && factory.level1Price > 0.0) { "usines.json : ${factory.id}.prixNiveau1 doit être positif" }
            check(factory.productionPerLevel.isFinite() && factory.productionPerLevel > 0.0) {
                "usines.json : ${factory.id}.productionParNiveau doit être positif"
            }
        }
        factories.factories.forEach { factory ->
            factory.products.filter { it.isBlank() || it.length > RESOURCE_ID_MAX_LENGTH }.forEach { product ->
                problems += "usines.json : ${factory.id}.produit « $product » doit faire de 1 à $RESOURCE_ID_MAX_LENGTH caractères"
            }
        }
        check(factories.factories.any { it.products.isNotEmpty() }) { "usines.json : aucune usine ne produit de ressource (la commande du jour n'aurait rien à demander)" }
        check(prestige.courseStart.factoryId in ids) {
            "economie.json : prestige.departDeCourse.usine « ${prestige.courseStart.factoryId} » n'est pas une usine de usines.json"
        }

        val grid = world.archipelago
        check(grid.slotSpacing >= 1) { "monde.json : archipel.ecartEntreCases doit être au moins 1" }
        check(grid.slotsPerRow >= 1) { "monde.json : archipel.casesParRangee doit être au moins 1" }
        check(world.company.maxMembers >= 1) { "monde.json : entreprise.membresMax doit être au moins 1" }
        check(world.company.invitationHours >= 1) { "monde.json : entreprise.invitationHeures doit être au moins 1" }

        check(missions.missions.isNotEmpty()) { "missions.json : missions ne doit pas être vide" }
        val missionIds = missions.missions.map { it.id }
        check(missionIds.size == missionIds.toSet().size) { "missions.json : deux missions ont le même id" }
        missions.missions.forEachIndexed { index, mission ->
            check(MISSION_ID.matches(mission.id) && mission.id.length <= MISSION_ID_MAX_LENGTH) {
                "missions.json : missions[$index].id « ${mission.id} » doit être en kebab-case, $MISSION_ID_MAX_LENGTH caractères au plus"
            }
            check(mission.target >= 1) { "missions.json : ${mission.id}.cible doit être au moins 1" }
            check(economyInteger(economy, mission.reward) != null) {
                "missions.json : ${mission.id}.recompense « ${mission.reward} » ne mène pas à un nombre entier de Lingots dans economie.json"
            }
        }

        personnelProblems(personnel, ::check)
        incidentsProblems(incidents, factories, ::check)
        cityProblems(city, economy, ::check)
        voteProblems(vote, ::check)
        return problems
    }

    private fun cityProblems(city: CityDefinitions, economy: EconomyDefinitions, check: (Boolean, () -> String) -> Unit) {
        val grid = city.grid
        check(grid.blocksPerChunk >= 1 && grid.plotChunks >= 1) { "ville.json : ile.blocsParChunk et ile.parcelleChunks doivent être au moins 1" }

        val zones = city.zones
        val count = zones.order.size
        check(zones.columns >= 1 && zones.sizeChunks >= 1) { "ville.json : zones.colonnes et zones.tailleChunks doivent être au moins 1" }
        check(count == zones.columns * zones.columns) {
            "ville.json : zones.ordre doit lister les ${zones.columns * zones.columns} zones du quadrillage, il en liste $count"
        }
        check(count == economy.city.zonePricesLingots.size) {
            "ville.json : zones.ordre liste $count zones mais economie.json : ville.zonesPrixLingots liste ${economy.city.zonePricesLingots.size} prix"
        }
        check(count in 1..ZONES_MAX) { "ville.json : zones.ordre doit lister de 1 à $ZONES_MAX zones (city_zones.zone_index, contrat buyZone)" }
        check(zones.first.activeFactoriesMin >= 0) { "ville.json : zones.premiere.usinesActivesMin ne peut pas être négatif" }
        check(grid.plotChunks >= 1 && zones.sizeChunks % grid.plotChunks == 0) {
            "ville.json : ile.parcelleChunks doit diviser zones.tailleChunks"
        }
        check(zones.order.map { it.column to it.row }.toSet().size == count && zones.order.all { it.column in 0 until zones.columns && it.row in 0 until zones.columns }) {
            "ville.json : zones.ordre doit lister chaque case du quadrillage une fois"
        }
        zones.order.forEachIndexed { index, zone ->
            check(zone.townHallLevelRequired >= 1) { "ville.json : zones.ordre[$index].niveauMairieRequis doit être au moins 1" }
            check(index == 0 || zone.townHallLevelRequired >= zones.order[index - 1].townHallLevelRequired) {
                "ville.json : zones.ordre[$index].niveauMairieRequis ne peut pas être plus bas que celui de la zone d'avant"
            }
        }

        val plot = city.townHall.startPlot
        val zoneBlocks = zones.sizeChunks * grid.blocksPerChunk
        val plotBlocks = grid.plotBlocks()
        check(plotBlocks >= 1 && plot.x >= 0 && plot.z >= 0 && plot.x % plotBlocks == 0 && plot.z % plotBlocks == 0) {
            "ville.json : mairie.emplacementDepart doit tomber sur une parcelle ($plotBlocks blocs)"
        }
        val first = zones.order.firstOrNull()
        check(first != null && zoneBlocks >= 1 && plot.x / zoneBlocks == first.column && plot.z / zoneBlocks == first.row) {
            "ville.json : mairie.emplacementDepart doit être dans la première zone de zones.ordre"
        }
        check(plot.rotation in ROTATIONS) { "ville.json : mairie.emplacementDepart.rotation doit valoir 0, 90, 180 ou 270" }

        val discount = city.housing.salaryDiscountPercent
        check(discount.isFinite() && discount in 0.0..100.0) { "ville.json : logement.reductionSalairePourcent doit être entre 0 et 100" }
        for (type in BuildingBonusType.entries) {
            val cap = city.caps[type]
            check(cap != null && cap.isFinite() && cap > 0.0) { "ville.json : plafonds.$type doit être un nombre positif" }
        }

        val ids = city.buildings.map { it.id }
        check(ids.size == ids.toSet().size) { "ville.json : deux bâtiments ont le même id" }
        val hall = city.building(CityDefinitions.TOWN_HALL_ID)
        check(hall != null && !hall.placeable && hall.maxCount == 1) {
            "ville.json : batiments doit avoir la mairie (${CityDefinitions.TOWN_HALL_ID}), non posable, en un seul exemplaire"
        }
        val hallLevels = hall?.maxLevel() ?: 0
        check(hallLevels >= (zones.order.maxOfOrNull { it.townHallLevelRequired } ?: 1)) {
            "ville.json : la mairie doit avoir au moins autant de niveaux que le plus haut niveauMairieRequis des zones"
        }
        city.buildings.forEach { building ->
            val id = building.id
            check(MISSION_ID.matches(id) && id.length <= BUILDING_ID_MAX_LENGTH) {
                "ville.json : batiments « $id » doit avoir un id en kebab-case, $BUILDING_ID_MAX_LENGTH caractères au plus"
            }
            check(building.maxCount >= 1) { "ville.json : $id.quantiteMax doit être au moins 1" }
            check(building.levels.isNotEmpty()) { "ville.json : $id.niveaux ne doit pas être vide" }
            building.levels.forEachIndexed { index, level ->
                val where = "$id.niveaux[$index]"
                check(level.priceLingots >= 0 && (level.priceLingots >= 1 || (id == CityDefinitions.TOWN_HALL_ID && index == 0))) {
                    "ville.json : $where.prixLingots doit être au moins 1 (seul le niveau 1 de la mairie est gratuit)"
                }
                check(level.townHallLevelRequired in 1..maxOf(hallLevels, 1)) {
                    "ville.json : $where.niveauMairieRequis doit être un niveau de mairie qui existe (1 à $hallLevels)"
                }
                check(index == 0 || level.townHallLevelRequired >= building.levels[index - 1].townHallLevelRequired) {
                    "ville.json : $where.niveauMairieRequis ne peut pas baisser d'un niveau à l'autre"
                }
                check((building.bonusType == null) == (level.bonus == null)) {
                    "ville.json : $where.bonus doit être présent si et seulement si $id a un typeBonus"
                }
                val bonus = level.bonus
                check(bonus == null || (bonus.isFinite() && bonus > 0.0)) { "ville.json : $where.bonus doit être positif" }
                check(bonus == null || index == 0 || bonus >= (building.levels[index - 1].bonus ?: 0.0)) {
                    "ville.json : $where.bonus ne peut pas baisser d'un niveau à l'autre"
                }
            }
        }
    }

    private val ROTATIONS = setOf(0, 90, 180, 270)

    /** La longueur de `server_votes.site`. */
    const val VOTE_SITE_MAX_LENGTH = 32

    /** La longueur du nom d'un site de vote (`serviceName`) ; c'est son `id` qui s'écrit dans `server_votes.site`. */
    const val VOTE_SITE_NAME_MAX_LENGTH = 64

    private fun voteProblems(vote: VoteDefinitions, check: (Boolean, () -> String) -> Unit) {
        check(vote.reward.lingotsPerVote >= 1) { "vote.json : recompense.lingotsParVote doit être au moins 1" }
        check(vote.streak.days >= 2) { "vote.json : serie.jours doit être au moins 2" }
        check(vote.streak.bonusLingots >= 1) { "vote.json : serie.lingotsEnPlus doit être au moins 1" }
        check(vote.party.votesPerDay >= 1) { "vote.json : voteParty.votesParJour doit être au moins 1" }
        check(vote.party.lingotsPerPlayer >= 1) { "vote.json : voteParty.lingotsParJoueur doit être au moins 1" }
        check(vote.sites.isNotEmpty()) { "vote.json : sites ne doit pas être vide" }
        check(vote.sites.map { it.id }.let { it.size == it.toSet().size }) { "vote.json : deux sites ont le même id" }
        vote.sites.forEachIndexed { index, site ->
            check(MISSION_ID.matches(site.id) && site.id.length <= VOTE_SITE_MAX_LENGTH) {
                "vote.json : sites[$index].id « ${site.id} » doit être en kebab-case, $VOTE_SITE_MAX_LENGTH caractères au plus"
            }
            check(site.name.isNotBlank() && site.name.length <= VOTE_SITE_NAME_MAX_LENGTH) {
                "vote.json : sites[$index].nom doit avoir de 1 à $VOTE_SITE_NAME_MAX_LENGTH caractères"
            }
        }
    }

    private fun incidentsProblems(incidents: IncidentsDefinitions, factories: FactoriesDefinitions, check: (Boolean, () -> String) -> Unit) {
        val frequency = incidents.frequency
        check(frequency.slotMinutes in 1..MINUTES_PER_DAY && MINUTES_PER_DAY % frequency.slotMinutes == 0) {
            "incidents.json : frequence.creneauMinutes doit diviser une journée ($MINUTES_PER_DAY minutes)"
        }
        check(frequency.chancePercent.isFinite() && frequency.chancePercent > 0.0 && frequency.chancePercent <= 100.0) {
            "incidents.json : frequence.chancePourcent doit être entre 0 (exclu) et 100"
        }

        check(incidents.kinds.isNotEmpty()) { "incidents.json : sortes ne doit pas être vide" }
        check(incidents.kinds.map { it.id }.let { it.size == it.toSet().size }) { "incidents.json : deux sortes ont le même id" }
        incidents.kinds.forEachIndexed { index, kind ->
            check(MISSION_ID.matches(kind.id) && kind.id.length <= INCIDENT_KIND_MAX_LENGTH) {
                "incidents.json : sortes[$index].id « ${kind.id} » doit être en kebab-case, $INCIDENT_KIND_MAX_LENGTH caractères au plus"
            }
            check(kind.slowdown.isFinite() && kind.slowdown > 0.0 && kind.slowdown < 1.0) {
                "incidents.json : ${kind.id}.ralentissement doit être strictement entre 0 et 1"
            }
            check(kind.slowdown * 100.0 - Math.rint(kind.slowdown * 100.0) in -PERCENT_TOLERANCE..PERCENT_TOLERANCE) {
                "incidents.json : ${kind.id}.ralentissement a plus de deux décimales (colonne factory_incidents.slowdown)"
            }
            check(kind.weight >= 1) { "incidents.json : ${kind.id}.poids doit être au moins 1" }
        }

        check(incidents.reward.revenueMinutes.isFinite() && incidents.reward.revenueMinutes > 0.0) {
            "incidents.json : recompense.minutesDeRevenu doit être positif"
        }

        val electricity = incidents.electricity
        check(electricity.fromEra >= 1) { "incidents.json : electricite.aPartirDeEpoque doit être au moins 1" }
        val powerPlant = factories.factories.firstOrNull { it.id == electricity.powerPlantId }
        check(powerPlant != null) { "incidents.json : electricite.usineCentrale « ${electricity.powerPlantId} » n'est pas une usine de usines.json" }
        check(powerPlant == null || !powerPlant.needsElectricity) {
            "incidents.json : electricite.usineCentrale « ${electricity.powerPlantId} » dépend elle-même de l'électricité"
        }
    }

    private fun personnelProblems(personnel: PersonnelDefinitions, check: (Boolean, () -> String) -> Unit) {
        val agency = personnel.agency
        check(agency.candidatesPerDay in 1..AGENCY_SLOTS_MAX) { "personnel.json : agence.candidatsParJour doit être entre 1 et $AGENCY_SLOTS_MAX" }
        check(agency.rolesPerSlot.size == agency.candidatesPerDay) {
            "personnel.json : agence.rolesParPlace doit avoir une entrée par place (${agency.candidatesPerDay}), il en a ${agency.rolesPerSlot.size}"
        }
        check(agency.names.size == agency.names.toSet().size && agency.names.size >= agency.candidatesPerDay) {
            "personnel.json : agence.noms doit lister au moins ${agency.candidatesPerDay} noms, tous différents"
        }
        check(agency.names.all { it.isNotBlank() && it.length <= STAFF_NAME_MAX_LENGTH }) {
            "personnel.json : agence.noms ne contient que des noms de 1 à $STAFF_NAME_MAX_LENGTH caractères"
        }
        for (role in agency.rolesPerSlot.toSet()) {
            val skins = agency.appearances[role].orEmpty()
            check(skins.isNotEmpty() && skins.size == skins.toSet().size) {
                "personnel.json : agence.apparences.$role doit lister au moins une apparence, sans doublon"
            }
            check(skins.all { MISSION_ID.matches(it) && it.length <= STAFF_SKIN_MAX_LENGTH }) {
                "personnel.json : agence.apparences.$role ne contient que des identifiants en kebab-case de $STAFF_SKIN_MAX_LENGTH caractères au plus"
            }
            check(personnel.roles.containsKey(role)) { "personnel.json : roles.$role manque alors que l'agence le propose" }
        }

        check(personnel.rarities.keys == StaffRarity.entries.toSet()) { "personnel.json : raretes doit avoir exactement ${StaffRarity.entries.joinToString(", ")}" }
        val total = personnel.rarities.values.sumOf { it.sharePercent }
        check(kotlin.math.abs(total - 100.0) <= PERCENT_TOLERANCE) {
            "personnel.json : les parts raretes.*.repartitionPourcent doivent faire 100, elles font $total"
        }
        for (rarity in StaffRarity.entries) {
            val definition = personnel.rarities[rarity] ?: continue
            check(definition.sharePercent.isFinite() && definition.sharePercent in 0.0..100.0) {
                "personnel.json : raretes.$rarity.repartitionPourcent doit être entre 0 et 100"
            }
            check(definition.productionBonusPercent.isFinite() && definition.productionBonusPercent > 0.0) {
                "personnel.json : raretes.$rarity.bonusProductionPourcent doit être positif"
            }
            check(definition.salaryPercent.isFinite() && definition.salaryPercent >= 0.0) {
                "personnel.json : raretes.$rarity.salairePourcent ne peut pas être négatif"
            }
            check(definition.hireCostHours.isFinite() && definition.hireCostHours > 0.0) {
                "personnel.json : raretes.$rarity.coutHeuresDeRevenu doit être positif"
            }
            for ((role, factors) in personnel.roles) {
                check(definition.salaryPercent * factors.salaryFactor < definition.productionBonusPercent) {
                    "personnel.json : raretes.$rarity : le salaire d'un $role doit rester sous son bonus de production"
                }
            }
        }
        for ((role, factors) in personnel.roles) {
            check(factors.costFactor.isFinite() && factors.costFactor > 0.0) { "personnel.json : roles.$role.coutFacteur doit être positif" }
            check(factors.salaryFactor.isFinite() && factors.salaryFactor >= 0.0) { "personnel.json : roles.$role.salaireFacteur ne peut pas être négatif" }
        }
        check(personnel.roles.keys == StaffRole.entries.toSet()) { "personnel.json : roles doit avoir exactement ${StaffRole.entries.joinToString(", ")}" }

        check(personnel.posts.baseEmployees >= 0) { "personnel.json : postes.salariesBase ne peut pas être négatif" }
        check(personnel.posts.levelsPerPost >= 1) { "personnel.json : postes.niveauxParPoste doit être au moins 1" }
        check(personnel.posts.leadersPerFactory >= 0) { "personnel.json : postes.chefsParUsine ne peut pas être négatif" }
        check(personnel.teamLeader.offlineCapBonusHours.isFinite() && personnel.teamLeader.offlineCapBonusHours >= 0.0) {
            "personnel.json : chefEquipe.plafondHorsLigneBonusHeures ne peut pas être négatif"
        }
    }
}
