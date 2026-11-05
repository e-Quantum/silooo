package com.silooo.core.sitedata

import java.sql.Connection
import java.time.Instant

/**
 * Le faux de [SiteStatsStore] : un classement et des fiches posés à la main, et le compte des lectures (pour vérifier le cache).
 * Les joueurs portent un XUID reconnaissable que le faux ne rend jamais : s'il apparaît dans une page, le test le voit.
 */
class InMemorySiteStatsStore : SiteStatsStore {
    class Entry(val xuid: String, val gamertag: String, val companyId: Long?, val companyName: String?, val lingots: Long)

    val entries = mutableListOf<Entry>()
    val companies = mutableMapOf<Long, CompanyCard>()

    var rankingReads = 0
        private set
    var companyReads = 0
        private set

    /** Les bornes de la dernière lecture du classement. */
    var lastFrom: Instant? = null
        private set
    var lastUntil: Instant? = null
        private set

    override fun weeklyRanking(connection: Connection, from: Instant, until: Instant, limit: Int, offset: Int): List<RankedPlayer> {
        rankingReads++
        lastFrom = from
        lastUntil = until
        return entries.sortedByDescending { it.lingots }.drop(offset).take(limit).mapIndexed { index, entry ->
            RankedPlayer(offset + index + 1, entry.gamertag, entry.companyId, entry.companyName, entry.lingots)
        }
    }

    override fun company(connection: Connection, id: Long): CompanyCard? {
        companyReads++
        return companies[id]
    }
}

/** Le faux de [ShopDeliveryStore] : une table en mémoire dont la clé est l'identifiant de commande. */
class InMemoryShopDeliveryStore : ShopDeliveryStore {
    class Row(val orderId: String, val gamertag: String, val gemmes: Long)

    val rows = linkedMapOf<String, Row>()

    override fun insert(connection: Connection, orderId: String, gamertag: String, gemmes: Long): Boolean {
        if (orderId in rows) return false
        rows[orderId] = Row(orderId, gamertag, gemmes)
        return true
    }
}
