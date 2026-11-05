package com.silooo.core.economy

import com.silooo.core.api.FactoryState
import com.silooo.core.api.Staff
import com.silooo.core.db.CompanyColumn
import com.silooo.core.db.updateCompany
import com.silooo.core.definitions.StaffRole
import com.silooo.core.session.CompanyFigures
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.EconomyFigures
import com.silooo.core.session.FactoryFigures
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.Production
import com.silooo.core.session.Regime
import com.silooo.core.session.SessionStore
import com.silooo.core.session.StaffRecord
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
        val staff = store.loadStaff(connection, company.id)
        val crews = crewsOf(known, staff)
        return CompanyFigures(
            ratePerHour = engine.ratePerHour(producingLevels(known), multiplier, crews),
            multiplier = multiplier,
            threshold = engine.prestigeThreshold(company.courseNumber),
            factories = known.associate { factory ->
                factory.factoryId to FactoryFigures(
                    // Même multiplicateur que le total : la somme des usines qui produisent redonne le revenu de l'entreprise.
                    ratePerHour = if (factory.state.produces()) {
                        multiplier * engine.factoryRate(factory.factoryId, factory.level, crews[factory.factoryId].orEmpty())
                    } else {
                        0.0
                    },
                    nextCost = engine.nextLevelCost(factory.factoryId, factory.level),
                    nextMilestone = engine.nextMilestone(factory.level),
                    // Pas encore de mécanique d'énergie : aucune usine ne s'arrête faute de courant.
                    stoppedNoPower = false,
                )
            },
            staff = staff.map { member -> staffView(member, known, multiplier) },
        )
    }

    /**
     * Le personnel qui travaille, par usine : seuls comptent les membres affectés à une usine qui produit (`ACTIVE`, connue
     * de `usines.json`). Un membre sans usine, ou dans une usine en ruine, ne rapporte rien et ne coûte rien. L'ordre est
     * celui de [StaffRecord.id], donc le même à chaque calcul.
     */
    private fun crewsOf(known: List<FactoryRecord>, staff: List<StaffRecord>): Map<String, List<CrewMember>> {
        if (staff.isEmpty()) return emptyMap()
        val producing = known.filter { it.state.produces() }.map { it.factoryId }.toSet()
        val crews = LinkedHashMap<String, MutableList<CrewMember>>()
        for (member in staff) {
            val factoryId = member.factoryId ?: continue
            if (factoryId in producing) crews.getOrPut(factoryId) { mutableListOf() } += CrewMember(member.role, member.rarity)
        }
        return crews
    }

    /** Un membre du personnel tel que le contrat le montre : ses pourcents, et ce que son salaire coûte par heure maintenant. */
    private fun staffView(member: StaffRecord, known: List<FactoryRecord>, multiplier: Double): Staff {
        val workplace = known.firstOrNull { it.factoryId == member.factoryId && it.state.produces() }
        return Staff(
            staffId = member.id,
            name = member.name,
            skinId = member.skinId,
            role = member.role,
            rarity = member.rarity,
            bonusPercent = engine.staffBonusPercent(member.rarity),
            salaryPercent = engine.staffSalaryPercent(member.role, member.rarity),
            salaryPerHour = if (workplace == null) 0.0 else engine.salaryPerHour(workplace.factoryId, workplace.level, member.role, member.rarity, multiplier),
            factoryId = member.factoryId,
        )
    }

    /**
     * La période se compte depuis `company.lastCollectAt`, jamais depuis l'heure de départ du joueur : la
     * production appartient à l'entreprise, pas à celui qui rentre. Quand deux associés se relaient, ou que
     * l'un joue pendant que l'autre est absent, `last_collect_at` avance à chaque calcul de production de
     * l'entreprise ; compter depuis le départ du joueur ferait payer deux fois la même heure (une fois à
     * chaque associé) ou oublierait celle qu'un associé a déjà encaissée. [playerLastSeenAt] ne sert donc pas
     * au calcul : il reste dans l'interface pour qui voudrait afficher « absent depuis ».
     *
     * Le régime dit comment la période se paie (voir [pendingProduction]). `last_collect_at` est remis à maintenant
     * dès que du temps s'est écoulé, même quand le gain est nul (plafond nul, aucune usine active) : sans cela, un
     * `collect` ultérieur compterait ces heures au revenu plein. Il n'y a rien à écrire, et rien n'est écrit, quand
     * l'horloge n'a pas avancé ou qu'elle est en retard sur la base.
     */
    override fun settle(connection: Connection, company: CompanyRecord, regime: Regime, playerLastSeenAt: Instant?): Double {
        val production = pendingProduction(connection, company, store.loadFactories(connection, company.id), regime)
        if (production.at == company.lastCollectAt) return 0.0

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
     * Le temps écoulé depuis `company.lastCollectAt`, au revenu d'aujourd'hui (multiplicateur de maintenant compris),
     * payé selon le [regime] :
     *  - [Regime.ONLINE] : le revenu plein sur toutes les heures, sans plafond ni efficacité ([EconomyEngine.onlineEarnings]) ;
     *  - [Regime.OFFLINE] : la formule du hors ligne, heures plafonnées par `company.offlineCapHours` et efficacité
     *    `horsLigne.efficacite` ([EconomyEngine.offlineEarnings]).
     *
     * `collect`, `buyLevels` et `restoreStep` appellent ceci en ligne et versent la production dans leur propre écriture.
     */
    override fun pendingProduction(connection: Connection, company: CompanyRecord, factories: List<FactoryRecord>, regime: Regime): Production {
        // La base garde la milliseconde (DATETIME(3)) : on écrit et on compte avec elle.
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        val awayMillis = Duration.between(company.lastCollectAt, now).toMillis()
        if (awayMillis <= 0L) return Production(earned = 0.0, countedHours = 0.0, at = company.lastCollectAt)

        val awayHours = awayMillis.toDouble() / MILLIS_PER_HOUR
        val known = withoutUnknown(company, factories)
        val multiplier = multiplierOf(connection, company)
        val crews = crewsOf(known, store.loadStaff(connection, company.id))
        val producing = producingLevels(known)
        val ratePerHour = engine.ratePerHour(producing, multiplier, crews)
        return when (regime) {
            // Le salaire est déjà retiré du revenu net : en ligne comme hors ligne, il se paie au rythme du revenu.
            Regime.ONLINE -> Production(earned = engine.onlineEarnings(ratePerHour, awayHours), countedHours = awayHours, at = now)
            Regime.OFFLINE -> offlineProduction(producing, crews, multiplier, ratePerHour, company.offlineCapHours, awayHours, now)
        }
    }

    /**
     * La production hors ligne. Sans chef d'équipe nulle part, c'est la formule de toujours ([EconomyEngine.offlineEarnings]) : le
     * plafond de l'entreprise s'applique à tout. Avec un chef dans une usine, cette usine compte jusqu'au plafond relevé
     * ([EconomyEngine.leaderCapHours]) et les autres restent au plafond de l'entreprise ; les heures comptées sont alors celles du
     * plafond relevé (la plus longue des deux).
     */
    private fun offlineProduction(
        producing: Map<String, Int>,
        crews: Map<String, List<CrewMember>>,
        multiplier: Double,
        ratePerHour: Double,
        capHours: Double,
        awayHours: Double,
        at: Instant,
    ): Production {
        val led = producing.filterKeys { factoryId -> crews[factoryId].orEmpty().any { it.role == StaffRole.CHEF } }
        if (led.isEmpty()) {
            return Production(
                earned = engine.offlineEarnings(ratePerHour, awayHours, capHours),
                countedHours = engine.offlineHours(awayHours, capHours),
                at = at,
            )
        }
        val others = producing.filterKeys { it !in led }
        return Production(
            earned = engine.offlineEarningsWithLeaders(
                othersRatePerHour = engine.ratePerHour(others, multiplier, crews),
                ledRatePerHour = engine.ratePerHour(led, multiplier, crews),
                awayHours = awayHours,
                capHours = capHours,
            ),
            countedHours = engine.offlineHours(awayHours, engine.leaderCapHours(capHours)),
            at = at,
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
