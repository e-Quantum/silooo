package com.silooo.core.findings

import com.silooo.core.db.Xuid
import com.silooo.core.events.GameEventKind
import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/** Une ligne de `player_findings` : la ressource trouvée et le moment où le joueur l'a trouvée. */
data class FindingRow(val resourceId: String, val foundAt: Instant)

/**
 * Les accès SQL du Carnet de trouvailles (`player_findings`), derrière une interface : les tests de route tournent avec
 * un faux en mémoire, le SQL avec la vraie base (Testcontainers). Le versement des Lingots et l'événement passent par
 * ici aussi, pour que le service n'écrive rien d'autre que ce que cette interface dit.
 */
interface FindingStore {
    /** Vrai si le joueur [xuid] existe (`players`). */
    fun playerExists(connection: Connection, xuid: Xuid): Boolean

    /** Les trouvailles du joueur, de la plus ancienne à la plus récente (à égalité, par identifiant de ressource). */
    fun load(connection: Connection, xuid: Xuid): List<FindingRow>

    /**
     * Inscrit [resourceId] au Carnet de [xuid]. Vrai si la trouvaille est nouvelle, faux si le joueur l'avait déjà (ou si une
     * transaction concurrente vient de l'inscrire) : la clé primaire décide, et seul l'appel qui l'écrit paie la prime.
     */
    fun add(connection: Connection, xuid: Xuid, resourceId: String): Boolean

    /**
     * Verse [amount] Lingots à [xuid] : `player_balances` et une ligne de `currency_ledger`, dans la transaction de
     * [connection] (voir `creditBalance`). Rend le solde après le versement.
     */
    fun creditLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long

    /** Écrit un événement dans `game_events`, dans la transaction de [connection] (voir `publishEvent`). Rend son identifiant. */
    fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long
}
