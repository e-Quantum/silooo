package com.silooo.core.pass

import com.silooo.core.TestDefinitions
import com.silooo.core.api.CatalogItem
import com.silooo.core.api.PassFreeReward
import com.silooo.core.api.PassPremiumReward
import com.silooo.core.api.RefusalCodes
import com.silooo.core.shop.Holdings
import com.silooo.core.shop.ShopRules
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [PassRules] sur les `definitions/` du dépôt, sans base ni HTTP. Aucun chiffre n'est écrit ici : les dates, les points, le prix et
 * les paliers sortent de `pass.json`, comme dans le service. Le déroulé complet d'une réclamation est dans `PassRoutesTest`, le SQL
 * dans `PassSqlTest`.
 */
class PassRulesTest {
    private val definitions = TestDefinitions.loaded
    private val pass = definitions.pass
    private val rules = PassRules(definitions)

    private val first: LocalDate = pass.season.startDay
    private val last: LocalDate = pass.season.endDay
    private val during: LocalDate = first

    /** Un palier qui a une récompense sur chaque piste, et un qui n'en a pas sur la piste premium. */
    private val full = pass.tiers.first { it.free != null && it.premium != null }
    private val freeOnly = pass.tiers.first { it.free != null && it.premium == null }

    private fun progress(points: Int = 0, premium: Boolean = false, vararg claims: PassClaim) =
        PassProgress(points, premium, claims.toSet())

    @Test
    @DisplayName("isActive : du premier au dernier jour de la saison, compris ; la veille et le lendemain, non")
    fun seasonBounds() {
        assertEquals(false, rules.isActive(first.minusDays(1)))
        assertEquals(true, rules.isActive(first))
        assertEquals(true, rules.isActive(first.plusDays(1)))
        assertEquals(true, rules.isActive(last))
        assertEquals(false, rules.isActive(last.plusDays(1)))
    }

    @Test
    @DisplayName("pointsFor : chaque source rapporte les points de pass.json")
    fun pointsComeFromTheDefinitions() {
        assertEquals(pass.points.perPrestige, rules.pointsFor(PassSource.PRESTIGE))
        assertEquals(pass.points.perOrder, rules.pointsFor(PassSource.ORDER))
        assertEquals(pass.points.perMission, rules.pointsFor(PassSource.MISSION))
        assertTrue(PassSource.entries.all { rules.pointsFor(it) > 0 }, "les valeurs de départ rapportent toutes quelque chose")
    }

    @Test
    @DisplayName("tier : à partir de 1 ; zéro, un numéro négatif et le suivant du dernier n'existent pas")
    fun tierNumbering() {
        assertNull(rules.tier(0))
        assertNull(rules.tier(-1))
        assertNull(rules.tier(pass.tiers.size + 1))
        pass.tiers.forEach { assertEquals(it, rules.tier(it.tier)) }
    }

    @Test
    @DisplayName("hasReward : suit la présence d'une récompense à ce palier sur cette piste")
    fun rewardPresence() {
        pass.tiers.forEach {
            assertEquals(it.free != null, rules.hasReward(it, PassTrack.FREE), "palier ${it.tier}, piste gratuite")
            assertEquals(it.premium != null, rules.hasReward(it, PassTrack.PREMIUM), "palier ${it.tier}, piste premium")
        }
        assertTrue(pass.tiers.any { it.premium == null }, "le test n'a de sens que s'il y a un palier sans piste premium")
    }

    @Test
    @DisplayName("claimRefusal : rien ne s'oppose au palier atteint, au seuil exact comme au-dessus")
    fun reachedTierIsClaimable() {
        assertNull(rules.claimRefusal(progress(full.points), full, PassTrack.FREE))
        assertNull(rules.claimRefusal(progress(full.points + 1), full, PassTrack.FREE))
        assertNull(rules.claimRefusal(progress(full.points, premium = true), full, PassTrack.PREMIUM))
    }

