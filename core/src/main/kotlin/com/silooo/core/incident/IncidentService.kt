package com.silooo.core.incident

import com.silooo.core.api.CompanyState
import com.silooo.core.api.CompanyUpdated
import com.silooo.core.api.FactoryState
import com.silooo.core.db.Xuid
import com.silooo.core.economy.CompanyStore
import com.silooo.core.economy.EconomyEngine
import com.silooo.core.economy.RequestRejected
import com.silooo.core.economy.companyEvent
import com.silooo.core.economy.loadForActor
import com.silooo.core.economy.writeProduction
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.EconomyFigures
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.OnlinePlayers
import com.silooo.core.session.Regime
import com.silooo.core.session.SessionStore
import com.silooo.core.session.buildCompanyState
import java.sql.Connection
import java.time.Clock
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.put

/** Un incident que [IncidentService.tick] vient d'ouvrir. */
data class OpenedIncident(val incidentId: Long, val factoryId: String, val kind: String, val slowdown: Double)

/**
 * Les incidents d'usine, sans HTTP : l'ouverture ([tick], appelé par le minuteur du service) et la réparation ([resolve],
 * derrière `resolveIncident`). Ce service lit, décide et écrit dans la transaction que l'appelant lui donne ([Connection]).
 * Il ne contient aucun chiffre du jeu : cadence, sortes, ralentissements et récompense viennent de `definitions/incidents.json`
 * (par [IncidentDraw] et le [EconomyEngine]). Il ne contient aucun texte pour les joueurs non plus : l'add-on les écrit.
 *
 * Un incident ralentit une usine et ne fait jamais rien perdre : ni Donar, ni niveau, ni personnel. Il ne s'ouvre que pendant
 * qu'un membre de l'entreprise est connecté, jamais pendant une absence. Les règles que `incidents.json` ne dit pas (tranchées
 * au plus simple, **à valider par Kr**) :
 *  - au plus un incident ouvert par usine, et au plus un incident ouvert par créneau et par entreprise ;
 *  - le tirage ([IncidentDraw]) ne frappe qu'une usine `ACTIVE`, de niveau 1 au moins, sans incident ouvert et qui n'est pas
 *    à l'arrêt faute de courant ;
 *  - l'ouverture verse d'abord la production en attente au rythme d'avant : le ralentissement ne touche que ce qui vient ensuite ;
 *  - n'importe quel membre de l'entreprise peut réparer ; un incident déjà clos (réparé par un associé, ou clos par un prestige)
 *    ne rapporte rien et ne change rien : la réponse est l'état d'aujourd'hui, comme pour un `assignStaff` sans effet ;
 *  - la récompense est du Donar, comptée dans le Donar gagné de la course comme n'importe quelle production.
 */
