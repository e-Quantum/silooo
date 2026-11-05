package com.silooo.core.orders

import com.silooo.core.api.OrderDelivered
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.WorldOrder
import com.silooo.core.api.refuse
import com.silooo.core.db.CompanyColumn
import com.silooo.core.db.Xuid
import com.silooo.core.db.updateCompany
import com.silooo.core.definitions.Definitions
import com.silooo.core.economy.RequestRejected
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.EconomyFigures
import com.silooo.core.session.Regime
import com.silooo.core.session.SessionStore
import com.silooo.core.session.buildCompanyState
import java.sql.Connection
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.floor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * La commande mondiale du jour, sans HTTP : `todayOrder` (lire) et `deliverOrder` (livrer). Les routes valident
 * l'entrée et répondent ; ce service lit, décide et écrit, dans la transaction que l'appelant lui donne.
 *
 * Aucun chiffre du jeu ici : la ressource, l'objectif et la prime viennent de [OrderPlan], le prix d'une unité de
 * `economie.json` (`commandeDuJour.minutesDeProductionParUnite`) et du revenu de l'entreprise ([EconomyFigures]).
 *
 * Règles que le game design ne donne pas, toutes **à valider par Kr** :
 *  - Une unité livrée coûte du Donar à l'entreprise du joueur : le nombre de minutes de SON revenu que dit `economie.json`.
 *    Le temps de jeu est le même pour un petit joueur et pour un gros ; le Donar d'un gros joueur n'achète pas la commande.
 *  - Un joueur peut livrer plusieurs fois par jour, ses parts s'additionnent. Une livraison qui dépasse le reste à livrer est
 *    ramenée à ce reste.
 *  - La prime (Lingots hors prestige) est versée à la fin de la commande, une fois, à chaque joueur qui a livré : celui qui
 *    livre le dernier lot ne prend rien de plus que les autres, et personne ne perd sa prime parce qu'il a livré tôt.
 *  - Une commande terminée n'a plus rien à recevoir : la livraison suivante ne prend rien, n'écrit rien et rend la commande
     *    telle qu'elle est (`completed` vrai). Le contrat n'a pas de code de refus pour cela, et en ajouter un casserait les
     *    tests de l'add-on, que cette tranche ne touche pas ; si Kr préfère un refus, il s'ajoute à `Refused.reason`.
 */
