package com.silooo.core.pass

import com.silooo.core.db.Xuid
import java.sql.Connection

/** Une piste du pass. Les noms écrits sont ceux de l'énumération `season_claims.track` et de l'OpenAPI. */
enum class PassTrack(val wire: String) {
    FREE("GRATUIT"),
    PREMIUM("PREMIUM"),
    ;

    companion object {
        /** La piste écrite [wire] (`GRATUIT` ou `PREMIUM`, en majuscules), ou `null`. */
        fun parse(wire: String): PassTrack? = entries.firstOrNull { it.wire == wire }
    }
}

/** Un palier réclamé : une ligne de `season_claims`. */
data class PassClaim(val tier: Int, val track: PassTrack)

/** Où en est un joueur dans une saison : ses points, la piste premium, les paliers réclamés (une ligne de `season_pass` et ses `season_claims`). */
data class PassProgress(val points: Int, val premium: Boolean, val claims: Set<PassClaim>) {
    companion object {
        /** Un joueur qui n'a rien fait dans la saison : pas de ligne en base. */
        val NOTHING = PassProgress(points = 0, premium = false, claims = emptySet())
    }
}

/**
 * Les accès SQL du pass de saison (`season_pass`, `season_claims`, `player_cosmetics`) et le versement des Lingots du palier gratuit,
 * derrière une interface : les tests de route tournent avec un faux en mémoire, le SQL avec la vraie base (Testcontainers,
 * `PassSqlTest`).
 *
 * Tout ce qui écrit passe par la transaction de la [Connection] reçue : une réclamation ou un achat part en entier ou pas du tout.
 * Aucune table nouvelle : le pass vit dans celles de V1.
 */
interface PassStore {
    /** Vrai si [xuid] a une ligne `players` : un joueur que le service connaît (il est déjà passé par `join`). */
    fun isKnownPlayer(connection: Connection, xuid: Xuid): Boolean

    /** Le pass de [xuid] dans la saison [seasonId], sans verrou ; [PassProgress.NOTHING] s'il n'a pas de ligne. Rien n'est écrit. */
    fun load(connection: Connection, xuid: Xuid, seasonId: String): PassProgress

    /**
     * Rend le pass de [xuid] dans [seasonId] en verrouillant sa ligne jusqu'à la fin de la transaction ; la crée à zéro si elle n'existe
     * pas. Deux réclamations ou un achat et une réclamation du même joueur passent donc l'un après l'autre. Joueur inconnu :
     * [IllegalArgumentException].
     */
    fun lock(connection: Connection, xuid: Xuid, seasonId: String): PassProgress

    /**
     * Ajoute [points] au pass de [xuid] dans [seasonId], et crée la ligne si elle manque : une seule écriture, qui ne perd aucun point si
     * deux actions du même joueur arrivent en même temps.
     */
    fun addPoints(connection: Connection, xuid: Xuid, seasonId: String, points: Int)

    /** Pose la piste premium de la ligne [xuid]/[seasonId] (à appeler après [lock]). Rend faux si elle y était déjà. */
    fun grantPremium(connection: Connection, xuid: Xuid, seasonId: String): Boolean

    /** Note le palier réclamé. Rend faux si [claim] l'était déjà (deux réclamations en même temps : la seconde ne l'écrit pas). */
    fun recordClaim(connection: Connection, xuid: Xuid, seasonId: String, claim: PassClaim): Boolean

    /** Donne le cosmétique [cosmeticId] à [xuid] avec la source `pass`. Rend faux s'il l'avait déjà. */
    fun grantCosmetic(connection: Connection, xuid: Xuid, cosmeticId: String): Boolean

    /**
     * Prend le verrou de la ligne de soldes de [xuid], sans rien changer. Une réclamation le prend avant celui du pass, comme le prestige,
     * une mission ou une commande qui versent leurs Lingots avant de compter les points : les verrous se prennent dans le même ordre.
     */
    fun lockBalances(connection: Connection, xuid: Xuid)

    /** Verse [amount] Lingots à [xuid] : `player_balances` et une ligne de `currency_ledger` (voir `creditBalance`). Rend le solde après. */
    fun creditLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long
}