class IncidentService(
    private val sessions: SessionStore,
    private val companies: CompanyStore,
    private val incidents: IncidentStore,
    private val engine: EconomyEngine,
    private val figures: EconomyFigures,
    private val draw: IncidentDraw,
    private val presence: OnlinePlayers,
    private val clock: Clock,
) {
    private val knownFactories: Set<String> = engine.factoryIds().toSet()

    /**
     * Joue le tirage du créneau d'aujourd'hui pour l'entreprise [companyId], dont [online] sont les membres connectés (au moins
     * un : l'appelant ne tire jamais pour une entreprise sans personne en ligne). Ouvre l'incident et rend lequel, ou rend `null`
     * quand le tirage ne tombe pas, qu'aucune usine ne peut être touchée, ou que l'entreprise a déjà eu son incident du créneau.
     *
     * Le tirage ne change pas d'un appel à l'autre dans le même créneau : un échec de verrou (une action de l'entreprise passe
     * en même temps) se reprend simplement à l'appel suivant.
     */
    fun tick(connection: Connection, companyId: Long, online: Collection<Xuid>): OpenedIncident? {
        require(online.isNotEmpty()) { "Un incident ne s'ouvre que pendant qu'un membre est connecté" }
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        val slot = draw.slotAt(now)
        val roll = draw.roll(companyId, slot) ?: return null
        if (incidents.startedSince(connection, companyId, slot.start)) return null

        val company = sessions.loadCompany(connection, companyId)
        val factories = sessions.loadFactories(connection, companyId)
        val numbers = figures.forCompany(connection, company, factories)
        val eligible = factories
            .filter { it.canBeHit() && !numbers.forFactory(it.factoryId).stoppedNoPower }
            .map { it.factoryId }
            .sorted()
        val factoryId = draw.factoryFor(roll, eligible) ?: return null

        // Le Donar produit jusqu'ici l'a été au rythme d'avant l'incident : on le verse avant d'écrire le ralentissement.
        val production = figures.pendingProduction(connection, company, factories, Regime.ONLINE)
        writeProduction(connection, company, production, spent = 0.0)
        val incidentId = incidents.open(connection, companyId, factoryId, roll.kind.id, roll.kind.slowdown, now)
        for (xuid in online) {
            companies.publish(
                connection,
                EVENT_KIND,
                xuid,
                companyEvent(ACTION_OPENED, companyId) {
                    put("incidentId", incidentId)
                    put("factoryId", factoryId)
                    put("kind", roll.kind.id)
                    put("slowdown", roll.kind.slowdown)
                },
            )
        }
        return OpenedIncident(incidentId, factoryId, roll.kind.id, roll.kind.slowdown)
    }

    /**
     * Clôt l'incident [incidentId] de l'entreprise [companyId] : verse la production en attente (au rythme ralenti, l'incident
     * n'était pas fini), puis la récompense, relance l'usine, publie l'événement. Un incident inconnu de l'entreprise, ou un acteur
     * qui n'en est pas membre : [RequestRejected]. Un incident déjà clos ne change rien (voir la description de la classe).
     */
    fun resolve(connection: Connection, companyId: Long, incidentId: Long, actor: Xuid): CompanyUpdated {
        val (company, factories) = sessions.loadForActor(connection, companyId, actor)
        val incident = incidents.find(connection, companyId, incidentId)
            ?: throw RequestRejected("Incident $incidentId inconnu de l'entreprise $companyId")
        if (incident.resolvedAt != null) return updated(connection, actor, company, factories)

        val production = figures.pendingProduction(connection, company, factories, Regime.ONLINE)
        val nominalRate = figures.forCompany(connection, company, factories).forFactory(incident.factoryId).nominalRatePerHour
        val reward = engine.incidentReward(nominalRate)
        // La récompense est une production de plus : elle s'écrit avec le reste, en UNE écriture de l'entreprise.
        val after = writeProduction(connection, company, production.copy(earned = production.earned + reward), spent = 0.0)
        check(incidents.close(connection, companyId, incidentId, clock.instant().truncatedTo(ChronoUnit.MILLIS))) {
            "L'incident $incidentId a été clos pendant que l'entreprise $companyId était verrouillée"
        }
        val audience = (sessions.loadMemberXuids(connection, companyId).filter { presence.isOnline(it) } + actor).distinct()
        for (xuid in audience) {
            companies.publish(
                connection,
                EVENT_KIND,
                xuid,
                companyEvent(ACTION_RESOLVED, companyId) {
                    put("incidentId", incidentId)
                    put("factoryId", incident.factoryId)
                    put("kind", incident.kind)
                    put("reward", reward)
                    put("resolvedBy", actor.text)
                },
            )
        }
        return updated(connection, actor, after, sessions.loadFactories(connection, companyId))
    }

    /** Une usine que l'incident peut frapper : elle produit, elle a au moins un niveau, `usines.json` la connaît et rien ne la ralentit déjà. */
    private fun FactoryRecord.canBeHit(): Boolean =
        state == FactoryState.ACTIVE && level >= 1 && openIncidentId == null && factoryId in knownFactories

    private fun stateOf(connection: Connection, company: CompanyRecord, factories: List<FactoryRecord>): CompanyState =
        buildCompanyState(company, factories, figures.forCompany(connection, company, factories), sessions.loadZones(connection, company.id))

    private fun updated(connection: Connection, actor: Xuid, company: CompanyRecord, factories: List<FactoryRecord>): CompanyUpdated =
        CompanyUpdated(stateOf(connection, company, factories), sessions.loadBalances(connection, actor))

    companion object {
        /** Le type d'événement des incidents : `INCIDENT`, le détail est dans `payload.action` (voir `GameEvent` dans le contrat). */
        val EVENT_KIND = GameEventKind.INCIDENT

        /** Valeurs de `payload.action`. */
        const val ACTION_OPENED = "opened"
        const val ACTION_RESOLVED = "resolved"
    }
}
