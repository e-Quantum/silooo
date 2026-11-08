package com.silooo.core.city

import com.silooo.core.api.CompanyUpdated
import com.silooo.core.api.FactoryState
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.refuse
import com.silooo.core.db.InsufficientBalance
import com.silooo.core.db.Xuid
import com.silooo.core.definitions.MissionEvent
import com.silooo.core.economy.CompanyStore
import com.silooo.core.economy.EconomyEngine
import com.silooo.core.economy.RequestRejected
import com.silooo.core.economy.companyEvent
import com.silooo.core.economy.loadForActor
import com.silooo.core.economy.writeProduction
import com.silooo.core.events.GameEventKind
import com.silooo.core.mission.MissionService
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.EconomyFigures
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.Regime
import com.silooo.core.session.SessionStore
import com.silooo.core.session.buildCompanyState
import java.sql.Connection
import kotlinx.serialization.json.put

/**
 * La ville, sans HTTP : `buyZone`, `placeBuilding` et `upgradeBuilding`. Les routes valident l'entrée et répondent ; ce service
 * lit, décide et écrit, dans la transaction que l'appelant lui donne ([Connection]). Il ne contient aucun chiffre du jeu : prix,
 * niveaux, quantités et bonus viennent de [CityRules], qui les tire de `definitions/ville.json` et `economie.json`. Il ne contient
 * aucun texte pour les joueurs : l'add-on affiche ce que le contrat lui rend.
 *
 * Une action suit le déroulé de [com.silooo.core.staff.StaffService] :
 *  1. L'acteur est membre de l'entreprise ; sinon [RequestRejected] (400).
 *  2. Les refus du jeu (`Refusal`) tombent avant la première écriture, dans l'ordre de leur description dans l'OpenAPI, les Lingots
 *     en dernier ([requireLingots] lit le solde ; `creditBalance` le revérifie sous verrou et reste le filet si deux dépenses se croisent).
 *  3. La production en attente est versée d'abord ([EconomyFigures.pendingProduction], en ligne) avec le reste de l'action, en
 *     UNE écriture `updateCompany` (verrou optimiste) : les Lingots détenus font bouger le multiplicateur, et les bâtiments aussi,
 *     donc l'état d'avant a produit jusqu'à maintenant, jamais au-delà. Puis les Lingots sont retirés, la ville est écrite, puis
 *     l'événement.
 *  4. L'état rendu est bâti sur ce qui vient d'être écrit.
 *
 * Les règles que les définitions ne disent pas (tranchées au plus simple, **à valider par Kr**) :
 *  - tout membre de l'entreprise, PDG ou associé, peut acheter, poser et améliorer ; il paie avec SES Lingots (ils sont personnels) ;
 *  - une zone ne s'achète que dans l'ordre ; la première demande aussi des usines actives ;
 *  - l'achat de la première zone pose la mairie, au niveau 1, gratuite, à son emplacement de départ ;
 *  - le niveau de la mairie vit dans `companies.mairie_level` (le verrou optimiste le protège) ; la ligne de la mairie dans
 *    `city_buildings` le suit ;
 *  - faute de code dédié dans l'OpenAPI, un bâtiment que la mairie ne permet pas encore rend `ZONE_VERROUILLEE`, et un bâtiment
 *    déjà au maximum (en nombre, ou en niveau) rend `DEJA_POSSEDE`.
 */
