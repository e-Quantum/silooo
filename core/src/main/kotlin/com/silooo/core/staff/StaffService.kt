package com.silooo.core.staff

import com.silooo.core.api.CompanyState
import com.silooo.core.api.CompanyUpdated
import com.silooo.core.api.FactoryState
import com.silooo.core.api.Refusal
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.Role
import com.silooo.core.api.Staff
import com.silooo.core.api.refuse
import com.silooo.core.db.Xuid
import com.silooo.core.definitions.StaffRole
import com.silooo.core.economy.CompanyStore
import com.silooo.core.economy.EconomyEngine
import com.silooo.core.economy.RequestRejected
import com.silooo.core.economy.companyEvent
import com.silooo.core.economy.finiteOrMax
import com.silooo.core.economy.loadForActor
import com.silooo.core.economy.requireMembership
import com.silooo.core.economy.writeProduction
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.EconomyFigures
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.Regime
import com.silooo.core.session.SessionStore
import com.silooo.core.session.StaffRecord
import com.silooo.core.session.buildCompanyState
import io.ktor.http.HttpStatusCode
import java.sql.Connection
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.put

/**
 * Le personnel, sans HTTP : `getCandidates`, `hire`, `assignStaff` et `fireStaff`. Les routes valident l'entrée et
 * répondent ; ce service lit, décide et écrit, dans la transaction que l'appelant lui donne ([Connection]). Il ne contient
 * aucun chiffre du jeu : prix, postes, bonus et salaires viennent du [EconomyEngine], qui les tire de
 * `definitions/personnel.json`. Il ne contient aucun texte pour les joueurs non plus : les noms et les apparences viennent
 * de la liste de l'agence, que l'add-on affiche.
 *
 * Une action d'écriture suit le déroulé de [com.silooo.core.economy.CompanyService] :
 *  1. L'acteur est membre de l'entreprise ; sinon [RequestRejected] (400).
 *  2. Les refus du jeu (`Refusal`) tombent avant la première écriture.
 *  3. La production en attente est versée d'abord ([EconomyFigures.pendingProduction], en ligne) avec le reste de l'action,
 *     en UNE écriture `updateCompany` (verrou optimiste) : le revenu change avec l'équipe, et l'équipe d'avant a produit
 *     jusqu'à maintenant, jamais au-delà. Puis le personnel est écrit, puis l'événement.
 *  4. L'état rendu est bâti sur ce qui vient d'être écrit.
 *
 * Les règles que personnel.json ne dit pas (et qu'on a tranchées au plus simple, **à valider par Kr**) :
 *  - tout membre de l'entreprise peut embaucher et affecter ; seul le PDG renvoie ;
 *  - un candidat embauché reste dans la liste du jour avec son `staffId` ; renvoyé, il redevient embauchable aujourd'hui au
 *    prix du moment ;
 *  - un membre sans usine, ou dans une usine qui ne produit pas, ne rapporte rien et ne coûte rien ;
 *  - les postes ne limitent que les embauches et les affectations : une usine qui perd des postes (prestige) garde son équipe.
 */
