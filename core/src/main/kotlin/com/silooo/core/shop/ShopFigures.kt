package com.silooo.core.shop

import com.silooo.core.economy.ShopBonusSource
import com.silooo.core.economy.ShopBonuses
import com.silooo.core.session.CompanyRecord
import java.sql.Connection
import java.time.Clock

/**
 * Les bonus de Donar que le moteur d'économie lit ([ShopBonusSource]) : le plus haut grade à vie du PDG de l'entreprise, son Pilier
 * tant qu'il court, et le boost collectif qui court pour tout le serveur, mis en forme par [ShopRules]. C'est le PDG qui compte pour le
 * grade et le Pilier, pas celui qui agit : le multiplicateur est celui de l'entreprise, le même pour tous ses membres. Le boost est le
 * même pour toutes les entreprises. Lecture seule, dans la transaction de l'appelant.
 */
class ShopFigures(
    private val rules: ShopRules,
    private val store: ShopStore,
    private val clock: Clock = Clock.systemUTC(),
) : ShopBonusSource {
    override fun of(connection: Connection, company: CompanyRecord): ShopBonuses {
        val now = clock.instant()
        return rules.bonuses(store.ceoHoldings(connection, company.id), now, store.activeBoostPercent(connection, now))
    }
}
