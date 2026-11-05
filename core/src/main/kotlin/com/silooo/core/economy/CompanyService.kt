package com.silooo.core.economy

import com.silooo.core.api.CompanyState
import com.silooo.core.api.CompanyUpdated
import com.silooo.core.api.FactoryState
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.refuse
import com.silooo.core.db.CompanyColumn
import com.silooo.core.db.Xuid
import com.silooo.core.db.updateCompany
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.EconomyFigures
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.Production
import com.silooo.core.session.SessionStore
import com.silooo.core.session.buildCompanyState
import java.sql.Connection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Une requête qui n'a pas la forme que le jeu attend : usine inconnue de `usines.json`, acteur qui n'est pas membre de
 * l'entreprise, entreprise inconnue. Le contrat n'a pas de code 404 ni 403 pour ces cas : la route répond 400
 * `REQUETE_INVALIDE`. Levée avant toute écriture.
 */
class RequestRejected(message: String) : RuntimeException(message, null, false, false)

/** Ce que `collect` rend : le Donar encaissé, les heures retenues (plafond appliqué) et l'état d'après. */
data class Collected(val collected: Double, val offlineHours: Double, val state: CompanyState)

/**
 * Les quatre routes d'économie de la phase 3, sans HTTP : `getCompany`, `collect`, `buyLevels` et `restoreStep`.
 * Les routes valident l'entrée et répondent ; ce service lit, décide et écrit, dans la transaction que l'appelant lui
 * donne ([Connection]). Il ne contient aucun chiffre du jeu : prix, production et plafond viennent du [EconomyEngine]
 * et de [EconomyFigures], qui les tirent de `definitions/`.
 *
 * Une action d'écriture suit toujours le même déroulé :
 *  1. L'acteur est membre de l'entreprise ; sinon [RequestRejected].
 *  2. Les refus du jeu (`Refusal`) tombent avant la première écriture.
 *  3. La production en attente est versée d'abord ([EconomyFigures.pendingProduction]), avec le reste de l'action, en
 *     UNE écriture `updateCompany` (verrou optimiste sur `companies.version`), puis l'usine est écrite, puis l'événement.
 *  4. L'état rendu est bâti sur ce qui vient d'être écrit, sans nouvelle lecture de l'entreprise.
 *
 * Le Donar est la monnaie de l'entreprise : il s'écrit par `updateCompany`, jamais par `creditBalance` (les monnaies
 * personnelles). Rien ici n'écrit dans `currency_ledger`.
 */
