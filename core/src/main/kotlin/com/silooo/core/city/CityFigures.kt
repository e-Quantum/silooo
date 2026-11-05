package com.silooo.core.city

import com.silooo.core.api.CityState
import com.silooo.core.economy.CityBonusSource
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.SessionStore
import java.sql.Connection

/**
 * La ville que le moteur d'économie lit ([CityBonusSource]) : les zones et les bâtiments de l'entreprise, mis en forme par
 * [CityRules]. Lecture seule, dans la transaction de l'appelant. Le niveau de la mairie est celui de la ligne [CompanyRecord] reçue :
 * une action qui vient de l'améliorer passe la ligne qu'elle a écrite, et la ville montre déjà le nouveau niveau.
 */
class CityFigures(
    private val rules: CityRules,
    private val sessions: SessionStore,
    private val store: CityStore,
) : CityBonusSource {
    override fun of(connection: Connection, company: CompanyRecord, staffCount: Int): CityState =
        rules.state(
            mairieLevel = company.mairieLevel,
            owned = sessions.loadZones(connection, company.id),
            buildings = store.loadBuildings(connection, company.id),
            staffCount = staffCount,
        )
}
