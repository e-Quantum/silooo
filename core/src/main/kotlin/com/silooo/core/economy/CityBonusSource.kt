package com.silooo.core.economy

import com.silooo.core.api.CityState
import com.silooo.core.session.CompanyRecord
import java.sql.Connection

/**
 * D'où le moteur d'économie tient la ville de l'entreprise : les bonus des bâtiments qu'il applique au multiplicateur et aux
 * salaires, et l'état de la ville que le contrat montre (`CompanyState.city`). L'implémentation du service est
 * `com.silooo.core.city.CityFigures` ; le moteur n'a ni bâtiment ni zone en tête, seulement les pourcents qu'elle lui rend.
 *
 * Sans source (`EngineFigures` sans ville), le calcul est celui d'avant la ville, bit pour bit : c'est aussi le résultat d'une
 * entreprise qui n'a aucun bâtiment à bonus.
 */
interface CityBonusSource {
    /**
     * La ville de [company], lue dans la transaction de [connection]. [staffCount] est le nombre de membres du personnel embauchés
     * (affectés ou non) : les logements en hébergent au plus autant. Lecture seule.
     */
    fun of(connection: Connection, company: CompanyRecord, staffCount: Int): CityState
}
