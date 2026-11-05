package com.silooo.core.members

import com.silooo.core.db.Xuid
import com.silooo.core.db.setXuid
import java.sql.Connection
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Le SQL de l'entreprise partagée sur `company_members` et `company_invites` (V1). Testé sur MariaDB par Testcontainers
 * (`MemberSqlTest`), jamais avec un faux.
 */
class JdbcMemberStore : MemberStore {

    override fun saveInvitation(connection: Connection, invitation: Invitation) {
        connection.prepareStatement(
            "INSERT INTO company_invites (company_id, xuid, invited_by, expires_at) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE invited_by = VALUES(invited_by), created_at = UTC_TIMESTAMP(3), expires_at = VALUES(expires_at)",
        ).use {
            it.setLong(1, invitation.companyId)
            it.setXuid(2, invitation.invitee)
            it.setXuid(3, invitation.invitedBy)
            it.setObject(4, LocalDateTime.ofInstant(invitation.expiresAt, ZoneOffset.UTC))
            it.executeUpdate()
        }
    }

    override fun findInvitation(connection: Connection, companyId: Long, invitee: Xuid): Invitation? =
        // CAST en texte : un BIGINT UNSIGNED ne tient pas dans un Long.
        connection.prepareStatement("SELECT CAST(invited_by AS CHAR), expires_at FROM company_invites WHERE company_id = ? AND xuid = ?").use {
            it.setLong(1, companyId)
            it.setXuid(2, invitee)
            it.executeQuery().use { rows ->
                if (!rows.next()) {
                    null
                } else {
                    Invitation(
                        companyId = companyId,
                        invitee = invitee,
                        invitedBy = checkNotNull(Xuid.parse(rows.getString(1))),
                        expiresAt = checkNotNull(rows.getObject(2, LocalDateTime::class.java)) { "company_invites.expires_at : date absente" }
                            .toInstant(ZoneOffset.UTC),
                    )
                }
            }
        }

    override fun deleteInvitations(connection: Connection, invitee: Xuid) {
        connection.prepareStatement("DELETE FROM company_invites WHERE xuid = ?").use {
            it.setXuid(1, invitee)
            it.executeUpdate()
        }
    }

    override fun moveMember(connection: Connection, xuid: Xuid, fromCompanyId: Long, toCompanyId: Long): Boolean =
        connection.prepareStatement(
            "UPDATE company_members SET company_id = ?, role = 'ASSOCIE', share_percent = 0, joined_at = UTC_TIMESTAMP(3) " +
                "WHERE xuid = ? AND company_id = ?",
        ).use {
            it.setLong(1, toCompanyId)
            it.setXuid(2, xuid)
            it.setLong(3, fromCompanyId)
            it.executeUpdate() > 0
        }

    override fun saveShares(connection: Connection, companyId: Long, shares: Map<Xuid, Int>) {
        connection.prepareStatement("UPDATE company_members SET share_percent = ? WHERE company_id = ? AND xuid = ?").use {
            for ((xuid, percent) in shares) {
                it.setInt(1, percent)
                it.setLong(2, companyId)
                it.setXuid(3, xuid)
                check(it.executeUpdate() == 1) { "company_members : $xuid n'est pas membre de l'entreprise $companyId" }
            }
        }
    }
}
