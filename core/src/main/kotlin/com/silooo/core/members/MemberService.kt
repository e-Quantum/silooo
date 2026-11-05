package com.silooo.core.members

import com.silooo.core.api.CompanyState
import com.silooo.core.api.CompanyUpdated
import com.silooo.core.api.Refusal
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.Role
import com.silooo.core.api.refuse
import com.silooo.core.db.VersionConflict
import com.silooo.core.db.Xuid
import com.silooo.core.db.updateCompany
import com.silooo.core.definitions.CompanyDefinition
import com.silooo.core.economy.CompanyStore
import com.silooo.core.economy.RequestRejected
import com.silooo.core.economy.companyEvent
import com.silooo.core.economy.requireMembership
import com.silooo.core.economy.writeProduction
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.CompanyBootstrap
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.EconomyFigures
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.OnlinePlayers
import com.silooo.core.session.Regime
import com.silooo.core.session.SessionStore
import com.silooo.core.session.buildCompanyState
import io.ktor.http.HttpStatusCode
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * L'entreprise partagée, sans HTTP : `invite`, `acceptInvite` et `setShares`. Les routes valident l'entrée et répondent ; ce
 * service lit, décide et écrit, dans la transaction que l'appelant lui donne ([Connection]). Il ne contient aucun chiffre du
 * jeu : le nombre de membres au plus et la durée d'une invitation viennent de `definitions/monde.json` ([CompanyDefinition]),
 * les usines de départ de [CompanyBootstrap]. Il ne contient aucun texte pour les joueurs non plus.
 *
 * Les règles que le contrat ne dit pas (tranchées au plus simple et au moins destructeur, **à valider par Kr**) :
 *  - une invitation est une ligne de `company_invites` (valable `entreprise.invitationHeures`) et un événement `INVITATION`
 *    pour l'invité ; elle ne change rien à l'entreprise tant que l'invité ne l'a pas acceptée par `acceptInvite` ;
 *  - un joueur n'est membre que d'une entreprise, et tout joueur en a une depuis son premier `join` : celle de l'invité doit
 *    être « à son départ » ([isUnderway]), sinon `ENTREPRISE_EN_COURS`, à l'invitation comme à l'acceptation ;
 *  - quand l'invité entre, son entreprise de départ n'est JAMAIS supprimée : elle reste, sans membre, avec ses usines et son île ;
 *  - l'associé qui entre a une part nulle : les parts de l'entreprise font toujours 100 et le PDG n'en perd aucune sans l'avoir
 *    décidé par `setShares` ;
 *  - la production gagnée jusqu'à l'arrivée est versée avant l'arrivée, au régime d'avant (en ligne si un membre est connecté,
 *    sinon hors ligne) : l'arrivée d'un joueur connecté ne transforme pas en revenu plein des heures où personne ne jouait.
 */