class CityService(
    private val rules: CityRules,
    private val engine: EconomyEngine,
    private val sessions: SessionStore,
    private val companies: CompanyStore,
    private val city: CityStore,
    private val figures: EconomyFigures,
    /** Les missions du joueur : l'achat d'une zone en fait avancer une. `null` : pas de missions (les tests qui n'en ont pas besoin). */
    private val missions: MissionService? = null,
) {
    private val knownFactories: Set<String> = engine.factoryIds().toSet()

    /**
     * Achète la zone [zoneIndex] avec les Lingots de [actor]. Refus : `DEJA_POSSEDE` (déjà à l'entreprise), `ZONE_VERROUILLEE` (pas la
     * zone suivante, mairie trop basse, ou pas assez d'usines actives pour la première), `PAS_ASSEZ_DE_LINGOTS` avec la somme qui
     * manque. Un numéro hors des zones du jeu : [RequestRejected].
     */
    fun buyZone(connection: Connection, companyId: Long, zoneIndex: Int, actor: Xuid): CompanyUpdated {
        if (zoneIndex !in 1..rules.zoneCount) throw RequestRejected("Zone hors de 1..${rules.zoneCount} : $zoneIndex")
        val (company, factories) = sessions.loadForActor(connection, companyId, actor)

        val owned = sessions.loadZones(connection, companyId)
        if (zoneIndex in owned) refuse(RefusalCodes.ALREADY_OWNED)
        if (zoneIndex != rules.nextZoneIndex(owned)) refuse(RefusalCodes.ZONE_LOCKED)
        if (company.mairieLevel < rules.zone(zoneIndex).townHallLevelRequired) refuse(RefusalCodes.ZONE_LOCKED)
        val active = factories.count { it.state == FactoryState.ACTIVE && it.factoryId in knownFactories }
        if (active < rules.activeFactoriesRequired(zoneIndex)) refuse(RefusalCodes.ZONE_LOCKED)

        val price = rules.zonePrice(zoneIndex)
        requireLingots(connection, actor, price)
        val production = figures.pendingProduction(connection, company, factories, Regime.ONLINE)
        val after = writeProduction(connection, company, production, spent = 0.0)
        pay(connection, actor, price, REASON_ZONE, "$companyId/$zoneIndex")
        city.insertZone(connection, companyId, zoneIndex, actor, price)
        // La mairie arrive avec la première zone, au niveau 1, à l'emplacement de départ.
        if (owned.isEmpty()) {
            val start = rules.startPlot
            city.insertBuilding(connection, companyId, rules.townHall.id, 1, zoneIndex, start.x, start.z, start.rotation)
        }
        companies.publish(
            connection,
            EVENT_KIND,
            actor,
            companyEvent(ACTION_BUY_ZONE, companyId) {
                put("zoneIndex", zoneIndex)
                put("lingots", price)
            },
        )
        missions?.record(connection, actor, MissionEvent.BUY_ZONE)
        return updated(connection, actor, after, factories)
    }

    /**
     * Pose un [buildingType] du catalogue sur la parcelle dont le coin est `(x, z)`, au niveau 1, payé en Lingots par [actor]. Refus :
     * `ZONE_VERROUILLEE` (zone pas à l'entreprise, ou mairie trop basse pour ce bâtiment), `EMPLACEMENT_OCCUPE` (un bâtiment est
     * déjà sur la parcelle), `DEJA_POSSEDE` (autant de ce bâtiment que le catalogue le permet), `PAS_ASSEZ_DE_LINGOTS`. Un type
     * inconnu ou que le joueur ne pose pas, une parcelle hors de l'île ou mal alignée, une rotation hors 0/90/180/270 :
     * [RequestRejected].
     */
    fun placeBuilding(connection: Connection, companyId: Long, buildingType: String, x: Int, z: Int, rotation: Int, actor: Xuid): CompanyUpdated {
        val definition = rules.building(buildingType)?.takeIf { it.placeable }
            ?: throw RequestRejected("Bâtiment inconnu ou qu'on ne pose pas : $buildingType")
        val zoneIndex = rules.zoneOfPlot(x, z) ?: throw RequestRejected("Parcelle hors de l'île ou mal alignée : ($x, $z)")
        if (rotation !in ROTATIONS) throw RequestRejected("Rotation hors 0, 90, 180, 270 : $rotation")
        val (company, factories) = sessions.loadForActor(connection, companyId, actor)

        if (zoneIndex !in sessions.loadZones(connection, companyId)) refuse(RefusalCodes.ZONE_LOCKED)
        val first = definition.level(1)
        if (company.mairieLevel < first.townHallLevelRequired) refuse(RefusalCodes.ZONE_LOCKED)
        val buildings = city.loadBuildings(connection, companyId)
        if (buildings.any { it.x == x && it.z == z }) refuse(RefusalCodes.SPOT_TAKEN)
        if (buildings.count { it.buildingType == definition.id } >= definition.maxCount) refuse(RefusalCodes.ALREADY_OWNED)

        requireLingots(connection, actor, first.priceLingots)
        val production = figures.pendingProduction(connection, company, factories, Regime.ONLINE)
        val after = writeProduction(connection, company, production, spent = 0.0)
        val buildingId = city.insertBuilding(connection, companyId, definition.id, 1, zoneIndex, x, z, rotation)
        pay(connection, actor, first.priceLingots, REASON_BUILDING, "$companyId/$buildingId")
        companies.publish(
            connection,
            EVENT_KIND,
            actor,
            companyEvent(ACTION_PLACE_BUILDING, companyId) {
                put("buildingId", buildingId)
                put("buildingType", definition.id)
                put("zoneIndex", zoneIndex)
                put("x", x)
                put("z", z)
                put("rotation", rotation)
                put("lingots", first.priceLingots)
            },
        )
        return updated(connection, actor, after, factories)
    }

    /**
     * Passe le bâtiment [buildingId] au niveau suivant, payé en Lingots par [actor]. Pour la mairie, le nouveau niveau s'écrit aussi
     * dans `companies.mairie_level`, dans la même écriture que la production. Refus : `DEJA_POSSEDE` (déjà au dernier niveau),
     * `ZONE_VERROUILLEE` (mairie trop basse pour ce niveau), `PAS_ASSEZ_DE_LINGOTS`. Un bâtiment inconnu de l'entreprise, ou d'un
     * type qui a quitté `ville.json` : [RequestRejected].
     */
    fun upgradeBuilding(connection: Connection, companyId: Long, buildingId: Long, actor: Xuid): CompanyUpdated {
        val (company, factories) = sessions.loadForActor(connection, companyId, actor)
        val building = city.findBuilding(connection, companyId, buildingId)
            ?: throw RequestRejected("Bâtiment $buildingId inconnu de l'entreprise $companyId")
        val definition = rules.building(building.buildingType)
            ?: throw RequestRejected("Le bâtiment $buildingId est d'un type absent de ville.json : ${building.buildingType}")

        if (building.level >= definition.maxLevel()) refuse(RefusalCodes.ALREADY_OWNED)
        val nextLevel = building.level + 1
        val next = definition.level(nextLevel)
        if (company.mairieLevel < next.townHallLevelRequired) refuse(RefusalCodes.ZONE_LOCKED)

        requireLingots(connection, actor, next.priceLingots)
        val production = figures.pendingProduction(connection, company, factories, Regime.ONLINE)
        val isTownHall = definition.id == rules.townHall.id
        val after = writeProduction(connection, company, production, spent = 0.0, mairieLevel = if (isTownHall) nextLevel else null)
        pay(connection, actor, next.priceLingots, REASON_UPGRADE, "$companyId/$buildingId/$nextLevel")
        city.setBuildingLevel(connection, companyId, buildingId, nextLevel)
        companies.publish(
            connection,
            EVENT_KIND,
            actor,
            companyEvent(ACTION_UPGRADE_BUILDING, companyId) {
                put("buildingId", buildingId)
                put("buildingType", definition.id)
                put("level", nextLevel)
                put("lingots", next.priceLingots)
            },
        )
        return updated(connection, actor, after, factories)
    }

    /** Refuse `PAS_ASSEZ_DE_LINGOTS`, avec ce qui manque, si [actor] détient moins de [amount] Lingots : avant toute écriture. */
    private fun requireLingots(connection: Connection, actor: Xuid, amount: Long) {
        val held = sessions.loadBalances(connection, actor).lingots
        if (held < amount) refuse(RefusalCodes.NOT_ENOUGH_LINGOTS, missing = (amount - held).toDouble())
    }

    /** Retire [amount] Lingots à [actor] ; un solde trop juste devient le refus `PAS_ASSEZ_DE_LINGOTS`, avec ce qui manque. */
    private fun pay(connection: Connection, actor: Xuid, amount: Long, reason: String, refId: String) {
        if (amount <= 0L) return
        try {
            city.spendLingots(connection, actor, amount, reason, refId)
        } catch (e: InsufficientBalance) {
            throw e.toRefusal()
        }
    }

    private fun updated(connection: Connection, actor: Xuid, company: CompanyRecord, factories: List<FactoryRecord>): CompanyUpdated =
        CompanyUpdated(
            buildCompanyState(company, factories, figures.forCompany(connection, company, factories), sessions.loadZones(connection, company.id)),
            sessions.loadBalances(connection, actor),
        )

    companion object {
        /** Le type d'événement des trois actions : `ANNONCE` générique, le détail est dans `payload.action` (comme `collect`). */
        val EVENT_KIND = GameEventKind.ANNONCE

        /** Valeurs de `payload.action`. */
        const val ACTION_BUY_ZONE = "buyZone"
        const val ACTION_PLACE_BUILDING = "placeBuilding"
        const val ACTION_UPGRADE_BUILDING = "upgradeBuilding"

        /** Les motifs du journal des monnaies (`currency_ledger.reason`, 32 caractères au plus). */
        const val REASON_ZONE = "zone"
        const val REASON_BUILDING = "batiment"
        const val REASON_UPGRADE = "amelioration"

        /** Les rotations que le contrat accepte. */
        val ROTATIONS = setOf(0, 90, 180, 270)
    }
}
