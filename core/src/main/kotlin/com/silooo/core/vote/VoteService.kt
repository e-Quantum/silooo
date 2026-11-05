package com.silooo.core.vote

import com.silooo.core.api.PlayerVotes
import com.silooo.core.api.VotesToday
import com.silooo.core.db.Database
import com.silooo.core.db.Xuid
import com.silooo.core.db.isTransientConflict
import com.silooo.core.definitions.Definitions
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.PendingVotes
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Un vote tel que le service le reçoit : le pseudo saisi sur le site (`username`) et le site (`serviceName`). */
data class IncomingVote(val gamertag: String, val site: String)

/** Ce qu'un vote a versé à son joueur : les Lingots en tout, la série à ce jour, et la part de bonus de série comprise dans les Lingots. */
data class VoteGrant(val lingots: Long, val streak: Int, val streakBonusLingots: Long)

/** La Vote party qu'un vote a déclenchée : le jour, les votes du jour, les joueurs présents qui ont reçu les Lingots et le montant de chacun. */
data class PartyOutcome(val day: LocalDate, val votes: Int, val players: List<Xuid>, val lingotsPerPlayer: Long)

/**
 * Ce qu'un vote reçu a fait. [xuid] et [grant] sont nuls quand le pseudo est inconnu (le vote attend le `join`) ; [party] est nul
 * quand ce vote n'est pas celui qui atteint l'objectif du jour.
 */
data class VoteReceipt(val voteId: Long, val xuid: Xuid?, val grant: VoteGrant?, val rankToday: Int, val party: PartyOutcome?)

/**
 * Les votes pour le serveur, sans HTTP : recevoir un vote ([receive]), rattraper ceux d'un pseudo qui arrive ([relieve], appelé par
 * `join`) et lire le jour ([today]). Les chiffres viennent de `definitions/vote.json` ([com.silooo.core.definitions.VoteDefinitions]),
 * les règles de [VoteRules]. Tout ce qui suit est **à valider par Kr**.
 *
 *  - Un vote reçu est une ligne de `server_votes` : `gamertag` = `username`, `site` = le site connu de `vote.json` (ou le nom annoncé),
 *    `xuid` = le joueur de ce pseudo (le vu en dernier), nul s'il est inconnu, `voted_at` = l'heure du service (UTC).
 *  - Joueur connu : dans la même transaction, la ligne est marquée versée (`rewarded_at`), les Lingots hors prestige sont crédités
 *    (`recompense.lingotsParVote`, plus `serie.lingotsEnPlus` quand la série boucle ; motif `vote`, référence : l'id de la ligne) et un
 *    événement `ANNONCE` (`payload.action` = `vote`) est écrit pour lui.
 *  - Joueur inconnu : la ligne attend. `join` appelle [relieve] et la verse alors, comme un vote reçu ce jour-là.
 *  - La série d'un jour de vote est le nombre de jours UTC de suite, jusqu'à ce jour, où le joueur a voté. À `serie.jours` jours de suite
 *    (puis à chaque multiple), le PREMIER vote du jour du joueur donne le bonus ; les autres votes du même jour n'en donnent pas.
 *  - Vote party : le vote dont le rang dans la journée (tous les votes reçus ce jour UTC, pseudos connus ou non) est exactement
 *    `voteParty.votesParJour` la déclenche : chaque joueur présent ([present], d'après les signaux de vie) reçoit
 *    `voteParty.lingotsParJoueur` (motif `vote-party`, référence : le jour) et un événement `ANNONCE` pour tout le serveur
 *    (`xuid` nul, `payload.action` = `voteParty`) est écrit. Un seul vote a ce rang : la fête n'a lieu qu'une fois par jour. Les votes
 *    suivants comptent mais ne la redonnent pas. Un vote rattrapé au `join` ne déclenche jamais la fête : elle a lieu à la réception.
 *
 * Deux réceptions ne se croisent pas ([lock]) : sans cela, deux votes en même temps pourraient lire le même rang, et la fête
 * partirait deux fois ou jamais. Le verrou vaut pour un service ; il n'y en a qu'un par processus.
 */
