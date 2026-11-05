package com.silooo.core.shop

import com.silooo.core.api.CatalogItem
import com.silooo.core.api.RefusalCodes
import com.silooo.core.definitions.Definitions
import com.silooo.core.economy.ShopBonuses
import java.time.Duration
import java.time.Instant

/** Un article que le service vend, tiré de `boutique.json`. Les cosmétiques, les boosts et le pass s'ajouteront ici quand le jeu les vendra. */
sealed interface ShopItem {
    val id: String

    /** Le prix en Gemmes. */
    val gems: Long

    /** Un grade à vie ; [position] est sa place dans l'ordre imposé (0 pour le premier), [donarBonusPercent] son bonus de Donar. */
    data class Grade(override val id: String, override val gems: Long, val position: Int, val donarBonusPercent: Int) : ShopItem

    /**
     * Un abonnement de [days] jours. [gemsReturned] : les Gemmes qu'il rend au destinataire à chaque achat (0 si aucune).
     * [requiredGradeId] : le grade à vie qu'il demande (`null` : aucun). [donarBonusPercent] : son bonus de Donar tant qu'il court.
     */
    data class Subscription(
        override val id: String,
        override val gems: Long,
        val days: Int,
        val gemsReturned: Long,
        val requiredGradeId: String?,
        val donarBonusPercent: Int,
    ) : ShopItem
}

/**
 * Ce qu'un joueur possède dans la boutique : ses grades à vie, et la fin de chacun de ses abonnements (même échue : c'est la date qui
 * dit s'il court encore, à l'instant où on la lit).
 */
data class Holdings(val ranks: Set<String>, val subscriptionEnds: Map<String, Instant>) {
    /** Vrai si l'abonnement [subscriptionId] court encore à l'instant [now] (sa fin est strictement après). */
    fun isActive(subscriptionId: String, now: Instant): Boolean = subscriptionEnds[subscriptionId]?.isAfter(now) == true

    companion object {
        val NOTHING = Holdings(emptySet(), emptyMap())
    }
}

/** Un abonnement tel que `player_subscriptions` le garde : depuis quand il court sans trou, jusqu'à quand, et combien de mois en tout. */
data class SubscriptionTerm(val startedAt: Instant, val endsAt: Instant, val monthsTotal: Int)

/**
 * Les règles de la boutique, sans base ni HTTP : l'ordre des grades, ce que le catalogue montre, ce qui refuse un achat, la
 * prolongation d'un abonnement et les bonus de Donar. Aucun chiffre n'est écrit ici : prix, durées, bonus et Gemmes rendues viennent
 * de `definitions/boutique.json`, l'ordre des grades est celui de son tableau `grades`.
 *
 * Les règles que les définitions ne disent pas (tranchées au plus simple, **à valider par Kr**) :
 *  - un grade s'achète dans l'ordre : le seul achetable est le premier que le destinataire n'a pas (`regleGrades`) ;
 *  - un abonnement qui demande un grade (`reserveA`) se refuse sans lui avec `GRADE_PRECEDENT_REQUIS`, faute d'un code dédié ;
 *  - un abonnement se rachète à volonté : il s'ajoute à la fin de l'abonnement en cours, ou à maintenant s'il est échu ;
 *  - `months_total` compte les achats, sans retomber à zéro quand l'abonnement a lâché : le badge qui évolue ne perd rien.
 */
class ShopRules(definitions: Definitions) {
    private val shop = definitions.shop

    /** Les six grades à vie, dans l'ordre imposé. */
    val grades: List<ShopItem.Grade> = shop.grades.mapIndexed { index, grade -> ShopItem.Grade(grade.id, grade.gems, index, grade.donarBonusPercent) }

    /** Les abonnements, dans l'ordre de `boutique.json`. */
    val subscriptions: List<ShopItem.Subscription> = shop.subscriptions.map {
        ShopItem.Subscription(it.id, it.gems, it.days, it.gemsReturned ?: 0L, it.reservedForGradeId, it.donarBonusPercent ?: 0)
    }

    private val byId: Map<String, ShopItem> = (grades + subscriptions).associateBy { it.id }

