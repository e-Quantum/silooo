package com.silooo.core.economy

import com.silooo.core.api.FactoryState
import com.silooo.core.db.Xuid
import com.silooo.core.events.GameEventKind
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
}
