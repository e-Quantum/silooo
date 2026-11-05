package com.silooo.core.economy

import com.silooo.core.api.FactoryState
import com.silooo.core.db.Xuid
import com.silooo.core.events.GameEventKind
import java.math.BigDecimal
import java.sql.Connection
import kotlinx.serialization.json.JsonObject

/**
 * Les accès SQL propres aux routes d'économie (`getCompany`, `collect`, `buyLevels`, `restoreStep`), derrière une
 * interface : les tests de route tournent avec un faux en mémoire, le SQL avec la vraie base (Testcontainers).
 *
 * La lecture de l'entreprise, de ses usines, de ses zones et des soldes passe par `SessionStore`, que `join` utilise
 * déjà ; l'écriture de l'entreprise passe par `updateCompany` (verrou optimiste). Ce qui reste ici : l'existence d'une
 * entreprise, l'écriture d'une usine et la publication d'un événement.
 */
interface CompanyStore {
    /** Vrai si l'entreprise [companyId] existe. */
    fun companyExists(connection: Connection, companyId: Long): Boolean

    /**
     * Écrit l'état, l'étape de remise en état et le niveau de l'usine [factoryId] de l'entreprise [companyId].
     * À appeler APRÈS `updateCompany` : c'est la version de l'entreprise qui protège cette écriture d'une action
     * concurrente. Une ligne absente est une erreur de programmation, pas un cas de jeu (le service l'a lue juste avant).
     */
    fun saveFactory(connection: Connection, companyId: Long, factoryId: String, state: FactoryState, restoreStep: Int, level: Int)

    /** Écrit un événement dans `game_events`, dans la transaction de [connection] (voir `publishEvent`). Rend son identifiant. */
    fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long

    /** Les membres de l'entreprise et leur part de Lingots au prestige (`company_members`), par XUID croissant. */
    fun loadShares(connection: Connection, companyId: Long): List<MemberShare>

    /**
     * Verse [amount] Lingots à [xuid] : `player_balances` et une ligne de `currency_ledger`, dans la transaction de
     * [connection] (voir `creditBalance`). Rend le solde après le versement.
     */
    fun creditLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long

    /**
     * Remet les usines de l'entreprise au départ d'une course : les usines de [starting] (identifiant et niveau de
     * départ) `ACTIVE` à ce niveau, toutes les autres `EN_RUINE` au niveau 0, étape 0 ; les incidents ouverts sont
     * clos. Les lignes sont gardées : une usine déjà découverte n'est pas à redécouvrir, seulement à remettre en état.
     * À appeler APRÈS `updateCompany`, comme [saveFactory].
     */
    fun restartFactories(connection: Connection, companyId: Long, starting: Map<String, Int>)

    /** Ajoute la ligne de `prestige_history` d'un prestige réussi. */
    fun recordPrestige(connection: Connection, record: PrestigeRecord)
}

/** Une ligne de `prestige_history`. */
data class PrestigeRecord(
    val companyId: Long,
    /** La course qui se termine. */
    val courseNumber: Int,
    val eraBefore: Int,
    val starsAfter: Int,
    val threshold: Double,
    val donarEarned: Double,
    /** Les Lingots avant partage entre les associés. */
    val lingotsTotal: Long,
    /** La durée de la course, en heures, à deux décimales. */
    val durationHours: BigDecimal,
    /** Le PDG qui a décidé. */
    val decidedBy: Xuid,
)
