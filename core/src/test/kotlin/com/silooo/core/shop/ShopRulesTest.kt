package com.silooo.core.shop

import com.silooo.core.TestDefinitions
import com.silooo.core.api.CatalogItem
import com.silooo.core.api.RefusalCodes
import com.silooo.core.economy.ShopBonuses
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les règles de la boutique, sans base ni HTTP : [ShopRules] sur les `definitions/` du dépôt. Les chiffres attendus sortent de
 * `boutique.json` lu dans le test (prix, jours, bonus, Gemmes rendues), jamais d'un littéral : changer le JSON ne casse pas ces tests
 * tant que les règles tiennent.
 */
class ShopRulesTest {
    private val shop = TestDefinitions.loaded.shop
    private val rules = ShopRules(TestDefinitions.loaded)
    private val now = Instant.parse("2026-11-05T12:00:00Z")
    private val pilier = rules.subscriptions.single { it.id == ShopRules.PILIER_ID }
    private val perso = rules.subscriptions.single { it.requiredGradeId != null }
    private val personsGrade = checkNotNull(perso.requiredGradeId)
    private val gradeIds = shop.grades.map { it.id }

    private fun owning(vararg ranks: String, ends: Map<String, Instant> = emptyMap()) = Holdings(ranks.toSet(), ends)

    /** Les [count] premiers grades, dans l'ordre. */
    private fun firstGrades(count: Int) = Holdings(gradeIds.take(count).toSet(), emptyMap())

    private fun List<CatalogItem>.entry(itemId: String) = single { it.itemId == itemId }

    // --- les articles

    @Test
    @DisplayName("Les grades et les abonnements sont ceux de boutique.json, dans son ordre, aux mêmes prix")
    fun itemsComeFromTheDefinitions() {
        assertEquals(gradeIds, rules.grades.map { it.id })
        assertEquals(shop.grades.map { it.gems }, rules.grades.map { it.gems })
        assertEquals(shop.grades.indices.toList(), rules.grades.map { it.position })
        assertEquals(shop.subscriptions.map { it.id }, rules.subscriptions.map { it.id })
        assertEquals(shop.subscriptions.map { it.gems }, rules.subscriptions.map { it.gems })
        assertEquals(shop.subscriptions.map { it.days }, rules.subscriptions.map { it.days })
        assertEquals(rules.grades[0], rules.item(gradeIds[0]))
        assertEquals(pilier, rules.item(ShopRules.PILIER_ID))
    }

    @Test
    @DisplayName("Un article que le jeu ne vend pas (inconnu, vide, cosmétique à venir) n'existe pas pour la boutique")
    fun unknownItems() {
        for (itemId in listOf("", "inconnu", "MVIP", " mvip", "cosmetique-1", "pass-saison")) assertNull(rules.item(itemId), itemId)
    }

    @Test
    @DisplayName("Les cosmétiques en vente sont ceux de boutique.json marqués achetables, dans son ordre et à son prix ; le titre Pionnier n'en est pas")
    fun cosmeticsComeFromTheDefinitions() {
        val sold = shop.cosmetics.filter { it.purchasable }
        assertTrue(sold.size >= 2, "de quoi acheter, offrir et refuser")
        assertEquals(sold.map { it.id }, rules.cosmetics.map { it.id })
        assertEquals(sold.map { it.gems }, rules.cosmetics.map { it.gems })
        assertEquals(sold.map { it.category }, rules.cosmetics.map { it.category })
        assertEquals(rules.cosmetics.first(), rules.item(sold.first().id))
        assertNull(rules.item(ShopRules.PIONEER_TITLE_ID), "donné par le site, jamais vendu")
        assertTrue(shop.cosmetics.any { it.id == ShopRules.PIONEER_TITLE_ID && !it.purchasable }, "il est bien dans le fichier")
    }