class OrderService(
    private val definitions: Definitions,
    private val sessions: SessionStore,
    private val orders: OrderStore,
    private val figures: EconomyFigures,
    private val clock: Clock,
) {
    private val plan = OrderPlan(definitions)

    /** Le jour de jeu : la date UTC de l'horloge du service. */
    private fun gameDay(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

    /**
     * La commande du jour et sa progression. [viewer], s'il y en a un, donne `mine` : sa part déjà livrée. Rien n'est écrit :
     * tant que personne n'a livré, la commande n'existe qu'au plan.
     */
    fun today(connection: Connection, viewer: Xuid?): WorldOrder {
        val planned = plan.forDay(gameDay())
        val row = orders.find(connection, planned.day)
            ?: return WorldOrder(
                day = planned.day.toString(),
                resourceId = planned.resourceId,
                target = planned.targetUnits.toDouble(),
                delivered = 0.0,
                rewardLingots = planned.rewardLingots,
                completed = false,
                mine = 0.0,
            )
        return worldOrder(row, if (viewer == null) 0.0 else orders.contribution(connection, row.id, viewer))
    }

    /**
     * Livre [quantity] unités de la ressource du jour pour l'entreprise de [actor]. Dans l'ordre :
     *  1. L'acteur a une entreprise ; sinon [RequestRejected].
     *  2. La commande du jour est écrite si besoin et VERROUILLÉE, avant de lire l'entreprise : deux livraisons de la même
     *     entreprise se suivent, la seconde lit l'entreprise déjà débitée par la première.
     *  3. Si elle est terminée, rien n'est pris ni écrit : la réponse montre la commande finie et l'entreprise telle quelle ;
     *     sinon la livraison est ramenée au reste à livrer.
     *  4. Le prix en Donar est pris sur le solde AVEC la production en attente (versée dans la même écriture, comme le fait
     *     `buyLevels`) ; refus `PAS_ASSEZ_DE_DONAR` avec ce qui manque quand il ne couvre pas le prix.
     *  5. L'entreprise est écrite (verrou optimiste), la part du joueur et la commande aussi, puis l'événement `ANNONCE`.
     *  6. Si la commande est terminée : chaque joueur qui a livré reçoit sa prime et un événement `COMMANDE_TERMINEE`.
     */
    fun deliver(connection: Connection, actor: Xuid, quantity: Long): OrderDelivered {
        if (quantity < 1) throw RequestRejected("Une livraison porte au moins une unité : $quantity")
        val membership = sessions.membershipOf(connection, actor) ?: throw RequestRejected("L'acteur $actor n'a pas d'entreprise")

        val planned = plan.forDay(gameDay())
        val row = orders.lockOrCreate(connection, planned.day, planned.resourceId, planned.targetUnits.toDouble(), planned.rewardLingots)
        val remaining = floor(row.target - row.delivered).toLong()

        val company = sessions.loadCompany(connection, membership.companyId)
        val factories = sessions.loadFactories(connection, membership.companyId)
        if (row.completed || remaining < 1) {
            // Plus rien à livrer aujourd'hui : on montre la commande finie, sans prendre de Donar ni écrire quoi que ce soit.
            val finishedOrder = worldOrder(row, orders.contribution(connection, row.id, actor))
            val state = buildCompanyState(company, factories, figures.forCompany(connection, company, factories), sessions.loadZones(connection, company.id))
            return OrderDelivered(finishedOrder, state, sessions.loadBalances(connection, actor))
        }
        val units = minOf(quantity, remaining)
        val production = figures.pendingProduction(connection, company, factories, Regime.ONLINE)
        val rate = figures.forCompany(connection, company, factories).ratePerHour
        val cost = units * definitions.economy.dailyOrder.minutesOfIncomePerUnit / MINUTES_PER_HOUR * rate
        val balance = company.donar + production.earned
        if (cost > balance) refuse(RefusalCodes.NOT_ENOUGH_DONAR, missing = (cost - balance).finiteOrMax())

        // Comme CompanyService.write : la production est versée, le prix retiré, UNE écriture, un seul changement de version.
        val donar = balance - cost
        val earned = company.donarEarned + production.earned
        val version = updateCompany(
            connection,
            company.id,
            company.version,
            CompanyColumn.DONAR to donar,
            CompanyColumn.DONAR_EARNED to earned,
            CompanyColumn.LAST_COLLECT_AT to production.at,
        )
        val after = company.copy(donar = donar, donarEarned = earned, lastCollectAt = production.at, version = version)

        orders.addContribution(connection, row.id, actor, units.toDouble())
        val delivered = row.delivered + units
        val completed = delivered >= row.target
        orders.saveProgress(connection, row.id, delivered, completed)
        orders.publish(
            connection,
            EVENT_KIND,
            actor,
            buildJsonObject {
                put("action", ACTION_DELIVER)
                put("day", row.day.toString())
                put("resourceId", row.resourceId)
                put("quantity", units)
            },
        )
        if (completed) payContributors(connection, row)

        val order = worldOrder(row.copy(delivered = delivered, completed = completed), orders.contribution(connection, row.id, actor))
        val state = buildCompanyState(after, factories, figures.forCompany(connection, after, factories), sessions.loadZones(connection, after.id))
        return OrderDelivered(order, state, sessions.loadBalances(connection, actor))
    }

    /** La commande vient de se terminer : chaque joueur qui a livré touche sa prime une fois, puis apprend que c'est fini. */
    private fun payContributors(connection: Connection, row: OrderRow) {
        for (contributor in orders.unrewardedContributors(connection, row.id)) {
            if (row.rewardLingots > 0) orders.creditLingots(connection, contributor, row.rewardLingots, REASON, "$REASON-${row.day}")
            orders.markRewarded(connection, row.id, contributor)
            orders.publish(connection, GameEventKind.COMMANDE_TERMINEE, contributor, finished(row))
        }
    }

    private fun finished(row: OrderRow): JsonObject = buildJsonObject {
        put("day", row.day.toString())
        put("resourceId", row.resourceId)
        put("rewardLingots", row.rewardLingots)
    }

    private fun worldOrder(row: OrderRow, mine: Double) = WorldOrder(
        day = row.day.toString(),
        resourceId = row.resourceId,
        target = row.target,
        delivered = row.delivered,
        rewardLingots = row.rewardLingots,
        completed = row.completed,
        mine = mine,
    )

    /** Un montant manquant doit rester un nombre JSON : un prix qui sortirait des doubles vaut [Double.MAX_VALUE]. */
    private fun Double.finiteOrMax(): Double = if (isFinite()) this else Double.MAX_VALUE

    companion object {
        /**
         * Le type d'événement d'une livraison, adressé à celui qui livre. L'OpenAPI n'a pas de type propre aux livraisons et en
         * ajouter un casserait `ContractTest.codesMatchTheContract` : `ANNONCE` est le type générique, le détail est dans
         * `payload.action`. La fin de la commande a, elle, son type : `COMMANDE_TERMINEE`.
         */
        val EVENT_KIND = GameEventKind.ANNONCE

        /** Valeur de `payload.action` d'une livraison. */
        const val ACTION_DELIVER = "deliverOrder"

        /** `currency_ledger.reason` de la prime de la commande ; `ref_id` porte `commande-<jour>`. */
        const val REASON = "commande"

        /** Les minutes d'une heure : le revenu de l'entreprise est par heure, le prix d'une unité en minutes. Une unité de temps, pas un réglage. */
        private const val MINUTES_PER_HOUR = 60.0
    }
}
