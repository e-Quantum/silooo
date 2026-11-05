package com.silooo.core.pass

import com.silooo.core.api.CatalogItem
import com.silooo.core.api.PassFreeReward
import com.silooo.core.api.PassPremiumReward
import com.silooo.core.api.PassState
import com.silooo.core.api.PassTier
import com.silooo.core.api.RefusalCodes
import com.silooo.core.definitions.Definitions
import com.silooo.core.definitions.PassTierDefinition
import java.time.LocalDate

/** Ce qui rapporte des points au pass. Les chiffres sont ceux de `pass.json` (`points`). */
enum class PassSource {
    /** Un prestige : les points vont à chaque associé de l'entreprise. */
    PRESTIGE,

    /** La commande du jour se termine : les points vont à chaque joueur qui a livré. */
    ORDER,

    /** Une mission est terminée. */
    MISSION,
}

/** Un refus du pass : le code du contrat et, pour `PALIER_NON_ATTEINT`, les points qui manquent. */
data class PassRefusal(val code: String, val missing: Double? = null)

/**
 * Les règles du pass de saison, sans base ni HTTP : quand la saison court, ce que chaque action rapporte, ce qu'une réclamation exige,
 * ce que `getPass` et le catalogue montrent. Aucun chiffre n'est écrit ici : la saison, le prix, les points et les paliers viennent de
 * `definitions/pass.json`. Tous les jours sont des jours UTC.
 *
 * Les règles que `pass.json` ne dit pas (tranchées au plus simple, **à valider par Kr**) :
 *  - les points ne montent que du premier au dernier jour de la saison, compris ; les paliers atteints se réclament encore après
 *    sa fin, tant que `pass.json` décrit cette saison ;
 *  - la piste premium s'achète tant que la saison court, une fois par saison et par joueur ;
 *  - un palier se réclame à la main (une réclamation par piste et par palier), dans n'importe quel ordre ;
 *  - un cosmétique que le joueur a déjà ne bloque pas la réclamation du palier.
 */
class PassRules(definitions: Definitions) {
    private val pass = definitions.pass

    /** L'identifiant de la saison (`season_pass.season_id`). */
    val seasonId: String = pass.season.id

    /** Le prix de la piste premium, en Gemmes. */
    val premiumGems: Long = pass.premium.gems

    /** Vrai si la saison court le [day] (jour UTC), du premier au dernier jour compris. */
    fun isActive(day: LocalDate): Boolean = pass.season.covers(day)

    /** Les points que rapporte [source] (zéro quand `pass.json` ne lui en donne pas). */
    fun pointsFor(source: PassSource): Int = when (source) {
        PassSource.PRESTIGE -> pass.points.perPrestige
        PassSource.ORDER -> pass.points.perOrder
        PassSource.MISSION -> pass.points.perMission
    }

    /** Le palier numéro [tier] (à partir de 1), ou `null` s'il n'existe pas. */
    fun tier(tier: Int): PassTierDefinition? = pass.tier(tier)

    /** Vrai si le palier [tier] a une récompense sur la piste [track]. */
    fun hasReward(tier: PassTierDefinition, track: PassTrack): Boolean = when (track) {
        PassTrack.FREE -> tier.free != null
        PassTrack.PREMIUM -> tier.premium != null
    }

    /**
     * Le refus d'une réclamation du palier [tier] sur la piste [track] par un joueur dont le pass est [progress], ou `null` si rien ne
     * s'y oppose : `DEJA_RECLAME`, puis `PREMIUM_REQUIS`, puis `PALIER_NON_ATTEINT` avec les points qui manquent.
     */
    fun claimRefusal(progress: PassProgress, tier: PassTierDefinition, track: PassTrack): PassRefusal? = when {
        PassClaim(tier.tier, track) in progress.claims -> PassRefusal(RefusalCodes.ALREADY_CLAIMED)
        track == PassTrack.PREMIUM && !progress.premium -> PassRefusal(RefusalCodes.PREMIUM_REQUIRED)
        progress.points < tier.points -> PassRefusal(RefusalCodes.TIER_NOT_REACHED, (tier.points - progress.points).toDouble())
        else -> null
    }

    /**
     * Le refus d'un achat de la piste premium par un destinataire dont le pass est [progress], le [day] : `SAISON_INACTIVE` quand aucune
     * saison ne court, `DEJA_POSSEDE` s'il l'a déjà ; `null` sinon.
     */
    fun premiumRefusal(progress: PassProgress, day: LocalDate): String? = when {
        !isActive(day) -> RefusalCodes.SEASON_INACTIVE
        progress.premium -> RefusalCodes.ALREADY_OWNED
        else -> null
    }

    /** L'article de catalogue de la piste premium : `owned` si le joueur l'a, `available` tant que la saison court et qu'il ne l'a pas. */
    fun catalogItem(progress: PassProgress, day: LocalDate): CatalogItem =
        CatalogItem(PREMIUM_ITEM_ID, premiumGems, owned = progress.premium, available = premiumRefusal(progress, day) == null)

    /** Le texte de `PassState.claimed` pour un palier réclamé : « palier-PISTE », par exemple `3-GRATUIT`. */
    fun claimKey(claim: PassClaim): String = "${claim.tier}-${claim.track.wire}"

    /** Le pass tel que `getPass` le montre, le [day] : toute la saison, les points du joueur, ce qu'il a déjà réclamé. */
    fun state(progress: PassProgress, day: LocalDate): PassState = PassState(
        seasonId = pass.season.id,
        name = pass.season.name,
        startsOn = pass.season.start,
        endsOn = pass.season.end,
        active = isActive(day),
        points = progress.points,
        premium = progress.premium,
        claimed = progress.claims.sortedWith(compareBy({ it.tier }, { it.track.ordinal })).map(::claimKey),
        tiers = pass.tiers.map { tier ->
            PassTier(
                tier = tier.tier,
                points = tier.points,
                free = tier.free?.let { PassFreeReward(it.lingots) },
                premium = tier.premium?.let { PassPremiumReward(it.cosmeticId) },
            )
        },
    )

    companion object {
        /** L'`id` de la piste premium dans le catalogue de la boutique et dans `shop_purchases.item_id`. */
        const val PREMIUM_ITEM_ID = "pass-premium"
    }
}
