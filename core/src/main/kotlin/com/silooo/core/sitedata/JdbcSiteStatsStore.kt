package com.silooo.core.sitedata

import java.sql.Connection
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Le SQL de [SiteStatsStore] sur les tables de V1 (`currency_ledger`, `players`, `company_members`, `companies`, `prestige_history`).
 * Que des `SELECT` : le site ne modifie rien ici. Testé sur MariaDB par Testcontainers (`SiteStatsSqlTest`).
 *
 * Le classement somme, par joueur, les mouvements positifs de Lingots dont la date tombe dans la semaine (UTC, les dates de la base
 * sont en UTC). Il ne lit donc ni les soldes ni la richesse : un joueur qui dépense ne descend pas, un joueur qui ne gagne rien n'y est pas.
 * À égalité, l'ordre alphabétique du gamertag départage (puis le XUID, qui ne sort jamais de la requête).
 */
class JdbcSiteStatsStore : SiteStatsStore {

    override fun weeklyRanking(connection: Connection, from: Instant, until: Instant, limit: Int, offset: Int): List<RankedPlayer> =
        connection.prepareStatement(
            "SELECT p.gamertag, c.id, c.name, w.lingots " +
                "FROM (SELECT xuid, SUM(delta) AS lingots FROM currency_ledger " +
                "WHERE currency = 'LINGOTS' AND delta > 0 AND created_at >= ? AND created_at < ? GROUP BY xuid) w " +
                "JOIN players p ON p.xuid = w.xuid " +
                "LEFT JOIN company_members m ON m.xuid = w.xuid " +
                "LEFT JOIN companies c ON c.id = m.company_id " +
                "ORDER BY w.lingots DESC, p.gamertag, p.xuid LIMIT ? OFFSET ?",
        ).use {
            it.setObject(1, utc(from))
            it.setObject(2, utc(until))
            it.setInt(3, limit)
            it.setInt(4, offset)
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val companyId = rows.getLong(2).takeUnless { rows.wasNull() }
                        add(RankedPlayer(offset + size + 1, rows.getString(1), companyId, rows.getString(3), rows.getLong(4)))
                    }
                }
            }
        }

    override fun company(connection: Connection, id: Long): CompanyCard? {
        val head = connection.prepareStatement("SELECT name, era, stars, created_at FROM companies WHERE id = ?").use {
            it.setLong(1, id)
            it.executeQuery().use { rows ->
                if (rows.next()) {
                    Head(rows.getString(1), rows.getInt(2), rows.getInt(3), rows.getObject(4, LocalDateTime::class.java).toInstant(ZoneOffset.UTC))
                } else {
                    null
                }
            }
        } ?: return null
        val prestiges = connection.prepareStatement("SELECT COUNT(*) FROM prestige_history WHERE company_id = ?").use {
            it.setLong(1, id)
            it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
        val members = connection.prepareStatement(
            "SELECT p.gamertag, m.role FROM company_members m JOIN players p ON p.xuid = m.xuid WHERE m.company_id = ? " +
                "ORDER BY CASE m.role WHEN 'PDG' THEN 0 ELSE 1 END, m.joined_at, p.gamertag",
        ).use {
            it.setLong(1, id)
            it.executeQuery().use { rows ->
                buildList { while (rows.next()) add(CompanyMember(rows.getString(1), MemberRole.valueOf(rows.getString(2)))) }
            }
        }
        return CompanyCard(id, head.name, head.era, head.stars, head.createdAt, prestiges, members)
    }

    private class Head(val name: String, val era: Int, val stars: Int, val createdAt: Instant)

    private fun utc(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
}
