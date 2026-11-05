package com.silooo.core.pass

import com.silooo.core.api.CatalogItem
import com.silooo.core.api.PassClaimed
import com.silooo.core.api.PassState
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.refuse
import com.silooo.core.db.Xuid
import com.silooo.core.definitions.Definitions
import com.silooo.core.economy.RequestRejected
import com.silooo.core.session.SessionStore
import java.sql.Connection
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Le pass de saison, sans HTTP : ce que `getPass` montre, la réclamation d'un palier (`claimPass`), les points que les autres actions du
 * service versent, et les deux gestes que la boutique demande pour la piste premium. Les routes valident l'entrée et répondent ; ce
 * service lit, décide et écrit dans la transaction que l'appelant lui donne ([Connection]). Il ne contient aucun chiffre du jeu
 * (voir [PassRules]) ni aucun texte pour les joueurs.
 *
 * Une réclamation suit le déroulé des autres actions :
 *  1. Les cas de 400 tombent avant toute écriture ([RequestRejected]) : acteur inconnu, palier qui n'existe pas, piste sans récompense
 *     à ce palier.
 *  2. Le verrou de la ligne de soldes du joueur, puis celui de son pass : deux réclamations se suivent, et l'ordre des verrous est celui
 *     des actions qui versent des Lingots avant de compter des points.
 *  3. Les refus du jeu (`Refusal`) tombent avant la première écriture : `DEJA_RECLAME`, `PREMIUM_REQUIS`, `PALIER_NON_ATTEINT`.
 *  4. Le palier est noté (clé unique), puis la récompense est donnée : des Lingots hors prestige (piste gratuite) ou un cosmétique
 *     (piste premium, jamais de monnaie). Aucun événement n'est publié.
 *  5. La réponse dit ce qui a été donné et rend les soldes de l'acteur.
 *
 * Les points se versent dans la transaction de l'action qui les rapporte ([onPrestige], [onOrderCompleted], [onMissionCompleted]) :
 * si l'action échoue ou rejoue, les points repartent avec elle. Hors saison, rien n'est écrit.
 */
class PassService(
    definitions: Definitions,
    private val store: PassStore,
    private val sessions: SessionStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    val rules = PassRules(definitions)

    /** Le jour de jeu : la date UTC de l'horloge du service. */
    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

    /** Le pass d'[actor]. Un joueur que le service ne connaît pas : [RequestRejected]. Rien n'est écrit. */
    fun state(connection: Connection, actor: Xuid): PassState {
        if (!store.isKnownPlayer(connection, actor)) throw RequestRejected("Joueur inconnu : $actor")
        return rules.state(store.load(connection, actor, rules.seasonId), today())
    }

    /** [actor] réclame le palier [tier] sur la piste [track]. Voir le déroulé de la classe. */
    fun claim(connection: Connection, actor: Xuid, tier: Int, track: PassTrack): PassClaimed {
        val definition = rules.tier(tier) ?: throw RequestRejected("Ce palier n'existe pas : $tier")
        if (!rules.hasReward(definition, track)) throw RequestRejected("Le palier $tier n'a pas de récompense sur la piste ${track.wire}")
        if (!store.isKnownPlayer(connection, actor)) throw RequestRejected("Joueur inconnu : $actor")

        store.lockBalances(connection, actor)
        val progress = store.lock(connection, actor, rules.seasonId)
        rules.claimRefusal(progress, definition, track)?.let { refuse(it.code, it.missing) }

        // Faux : une autre réclamation du même palier a passé entre-temps. La transaction est annulée, rien n'est donné.
        if (!store.recordClaim(connection, actor, rules.seasonId, PassClaim(tier, track))) refuse(RefusalCodes.ALREADY_CLAIMED)

        var lingots = 0L
        var cosmeticId: String? = null
        when (track) {
            PassTrack.FREE -> {
                lingots = checkNotNull(definition.free).lingots
                store.creditLingots(connection, actor, lingots, REASON, "${rules.seasonId}:$tier:${track.wire}")
            }
            PassTrack.PREMIUM -> {
                cosmeticId = checkNotNull(definition.premium).cosmeticId
                // Un cosmétique déjà possédé (une autre source) ne bloque pas le palier : le joueur ne perd pas sa réclamation.
                store.grantCosmetic(connection, actor, cosmeticId)
            }
        }
        return PassClaimed(rules.seasonId, tier, track.wire, lingots, cosmeticId, sessions.loadBalances(connection, actor))
    }

    /** Un prestige vient de se faire : les points de [xuid], un associé de l'entreprise. */
    fun onPrestige(connection: Connection, xuid: Xuid) = addPoints(connection, xuid, PassSource.PRESTIGE)

    /** La commande du jour vient de se terminer : les points de [xuid], un joueur qui a livré. */
    fun onOrderCompleted(connection: Connection, xuid: Xuid) = addPoints(connection, xuid, PassSource.ORDER)

    /** Une mission de [xuid] vient de se terminer. */
    fun onMissionCompleted(connection: Connection, xuid: Xuid) = addPoints(connection, xuid, PassSource.MISSION)

    private fun addPoints(connection: Connection, xuid: Xuid, source: PassSource) {
        val points = rules.pointsFor(source)
        if (points <= 0 || !rules.isActive(today())) return
        store.addPoints(connection, xuid, rules.seasonId, points)
    }

    // ---------------------------------------------------------------- la boutique

    /** L'article `pass-premium` du catalogue vu par [actor] : son prix, s'il l'a, s'il peut l'acheter. */
    fun catalogItem(connection: Connection, actor: Xuid): CatalogItem =
        rules.catalogItem(store.load(connection, actor, rules.seasonId), today())

    /** Le code de refus de l'achat de la piste premium pour [recipient] (`SAISON_INACTIVE`, `DEJA_POSSEDE`), ou `null`. */
    fun premiumRefusal(connection: Connection, recipient: Xuid): String? =
        rules.premiumRefusal(store.load(connection, recipient, rules.seasonId), today())

    /**
     * Ouvre la piste premium de [recipient] pour la saison en cours, en verrouillant son pass. Rend faux s'il l'avait déjà (un autre achat
     * a passé entre-temps) : l'achat est alors annulé par l'appelant.
     */
    fun grantPremium(connection: Connection, recipient: Xuid): Boolean {
        store.lock(connection, recipient, rules.seasonId)
        return store.grantPremium(connection, recipient, rules.seasonId)
    }

    companion object {
        /** `currency_ledger.reason` des Lingots d'un palier ; `ref_id` porte « saison:palier:piste ». */
        const val REASON = "pass"
    }
}
