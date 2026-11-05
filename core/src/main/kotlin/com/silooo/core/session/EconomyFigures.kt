package com.silooo.core.session

import com.silooo.core.api.CityState
import com.silooo.core.api.Member
import com.silooo.core.api.Staff
import java.sql.Connection
import java.time.Instant

/**
 * Les chiffres d'une usine que le contrat demande (schéma `Factory`). [nominalRatePerHour] est le revenu de l'usine sans le
 * ralentissement d'un incident (le même que [ratePerHour] quand il n'y en a pas) : la récompense d'une réparation en dépend.
 * Une usine à l'arrêt faute de courant ([stoppedNoPower]) rapporte zéro, nominalement aussi.
 */
data class FactoryFigures(
    val ratePerHour: Double,
    val nextCost: Double,
    val nextMilestone: Int,
    val stoppedNoPower: Boolean = false,
    val nominalRatePerHour: Double = ratePerHour,
)

/**
 * Les chiffres d'une entreprise (schéma `CompanyState`), avec ceux de chacune de ses usines et de son personnel.
 * [ratePerHour] est net des salaires et bonus de production compris ; [staff] est le personnel embauché, avec ses
 * chiffres (salaire, bonus) déjà calculés par le moteur ; [members] sont ses membres, avec leur part de Lingots.
 */
class CompanyFigures(
    val ratePerHour: Double,
    val multiplier: Double,
    val threshold: Double,
    private val factories: Map<String, FactoryFigures>,
    val staff: List<Staff> = emptyList(),
    /** La ville, quand le moteur en a une source (voir `EngineFigures`) ; `null` sinon, et le champ n'est pas écrit. */
    val city: CityState? = null,
    val members: List<Member> = emptyList(),
) {
    /** Les chiffres de l'usine [factoryId]. Une usine que le moteur ne connaît pas n'a pas de chiffres : zéro. */
    fun forFactory(factoryId: String): FactoryFigures = factories[factoryId] ?: FactoryFigures(0.0, 0.0, 0)
}

/**
 * Comment se paie la production écoulée depuis `companies.last_collect_at`. Au moins un membre de l'entreprise
 * connecté pendant la période : [ONLINE]. Personne : [OFFLINE].
 */
enum class Regime {
    /** Revenu plein (`ratePerHour` fois les heures), sans plafond ni efficacité : ce que le compteur de l'add-on a affiché. */
    ONLINE,

    /** La formule du hors ligne : heures plafonnées par la colonne de l'entreprise, efficacité `horsLigne.efficacite`. */
    OFFLINE,
}

/**
 * La production qu'une entreprise a gagnée depuis son dernier calcul, telle que le moteur la verse
 * ([EconomyFigures.pendingProduction]).
 */
data class Production(
    /** Le Donar à verser. 0 quand rien n'est dû. */
    val earned: Double,
    /**
     * Les heures comptées : toutes les heures écoulées en régime [Regime.ONLINE], les heures plafonnées en régime
     * [Regime.OFFLINE]. C'est `offlineHours` dans la réponse de `collect`.
     */
    val countedHours: Double,
    /**
     * Le nouveau `companies.last_collect_at` : maintenant (à la milliseconde, la précision de la base), ou
     * l'ancienne valeur quand l'horloge du service est en retard sur la base, pour que la date ne recule jamais.
     */
    val at: Instant,
)

/**
 * Tout ce que `join` doit afficher et que le MOTEUR D'ÉCONOMIE calcule : le revenu par heure, le
 * multiplicateur, le seuil de prestige, le prix du prochain niveau et le prochain palier de chaque usine, et
 * le Donar gagné pendant l'absence. Ces chiffres viennent de `definitions/` et des formules de
 * `docs/economie.md` ; ils ne vivent pas dans les routes de session (un chiffre du jeu vit dans
 * `definitions/` et nulle part ailleurs). Les routes d'économie (`collect`, `buyLevels`, `restoreStep`) y
 * prennent aussi la production à verser ([pendingProduction]).
 *
 * L'implémentation du service est `com.silooo.core.economy.EngineFigures` ; les tests de routes passent leurs
 * faux à `Dependencies(economyFigures = ...)`. Il n'existe pas d'implémentation « tout à zéro » : un état sans
 * production n'est pas un état de jeu.
 */
interface EconomyFigures {
    /**
     * Les chiffres de [company] et de ses [factories] à cet instant. Lecture seule, dans la transaction de
     * [connection] : le multiplicateur lit les Lingots détenus par les membres.
     */
    fun forCompany(connection: Connection, company: CompanyRecord, factories: List<FactoryRecord>): CompanyFigures

    /**
     * Verse la production écoulée depuis `company.lastCollectAt`, selon [regime], et remet `last_collect_at` à
     * maintenant : `join` (régime selon la présence des autres membres) et `leave` (en ligne) l'appellent pour une
     * entreprise qui existait déjà, jamais à la création. Rend le Donar versé.
     *
     * Le moteur écrit dans la transaction de [connection], avec le verrou optimiste (`updateCompany`
     * avec `company.version`) : si l'appelant échoue ensuite, rien n'est versé. La date est remise à maintenant même
     * quand le gain est nul, pour qu'un `collect` ultérieur ne compte pas ces heures au revenu plein ; il n'y a
     * d'écriture que si du temps s'est écoulé (pas à la même milliseconde, pas quand l'horloge du service est en
     * retard sur la base). [playerLastSeenAt] est l'heure de départ figée par le dernier `leave` de CE joueur
     * (`null` s'il n'a jamais été vu) : l'implémentation du service ne s'en sert pas, la période se compte depuis
     * `company.lastCollectAt` seul (voir `EngineFigures.settle`).
     */
    fun settle(connection: Connection, company: CompanyRecord, regime: Regime, playerLastSeenAt: Instant? = null): Double

    /**
     * La production de [company] depuis `company.lastCollectAt` jusqu'à maintenant, avec la même règle que
     * [settle] (revenu d'aujourd'hui, puis [regime]), SANS rien écrire : la route (`collect`, `buyLevels`,
     * `restoreStep`) verse [Production.earned] elle-même, dans la même écriture que le reste de son action, pour
     * que la version de l'entreprise ne change qu'une fois. Ces routes passent [Regime.ONLINE] : l'acteur, par
     * construction, était connecté depuis le dernier calcul. [factories] sont les usines de l'entreprise telles que
     * la base les garde, avant l'action.
     */
    fun pendingProduction(connection: Connection, company: CompanyRecord, factories: List<FactoryRecord>, regime: Regime): Production
}
