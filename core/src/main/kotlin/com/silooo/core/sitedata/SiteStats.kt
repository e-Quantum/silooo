package com.silooo.core.sitedata

import java.sql.Connection
import java.time.Instant

/** Une ligne du classement : le rang, le gamertag, l'entreprise s'il en a une et les Lingots gagnés dans la semaine. Jamais de XUID. */
data class RankedPlayer(
    val rank: Int,
    val gamertag: String,
    val companyId: Long?,
    val companyName: String?,
    val lingots: Long,
)

/** Une page du classement : [rows] et, pour le lien « Page suivante », s'il en reste après. */
data class RankingPage(val rows: List<RankedPlayer>, val hasNext: Boolean)

/** Un membre sur la fiche d'une entreprise : le gamertag et son rôle. */
data class CompanyMember(val gamertag: String, val role: MemberRole)

enum class MemberRole { PDG, ASSOCIE }

/**
 * Ce que la fiche publique d'une entreprise montre, et rien d'autre : ni Donar, ni Lingots, ni XUID. Le slogan, le logo et les couleurs
 * existent dans `companies` mais le jeu ne les écrit pas encore : ils viendront ici le jour où il les écrit.
 */
data class CompanyCard(
    val id: Long,
    val name: String,
    val era: Int,
    val stars: Int,
    val createdAt: Instant,
    val prestiges: Int,
    /** Le PDG d'abord, puis les associés par date d'arrivée. */
    val members: List<CompanyMember>,
)

/**
 * Les lectures du site sur la base du jeu : en lecture seule, toujours. Le SQL est dans [JdbcSiteStatsStore], testé sur MariaDB par
 * `SiteStatsSqlTest` ; les tests de pages utilisent un faux en mémoire.
 */
interface SiteStatsStore {
    /**
     * Les joueurs de la semaine [from] (inclus) à [until] (exclu), classés par Lingots gagnés, [limit] lignes à partir de [offset].
     * Le rang de la première ligne est `offset + 1`.
     */
    fun weeklyRanking(connection: Connection, from: Instant, until: Instant, limit: Int, offset: Int): List<RankedPlayer>

    /** La fiche de l'entreprise [id], ou `null` si elle n'existe pas. */
    fun company(connection: Connection, id: Long): CompanyCard?
}
