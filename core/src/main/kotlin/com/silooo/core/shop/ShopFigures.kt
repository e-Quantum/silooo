package com.silooo.core.shop

import com.silooo.core.economy.ShopBonusSource
import com.silooo.core.economy.ShopBonuses
import com.silooo.core.session.CompanyRecord
import java.sql.Connection
import java.time.Clock

/**
 * Les bonus de Donar que le moteur d'économie lit ([ShopBonusSource]) : le plus haut grade à vie du PDG de l'entreprise, et son Pilier
 * tant qu'il court, mis en forme par [ShopRules]. C'est le PDG qui compte, pas celui qui agit : le multiplicateur est celui de
 * l'entreprise, le même pour tous ses membres. Lecture seule, dans la transaction de l'appelant.
 */
class ShopFigures(
    private val rules: ShopRules,
    private val store: ShopStore,
    private val clock: Clock = Clock.systemUTC(),
) : ShopBonusSource {
    override fun of(connection: Connection, company: CompanyRecord): ShopBonuses =
        rules.bonuses(store.ceoHoldings(connection, company.id), clock.instant())
}
