package com.silooo.core.vote

import com.silooo.core.db.Xuid
import com.silooo.core.events.GameEventKind
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject

/** Une ligne de `server_votes`. [xuid] est nul tant que le pseudo n'est pas rattaché à un joueur ; [rewarded] dit que `rewarded_at` est posé. */
data class VoteRow(
    val id: Long,
    val xuid: Xuid?,
    val gamertag: String,
    val site: String,
    val votedAt: Instant,
    val rewarded: Boolean,
)

/**
 * Les accès SQL des votes (`server_votes`), derrière une interface : les tests de règles et de route tournent avec un faux en
 * mémoire, le SQL avec la vraie base (Testcontainers, `VoteSqlTest`). Les Lingots et les événements passent par ici aussi, pour que
 * le service n'écrive rien d'autre que ce que cette interface dit.
 *
 * Un « jour » est un jour UTC, comme partout dans le jeu : `voted_at` est en UTC (`DATETIME(3)`).
 */
interface VoteStore {
    /** Le joueur de pseudo [gamertag] (casse ignorée, comme la colonne), le vu en dernier s'ils sont plusieurs ; `null` si personne. */
    fun playerByGamertag(connection: Connection, gamertag: String): Xuid?

    /** Écrit un vote reçu à [votedAt], pas encore versé (`rewarded_at` nul). [xuid] est celui du joueur si le pseudo est connu. Rend l'identifiant. */
    fun insertVote(connection: Connection, gamertag: String, site: String, votedAt: Instant, xuid: Xuid?): Long

    /**
     * Marque le vote [id] comme versé à [xuid], à [at] : `UPDATE ... WHERE id = ? AND rewarded_at IS NULL`. Vrai si CET appel l'a marqué,
     * faux si un autre passage l'avait fait : seul le premier verse les Lingots.
     */
    fun markRewarded(connection: Connection, id: Long, xuid: Xuid, at: Instant): Boolean

    /**
     * Les votes pas encore versés qui attendent le joueur [xuid] de pseudo [gamertag] (sans joueur, ou déjà à son nom), du plus
     * ancien au plus récent, [limit] au plus. Les lignes sont verrouillées (`FOR UPDATE`) : deux `join` du même joueur ne se les disputent pas.
     */
    fun pendingFor(connection: Connection, xuid: Xuid, gamertag: String, limit: Int): List<VoteRow>

    /** Le nombre de votes reçus le jour [day] (UTC), tous sites et tous pseudos, rattachés ou non. */
    fun countOn(connection: Connection, day: LocalDate): Int

    /** Le nombre de votes du joueur [xuid] le jour [day]. */
    fun countOfPlayerOn(connection: Connection, xuid: Xuid, day: LocalDate): Int

    /** Les jours (UTC) où [xuid] a voté, jusqu'à [through] compris. */
    fun votedDays(connection: Connection, xuid: Xuid, through: LocalDate): Set<LocalDate>

    /** L'identifiant du premier vote de [xuid] le jour [day], ou `null` s'il n'a pas voté ce jour-là. */
    fun firstVoteIdOn(connection: Connection, xuid: Xuid, day: LocalDate): Long?

    /** Ceux de [xuids] que `players` connaît, du plus petit XUID au plus grand : c'est l'ordre dans lequel on les verse. */
    fun knownPlayers(connection: Connection, xuids: Collection<Xuid>): List<Xuid>

    /**
     * Verse [amount] Lingots à [xuid] : `player_balances` et une ligne de `currency_ledger`, dans la transaction de [connection]
     * (voir `creditBalance`). Rend le solde après le versement.
     */
    fun creditLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long

    /** Écrit un événement dans `game_events`, dans la transaction de [connection] (voir `publishEvent`). Rend son identifiant. */
    fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long
}