    @Test
    @DisplayName("claimRefusal : sous le seuil, PALIER_NON_ATTEINT avec les points qui manquent")
    fun tierNotReached() {
        val refusal = checkNotNull(rules.claimRefusal(progress(full.points - 1), full, PassTrack.FREE))
        assertEquals(RefusalCodes.TIER_NOT_REACHED, refusal.code)
        assertEquals(1.0, refusal.missing)
        val none = checkNotNull(rules.claimRefusal(PassProgress.NOTHING, full, PassTrack.FREE))
        assertEquals(full.points.toDouble(), none.missing)
    }

    @Test
    @DisplayName("claimRefusal : la piste premium exige l'achat, même avec les points ; sans l'achat, PREMIUM_REQUIS passe avant PALIER_NON_ATTEINT")
    fun premiumRequiresThePurchase() {
        val enough = checkNotNull(rules.claimRefusal(progress(full.points), full, PassTrack.PREMIUM))
        assertEquals(RefusalCodes.PREMIUM_REQUIRED, enough.code)
        assertNull(enough.missing)
        val short = checkNotNull(rules.claimRefusal(progress(0), full, PassTrack.PREMIUM))
        assertEquals(RefusalCodes.PREMIUM_REQUIRED, short.code)
        val owned = checkNotNull(rules.claimRefusal(progress(0, premium = true), full, PassTrack.PREMIUM))
        assertEquals(RefusalCodes.TIER_NOT_REACHED, owned.code)
    }

    @Test
    @DisplayName("claimRefusal : un palier déjà réclamé sur cette piste donne DEJA_RECLAME avant tout autre refus ; l'autre piste reste libre")
    fun alreadyClaimedComesFirst() {
        val claimed = progress(0, false, PassClaim(full.tier, PassTrack.PREMIUM))
        assertEquals(RefusalCodes.ALREADY_CLAIMED, rules.claimRefusal(claimed, full, PassTrack.PREMIUM)?.code)
        assertEquals(RefusalCodes.TIER_NOT_REACHED, rules.claimRefusal(claimed, full, PassTrack.FREE)?.code, "la piste gratuite du même palier est une autre réclamation")
        assertEquals(
            RefusalCodes.ALREADY_CLAIMED,
            rules.claimRefusal(progress(full.points, true, PassClaim(full.tier, PassTrack.FREE)), full, PassTrack.FREE)?.code,
        )
    }

    @Test
    @DisplayName("premiumRefusal : hors saison SAISON_INACTIVE (même si le joueur l'a), sinon DEJA_POSSEDE s'il l'a, sinon rien")
    fun premiumPurchaseRefusals() {
        assertEquals(RefusalCodes.SEASON_INACTIVE, rules.premiumRefusal(progress(), first.minusDays(1)))
        assertEquals(RefusalCodes.SEASON_INACTIVE, rules.premiumRefusal(progress(), last.plusDays(1)))
        assertEquals(RefusalCodes.SEASON_INACTIVE, rules.premiumRefusal(progress(premium = true), last.plusDays(1)))
        assertEquals(RefusalCodes.ALREADY_OWNED, rules.premiumRefusal(progress(premium = true), during))
        assertNull(rules.premiumRefusal(progress(), during))
        assertNull(rules.premiumRefusal(progress(), last))
    }

    @Test
    @DisplayName("catalogItem : pass-premium au prix de pass.json ; owned s'il l'a, available tant que la saison court et qu'il ne l'a pas")
    fun catalogEntry() {
        assertEquals(CatalogItem("pass-premium", pass.premium.gems, owned = false, available = true), rules.catalogItem(progress(), during))
        assertEquals(CatalogItem("pass-premium", pass.premium.gems, owned = true, available = false), rules.catalogItem(progress(premium = true), during))
        assertEquals(CatalogItem("pass-premium", pass.premium.gems, owned = false, available = false), rules.catalogItem(progress(), last.plusDays(1)))
        assertEquals(PassRules.PREMIUM_ITEM_ID, "pass-premium")
        assertEquals(pass.premium.gems, rules.premiumGems)
    }

