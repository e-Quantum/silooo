package com.silooo.core.members

import com.silooo.core.api.Role
import com.silooo.core.db.Xuid
import com.silooo.core.session.InMemorySessionStore
import com.silooo.core.session.Membership
import java.sql.Connection

/**
 * Le faux en mémoire de [MemberStore], pour les tests des routes de l'entreprise partagée sans base. Les membres vivent dans le
 * faux [InMemorySessionStore] (`members`, `sharePercents`), que le moteur d'économie et `CompanyState.members` lisent ; les
 * invitations sont ici, comme `company_invites`. Le SQL lui-même se teste sur MariaDB (`MemberSqlTest`).
 */
class InMemoryMemberStore(private val sessions: InMemorySessionStore) : MemberStore {
    /** Une écriture de membre, dans l'ordre : ce que le service a demandé. */
    sealed interface Write {
        data class Moved(val xuid: Xuid, val fromCompanyId: Long, val toCompanyId: Long) : Write
        data class Shares(val companyId: Long, val shares: Map<Xuid, Int>) : Write
    }

    /** `company_invites`, une ligne par (entreprise, invité). */
    val invitations = LinkedHashMap<Pair<Long, Xuid>, Invitation>()
    val writes = mutableListOf<Write>()

    override fun saveInvitation(connection: Connection, invitation: Invitation) {
        invitations[invitation.companyId to invitation.invitee] = invitation
    }

    override fun findInvitation(connection: Connection, companyId: Long, invitee: Xuid): Invitation? = invitations[companyId to invitee]

    override fun deleteInvitations(connection: Connection, invitee: Xuid) {
        invitations.keys.removeAll { it.second == invitee }
    }

    override fun moveMember(connection: Connection, xuid: Xuid, fromCompanyId: Long, toCompanyId: Long): Boolean {
        if (sessions.members[xuid]?.companyId != fromCompanyId) return false
        // Retiré puis remis : il arrive en dernier, comme `joined_at` à maintenant.
        sessions.members.remove(xuid)
        sessions.members[xuid] = Membership(toCompanyId, Role.ASSOCIE)
        sessions.sharePercents[xuid] = 0
        writes += Write.Moved(xuid, fromCompanyId, toCompanyId)
        return true
    }

    override fun saveShares(connection: Connection, companyId: Long, shares: Map<Xuid, Int>) {
        for ((xuid, percent) in shares) {
            check(sessions.members[xuid]?.companyId == companyId) { "company_members : $xuid n'est pas membre de l'entreprise $companyId" }
            sessions.sharePercents[xuid] = percent
        }
        writes += Write.Shares(companyId, shares)
    }
}
