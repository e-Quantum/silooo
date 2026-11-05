package com.silooo.core.economy

import com.silooo.core.api.CompanyState
import com.silooo.core.api.CompanyUpdated
import com.silooo.core.api.FactoryState
import com.silooo.core.api.PrestigePreview
import com.silooo.core.api.PrestigeShare
import com.silooo.core.api.Refusal
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.Role
import com.silooo.core.api.refuse
import com.silooo.core.db.CompanyColumn
import com.silooo.core.db.Xuid
import com.silooo.core.db.updateCompany
import com.silooo.core.definitions.MissionEvent
import com.silooo.core.events.GameEventKind
import com.silooo.core.findings.FindingService
import com.silooo.core.mission.MissionService
import com.silooo.core.pass.PassService
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.EconomyFigures
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.Membership
import com.silooo.core.session.Regime
import com.silooo.core.session.SessionStore
import com.silooo.core.session.buildCompanyState
import io.ktor.http.HttpStatusCode
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.time.Duration
import java.time.Instant
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

/**
 * Ce que `collect` rend : le Donar encaissé, les heures comptées et l'état d'après. `offlineHours` garde le nom du
 * contrat mais compte toutes les heures écoulées : `collect` paie en ligne, au revenu plein, sans plafond.
 */
data class Collected(val collected: Double, val offlineHours: Double, val state: CompanyState)

