package com.silooo.core.mission

import com.silooo.core.db.Xuid
import com.silooo.core.events.GameEventKind
import java.sql.Connection
import kotlinx.serialization.json.JsonObject

/** Une ligne de `player_missions` : où en est le joueur dans une mission. */
data class MissionRow(val progress: Int, val completed: Boolean)

/**
 * Les accès SQL des missions (`player_missions`), derrière une interface : les tests de route tournent avec un faux
 * en mémoire, le SQL avec la vraie base (Testcontainers). Le versement des Lingots et l'événement passent par ici
 * aussi, pour que le service n'écrive rien d'autre que ce que cette interface dit.
 */
interface MissionStore {
    /** Vrai si le joueur [xuid] existe (`players`). */
    fun playerExists(connection: Connection, xuid: Xuid): Boolean

    /** Les lignes du joueur, par identifiant de mission. Une mission jamais commencée n'a pas de ligne. */
    fun load(connection: Connection, xuid: Xuid): Map<String, MissionRow>

    /**
     * Rend la ligne de [missionId] pour [xuid] en la verrouillant jusqu'à la fin de la transaction ; la crée à zéro
     * si elle n'existe pas. Deux encaissements du même joueur en même temps passent donc l'un après l'autre.
     */
    fun lock(connection: Connection, xuid: Xuid, missionId: String): MissionRow

    /** Écrit l'avancement ; [completed] pose `completed_at` à maintenant, sinon à `NULL`. À appeler après [lock]. */
    fun save(connection: Connection, xuid: Xuid, missionId: String, progress: Int, completed: Boolean)

    /**
     * Verse [amount] Lingots à [xuid] : `player_balances` et une ligne de `currency_ledger`, dans la transaction de
     * [connection] (voir `creditBalance`). Rend le solde après le versement.
     */
    fun creditLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long

    /** Écrit un événement dans `game_events`, dans la transaction de [connection] (voir `publishEvent`). Rend son identifiant. */
    fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long
}