    @Test
    @DisplayName("Le boost collectif vient de boutique.json : prix, hausse et durée ; son identifiant est celui de buyItem")
    fun boostComesFromTheDefinitions() {
        val fromJson = shop.collectiveBoost
        assertEquals(ShopRules.BOOST_ID, rules.boost.id)
        assertEquals(fromJson.gems, rules.boost.gems)
        assertEquals(fromJson.bonusPercent, rules.boost.bonusPercent)
        assertEquals(fromJson.durationMinutes, rules.boost.minutes)
        assertEquals(rules.boost, rules.item(ShopRules.BOOST_ID))
    }

    @Test
    @DisplayName("Le Pilier rend ses Gemmes et porte un bonus de Donar ; le Perso est réservé à un grade et n'a ni l'un ni l'autre")
    fun subscriptionsCarryTheirOwnFigures() {
        val fromJson = shop.subscriptions.single { it.id == ShopRules.PILIER_ID }
        assertEquals(fromJson.gemsReturned, pilier.gemsReturned)
        assertEquals(fromJson.donarBonusPercent, pilier.donarBonusPercent)
        assertNull(pilier.requiredGradeId)
        assertTrue(personsGrade in gradeIds, "le grade que le Perso demande existe")
        assertEquals(0L, perso.gemsReturned)
        assertEquals(0, perso.donarBonusPercent)
    }

    // --- l'ordre des grades

    @Test
    @DisplayName("Le prochain grade est le premier que le joueur n'a pas ; aucun une fois qu'il les a tous")
    fun nextGrade() {
        assertEquals(gradeIds[0], rules.nextGrade(Holdings.NOTHING)?.id)
        assertEquals(gradeIds[2], rules.nextGrade(firstGrades(2))?.id)
        assertEquals(gradeIds.last(), rules.nextGrade(firstGrades(gradeIds.size - 1))?.id)
        assertNull(rules.nextGrade(firstGrades(gradeIds.size)))
    }

    @Test
    @DisplayName("Un grade se refuse s'il est déjà possédé (DEJA_POSSEDE) ou s'il saute une marche (GRADE_PRECEDENT_REQUIS) ; le prochain passe")
    fun gradeRefusals() {
        val held = firstGrades(2)
        assertEquals(RefusalCodes.ALREADY_OWNED, rules.refusalFor(rules.grades[0], held))
        assertEquals(RefusalCodes.ALREADY_OWNED, rules.refusalFor(rules.grades[1], held))
        assertNull(rules.refusalFor(rules.grades[2], held))
        assertEquals(RefusalCodes.PREVIOUS_RANK_REQUIRED, rules.refusalFor(rules.grades[3], held))
        assertEquals(RefusalCodes.PREVIOUS_RANK_REQUIRED, rules.refusalFor(rules.grades.last(), Holdings.NOTHING))
        assertNull(rules.refusalFor(rules.grades[0], Holdings.NOTHING))
    }

    @Test
    @DisplayName("Le Pilier s'achète toujours ; le Perso demande son grade (GRADE_PRECEDENT_REQUIS sans lui)")
    fun subscriptionRefusals() {
        assertNull(rules.refusalFor(pilier, Holdings.NOTHING))
        assertNull(rules.refusalFor(pilier, firstGrades(gradeIds.size)))
        assertEquals(RefusalCodes.PREVIOUS_RANK_REQUIRED, rules.refusalFor(perso, Holdings.NOTHING))
        // Tous les grades sauf celui que le Perso demande : il manque encore.
        assertEquals(RefusalCodes.PREVIOUS_RANK_REQUIRED, rules.refusalFor(perso, Holdings(gradeIds.toSet() - personsGrade, emptyMap())))
        assertNull(rules.refusalFor(perso, owning(personsGrade)))
    }