class CompanyService(
    private val sessions: SessionStore,
    private val companies: CompanyStore,
    private val engine: EconomyEngine,
    private val figures: EconomyFigures,
) {
    private val knownFactories: Set<String> = engine.factoryIds().toSet()

    /** L'état de l'entreprise tel que la base le garde, ou `null` si elle n'existe pas. Rien n'est écrit. */
    fun read(connection: Connection, companyId: Long): CompanyState? {
        if (!companies.companyExists(connection, companyId)) return null
        return stateOf(connection, sessions.loadCompany(connection, companyId), sessions.loadFactories(connection, companyId))
    }

    /**
     * Verse la production depuis `last_collect_at` (voir [EconomyFigures.pendingProduction] : même règle que le
     * hors ligne de `join`) et remet `last_collect_at` à maintenant. Rien n'est écrit, ni événement publié, quand
     * l'horloge du service est en retard sur la base (rien n'est dû).
     */
    fun collect(connection: Connection, companyId: Long, actor: Xuid): Collected {
        val (company, factories) = loadForActor(connection, companyId, actor)
        val production = figures.pendingProduction(connection, company, factories)
        if (production.at == company.lastCollectAt) {
            return Collected(0.0, 0.0, stateOf(connection, company, factories))
        }
        val after = write(connection, company, production, spent = 0.0)
        companies.publish(
            connection,
            EVENT_KIND,
            actor,
            event(ACTION_COLLECT, companyId) {
                put("collected", production.earned)
                put("offlineHours", production.countedHours)
            },
        )
        return Collected(production.earned, production.countedHours, stateOf(connection, after, factories))
    }

    /**
     * Monte l'usine de [amount] niveaux. La production en attente est versée avant le débit : l'usine produit au
     * nouveau niveau à partir de maintenant, jamais pour le temps déjà écoulé.
     *
     * Refus : `USINE_VERROUILLEE` si l'entreprise n'a pas cette usine ou si elle ne produit pas (`EN_RUINE`,
     * `REMISE_EN_ETAT` : on la remet en état d'abord) ; `PAS_ASSEZ_DE_DONAR` avec la somme qui manque quand le solde
     * ne paie pas. `1`, `10` et `palier` se paient en entier ou pas du tout ; `max` achète tout ce que le solde paie
     * et ne refuse que s'il ne paie pas même un niveau.
     */
    fun buyLevels(connection: Connection, companyId: Long, factoryId: String, amount: BuyAmount, actor: Xuid): CompanyUpdated {
        requireKnownFactory(factoryId)
        val (company, factories) = loadForActor(connection, companyId, actor)
        val factory = factories.firstOrNull { it.factoryId == factoryId } ?: refuse(RefusalCodes.FACTORY_LOCKED)
        if (factory.state != FactoryState.ACTIVE) refuse(RefusalCodes.FACTORY_LOCKED)

        val production = figures.pendingProduction(connection, company, factories)
        val balance = company.donar + production.earned
        val quote = engine.quote(factoryId, factory.level, amount, balance)
        if (!quote.affordable) {
            // `max` sans un seul niveau payable (levels = 0) : il manque le prix du prochain niveau.
            val needed = if (quote.levels == 0) engine.nextLevelCost(factoryId, factory.level) else quote.cost
            refuse(RefusalCodes.NOT_ENOUGH_DONAR, missing = (needed - balance).finiteOrMax())
        }

        val after = write(connection, company, production, spent = quote.cost)
        val bought = factory.copy(level = quote.newLevel)
        companies.saveFactory(connection, companyId, factoryId, bought.state, bought.restoreStep, bought.level)
        companies.publish(
            connection,
            EVENT_KIND,
            actor,
            event(ACTION_BUY_LEVELS, companyId) {
                put("factoryId", factoryId)
                put("levels", quote.levels)
                put("level", quote.newLevel)
                put("cost", quote.cost)
            },
        )
        return updated(connection, actor, after, factories.replacing(bought))
    }

    /**
     * Avance la remise en état d'une usine d'une étape. Les étapes se font dans l'ordre, une fois chacune :
     *  - `EN_RUINE` : seule l'étape 1 est permise ; l'usine passe `REMISE_EN_ETAT`.
     *  - `REMISE_EN_ETAT` à l'étape *s* : seule l'étape *s* + 1 est permise. La dernière ([LAST_RESTORE_STEP]) passe l'usine
     *    `ACTIVE`, au niveau [EconomyEngine.restoredLevel].
     *
     * Refus : `USINE_VERROUILLEE` pour une usine que l'entreprise n'a pas ou une étape trop loin ; `DEJA_POSSEDE` pour
     * une étape déjà faite ou une usine déjà `ACTIVE`. Aucune étape ne coûte de Donar (rien dans `definitions/`) ; la
     * production en attente est tout de même versée, pour que l'usine remise en état ne produise que depuis maintenant.
     */
    fun restoreStep(connection: Connection, companyId: Long, factoryId: String, step: Int, actor: Xuid): CompanyUpdated {
        if (step !in 1..LAST_RESTORE_STEP) throw RequestRejected("Étape de remise en état hors de 1..$LAST_RESTORE_STEP : $step")
        requireKnownFactory(factoryId)
        val (company, factories) = loadForActor(connection, companyId, actor)
        val factory = factories.firstOrNull { it.factoryId == factoryId } ?: refuse(RefusalCodes.FACTORY_LOCKED)
        if (factory.state == FactoryState.ACTIVE || step <= factory.restoreStep) refuse(RefusalCodes.ALREADY_OWNED)
        if (step > factory.restoreStep + 1) refuse(RefusalCodes.FACTORY_LOCKED)

        val production = figures.pendingProduction(connection, company, factories)
        val after = write(connection, company, production, spent = 0.0)
        val finished = step == LAST_RESTORE_STEP
        val restored = factory.copy(
            state = if (finished) FactoryState.ACTIVE else FactoryState.REMISE_EN_ETAT,
            restoreStep = step,
            level = if (finished) engine.restoredLevel(factory.level) else factory.level,
        )
        companies.saveFactory(connection, companyId, factoryId, restored.state, restored.restoreStep, restored.level)
        companies.publish(
            connection,
            EVENT_KIND,
            actor,
            event(ACTION_RESTORE_STEP, companyId) {
                put("factoryId", factoryId)
                put("step", step)
                put("state", restored.state.name)
                put("level", restored.level)
            },
        )
        return updated(connection, actor, after, factories.replacing(restored))
    }

    private fun requireKnownFactory(factoryId: String) {
        if (factoryId !in knownFactories) throw RequestRejected("Usine inconnue de usines.json : $factoryId")
    }

    /** L'entreprise et ses usines, pour un acteur qui en est membre. Sinon [RequestRejected]. */
    private fun loadForActor(connection: Connection, companyId: Long, actor: Xuid): Pair<CompanyRecord, List<FactoryRecord>> {
        val membership = sessions.membershipOf(connection, actor)
        if (membership == null || membership.companyId != companyId) {
            throw RequestRejected("L'acteur $actor n'est pas membre de l'entreprise $companyId")
        }
        return sessions.loadCompany(connection, companyId) to sessions.loadFactories(connection, companyId)
    }

    /**
     * Verse [production] sur l'entreprise, retire [spent] du Donar et remet `last_collect_at` : UNE écriture, un seul
     * changement de version. Rend la ligne telle qu'elle est maintenant, sans la relire.
     *
     * Le solde est calculé comme dans le devis de `buyLevels` (`donar + production`) avant le retrait : un achat jugé
     * payable ne laisse jamais un Donar négatif, même à l'arrondi près.
     */
    private fun write(connection: Connection, company: CompanyRecord, production: Production, spent: Double): CompanyRecord {
        val balance = company.donar + production.earned
        val donar = balance - spent
        val earned = company.donarEarned + production.earned
        val version = updateCompany(
            connection,
            company.id,
            company.version,
            CompanyColumn.DONAR to donar,
            CompanyColumn.DONAR_EARNED to earned,
            CompanyColumn.LAST_COLLECT_AT to production.at,
        )
        return company.copy(donar = donar, donarEarned = earned, lastCollectAt = production.at, version = version)
    }

    private fun stateOf(connection: Connection, company: CompanyRecord, factories: List<FactoryRecord>): CompanyState =
        buildCompanyState(company, factories, figures.forCompany(connection, company, factories), sessions.loadZones(connection, company.id))

    private fun updated(connection: Connection, actor: Xuid, company: CompanyRecord, factories: List<FactoryRecord>): CompanyUpdated =
        CompanyUpdated(stateOf(connection, company, factories), sessions.loadBalances(connection, actor))

    private fun List<FactoryRecord>.replacing(factory: FactoryRecord): List<FactoryRecord> =
        map { if (it.factoryId == factory.factoryId) factory else it }

    /** Un montant manquant doit rester un nombre JSON : un prix qui sortirait des doubles vaut [Double.MAX_VALUE]. */
    private fun Double.finiteOrMax(): Double = if (isFinite()) this else Double.MAX_VALUE

    private fun event(action: String, companyId: Long, fill: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("action", action)
            put("companyId", companyId)
            fill()
        }

    companion object {
        /**
         * Le nombre d'étapes de remise en état : `step` va de 1 à 4 dans l'OpenAPI (dialogue, tâches, livraison,
         * recrutement). Une forme du contrat, pas un réglage d'équilibrage.
         */
        const val LAST_RESTORE_STEP = 4

        /**
         * Le type d'événement des trois actions, adressé à l'acteur. L'OpenAPI n'a pas de type propre à `collect`,
         * `buyLevels` ni `restoreStep`, et en ajouter un casserait `ContractTest.codesMatchTheContract` : `ANNONCE` est
         * le type générique, le détail est dans `payload.action`. Un type dédié s'ajoute d'abord à l'OpenAPI.
         */
        val EVENT_KIND = GameEventKind.ANNONCE

        /** Valeurs de `payload.action` : de quelle action vient l'événement. */
        const val ACTION_COLLECT = "collect"
        const val ACTION_BUY_LEVELS = "buyLevels"
        const val ACTION_RESTORE_STEP = "restoreStep"
    }
}