    @Test
    @DisplayName("claimKey : « palier-PISTE », comme PassState.claimed")
    fun claimKeyFormat() {
        assertEquals("3-GRATUIT", rules.claimKey(PassClaim(3, PassTrack.FREE)))
        assertEquals("10-PREMIUM", rules.claimKey(PassClaim(10, PassTrack.PREMIUM)))
    }

    @Test
    @DisplayName("PassTrack.parse : GRATUIT et PREMIUM en majuscules seulement")
    fun trackParsing() {
        assertEquals(PassTrack.FREE, PassTrack.parse("GRATUIT"))
        assertEquals(PassTrack.PREMIUM, PassTrack.parse("PREMIUM"))
        listOf("gratuit", "Premium", "", " GRATUIT", "FREE", "BRONZE").forEach { assertNull(PassTrack.parse(it), it) }
    }

    @Test
    @DisplayName("state : la saison, les points du joueur, ses paliers réclamés dans l'ordre palier puis piste, et tous les paliers de pass.json")
    fun stateShowsTheWholeSeason() {
        val claims = arrayOf(PassClaim(full.tier, PassTrack.PREMIUM), PassClaim(freeOnly.tier, PassTrack.FREE), PassClaim(full.tier, PassTrack.FREE))
        val state = rules.state(progress(7, true, *claims), during)

        assertEquals(pass.season.id, state.seasonId)
        assertEquals(pass.season.name, state.name)
        assertEquals(pass.season.start, state.startsOn)
        assertEquals(pass.season.end, state.endsOn)
        assertEquals(true, state.active)
        assertEquals(7, state.points)
        assertEquals(true, state.premium)
        val expectedOrder = claims.sortedWith(compareBy({ it.tier }, { it.track.ordinal })).map { "${it.tier}-${it.track.wire}" }
        assertEquals(expectedOrder, state.claimed)
        assertEquals(pass.tiers.size, state.tiers.size)
        state.tiers.zip(pass.tiers).forEach { (shown, defined) ->
            assertEquals(defined.tier, shown.tier)
            assertEquals(defined.points, shown.points)
            assertEquals(defined.free?.let { PassFreeReward(it.lingots) }, shown.free)
            assertEquals(defined.premium?.let { PassPremiumReward(it.cosmeticId) }, shown.premium)
        }
    }

    @Test
    @DisplayName("Tout cosmétique que boutique.json ne vend pas (achetable à faux : ceux du pass comme le titre Pionnier) n'est pas un article et reste hors du catalogue, même possédé")
    fun cosmeticsThatAreNotSoldAreNeverShopItems() {
        val shopRules = ShopRules(definitions)
        val notSold = definitions.shop.cosmetics.filter { !it.purchasable }.map { it.id }
        val given = pass.tiers.mapNotNull { it.premium?.cosmeticId }
        assertTrue(given.isNotEmpty())
        assertTrue(notSold.containsAll(given + ShopRules.PIONEER_TITLE_ID), "le pass et le titre Pionnier sont déclarés sans être vendus")

        val catalog = shopRules.catalog(Holdings(emptySet(), emptyMap(), cosmetics = notSold.toSet()), Instant.EPOCH)
        for (id in notSold) {
            assertNull(shopRules.item(id), "« $id » n'est pas un article")
            assertTrue(catalog.none { it.itemId == id }, "« $id » n'est pas au catalogue, même possédé")
        }
        assertEquals(shopRules.cosmetics.map { it.id }, catalog.map { it.itemId }.filter { id -> definitions.shop.cosmetics.any { it.id == id } })
    }

    @Test
    @DisplayName("state : active suit la date, et le pass reste lisible hors saison")
    fun stateOutsideTheSeason() {
        assertEquals(false, rules.state(PassProgress.NOTHING, first.minusDays(1)).active)
        assertEquals(true, rules.state(PassProgress.NOTHING, last).active)
        val after = rules.state(progress(points = full.points), last.plusDays(1))
        assertEquals(false, after.active)
        assertEquals(full.points, after.points)
        assertNotNull(after.tiers.firstOrNull())
    }
}