    @Test
    @DisplayName("Un cosmétique se refuse s'il est déjà possédé (DEJA_POSSEDE) ; il ne demande ni grade ni abonnement ; le boost ne se refuse jamais")
    fun cosmeticAndBoostRefusals() {
        val cosmetic = rules.cosmetics.first()
        assertNull(rules.refusalFor(cosmetic, Holdings.NOTHING))
        assertNull(rules.refusalFor(cosmetic, Holdings(emptySet(), emptyMap(), cosmetics = rules.cosmetics.drop(1).map { it.id }.toSet())), "un autre cosmétique ne gêne pas")
        assertEquals(RefusalCodes.ALREADY_OWNED, rules.refusalFor(cosmetic, Holdings(emptySet(), emptyMap(), cosmetics = setOf(cosmetic.id))))
        assertNull(rules.refusalFor(rules.boost, Holdings.NOTHING))
        assertNull(rules.refusalFor(rules.boost, firstGrades(gradeIds.size)))
    }

    // --- le catalogue

    @Test
    @DisplayName("Catalogue d'un joueur neuf : les grades, les abonnements, les cosmétiques puis le boost, rien de possédé, à vendre : le premier grade, le Pilier, les cosmétiques et le boost")
    fun catalogOfANewPlayer() {
        val catalog = rules.catalog(Holdings.NOTHING, now)
        val cosmeticIds = rules.cosmetics.map { it.id }
        assertEquals(gradeIds + shop.subscriptions.map { it.id } + cosmeticIds + ShopRules.BOOST_ID, catalog.map { it.itemId })
        assertEquals(
            shop.grades.map { it.gems } + shop.subscriptions.map { it.gems } + rules.cosmetics.map { it.gems } + shop.collectiveBoost.gems,
            catalog.map { it.gemmes },
        )
        assertTrue(catalog.none { it.owned })
        assertEquals(listOf(gradeIds[0], ShopRules.PILIER_ID) + cosmeticIds + ShopRules.BOOST_ID, catalog.filter { it.available }.map { it.itemId })
    }

    @Test
    @DisplayName("Catalogue : un cosmétique possédé est owned et n'est plus à vendre, les autres restent à vendre ; le boost n'est jamais owned")
    fun catalogShowsOwnedCosmetics() {
        val owned = rules.cosmetics.first().id
        val catalog = rules.catalog(Holdings(emptySet(), emptyMap(), cosmetics = setOf(owned, ShopRules.PIONEER_TITLE_ID)), now)
        assertTrue(catalog.entry(owned).owned)
        assertFalse(catalog.entry(owned).available)
        assertTrue(rules.cosmetics.drop(1).all { catalog.entry(it.id).let { entry -> !entry.owned && entry.available } })
        assertTrue(catalog.none { it.itemId == ShopRules.PIONEER_TITLE_ID }, "le titre Pionnier n'est pas au catalogue, même possédé")
        assertFalse(catalog.entry(ShopRules.BOOST_ID).owned)
        assertTrue(catalog.entry(ShopRules.BOOST_ID).available, "on en rachète quand on veut")
    }

    @Test
    @DisplayName("Catalogue en cours de route : les grades possédés sont owned, seul le suivant est available")
    fun catalogFollowsTheOrder() {
        val catalog = rules.catalog(firstGrades(2), now)
        assertEquals(gradeIds.take(2), catalog.filter { it.owned }.map { it.itemId })
        assertEquals(listOf(gradeIds[2]), catalog.filter { it.available && it.itemId in gradeIds }.map { it.itemId })
        assertFalse(catalog.entry(gradeIds[3]).available, "un grade qui saute une marche n'est pas à vendre")
    }

    @Test
    @DisplayName("Catalogue d'un joueur qui a tous les grades : plus aucun grade à vendre, le Perso s'ouvre")
    fun catalogWithEveryGrade() {
        val catalog = rules.catalog(firstGrades(gradeIds.size), now)
        assertTrue(catalog.filter { it.itemId in gradeIds }.all { it.owned && !it.available })
        assertTrue(catalog.entry(perso.id).available)
        assertFalse(catalog.entry(perso.id).owned)
    }

