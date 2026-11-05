package com.silooo.core.orders

import com.silooo.core.db.Xuid
import com.silooo.core.events.GameEventKind
import java.sql.Connection
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject

/** Une ligne de `world_orders` : la commande d'un jour, ce qui est déjà livré, et si elle est terminée (`completed_at`). */
data class OrderRow(
    val id: Long,
    val day: LocalDate,
    val resourceId: String,
    val target: Double,
    val delivered: Double,
    val rewardLingots: Long,
    val completed: Boolean,
)

/**
 * Les accès SQL de la commande mondiale (`world_orders`, `world_order_contributions`), derrière une interface : les
 * tests de route tournent avec un faux en mémoire, le SQL avec la vraie base (Testcontainers). Le versement des Lingots
 * et les événements passent par ici aussi, pour que le service n'écrive rien d'autre que ce que cette interface dit.
 */
interface OrderStore {
    /** La commande de [day], ou `null` tant que personne n'a livré ce jour-là. Rien n'est écrit, rien n'est verrouillé. */
    fun find(connection: Connection, day: LocalDate): OrderRow?

    /** La part déjà livrée par [xuid] à la commande [orderId] (0 s'il n'a rien livré). */
    fun contribution(connection: Connection, orderId: Long, xuid: Xuid): Double

    /**
     * Rend la commande de [day] en la verrouillant jusqu'à la fin de la transaction ; l'écrit d'abord si personne n'a
     * encore livré ce jour-là. Les trois valeurs sont celles du plan du jour ([OrderPlan]) : elles ne comptent que
     * pour la première écriture, une commande déjà écrite garde les siennes. Deux livraisons en même temps passent
     * donc l'une après l'autre.
     */
    fun lockOrCreate(connection: Connection, day: LocalDate, resourceId: String, target: Double, rewardLingots: Long): OrderRow

    /** Ajoute [quantity] à la part de [xuid] dans la commande [orderId] ; crée sa ligne à la première livraison. */
    fun addContribution(connection: Connection, orderId: Long, xuid: Xuid, quantity: Double)

    /** Écrit ce qui est livré ; [completed] pose `completed_at` à maintenant. À appeler après [lockOrCreate]. */
    fun saveProgress(connection: Connection, orderId: Long, delivered: Double, completed: Boolean)

    /** Les joueurs qui ont livré à la commande [orderId] et dont la prime n'est pas encore versée, par XUID croissant. */
    fun unrewardedContributors(connection: Connection, orderId: Long): List<Xuid>

    /** Note que la prime de [xuid] pour la commande [orderId] est versée (`rewarded_at`). */
    fun markRewarded(connection: Connection, orderId: Long, xuid: Xuid)

    /**
     * Verse [amount] Lingots à [xuid] : `player_balances` et une ligne de `currency_ledger`, dans la transaction de
     * [connection] (voir `creditBalance`). Rend le solde après le versement.
     */
    fun creditLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long

    /** Écrit un événement dans `game_events`, dans la transaction de [connection] (voir `publishEvent`). Rend son identifiant. */
    fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long
}
