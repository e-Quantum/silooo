package com.silooo.core.session

import java.sql.Connection
import java.time.Instant

/** Les chiffres d'une usine que le contrat demande (schéma `Factory`). */
data class FactoryFigures(val ratePerHour: Double, val nextCost: Double, val nextMilestone: Int, val stoppedNoPower: Boolean = false)

/** Les chiffres d'une entreprise (schéma `CompanyState`), avec ceux de chacune de ses usines. */
class CompanyFigures(
    val ratePerHour: Double,
    val multiplier: Double,
    val threshold: Double,
    private val factories: Map<String, FactoryFigures>,
) {
    /** Les chiffres de l'usine [factoryId]. Une usine que le moteur ne connaît pas n'a pas de chiffres : zéro. */
    fun forFactory(factoryId: String): FactoryFigures = factories[factoryId] ?: FactoryFigures(0.0, 0.0, 0)
}

/**
 * Tout ce que `join` doit afficher et que le MOTEUR D'ÉCONOMIE calcule : le revenu par heure, le
 * multiplicateur, le seuil de prestige, le prix du prochain niveau et le prochain palier de chaque usine, et
 * le Donar gagné pendant l'absence. Ces chiffres viennent de `definitions/` et des formules de
 * `docs/economie.md` ; ils ne vivent pas dans les routes de session (un chiffre du jeu vit dans
 * `definitions/` et nulle part ailleurs).
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
     * Encaisse la production de l'absence (plafond hors ligne appliqué) et rend le Donar encaissé : c'est
     * `JoinResponse.offlineCollected`. Appelée par `join` quand le joueur revient dans une entreprise qui
     * existait déjà, jamais à la création.
     *
     * Le moteur écrit dans la transaction de [connection], avec le verrou optimiste (`updateCompany`
     * avec `company.version`) : si le `join` échoue ensuite, rien n'est encaissé. [playerLastSeenAt] est
     * l'heure de départ figée par le dernier `leave` de CE joueur (`null` s'il n'a jamais été vu) ;
     * `company.lastCollectAt` est le dernier calcul de production de l'entreprise. L'implémentation du
     * service compte l'absence depuis `company.lastCollectAt` seul (voir `EngineFigures.settleOffline`).
     */
    fun settleOffline(connection: Connection, company: CompanyRecord, playerLastSeenAt: Instant?): Double
}