    @Test
    @DisplayName("Le Perso n'est à vendre qu'avec son grade, pas avant")
    fun persoWaitsForItsGrade() {
        assertFalse(rules.catalog(Holdings(gradeIds.toSet() - personsGrade, emptyMap()), now).entry(perso.id).available)
        assertFalse(rules.catalog(Holdings.NOTHING, now).entry(perso.id).available)
        assertTrue(rules.catalog(owning(personsGrade), now).entry(perso.id).available)
    }

    @Test
    @DisplayName("Un abonnement est owned tant qu'il court (fin strictement après maintenant), et reste à vendre pour le prolonger")
    fun subscriptionsAreOwnedWhileRunning() {
        val running = owning(ends = mapOf(pilier.id to now.plusSeconds(1)))
        assertTrue(rules.catalog(running, now).entry(pilier.id).owned)
        assertTrue(rules.catalog(running, now).entry(pilier.id).available, "le Pilier se prolonge")
        assertFalse(rules.catalog(owning(ends = mapOf(pilier.id to now)), now).entry(pilier.id).owned, "fini à l'instant même")
        val expired = rules.catalog(owning(ends = mapOf(pilier.id to now.minusSeconds(1))), now).entry(pilier.id)
        assertFalse(expired.owned)
        assertTrue(expired.available)
    }

    // --- la prolongation

    @Test
    @DisplayName("Premier achat d'un abonnement : il commence maintenant, dure `jours` jours, premier mois")
    fun firstPurchase() {
        val term = rules.extend(null, pilier, now)
        assertEquals(SubscriptionTerm(now, now.plus(Duration.ofDays(pilier.days.toLong())), 1), term)
    }

    @Test
    @DisplayName("Achat pendant l'abonnement : la fin recule de `jours` jours, le début reste, un mois de plus")
    fun extendingARunningSubscription() {
        val started = now.minus(Duration.ofDays(10))
        val ends = now.plus(Duration.ofDays(5))
        val term = rules.extend(SubscriptionTerm(started, ends, 3), pilier, now)
        assertEquals(started, term.startedAt)
        assertEquals(ends.plus(Duration.ofDays(pilier.days.toLong())), term.endsAt)
        assertEquals(4, term.monthsTotal)
    }

    @Test
    @DisplayName("Achat après la fin : il repart de maintenant avec un nouveau début, mais le compte des mois ne retombe pas à zéro")
    fun restartingAnExpiredSubscription() {
        val term = rules.extend(SubscriptionTerm(now.minus(Duration.ofDays(60)), now.minus(Duration.ofDays(2)), 2), pilier, now)
        assertEquals(now, term.startedAt)
        assertEquals(now.plus(Duration.ofDays(pilier.days.toLong())), term.endsAt)
        assertEquals(3, term.monthsTotal)
    }

    @Test
    @DisplayName("Achat à l'instant même de la fin : l'abonnement est échu, il repart de maintenant")
    fun extendingAtTheVeryEnd() {
        val term = rules.extend(SubscriptionTerm(now.minus(Duration.ofDays(28)), now, 1), pilier, now)
        assertEquals(now, term.startedAt)
        assertEquals(now.plus(Duration.ofDays(pilier.days.toLong())), term.endsAt)
    }

    // --- le boost collectif

    @Test
    @DisplayName("Sans boost réservé, ou avec un boost déjà fini, le nouveau commence maintenant et dure dureeMinutes")
    fun boostStartsNowWhenNothingRuns() {
        val expected = BoostWindow(now, now.plus(Duration.ofMinutes(rules.boost.minutes)))
        assertEquals(expected, rules.scheduleBoost(rules.boost, null, now))
        assertEquals(expected, rules.scheduleBoost(rules.boost, now.minusSeconds(1), now), "le dernier est fini")
        assertEquals(expected, rules.scheduleBoost(rules.boost, now, now), "fini à l'instant même : sa fin est exclue")
    }

