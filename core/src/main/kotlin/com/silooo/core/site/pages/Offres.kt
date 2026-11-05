package com.silooo.core.site.pages

import com.silooo.core.definitions.GemPackDefinition
import com.silooo.core.definitions.GradeDefinition
import com.silooo.core.definitions.ShopDefinitions
import com.silooo.core.definitions.SubscriptionDefinition
import kotlinx.html.FlowContent
import kotlinx.html.LI
import kotlinx.html.img
import kotlinx.html.li
import kotlinx.html.span
import kotlinx.html.ul

private const val GEM_ICON = "/public/atelier/icones/gemme.png"
private const val NO_BREAK_SPACE = ' '

/** Un nombre entier à la française : les milliers séparés par une espace insécable (3 500). */
internal fun formatNumber(value: Long): String =
    value.toString().reversed().chunked(3).joinToString(NO_BREAK_SPACE.toString()).reversed()

/** Un pour cent à la française : « 10 % » avec une espace insécable. */
internal fun formatPercent(value: Int): String = "$value$NO_BREAK_SPACE%"

/** Un prix en euros tel que le JSON l'écrit (« 4,99 »), suivi du signe. */
internal fun formatEuros(value: String): String = "$value$NO_BREAK_SPACE€"

internal fun formatDays(days: Int): String = if (days == 1) "1 jour" else "$days jours"

/** Le nom que les joueurs lisent pour un abonnement ; les définitions ne portent que son identifiant. */
private fun subscriptionName(id: String): String = when (id) {
    "pilier" -> "Pilier"
    "perso" -> "Préfixe perso"
    else -> id
}

/** Des Gemmes : l'icône (le mot « Gemmes » est son `alt`) et le nombre. Sur le papier des pages de lecture, en `fonte-900`. */
private fun LI.gems(count: Long) {
    span(classes = "sa-monnaie sa-monnaie--gemmes") {
        img(src = GEM_ICON, alt = "Gemmes", classes = "sa-icone sa-icone--x2")
        +formatNumber(count)
    }
}

/** Les packs de Gemmes : une ligne par pack, dans l'ordre des définitions. Le prix sort tel que le JSON l'écrit. */
internal fun FlowContent.packsDeGemmes(shop: ShopDefinitions) {
    ul(classes = "offres") {
        for (pack: GemPackDefinition in shop.gemPacks) {
            li(classes = "offre") {
                gems(pack.gems)
                span(classes = "sa-ticket") { +formatEuros(pack.euros) }
                span(classes = "offre__detail") { +if (pack.bonusPercent == 0) "Sans bonus" else "Bonus ${formatPercent(pack.bonusPercent)}" }
            }
        }
    }
}

/** Les grades : le nom des définitions, les Gemmes et le bonus de Donar. */
internal fun FlowContent.grades(shop: ShopDefinitions) {
    ul(classes = "offres") {
        for (grade: GradeDefinition in shop.grades) {
            li(classes = "offre") {
                span(classes = "offre__nom") { +grade.name }
                gems(grade.gems)
                span(classes = "offre__detail") { +"Bonus de Donar ${formatPercent(grade.donarBonusPercent)}" }
            }
        }
    }
}

/** Les abonnements : le Pilier (bonus et Gemmes rendues) et l'option réservée à un grade, nommé d'après les définitions. */
internal fun FlowContent.abonnements(shop: ShopDefinitions) {
    ul(classes = "offres") {
        for (subscription: SubscriptionDefinition in shop.subscriptions) {
            li(classes = "offre") {
                span(classes = "offre__nom") { +subscriptionName(subscription.id) }
                gems(subscription.gems)
                span(classes = "offre__detail") { +formatDays(subscription.days) }
                subscription.donarBonusPercent?.let { span(classes = "offre__detail") { +"Bonus de Donar ${formatPercent(it)}" } }
                subscription.gemsReturned?.let { span(classes = "offre__detail") { +"${formatNumber(it)} Gemmes rendues" } }
                subscription.reservedForGradeId?.let { id ->
                    val grade = shop.grades.firstOrNull { it.id == id }?.name ?: id
                    span(classes = "offre__detail") { +"Réservé au grade $grade" }
                }
            }
        }
    }
}
