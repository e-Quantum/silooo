package com.silooo.core.mission

import com.silooo.core.api.Mission
import com.silooo.core.api.MissionState
import com.silooo.core.db.Xuid
import com.silooo.core.definitions.Definitions
import com.silooo.core.definitions.MissionDefinition
import com.silooo.core.definitions.MissionEvent
import com.silooo.core.events.GameEventKind
import com.silooo.core.pass.PassService
import java.sql.Connection
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Les missions du joueur : ce que `definitions/missions.json` demande, et où le joueur en est (`player_missions`).
 *
 * Les missions forment une chaîne, dans l'ordre du catalogue : la mission en cours est la première que le joueur n'a
 * pas terminée, et c'est la seule qui avance. Le service compte les actions lui-même ([record], appelé depuis la
 * transaction de l'action) : l'add-on ne dit jamais « mission faite ». Une mission qui atteint sa cible se termine
 * dans la même transaction : les Lingots de sa récompense sont versés (`player_balances` et `currency_ledger`) et un
 * événement est publié. Il n'y a pas de route pour « réclamer » : une mission terminée ne se rejoue jamais.
 *
 * Le nombre de Lingots n'est pas ici : `missions.json` donne le chemin du nombre dans `economie.json`,
 * [Definitions.missionRewards] le résout au chargement.
 */
class MissionService(
    private val definitions: Definitions,
    private val store: MissionStore,
    /** Le pass de saison : une mission terminée rapporte des points. `null` : pas de pass (les tests qui n'en ont pas besoin). */
    private val pass: PassService? = null,
) {
    private val catalogue: List<MissionDefinition> get() = definitions.missions.missions

    /**
     * La mission en cours et les missions terminées de [xuid], ou `null` si le joueur n'existe pas. [MissionState.current]
     * est `null` quand le joueur a tout terminé. Rien n'est écrit.
     */
    fun stateOf(connection: Connection, xuid: Xuid): MissionState? {
        if (!store.playerExists(connection, xuid)) return null
        val rows = store.load(connection, xuid)
        val current = catalogue.firstOrNull { rows[it.id]?.completed != true }
        return MissionState(
            current = current?.let { Mission(it.id, minOf(rows[it.id]?.progress ?: 0, it.target), it.target, rewardOf(it)) },
            completed = catalogue.filter { rows[it.id]?.completed == true }.map { it.id },
        )
    }

    /**
     * Compte une action du joueur [actor]. Seule la mission en cours peut avancer, et seulement si elle attend [event] ;
     * sinon rien n'est écrit. À appeler dans la transaction de l'action elle-même : si l'action échoue ou est rejouée par
     * un conflit, le compte repart avec elle.
     */
    fun record(connection: Connection, actor: Xuid, event: MissionEvent) {
        val rows = store.load(connection, actor)
        val mission = catalogue.firstOrNull { rows[it.id]?.completed != true } ?: return
        if (mission.event != event) return

        // La ligne est relue sous verrou : une action du même joueur validée entre-temps a pu terminer la mission.
        val row = store.lock(connection, actor, mission.id)
        if (row.completed) return

        val progress = minOf(mission.target, row.progress + 1)
        val completed = progress >= mission.target
        store.save(connection, actor, mission.id, progress, completed)
        if (!completed) return

        val reward = rewardOf(mission)
        if (reward > 0) store.creditLingots(connection, actor, reward, REASON, mission.id)
        store.publish(
            connection,
            EVENT_KIND,
            actor,
            buildJsonObject {
                put("action", ACTION)
                put("missionId", mission.id)
                put("rewardLingots", reward)
            },
        )
        // Les points du pass viennent après les Lingots : les verrous se prennent dans le même ordre que partout.
        pass?.onMissionCompleted(connection, actor)
    }

    private fun rewardOf(mission: MissionDefinition): Long = definitions.missionRewards.getValue(mission.id)

    companion object {
        /**
         * Le type de l'événement d'une mission terminée, adressé au joueur. L'OpenAPI n'a pas de type propre aux missions
         * et en ajouter un casserait `ContractTest.codesMatchTheContract` : `ANNONCE` est le type générique, le détail est
         * dans `payload.action`. Un type dédié s'ajoute d'abord à l'OpenAPI.
         */
        val EVENT_KIND = GameEventKind.ANNONCE

        /** Valeur de `payload.action` d'une mission terminée. */
        const val ACTION = "mission"

        /** `currency_ledger.reason` des Lingots d'une mission ; `ref_id` porte l'identifiant de la mission. */
        const val REASON = "mission"
    }
}
