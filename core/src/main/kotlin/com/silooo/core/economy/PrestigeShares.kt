package com.silooo.core.economy

import com.silooo.core.api.Role
import com.silooo.core.db.Xuid

/** La part de Lingots d'un membre au prestige : `company_members.share_percent`, de 0 à 100. */
data class MemberShare(val xuid: Xuid, val role: Role, val sharePercent: Int)

/** Les Lingots qu'un membre reçoit d'un prestige. */
data class LingotShare(val xuid: Xuid, val lingots: Long)

/**
 * Partage les [total] Lingots d'un prestige entre les membres, selon leur part (`share_percent`).
 *
 * Règle tranchée par le service, **à valider par Kr** : chaque membre reçoit sa part arrondie à l'inférieur, et le
 * reste (au plus un Lingot par membre) va au PDG. Le total est donc toujours distribué en entier, sans Lingot créé
 * ni perdu. Les parts d'une entreprise font 100 ; si elles faisaient moins, le PDG recevrait aussi la différence, et
 * si elles faisaient plus, ce serait une erreur de données qui ne doit pas fabriquer de Lingots : le prestige échoue.
 *
 * Le résultat suit l'ordre de [members].
 */
fun splitLingots(total: Long, members: List<MemberShare>): List<LingotShare> {
    require(total >= 0) { "Un total de Lingots n'est pas négatif : $total" }
    check(members.sumOf { it.sharePercent } <= FULL_SHARE) { "Les parts des membres dépassent $FULL_SHARE %" }
    check(members.any { it.role == Role.PDG }) { "Une entreprise sans PDG ne partage pas ses Lingots" }
    val parts = members.map { floorShare(total, it.sharePercent) }
    val remainder = total - parts.sum()
    return members.mapIndexed { index, member ->
        LingotShare(member.xuid, parts[index] + if (member.role == Role.PDG) remainder else 0L)
    }
}

/** `floor(total * percent / 100)` sans dépasser un `Long` : `total = 100 a + b`, donc `a * percent + b * percent / 100`. */
private fun floorShare(total: Long, percent: Int): Long = (total / FULL_SHARE) * percent + ((total % FULL_SHARE) * percent) / FULL_SHARE

private const val FULL_SHARE = 100