    /** L'article [itemId] que le service vend, ou `null` s'il n'existe pas ou si le jeu ne le vend pas encore. */
    fun item(itemId: String): ShopItem? = byId[itemId]

    /** Le premier grade que [holdings] n'a pas, ou `null` s'il les a tous : le seul qu'on puisse acheter. */
    fun nextGrade(holdings: Holdings): ShopItem.Grade? = grades.firstOrNull { it.id !in holdings.ranks }

    /**
     * Le catalogue vu par un joueur qui possède [holdings] à l'instant [now] : les grades dans l'ordre (le seul premier non possédé est
     * `available`), puis les abonnements dans l'ordre de `boutique.json`. Un abonnement est `owned` tant qu'il court, et `available`
     * dès que le grade qu'il demande est possédé (toujours, s'il n'en demande aucun).
     */
    fun catalog(holdings: Holdings, now: Instant): List<CatalogItem> {
        val next = nextGrade(holdings)
        return grades.map { CatalogItem(it.id, it.gems, owned = it.id in holdings.ranks, available = it.id == next?.id) } +
            subscriptions.map { CatalogItem(it.id, it.gems, owned = holdings.isActive(it.id, now), available = meetsRequirement(it, holdings)) }
    }

    /**
     * Le code de refus d'un achat de [item] par un destinataire qui possède [holdings], ou `null` si rien ne s'y oppose. Les Gemmes ne
     * sont pas ici : elles se vérifient après. Un grade déjà possédé : `DEJA_POSSEDE` ; un grade qui n'est pas le premier non
     * possédé : `GRADE_PRECEDENT_REQUIS` ; un abonnement dont le grade manque : `GRADE_PRECEDENT_REQUIS`.
     */
    fun refusalFor(item: ShopItem, holdings: Holdings): String? = when (item) {
        is ShopItem.Grade -> when {
            item.id in holdings.ranks -> RefusalCodes.ALREADY_OWNED
            item.id != nextGrade(holdings)?.id -> RefusalCodes.PREVIOUS_RANK_REQUIRED
            else -> null
        }
        is ShopItem.Subscription -> if (meetsRequirement(item, holdings)) null else RefusalCodes.PREVIOUS_RANK_REQUIRED
    }

    /**
     * L'abonnement après un achat de [item] à l'instant [now] : `item.days` jours de plus, à partir du plus tard de [now] et de la fin
     * de l'abonnement en cours. Un abonnement qui court garde son début ; un abonnement échu, ou neuf, recommence à [now].
     * `monthsTotal` gagne un mois à chaque achat.
     */
    fun extend(current: SubscriptionTerm?, item: ShopItem.Subscription, now: Instant): SubscriptionTerm {
        val running = current?.takeIf { it.endsAt.isAfter(now) }
        return SubscriptionTerm(
            startedAt = running?.startedAt ?: now,
            endsAt = (running?.endsAt ?: now).plus(Duration.ofDays(item.days.toLong())),
            monthsTotal = (current?.monthsTotal ?: 0) + 1,
        )
    }

    /**
     * Les bonus de Donar de [holdings] à l'instant [now] : celui du plus haut grade possédé (un seul, pas la somme), et celui du
     * Pilier tant qu'il court. Zéro sans grade ni Pilier.
     */
    fun bonuses(holdings: Holdings, now: Instant): ShopBonuses {
        val grade = grades.filter { it.id in holdings.ranks }.maxByOrNull { it.position }?.donarBonusPercent ?: 0
        val pilier = subscriptions.firstOrNull { it.id == PILIER_ID }?.takeIf { holdings.isActive(it.id, now) }?.donarBonusPercent ?: 0
        return ShopBonuses(gradePercent = grade, pilierPercent = pilier)
    }

    private fun meetsRequirement(item: ShopItem.Subscription, holdings: Holdings): Boolean =
        item.requiredGradeId == null || item.requiredGradeId in holdings.ranks

    companion object {
        /** L'`id` du Pilier dans `boutique.json` et `player_subscriptions.subscription_id` : le seul abonnement dont le bonus de Donar compte. */
        const val PILIER_ID = "pilier"
    }
}