class StaffService(
    private val sessions: SessionStore,
    private val companies: CompanyStore,
    private val staff: StaffStore,
    private val engine: EconomyEngine,
    private val figures: EconomyFigures,
    private val draw: AgencyDraw,
    private val clock: Clock,
) {
    private val knownFactories: Set<String> = engine.factoryIds().toSet()

    /**
     * La liste du jour de l'entreprise [companyId], ou `null` si elle n'existe pas. Rien n'est écrit. Un candidat libre porte son
     * prix d'embauche au revenu de maintenant ; un candidat embauché porte son `staffId` et son usine.
     */
    fun candidates(connection: Connection, companyId: Long): List<Staff>? {
        if (!companies.companyExists(connection, companyId)) return null
        val company = sessions.loadCompany(connection, companyId)
        val factories = sessions.loadFactories(connection, companyId)
        val day = today()
        val hired = staff.hiredSlots(connection, companyId, day).associateBy { it.candidate.slot }
        val members = sessions.loadStaff(connection, companyId).associateBy { it.id }
        val revenue = figures.forCompany(connection, company, factories).ratePerHour
        return draw.candidates(companyId, day).map { proposed ->
            val taken = hired[proposed.slot]
            if (taken == null) {
                view(proposed).copy(hireCost = engine.hireCost(proposed.role, proposed.rarity, revenue))
            } else {
                // Le candidat tel qu'il a été proposé et embauché, même si personnel.json a changé depuis.
                view(taken.candidate).copy(staffId = taken.staffId, factoryId = members[taken.staffId]?.factoryId)
            }
        }
    }

    /**
     * Embauche le candidat de la place [slot] de la liste du jour, payé en Donar sur le compte de l'entreprise, affecté à
     * [factoryId] s'il est donné. Refus : `DEJA_POSSEDE` (place déjà embauchée), `USINE_VERROUILLEE` (usine qui n'est pas à
     * l'entreprise ou pas active), `EMPLACEMENT_OCCUPE` (plus de poste libre pour ce rôle), `PAS_ASSEZ_DE_DONAR` avec la somme
     * qui manque.
     */
    fun hire(connection: Connection, companyId: Long, slot: Int, factoryId: String?, actor: Xuid): CompanyUpdated {
        if (slot !in 1..draw.slots) throw RequestRejected("Place de l'agence hors de 1..${draw.slots} : $slot")
        if (factoryId != null) requireKnownFactory(factoryId)
        val (company, factories) = sessions.loadForActor(connection, companyId, actor)

        val day = today()
        val candidate = draw.candidates(companyId, day)[slot - 1]
        if (staff.hiredSlots(connection, companyId, day).any { it.candidate.slot == slot }) refuse(RefusalCodes.ALREADY_OWNED)
        if (factoryId != null) {
            requireOpenPost(factories.firstOrNull { it.factoryId == factoryId }, candidate.role, sessions.loadStaff(connection, companyId), except = null)
        }

        val production = figures.pendingProduction(connection, company, factories, Regime.ONLINE)
        val balance = company.donar + production.earned
        val cost = engine.hireCost(candidate.role, candidate.rarity, figures.forCompany(connection, company, factories).ratePerHour)
        if (cost > balance) refuse(RefusalCodes.NOT_ENOUGH_DONAR, missing = (cost - balance).finiteOrMax())

        val after = writeProduction(connection, company, production, spent = cost)
        val bonus = engine.staffBonusPercent(candidate.rarity)
        val staffId = staff.insert(connection, companyId, candidate, factoryId, bonus)
        staff.recordHire(connection, companyId, day, candidate, bonus, staffId)
        companies.publish(
            connection,
            EVENT_KIND,
            actor,
            companyEvent(ACTION_HIRE, companyId) {
                put("staffId", staffId)
                put("slot", slot)
                put("role", candidate.role.name)
                put("rarity", candidate.rarity.name)
                if (factoryId != null) put("factoryId", factoryId)
                put("cost", cost)
            },
        )
        return updated(connection, actor, after, factories)
    }

    /**
     * Affecte le membre [staffId] à l'usine [factoryId], sans frais. Refus : `USINE_VERROUILLEE` (usine qui n'est pas à l'entreprise
     * ou pas active), `EMPLACEMENT_OCCUPE` (plus de poste libre pour son rôle). Un membre inconnu de l'entreprise : [RequestRejected].
     * L'affecter là où il est déjà ne change rien : ni écriture, ni événement.
     */
    fun assign(connection: Connection, companyId: Long, staffId: Long, factoryId: String, actor: Xuid): CompanyUpdated {
        requireKnownFactory(factoryId)
        val (company, factories) = sessions.loadForActor(connection, companyId, actor)
        val member = staff.find(connection, companyId, staffId) ?: throw RequestRejected("Membre $staffId inconnu de l'entreprise $companyId")
        val workplace = factories.firstOrNull { it.factoryId == factoryId }
        if (member.factoryId == factoryId && workplace?.state == FactoryState.ACTIVE) return updated(connection, actor, company, factories)
        requireOpenPost(workplace, member.role, sessions.loadStaff(connection, companyId), except = member.id)

        val production = figures.pendingProduction(connection, company, factories, Regime.ONLINE)
        val after = writeProduction(connection, company, production, spent = 0.0)
        staff.assign(connection, companyId, staffId, factoryId)
        companies.publish(
            connection,
            EVENT_KIND,
            actor,
            companyEvent(ACTION_ASSIGN, companyId) {
                put("staffId", staffId)
                put("factoryId", factoryId)
            },
        )
        return updated(connection, actor, after, factories)
    }

    /**
     * Renvoie le membre [staffId] : le PDG seulement (`PAS_LE_PDG`, 403, pour un associé). Définitif et sans remboursement. Un membre
     * inconnu de l'entreprise : [RequestRejected].
     */
    fun fire(connection: Connection, companyId: Long, staffId: Long, actor: Xuid): CompanyUpdated {
        val membership = sessions.requireMembership(connection, companyId, actor)
        if (membership.role != Role.PDG) throw Refusal(HttpStatusCode.Forbidden, RefusalCodes.NOT_THE_CEO)
        val member = staff.find(connection, companyId, staffId) ?: throw RequestRejected("Membre $staffId inconnu de l'entreprise $companyId")
        val company = sessions.loadCompany(connection, companyId)
        val factories = sessions.loadFactories(connection, companyId)

        val production = figures.pendingProduction(connection, company, factories, Regime.ONLINE)
        val after = writeProduction(connection, company, production, spent = 0.0)
        staff.delete(connection, companyId, staffId)
        companies.publish(
            connection,
            EVENT_KIND,
            actor,
            companyEvent(ACTION_FIRE, companyId) {
                put("staffId", staffId)
                put("role", member.role.name)
                put("rarity", member.rarity.name)
            },
        )
        return updated(connection, actor, after, factories)
    }

    /** Le jour de l'agence : le jour UTC de l'horloge du service. */
    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

    private fun requireKnownFactory(factoryId: String) {
        if (factoryId !in knownFactories) throw RequestRejected("Usine inconnue de usines.json : $factoryId")
    }

    /**
     * Vérifie que [factory] accepte un membre de plus du rôle [role] : l'usine est à l'entreprise et active (`USINE_VERROUILLEE`),
     * et le nombre de membres de ce rôle qui y travaillent (sans compter [except], déjà là) est sous ses postes (`EMPLACEMENT_OCCUPE`).
     */
    private fun requireOpenPost(factory: FactoryRecord?, role: StaffRole, members: List<StaffRecord>, except: Long?) {
        if (factory == null || factory.state != FactoryState.ACTIVE) refuse(RefusalCodes.FACTORY_LOCKED)
        val filled = members.count { it.factoryId == factory.factoryId && it.role == role && it.id != except }
        if (filled >= engine.staffPosts(role, factory.level)) refuse(RefusalCodes.SPOT_TAKEN)
    }

    /** Un candidat tel que le contrat le montre, sans prix ni embauche : ses pourcents sont ceux de sa rareté et de son rôle. */
    private fun view(candidate: Candidate): Staff = Staff(
        slot = candidate.slot,
        name = candidate.name,
        skinId = candidate.skinId,
        role = candidate.role,
        rarity = candidate.rarity,
        bonusPercent = engine.staffBonusPercent(candidate.rarity),
        salaryPercent = engine.staffSalaryPercent(candidate.role, candidate.rarity),
    )

    private fun stateOf(connection: Connection, company: CompanyRecord, factories: List<FactoryRecord>): CompanyState =
        buildCompanyState(company, factories, figures.forCompany(connection, company, factories), sessions.loadZones(connection, company.id))

    private fun updated(connection: Connection, actor: Xuid, company: CompanyRecord, factories: List<FactoryRecord>): CompanyUpdated =
        CompanyUpdated(stateOf(connection, company, factories), sessions.loadBalances(connection, actor))

    companion object {
        /** Le type d'événement des trois actions : `ANNONCE` générique, le détail est dans `payload.action` (comme `collect`). */
        val EVENT_KIND = GameEventKind.ANNONCE

        /** Valeurs de `payload.action`. */
        const val ACTION_HIRE = "hire"
        const val ACTION_ASSIGN = "assignStaff"
        const val ACTION_FIRE = "fireStaff"
    }
}