/**
 * Les routes d'économie de la phase 3 et le prestige, sans HTTP : `getCompany`, `collect`, `buyLevels`, `restoreStep`,
 * `prestigePreview` et `prestige`.
 * Les routes valident l'entrée et répondent ; ce service lit, décide et écrit, dans la transaction que l'appelant lui
 * donne ([Connection]). Il ne contient aucun chiffre du jeu : prix, production et plafond viennent du [EconomyEngine]
 * et de [EconomyFigures], qui les tirent de `definitions/`.
 *
 * Une action d'écriture suit toujours le même déroulé :
 *  1. L'acteur est membre de l'entreprise ; sinon [RequestRejected].
 *  2. Les refus du jeu (`Refusal`) tombent avant la première écriture.
 *  3. La production en attente est versée d'abord ([EconomyFigures.pendingProduction], en ligne), avec le reste de l'action, en
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
    private val missions: MissionService,
    private val findings: FindingService,
    /** Le pass de saison : un prestige rapporte des points à chaque associé. `null` : pas de pass (les tests qui n'en ont pas besoin). */
    private val pass: PassService? = null,
) {
    private val knownFactories: Set<String> = engine.factoryIds().toSet()

    /** L'état de l'entreprise tel que la base le garde, ou `null` si elle n'existe pas. Rien n'est écrit. */
    fun read(connection: Connection, companyId: Long): CompanyState? {
        if (!companies.companyExists(connection, companyId)) return null
        return stateOf(connection, sessions.loadCompany(connection, companyId), sessions.loadFactories(connection, companyId))
    }

    /**
     * Verse la production depuis `last_collect_at`, au revenu plein ([Regime.ONLINE] : l'acteur était connecté depuis
     * le dernier calcul, `join` et `leave` remettent la date), et remet `last_collect_at` à maintenant. Rien n'est
     * écrit, ni événement publié, quand l'horloge du service est en retard sur la base (rien n'est dû).
     */
    fun collect(connection: Connection, companyId: Long, actor: Xuid): Collected {
        val (company, factories) = loadForActor(connection, companyId, actor)
        val production = figures.pendingProduction(connection, company, factories, Regime.ONLINE)
        if (production.at == company.lastCollectAt) {
            return Collected(0.0, 0.0, stateOf(connection, company, factories))
        }
        val after = writeProduction(connection, company, production, spent = 0.0)
        companies.publish(
            connection,
            EVENT_KIND,
            actor,
            event(ACTION_COLLECT, companyId) {
                put("collected", production.earned)
                put("offlineHours", production.countedHours)
            },
        )
        // Un encaissement qui rapporte du Donar compte pour la mission en cours et inscrit au Carnet les ressources que les
        // usines en marche produisent ; la même transaction l'écrit ou l'annule.
        if (production.earned > 0.0) {
            missions.record(connection, actor, MissionEvent.COLLECT)
            findings.record(connection, actor, factories)
        }
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

        val production = figures.pendingProduction(connection, company, factories, Regime.ONLINE)
        val balance = company.donar + production.earned
        val quote = engine.quote(factoryId, factory.level, amount, balance)
        if (!quote.affordable) {
            // `max` sans un seul niveau payable (levels = 0) : il manque le prix du prochain niveau.
            val needed = if (quote.levels == 0) engine.nextLevelCost(factoryId, factory.level) else quote.cost
            refuse(RefusalCodes.NOT_ENOUGH_DONAR, missing = (needed - balance).finiteOrMax())
        }

        val after = writeProduction(connection, company, production, spent = quote.cost)
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

        val production = figures.pendingProduction(connection, company, factories, Regime.ONLINE)
        val after = writeProduction(connection, company, production, spent = 0.0)
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
        if (finished) announceNoPower(connection, company, factories, restored, actor)
        return updated(connection, actor, after, factories.replacing(restored))
    }

    /**
     * Le courant qui manque (`docs/game-design.md`, « L'électricité, ressource obligatoire »). Le service ne garde aucun état « sans
     * courant » : il se déduit des usines ([EconomyEngine.stoppedNoPower]). La seule action qui fait passer une usine moderne à
     * l'arrêt est la fin de sa remise en état : l'époque ne change qu'au prestige, qui remet toutes les usines en ruine, et la
     * Centrale ne s'éteint que de la même façon. Ici donc, avant et après cette étape, on compare : l'entreprise n'avait aucune
     * usine arrêtée faute de courant et [restored] l'est maintenant. Un seul événement par passage à cet état (une deuxième usine
     * moderne remise en état pendant que la première attend déjà la Centrale n'en publie pas un autre), adressé à celui qui agit :
     * `ANNONCE`, `payload.action = "sansCourant"`, avec `factoryId`, `factoryName` et `playerName`. Le bot Discord (`docs/discord.md`) le lit ;
     * l'add-on ne le lit pas encore.
     */
    private fun announceNoPower(connection: Connection, company: CompanyRecord, before: List<FactoryRecord>, restored: FactoryRecord, actor: Xuid) {
        if (engine.stoppedNoPower(company.era, producingLevels(before)).isNotEmpty()) return
        val after = before.replacing(restored)
        if (restored.factoryId !in engine.stoppedNoPower(company.era, producingLevels(after))) return
        val playerName = sessions.loadMembers(connection, company.id).firstOrNull { it.xuid == actor }?.name
        companies.publish(
            connection,
            EVENT_KIND,
            actor,
            event(ACTION_NO_POWER, company.id) {
                put("factoryId", restored.factoryId)
                put("factoryName", engine.factory(restored.factoryId).name)
                if (playerName != null) put("playerName", playerName)
            },
        )
    }

    /** Le niveau de chaque usine qui produit (`ACTIVE`), comme le moteur le demande. */
    private fun producingLevels(factories: List<FactoryRecord>): Map<String, Int> =
        factories.filter { it.state == FactoryState.ACTIVE }.associate { it.factoryId to it.level }

    /**
     * Ce que rapporterait un prestige maintenant, ou null si l'entreprise n'existe pas. Rien n'est écrit. Le calcul
     * est celui de [prestige] : la production en attente (en ligne) compte dans les Donar gagnés, le seuil est celui de
     * la course. Quand le seuil n'est pas atteint le prestige est refusé, donc il ne rapporte rien : les Lingots et les
     * parts sont à zéro (**à valider par Kr**), `next` dit quand même ce qui viendrait après.
     */
    fun prestigePreview(connection: Connection, companyId: Long): PrestigePreview? {
        if (!companies.companyExists(connection, companyId)) return null
        val company = sessions.loadCompany(connection, companyId)
        val factories = sessions.loadFactories(connection, companyId)
        val earned = company.donarEarned + figures.pendingProduction(connection, company, factories, Regime.ONLINE).earned
        val course = company.courseNumber
        val allowed = engine.canPrestige(course, earned)
        val total = if (allowed) engine.prestigeLingots(course, earned) else 0L
        val doubled = if (allowed) engine.prestigeLingots(course, earned * 2) else 0L
        val shares = splitLingots(total, companies.loadShares(connection, companyId))
        return PrestigePreview(
            allowed = allowed,
            lingotsTotal = total,
            lingotsIfDoubled = doubled,
            shares = shares.map { PrestigeShare(it.xuid.text, it.lingots) },
            next = if (engine.prestigeGivesStar(course)) NEXT_STAR else "$NEXT_ERA_PREFIX${engine.eraAt(course + 1)}",
        )
    }

    /**
     * Le prestige : réservé au PDG, permis quand les Donar gagnés atteignent le seuil de la course. Dans l'ordre :
     *  1. La production en attente est versée d'abord (en ligne) : elle compte pour le seuil et pour les Lingots.
     *  2. La course suivante : Donar et Donar gagnés à zéro, `course_number` + 1, époque et étoiles selon le moteur,
     *     `course_started_at` et `last_collect_at` à maintenant, en UNE écriture `updateCompany`.
     *  3. Les usines repartent ([CompanyStore.restartFactories]), les Lingots se partagent ([splitLingots]) et se
     *     créditent un membre à la fois (`player_balances` et `currency_ledger`), la ligne de `prestige_history` s'écrit.
     *  4. Chaque membre reçoit un événement `ANNONCE` (`payload.action = "prestige"`) avec sa part, et tout le serveur un
     *     `PRESTIGE_ENTREPRISE` (`companyId`, `companyName`, `lingots` : le total avant partage, `prestigeCount` : la course finie).
     *
     * Refus : `PAS_LE_PDG` (403) pour un associé ; `SEUIL_NON_ATTEINT` avec les Donar qui manquent. Un acteur qui n'est pas
     * membre : [RequestRejected]. Rien n'est écrit avant un refus.
     */
    fun prestige(connection: Connection, companyId: Long, actor: Xuid): CompanyUpdated {
        val membership = membershipOf(connection, companyId, actor)
        if (membership.role != Role.PDG) throw Refusal(HttpStatusCode.Forbidden, RefusalCodes.NOT_THE_CEO)
        val company = sessions.loadCompany(connection, companyId)
        val factories = sessions.loadFactories(connection, companyId)

        val production = figures.pendingProduction(connection, company, factories, Regime.ONLINE)
        val earned = company.donarEarned + production.earned
        val course = company.courseNumber
        val threshold = engine.prestigeThreshold(course)
        if (!engine.canPrestige(course, earned)) refuse(RefusalCodes.THRESHOLD_NOT_REACHED, missing = (threshold - earned).finiteOrMax())

        val total = engine.prestigeLingots(course, earned)
        val shares = splitLingots(total, companies.loadShares(connection, companyId))
        val nextCourse = course + 1
        val nextEra = engine.eraAt(nextCourse)
        val nextStars = engine.starsAt(nextCourse)
        val now = production.at

        val version = updateCompany(
            connection,
            companyId,
            company.version,
            CompanyColumn.DONAR to 0.0,
            CompanyColumn.DONAR_EARNED to 0.0,
            CompanyColumn.COURSE_NUMBER to nextCourse,
            CompanyColumn.ERA to nextEra,
            CompanyColumn.STARS to nextStars,
            CompanyColumn.COURSE_STARTED_AT to now,
            CompanyColumn.LAST_COLLECT_AT to now,
        )
        companies.restartFactories(connection, companyId, engine.startingLevels())
        val refId = "entreprise-$companyId-course-$course"
        for (share in shares) {
            if (share.lingots > 0) companies.creditLingots(connection, share.xuid, share.lingots, REASON_PRESTIGE, refId)
            // Les points du pass viennent après les Lingots du même membre : les verrous se prennent dans le même ordre que partout.
            pass?.onPrestige(connection, share.xuid)
        }
        companies.recordPrestige(
            connection,
            PrestigeRecord(
                companyId = companyId,
                courseNumber = course,
                eraBefore = company.era,
                starsAfter = nextStars,
                threshold = threshold,
                donarEarned = earned,
                lingotsTotal = total,
                durationHours = hoursBetween(company.courseStartedAt, now),
                decidedBy = actor,
            ),
        )
        for (share in shares) {
            companies.publish(
                connection,
                EVENT_KIND,
                share.xuid,
                event(ACTION_PRESTIGE, companyId) {
                    put("course", course)
                    put("era", nextEra)
                    put("stars", nextStars)
                    put("lingotsTotal", total)
                    put("lingots", share.lingots)
                },
            )
        }
        // Une annonce pour tout le serveur (xuid nul), en plus des événements de chaque membre : le total avant partage et le numéro
        // de la course qui se termine. Le bot Discord la lit ; elle n'a rien à faire des parts, qui restent dans l'`ANNONCE` du membre.
        companies.publish(
            connection,
            GameEventKind.PRESTIGE_ENTREPRISE,
            null,
            buildJsonObject {
                put("companyId", companyId)
                put("companyName", company.name)
                put("lingots", total)
                put("prestigeCount", course)
            },
        )

        val after = company.copy(
            era = nextEra,
            stars = nextStars,
            courseNumber = nextCourse,
            donar = 0.0,
            donarEarned = 0.0,
            courseStartedAt = now,
            lastCollectAt = now,
            version = version,
        )
        return updated(connection, actor, after, sessions.loadFactories(connection, companyId))
    }

    /** La durée entre [from] et [to] en heures, à deux décimales (`prestige_history.duration_hours`), jamais négative. */
    private fun hoursBetween(from: Instant, to: Instant): BigDecimal {
        val millis = maxOf(0L, Duration.between(from, to).toMillis())
        return BigDecimal(millis).divide(BigDecimal(MILLIS_PER_HOUR), 2, RoundingMode.HALF_UP).min(MAX_DURATION_HOURS)
    }

    private fun requireKnownFactory(factoryId: String) {
        if (factoryId !in knownFactories) throw RequestRejected("Usine inconnue de usines.json : $factoryId")
    }

    /** L'appartenance de l'acteur à l'entreprise, avec son rôle. Sinon [RequestRejected]. */
    private fun membershipOf(connection: Connection, companyId: Long, actor: Xuid): Membership =
        sessions.requireMembership(connection, companyId, actor)

    /** L'entreprise et ses usines, pour un acteur qui en est membre. Sinon [RequestRejected]. */
    private fun loadForActor(connection: Connection, companyId: Long, actor: Xuid): Pair<CompanyRecord, List<FactoryRecord>> =
        sessions.loadForActor(connection, companyId, actor)

    private fun stateOf(connection: Connection, company: CompanyRecord, factories: List<FactoryRecord>): CompanyState =
        buildCompanyState(company, factories, figures.forCompany(connection, company, factories), sessions.loadZones(connection, company.id))

    private fun updated(connection: Connection, actor: Xuid, company: CompanyRecord, factories: List<FactoryRecord>): CompanyUpdated =
        CompanyUpdated(stateOf(connection, company, factories), sessions.loadBalances(connection, actor))

    private fun List<FactoryRecord>.replacing(factory: FactoryRecord): List<FactoryRecord> =
        map { if (it.factoryId == factory.factoryId) factory else it }

    private fun event(action: String, companyId: Long, fill: JsonObjectBuilder.() -> Unit): JsonObject = companyEvent(action, companyId, fill)

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
        const val ACTION_PRESTIGE = "prestige"
        const val ACTION_NO_POWER = "sansCourant"

        /** `currency_ledger.reason` des Lingots du prestige. */
        const val REASON_PRESTIGE = "prestige"

        /** Valeurs de `PrestigePreview.next` : `EPOQUE_<n>` pour l'époque suivante, `ETOILE` après la dernière. */
        const val NEXT_ERA_PREFIX = "EPOQUE_"
        const val NEXT_STAR = "ETOILE"

        /** Une heure en millisecondes, et la plus grande durée que `prestige_history.duration_hours` (DECIMAL(8,2)) garde. */
        private const val MILLIS_PER_HOUR = 3_600_000L
        private val MAX_DURATION_HOURS = BigDecimal("999999.99")
    }
}
