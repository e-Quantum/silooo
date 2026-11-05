package com.silooo.core.members

import com.silooo.core.db.Xuid
import java.sql.Connection
import java.time.Instant

/** Une ligne de `company_invites` : [invitedBy], le PDG de [companyId], a invité [invitee] jusqu'à [expiresAt] (UTC). */
data class Invitation(val companyId: Long, val invitee: Xuid, val invitedBy: Xuid, val expiresAt: Instant)

/**
 * Les accès SQL de l'entreprise partagée (`company_members`, `company_invites`), derrière une interface : les tests de route
 * tournent avec un faux en mémoire, le SQL avec la vraie base (Testcontainers, `MemberSqlTest`). La lecture des membres passe
 * par `SessionStore` (`loadMembers`, `loadMemberXuids`), le Donar de l'entreprise s'écrit par `updateCompany`, l'événement par
 * `CompanyStore.publish`. Aucune migration : les deux tables de V1 suffisent.
 *
 * Toute écriture de membre suit l'écriture de l'entreprise (`updateCompany`, verrou optimiste sur `companies.version`) : c'est la
 * version qui protège ces lignes d'une action concurrente.
 */
interface MemberStore {
    /** Garde l'invitation. Le même joueur invité deux fois par la même entreprise n'a qu'une ligne : la seconde renouvelle la première. */
    fun saveInvitation(connection: Connection, invitation: Invitation)

    /** L'invitation de [invitee] à l'entreprise [companyId], périmée ou non, ou `null`. */
    fun findInvitation(connection: Connection, companyId: Long, invitee: Xuid): Invitation?

    /** Supprime toutes les invitations de [invitee], de toutes les entreprises : il vient d'en rejoindre une. */
    fun deleteInvitations(connection: Connection, invitee: Xuid)

    /**
     * Fait passer [xuid] de l'entreprise [fromCompanyId] à l'entreprise [toCompanyId], comme `ASSOCIE` avec une part nulle, arrivé
     * maintenant. L'entreprise [fromCompanyId] reste, sans membre. Rend faux si [xuid] n'est plus membre de [fromCompanyId]
     * (un autre appel l'a déplacé entre-temps).
     */
    fun moveMember(connection: Connection, xuid: Xuid, fromCompanyId: Long, toCompanyId: Long): Boolean

    /** Écrit la part de chaque membre nommé de l'entreprise [companyId] (`share_percent`). */
    fun saveShares(connection: Connection, companyId: Long, shares: Map<Xuid, Int>)
}
