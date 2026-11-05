package com.silooo.core.economy

import com.silooo.core.api.FactoryState
import com.silooo.core.db.CompanyColumn
import com.silooo.core.db.updateCompany
import com.silooo.core.session.CompanyFigures
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.EconomyFigures
import com.silooo.core.session.FactoryFigures
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.Production
import com.silooo.core.session.SessionStore
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Les chiffres de `join`, calculés par le [EconomyEngine] : l'implémentation d'[EconomyFigures] que le service
 * utilise. Elle ne contient aucune formule ni aucun chiffre du jeu : elle choisit les entrées du moteur
 * (quelles usines produisent, quels Lingots comptent, depuis quand dure l'absence) et recopie ses sorties.
 *
 * Les Lingots des membres viennent de [store] (`player_balances`), dans la transaction du `join`. L'horloge
 * est injectable : un test avance le temps sans attendre.
 */
class EngineFigures(
    private val engine: EconomyEngine,
    private val store: SessionStore,
    private val clock: Clock = Clock.systemUTC(),
) : EconomyFigures {
    private val knownFactories: Set<String> = engine.factoryIds().toSet()

    override fun forCompany(connection: Connection, company: CompanyRecord, factories: List<FactoryRecord>): CompanyFigures {
        val known = withoutUnknown(company, factories)
        val multiplier = multiplierOf(connection, company)
        return CompanyFigures(
            ratePerHour = engine.ratePerHour(producingLevels(known), multiplier),
            multiplier = multiplier,
            threshold = engine.prestigeThreshold(company.courseNumber),
            factories = known.associate { factory ->
                factory.factoryId to FactoryFigures(
                    // Même multiplicateur que le total : la somme des usines qui produisent redonne le revenu de l'entreprise.
                    ratePerHour = if (factory.state.produces()) multiplier * engine.factoryRate(factory.factoryId, factory.level) else 0.0,
                    nextCost = engine.nextLevelCost(factory.factoryId, factory.level),
                    nextMilestone = engine.nextMilestone(factory.level),
                    // Pas encore de mécanique d'énergie : aucune usine ne s'arrête faute de courant.
                    stoppedNoPower = false,
                )
            },
        )
    }

    /**
     * L'absence se compte depuis `company.lastCollectAt`, jamais depuis l'heure de départ du joueur : la
     * production appartient à l'entreprise, pas à celui qui rentre. Quand deux associés se relaient, ou que
     * l'un joue pendant que l'autre est absent, `last_collect_at` avance à chaque calcul de production de
     * l'entreprise ; compter depuis le départ du joueur ferait payer deux fois la même heure (une fois à
     * chaque associé) ou oublierait celle qu'un associé a déjà encaissée. [playerLastSeenAt] ne sert donc pas
     * au calcul : il reste dans l'interface pour qui voudrait afficher « absent depuis ».
     *
     * Le plafond est celui de l'entreprise (`companies.offline_cap_hours`, étendu par les améliorations, repris de
     * `horsLigne.plafondHeures` à la création) ; la formule (plafond, efficacité) est celle du moteur. Le revenu
     * est celui de maintenant, multiplicateur d'aujourd'hui compris. Rien n'est écrit, pas même `last_collect_at`,
     * quand le gain est nul ou que l'horloge est en retard sur la base.
     */
    override fun settleOffline(connection: Connection, company: CompanyRecord, playerLastSeenAt: Instant?): Double {
        val production = pendingProduction(connection, company, store.loadFactories(connection, company.id))
        if (!(production.earned > 0.0)) return 0.0

        updateCompany(
            connection,
            company.id,
            company.version,
            CompanyColumn.DONAR to company.donar + production.earned,
            CompanyColumn.DONAR_EARNED to company.donarEarned + production.earned,
            CompanyColumn.LAST_COLLECT_AT to production.at,
        )
        return production.earned
    }

    /**
     * La même règle que [settleOffline], sans l'écriture : le temps écoulé depuis `company.lastCollectAt`, plafonné
     * par `company.offlineCapHours`, au revenu d'aujourd'hui et à l'efficacité hors ligne. `collect`, `buyLevels` et
     * `restoreStep` appellent ceci pour verser la production dans leur propre écriture.
     */
    override fun pendingProduction(connection: Connection, company: CompanyRecord, factories: List<FactoryRecord>): Production {
        // La base garde la milliseconde (DATETIME(3)) : on écrit et on compte avec elle.
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        val awayMillis = Duration.between(company.lastCollectAt, now).toMillis()
        if (awayMillis <= 0L) return Production(earned = 0.0, countedHours = 0.0, at = company.lastCollectAt)

        val awayHours = awayMillis.toDouble() / MILLIS_PER_HOUR
        val ratePerHour = engine.ratePerHour(producingLevels(withoutUnknown(company, factories)), multiplierOf(connection, company))
        return Production(
            earned = engine.offlineEarnings(ratePerHour, awayHours, company.offlineCapHours),
            countedHours = engine.offlineHours(awayHours, company.offlineCapHours),
            at = now,
        )
    }

    /** Le multiplicateur global : la moyenne des Lingots détenus par les membres, et les étoiles de l'entreprise. */
    private fun multiplierOf(connection: Connection, company: CompanyRecord): Double =
        engine.globalMultiplier(engine.averageHeldLingots(store.loadMemberLingots(connection, company.id)), company.stars)

    /** Le niveau des usines qui produisent, par identifiant : la matière du revenu de l'entreprise. */
    private fun producingLevels(factories: List<FactoryRecord>): Map<String, Int> =
        factories.filter { it.state.produces() }.associate { it.factoryId to it.level }

    /**
     * Une usine de la base que `usines.json` ne connaît plus n'a pas de chiffres (voir [CompanyFigures.forFactory]) et
     * ne compte pas dans le revenu : `join` ne tombe pas pour elle, mais le journal le dit.
     */
    private fun withoutUnknown(company: CompanyRecord, factories: List<FactoryRecord>): List<FactoryRecord> {
        val (known, unknown) = factories.partition { it.factoryId in knownFactories }
        if (unknown.isNotEmpty()) {
            log.warn("Entreprise {} : usine(s) absente(s) de usines.json, ignorée(s) : {}", company.id, unknown.map { it.factoryId })
        }
        return known
    }

    /**
     * Seule une usine en état `ACTIVE` produit. `EN_RUINE` (révélée, pas rachetée) et `REMISE_EN_ETAT` (rachetée,
     * les quatre étapes de remise en état en cours) ne rapportent rien, quel que soit leur niveau.
     */
    private fun FactoryState.produces(): Boolean = this == FactoryState.ACTIVE

    private companion object {
        val log = LoggerFactory.getLogger(EngineFigures::class.java)

        /** Les millisecondes d'une heure : une constante de la nature, pas un chiffre d'équilibrage. */
        val MILLIS_PER_HOUR: Double = TimeUnit.HOURS.toMillis(1).toDouble()
    }
}
