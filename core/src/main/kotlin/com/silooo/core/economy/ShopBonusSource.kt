package com.silooo.core.economy

import com.silooo.core.session.CompanyRecord
import java.sql.Connection

/**
 * Les deux bonus de Donar que la boutique donne à une entreprise, en pourcents : [gradePercent] est celui du plus haut grade à vie du
 * PDG (pas la somme des grades), [pilierPercent] celui de son abonnement Pilier tant qu'il court. Zéro quand le PDG n'a ni l'un ni
 * l'autre. Le moteur les applique au multiplicateur global ([EconomyEngine.withShopBonuses]).
 */
data class ShopBonuses(val gradePercent: Int, val pilierPercent: Int) {
    companion object {
        val NONE = ShopBonuses(0, 0)
    }
}

/**
 * D'où le moteur d'économie tient les bonus de la boutique de l'entreprise. L'implémentation du service est
 * `com.silooo.core.shop.ShopFigures` ; le moteur ne connaît ni grade ni abonnement, seulement les pourcents qu'elle lui rend.
 *
 * Sans source (`EngineFigures` sans boutique), le calcul est celui d'avant la boutique, bit pour bit : c'est aussi le résultat d'une
 * entreprise dont le PDG n'a ni grade ni Pilier.
 */
interface ShopBonusSource {
    /** Les bonus de [company] à cet instant, lus dans la transaction de [connection]. Lecture seule. */
    fun of(connection: Connection, company: CompanyRecord): ShopBonuses
}