class VoteService(
    private val definitions: Definitions,
    private val store: VoteStore,
    private val clock: Clock,
    /** Les joueurs présents en ce moment (sessions ouvertes) : le signal de vie des serveurs Bedrock. */
    private val present: () -> Set<Xuid>,
) : PendingVotes {
    private val rules get() = definitions.vote
    private val lock = Mutex()

    /** Reçoit un vote : une transaction, rejouée sur un blocage de la base. */
    suspend fun receive(database: Database, vote: IncomingVote): VoteReceipt =
        lock.withLock { withRetries { database.transaction { receiveIn(it, vote) } } }

    /** [receive] dans la transaction de [connection]. */
    fun receiveIn(connection: Connection, vote: IncomingVote): VoteReceipt {
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        val day = dayOf(now)
        val xuid = store.playerByGamertag(connection, vote.gamertag)
        val site = rules.siteFor(vote.site)
        val id = store.insertVote(connection, vote.gamertag, site, now, xuid)
        val grant = if (xuid != null) grant(connection, id, xuid, day, site, now) else null
        val rank = store.countOn(connection, day)
        val party = if (VoteRules.startsParty(rank, rules)) startParty(connection, day, rank) else null
        return VoteReceipt(id, xuid, grant, rank, party)
    }

    /** `join` : verse les votes en attente du joueur [xuid] de pseudo [gamertag], du plus ancien au plus récent. */
    override fun relieve(connection: Connection, xuid: Xuid, gamertag: String) {
        val waiting = store.pendingFor(connection, xuid, gamertag, MAX_CATCH_UP)
        if (waiting.isEmpty()) return
        // Un pseudo porté par un joueur vu plus récemment n'est pas le sien : ses votes attendent celui-là.
        if (store.playerByGamertag(connection, gamertag) != xuid) return
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        for (row in waiting) grant(connection, row.id, xuid, dayOf(row.votedAt), row.site, now)
    }

    /** `todayVotes` : les votes du jour, l'objectif, et la part de [viewer] (zéro sans joueur). Rien n'est écrit. */
    fun today(connection: Connection, viewer: Xuid?): VotesToday {
        val day = dayOf(clock.instant())
        val votes = store.countOn(connection, day)
        val mine = if (viewer == null) {
            PlayerVotes(votes = 0, streak = 0)
        } else {
            PlayerVotes(
                votes = store.countOfPlayerOn(connection, viewer, day),
                streak = VoteRules.currentStreak(store.votedDays(connection, viewer, day), day),
            )
        }
        return VotesToday(
            day = day.toString(),
            votes = votes,
            goal = rules.party.votesPerDay,
            partyReached = VoteRules.partyReached(votes, rules),
            rewardLingots = rules.reward.lingotsPerVote,
            partyRewardLingots = rules.party.lingotsPerPlayer,
            streakGoalDays = rules.streak.days,
            streakBonusLingots = rules.streak.bonusLingots,
            mine = mine,
        )
    }

    /**
     * Verse le vote [id] au joueur [xuid] : la ligne est marquée d'abord (`UPDATE ... WHERE rewarded_at IS NULL`, qui doit toucher une
     * ligne), puis les Lingots sont crédités et l'événement écrit. Un autre passage qui l'a déjà versé : rien n'est écrit, `null`.
     * [day] est le jour du vote, pas celui d'aujourd'hui : un vote rattrapé compte dans la série de son jour.
     */
    private fun grant(connection: Connection, id: Long, xuid: Xuid, day: LocalDate, site: String, now: Instant): VoteGrant? {
        if (!store.markRewarded(connection, id, xuid, now)) return null
        val streak = VoteRules.streakEndingOn(store.votedDays(connection, xuid, day), day)
        val bonusDue = store.firstVoteIdOn(connection, xuid, day) == id && VoteRules.streakBonusDue(streak, rules)
        val lingots = VoteRules.lingotsFor(bonusDue, rules)
        val bonus = if (bonusDue) rules.streak.bonusLingots else 0L
        store.creditLingots(connection, xuid, lingots, REASON_VOTE, id.toString())
        store.publish(
            connection,
            GameEventKind.ANNONCE,
            xuid,
            buildJsonObject {
                put("action", "vote")
                put("site", site)
                put("rewardLingots", lingots)
                put("streak", streak)
                put("streakBonusLingots", bonus)
            },
        )
        return VoteGrant(lingots, streak, bonus)
    }

    /** La Vote party : les Lingots à chaque joueur présent que `players` connaît (du plus petit XUID au plus grand), puis l'annonce pour tout le serveur. */
    private fun startParty(connection: Connection, day: LocalDate, votes: Int): PartyOutcome {
        val amount = rules.party.lingotsPerPlayer
        val players = store.knownPlayers(connection, present())
        for (player in players) store.creditLingots(connection, player, amount, REASON_PARTY, day.toString())
        store.publish(
            connection,
            GameEventKind.ANNONCE,
            null,
            buildJsonObject {
                put("action", "voteParty")
                put("day", day.toString())
                put("votes", votes)
                put("rewardLingots", amount)
                put("players", players.size)
            },
        )
        return PartyOutcome(day, votes, players, amount)
    }

    /** Une transaction rejouée sur un blocage détecté par la base (elle est annulée en entier) ; après [MAX_ATTEMPTS] essais l'erreur remonte. */
    private suspend fun <T> withRetries(action: suspend () -> T): T {
        repeat(MAX_ATTEMPTS - 1) { attempt ->
            try {
                return action()
            } catch (e: Exception) {
                if (!e.isTransientConflict()) throw e
            }
            delay(Random.nextLong(5L, 20L) * (attempt + 1))
        }
        return action()
    }

    private fun dayOf(instant: Instant): LocalDate = instant.atOffset(ZoneOffset.UTC).toLocalDate()

    companion object {
        /** Le motif du journal des monnaies (`currency_ledger.reason`) pour un vote : la référence est l'id de la ligne de `server_votes`. */
        const val REASON_VOTE = "vote"

        /** Le motif de la Vote party : la référence est le jour (`2026-11-05`). */
        const val REASON_PARTY = "vote-party"

        /** Les votes rattrapés par `join` au plus : de quoi vider un retard sans tenir la transaction du `join` longtemps. Un réglage technique. */
        const val MAX_CATCH_UP = 100

        /** Essais d'une réception qui tombe sur un blocage de la base. */
        private const val MAX_ATTEMPTS = 3
    }
}