    @Test
    @DisplayName("Un boost acheté pendant un autre commence à la fin du dernier boost réservé : les boosts se suivent, ils ne s'empilent pas")
    fun boostsFollowEachOther() {
        val length = Duration.ofMinutes(rules.boost.minutes)
        val first = rules.scheduleBoost(rules.boost, null, now)
        val second = rules.scheduleBoost(rules.boost, first.endsAt, now)
        assertEquals(first.endsAt, second.startsAt)
        assertEquals(first.endsAt.plus(length), second.endsAt)
        // Un troisième acheté plus tard, quand le premier court encore, suit le dernier réservé (le deuxième), pas le premier.
        val third = rules.scheduleBoost(rules.boost, second.endsAt, now.plusSeconds(60))
        assertEquals(second.endsAt, third.startsAt)
        assertEquals(second.endsAt.plus(length), third.endsAt)
    }

    // --- les bonus de Donar

    @Test
    @DisplayName("Sans grade ni Pilier : aucun bonus")
    fun noBonus() {
        assertEquals(ShopBonuses.NONE, rules.bonuses(Holdings.NOTHING, now))
        assertEquals(ShopBonuses(0, 0), rules.bonuses(Holdings.NOTHING, now))
    }

    @Test
    @DisplayName("Le bonus de grade est celui du plus haut grade possédé, jamais la somme des grades")
    fun highestGradeOnly() {
        for (count in 1..gradeIds.size) {
            assertEquals(shop.grades[count - 1].donarBonusPercent, rules.bonuses(firstGrades(count), now).gradePercent, "avec $count grade(s)")
        }
        val sum = shop.grades.sumOf { it.donarBonusPercent }
        assertTrue(rules.bonuses(firstGrades(gradeIds.size), now).gradePercent < sum, "pas cumulatif")
    }

    @Test
    @DisplayName("Le bonus de Pilier compte tant que l'abonnement court, plus après sa fin")
    fun pilierBonusWhileRunning() {
        val bonus = checkNotNull(shop.subscriptions.single { it.id == ShopRules.PILIER_ID }.donarBonusPercent)
        assertEquals(bonus, rules.bonuses(owning(ends = mapOf(pilier.id to now.plusSeconds(1))), now).pilierPercent)
        assertEquals(0, rules.bonuses(owning(ends = mapOf(pilier.id to now)), now).pilierPercent)
        assertEquals(0, rules.bonuses(owning(ends = mapOf(pilier.id to now.minusSeconds(1))), now).pilierPercent)
    }

    @Test
    @DisplayName("Le boost collectif passé à bonuses() est rendu tel quel à côté du grade et du Pilier, sans rien leur retirer ; il ne dépend pas de ce que le joueur possède")
    fun boostIsReportedBesideTheOthers() {
        val boost = rules.boost.bonusPercent
        assertEquals(ShopBonuses(0, 0, boost), rules.bonuses(Holdings.NOTHING, now, boost))
        assertEquals(ShopBonuses.NONE, rules.bonuses(Holdings.NOTHING, now, 0))
        val both = Holdings(setOf(gradeIds[0]), mapOf(pilier.id to now.plusSeconds(60)))
        val bonuses = rules.bonuses(both, now, boost)
        assertEquals(shop.grades[0].donarBonusPercent, bonuses.gradePercent)
        assertEquals(pilier.donarBonusPercent, bonuses.pilierPercent)
        assertEquals(boost, bonuses.boostPercent)
    }

    @Test
    @DisplayName("Le Perso ne donne aucun bonus de Donar, même actif ; le grade et le Pilier se rendent ensemble")
    fun persoHasNoBonusAndTheTwoAreKeptApart() {
        val persoOnly = owning(ends = mapOf(perso.id to now.plusSeconds(60)))
        assertEquals(ShopBonuses.NONE, rules.bonuses(persoOnly, now))
        val both = Holdings(setOf(gradeIds[0]), mapOf(pilier.id to now.plusSeconds(60)))
        val bonuses = rules.bonuses(both, now)
        assertEquals(shop.grades[0].donarBonusPercent, bonuses.gradePercent)
        assertEquals(pilier.donarBonusPercent, bonuses.pilierPercent)
    }
}