class MemberService(
    private val sessions: SessionStore,
    private val companies: CompanyStore,
    private val members: MemberStore,
    private val figures: EconomyFigures,
    private val bootstrap: CompanyBootstrap,
    private val limits: CompanyDefinition,
    private val online: OnlinePlayers,
    private val clock: Clock,
) {
    /**
     * Le PDG [actor] invite [target]. Refus : `PAS_LE_PDG` (403, l'acteur est un associé), `DEJA_POSSEDE` ([target] est déjà
     * membre), `ENTREPRISE_PLEINE`, `ENTREPRISE_EN_COURS` (l'entreprise de [target] est déjà lancée). Un acteur qui n'est pas de
     * l'entreprise, un [target] inconnu ou égal à l'acteur : [RequestRejected]. Rend l'état de l'entreprise, inchangé.
     */
    fun invite(connection: Connection, companyId: Long, target: Xuid, actor: Xuid): CompanyUpdated {
        val membership = sessions.requireMembership(connection, companyId, actor)
        if (membership.role != Role.PDG) throw Refusal(HttpStatusCode.Forbidden, RefusalCodes.NOT_THE_CEO)
        if (target == actor) throw RequestRejected("Le PDG $actor ne peut pas s'inviter lui-même")
        val targetMembership = sessions.membershipOf(connection, target) ?: throw RequestRejected("Joueur inconnu du service : $target")

        val current = sessions.loadMemberXuids(connection, companyId)
        if (target in current) refuse(RefusalCodes.ALREADY_OWNED)
        if (current.size >= limits.maxMembers) refuse(RefusalCodes.COMPANY_FULL)
        if (isUnderway(connection, targetMembership.companyId)) refuse(RefusalCodes.ENTERPRISE_UNDERWAY)

        val company = sessions.loadCompany(connection, companyId)
        val factories = sessions.loadFactories(connection, companyId)
        val expiresAt = clock.instant().plus(Duration.ofHours(limits.invitationHours.toLong()))
        members.saveInvitation(connection, Invitation(companyId, target, actor, expiresAt))
        val inviter = sessions.loadMembers(connection, companyId).first { it.xuid == actor }
        companies.publish(
            connection,
            GameEventKind.INVITATION,
            target,
            buildJsonObject {
                put("companyId", companyId)
                put("companyName", company.name)
                put("invitedBy", actor.text)
                put("invitedByName", inviter.name)
                put("expiresAt", DateTimeFormatter.ISO_INSTANT.format(expiresAt))
            },
        )
        return updated(connection, actor, company, factories)
    }

    /**
     * [actor] accepte l'invitation de l'entreprise [companyId] : il y entre comme associé, à 0 %, et quitte son entreprise de départ
     * (qui reste, sans membre). Refus : `DEJA_POSSEDE` (déjà membre de [companyId]), `ENTREPRISE_PLEINE`, `ENTREPRISE_EN_COURS` (son
     * entreprise est déjà lancée). Une invitation absente ou périmée, un acteur sans entreprise : [RequestRejected].
     */
    fun accept(connection: Connection, companyId: Long, actor: Xuid): CompanyUpdated {
        val own = sessions.membershipOf(connection, actor) ?: throw RequestRejected("Joueur sans entreprise : $actor")
        val invitation = members.findInvitation(connection, companyId, actor)
        if (invitation == null || !invitation.expiresAt.isAfter(clock.instant())) {
            throw RequestRejected("Aucune invitation valable de l'entreprise $companyId pour $actor")
        }
        if (own.companyId == companyId) refuse(RefusalCodes.ALREADY_OWNED)

        val company = sessions.loadCompany(connection, companyId)
        val factories = sessions.loadFactories(connection, companyId)
        val current = sessions.loadMemberXuids(connection, companyId)
        if (current.size >= limits.maxMembers) refuse(RefusalCodes.COMPANY_FULL)
        if (isUnderway(connection, own.companyId)) refuse(RefusalCodes.ENTERPRISE_UNDERWAY)

        // L'entreprise rejointe a produit sans lui : on le verse d'abord, au régime d'avant son arrivée. Cette écriture passe la
        // version de l'entreprise, qui protège aussi le nombre de membres de deux arrivées en même temps.
        val regime = if (current.any(online::isOnline)) Regime.ONLINE else Regime.OFFLINE
        val after = writeProduction(connection, company, figures.pendingProduction(connection, company, factories, regime), spent = 0.0)
        if (!members.moveMember(connection, actor, own.companyId, companyId)) throw VersionConflict()
        members.deleteInvitations(connection, actor)
        companies.publish(
            connection,
            GameEventKind.ANNONCE,
            actor,
            companyEvent(ACTION_ACCEPT, companyId) { put("fromCompanyId", own.companyId) },
        )
        return updated(connection, actor, after, factories)
    }

    /**
     * Le PDG [actor] fixe la part de chaque membre : [shares] nomme chaque membre une fois, de 0 à 100, pour 100 en tout. Refus :
     * `PAS_LE_PDG` (403). Une liste qui oublie un membre, en nomme un qui n'en est pas, deux fois le même, hors de 0 à 100 ou qui ne
     * fait pas 100 : [RequestRejected] (la requête est fausse, ce n'est pas un refus du jeu). La version de l'entreprise passe : un
     * prestige qui lisait les anciennes parts échoue, et `CompanyState.members` montre les nouvelles.
     */
    fun setShares(connection: Connection, companyId: Long, shares: List<Pair<Xuid, Int>>, actor: Xuid): CompanyUpdated {
        val membership = sessions.requireMembership(connection, companyId, actor)
        if (membership.role != Role.PDG) throw Refusal(HttpStatusCode.Forbidden, RefusalCodes.NOT_THE_CEO)

        val named = shares.map { it.first }
        if (named.size != named.toSet().size) throw RequestRejected("Un membre est nommé deux fois")
        if (named.toSet() != sessions.loadMemberXuids(connection, companyId).toSet()) {
            throw RequestRejected("Les parts doivent nommer chacun des membres de l'entreprise $companyId, et eux seuls")
        }
        if (shares.any { it.second !in 0..FULL_SHARE }) throw RequestRejected("Une part est hors de 0 à $FULL_SHARE")
        if (shares.sumOf { it.second } != FULL_SHARE) throw RequestRejected("Les parts doivent faire $FULL_SHARE en tout")

        val company = sessions.loadCompany(connection, companyId)
        val factories = sessions.loadFactories(connection, companyId)
        val version = updateCompany(connection, companyId, company.version)
        members.saveShares(connection, companyId, shares.toMap())
        companies.publish(
            connection,
            GameEventKind.ANNONCE,
            actor,
            companyEvent(ACTION_SET_SHARES, companyId) {
                put(
                    "shares",
                    buildJsonArray {
                        for ((xuid, percent) in shares) {
                            add(
                                buildJsonObject {
                                    put("xuid", xuid.text)
                                    put("percent", percent)
                                },
                            )
                        }
                    },
                )
            },
        )
        return updated(connection, actor, company.copy(version = version), factories)
    }

    /**
     * Vrai si l'entreprise [companyId] est déjà lancée : un autre membre que son PDG, un prestige ou une course déjà faits, une
     * mairie ou un silo améliorés, du personnel, une zone de la ville, ou des usines qui ne sont plus exactement celles du départ
     * (une usine de plus, un niveau gagné, une étape de remise en état faite). Elle ne peut alors pas rejoindre une autre entreprise.
     */
    private fun isUnderway(connection: Connection, companyId: Long): Boolean {
        val company = sessions.loadCompany(connection, companyId)
        if (sessions.loadMemberXuids(connection, companyId).size > 1) return true
        if (company.stars != 0 || company.courseNumber != FIRST || company.era != FIRST) return true
        if (company.mairieLevel != FIRST || company.siloLevel != FIRST) return true
        if (sessions.loadStaff(connection, companyId).isNotEmpty() || sessions.loadZones(connection, companyId).isNotEmpty()) return true
        val now = sessions.loadFactories(connection, companyId).map { listOf(it.factoryId, it.state, it.level, it.restoreStep) }.toSet()
        val start = bootstrap.startingFactories().map { listOf(it.factoryId, it.state, it.level, it.restoreStep) }.toSet()
        return now != start
    }

    private fun updated(connection: Connection, actor: Xuid, company: CompanyRecord, factories: List<FactoryRecord>): CompanyUpdated {
        val state: CompanyState = buildCompanyState(
            company,
            factories,
            figures.forCompany(connection, company, factories),
            sessions.loadZones(connection, company.id),
        )
        return CompanyUpdated(state, sessions.loadBalances(connection, actor))
    }

    companion object {
        /** Valeurs de `payload.action` des événements `ANNONCE`. */
        const val ACTION_ACCEPT = "acceptInvite"
        const val ACTION_SET_SHARES = "setShares"

        /** Les parts des membres font 100 : un pourcentage entier, pas un chiffre d'équilibrage. */
        const val FULL_SHARE = 100

        /** La première course, la première époque, le premier niveau de mairie et de silo : l'entreprise n'a rien fait encore. */
        private const val FIRST = 1
    }
}
