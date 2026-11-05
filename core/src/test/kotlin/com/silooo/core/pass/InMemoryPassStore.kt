package com.silooo.core.pass

import com.silooo.core.api.Balances
import com.silooo.core.db.Xuid
import com.silooo.core.session.InMemorySessionStore
import java.sql.Connection

/**
 * Le faux en mémoire de [PassStore], pour les tests du pass sans base. Les soldes et les joueurs connus vivent dans le faux
 * [InMemorySessionStore] (`balances`, `players`), que `loadBalances` lit. Le reste est ici, comme les tables de V1 : `season_pass`,
 * `season_claims`, `player_cosmetics`, et les mouvements de Lingots du pass.
 *
 * Il rejoue ce que le SQL tient : une réclamation ne s'écrit qu'une fois, un cosmétique ne se donne qu'une fois, la piste premium
 * ne se pose qu'une fois, un joueur inconnu n'a pas de ligne. Les verrous, la concurrence et le SQL lui-même se testent sur MariaDB
 * (`PassSqlTest`) ; ce faux ne note que l'ordre des verrous demandés ([locks]).
 */
class InMemoryPassStore(private val sessions: InMemorySessionStore) : PassStore {
    /** Une ligne de `season_pass`. */
    class Row(var points: Int = 0, var premium: Boolean = false)

    /** Une ligne de `player_cosmetics`. */
    data class Cosmetic(val xuid: Xuid, val cosmeticId: String, val source: String)

    /** Une ligne de `currency_ledger` pour les Lingots du pass. */
    data class Movement(val xuid: Xuid, val delta: Long, val reason: String, val refId: String)

    val rows = LinkedHashMap<Pair<Xuid, String>, Row>()
    val claims = LinkedHashMap<Pair<Xuid, String>, MutableSet<PassClaim>>()
    val cosmetics = mutableListOf<Cosmetic>()
    val movements = mutableListOf<Movement>()

    /** Les verrous demandés, dans l'ordre : `soldes` (ligne de soldes) puis `pass` (ligne du pass). */
    val locks = mutableListOf<String>()

    /** Pose les points et la piste premium d'un joueur directement : l'état de départ d'un test. */
    fun seed(xuid: Xuid, seasonId: String, points: Int = 0, premium: Boolean = false) {
        rows[xuid to seasonId] = Row(points, premium)
    }

    /** Pose un palier déjà réclamé directement : l'état de départ d'un test. */
    fun seedClaim(xuid: Xuid, seasonId: String, tier: Int, track: PassTrack) {
        claims.getOrPut(xuid to seasonId) { linkedSetOf() } += PassClaim(tier, track)
    }

    /** Les points de [xuid] dans [seasonId] (zéro sans ligne). */
    fun pointsOf(xuid: Xuid, seasonId: String): Int = rows[xuid to seasonId]?.points ?: 0

    /** La piste premium de [xuid] dans [seasonId]. */
    fun premiumOf(xuid: Xuid, seasonId: String): Boolean = rows[xuid to seasonId]?.premium ?: false

    /** Les cosmétiques de [xuid], dans l'ordre où ils ont été donnés. */
    fun cosmeticsOf(xuid: Xuid): List<String> = cosmetics.filter { it.xuid == xuid }.map { it.cosmeticId }

    override fun isKnownPlayer(connection: Connection, xuid: Xuid): Boolean = xuid in sessions.players

    override fun load(connection: Connection, xuid: Xuid, seasonId: String): PassProgress {
        val row = rows[xuid to seasonId] ?: return PassProgress.NOTHING
        return PassProgress(row.points, row.premium, claims[xuid to seasonId].orEmpty().toSet())
    }

    override fun lock(connection: Connection, xuid: Xuid, seasonId: String): PassProgress {
        locks += "pass"
        require(xuid in sessions.players) { "Joueur inconnu : $xuid" }
        rows.getOrPut(xuid to seasonId) { Row() }
        return load(connection, xuid, seasonId)
    }

    override fun addPoints(connection: Connection, xuid: Xuid, seasonId: String, points: Int) {
        require(points >= 0) { "Des points ne se retirent pas : $points" }
        rows.getOrPut(xuid to seasonId) { Row() }.points += points
    }

    override fun grantPremium(connection: Connection, xuid: Xuid, seasonId: String): Boolean {
        val row = rows[xuid to seasonId] ?: return false
        if (row.premium) return false
        row.premium = true
        return true
    }

    override fun recordClaim(connection: Connection, xuid: Xuid, seasonId: String, claim: PassClaim): Boolean =
        claims.getOrPut(xuid to seasonId) { linkedSetOf() }.add(claim)

    override fun grantCosmetic(connection: Connection, xuid: Xuid, cosmeticId: String): Boolean {
        if (cosmetics.any { it.xuid == xuid && it.cosmeticId == cosmeticId }) return false
        cosmetics += Cosmetic(xuid, cosmeticId, "pass")
        return true
    }

    override fun lockBalances(connection: Connection, xuid: Xuid) {
        locks += "soldes"
    }

    override fun creditLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long {
        require(amount >= 0L) { "Un versement n'est pas négatif : $amount" }
        val before = sessions.balances[xuid] ?: Balances(0, 0, 0, 0)
        sessions.balances[xuid] = before.copy(lingots = before.lingots + amount)
        movements += Movement(xuid, amount, reason, refId)
        return before.lingots + amount
    }
}
