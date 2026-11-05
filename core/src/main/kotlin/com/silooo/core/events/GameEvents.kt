package com.silooo.core.events

import com.silooo.core.api.GameEvent
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.Xuid
import com.silooo.core.db.setXuid
import java.sql.Connection
import java.sql.Statement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Les types d'événements du schéma `GameEvent` de l'OpenAPI. Un type nouveau s'ajoute d'abord au contrat,
 * puis ici ; `ContractTest` compare les deux listes.
 */
enum class GameEventKind {
    GEMMES_RECUES,
    COMMANDE_TERMINEE,
    PRESTIGE_ENTREPRISE,
    INVITATION,
    VENTE_MARCHE,
    INCIDENT,
    BOOST_COLLECTIF,
    ANNONCE,
}

/**
 * Écrit un événement dans `game_events`, dans la transaction de [connection] : il n'existe que si ce qui
 * le cause est validé (une Gemme créditée sans événement, ou un événement sans Gemme, n'arrive pas). [xuid]
 * `null` : pour tout le serveur. Rend l'identifiant, celui que l'add-on retrouvera dans `GET /v1/events`.
 *
 * À appeler depuis les routes qui ont quelque chose à dire au jeu (livraison de Gemmes, commande
 * terminée, prestige, invitation…), jamais depuis l'add-on : le service décide, l'add-on affiche.
 */
fun publishEvent(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject = JsonObject(emptyMap())): Long =
    connection.prepareStatement("INSERT INTO game_events (xuid, kind, payload) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS).use {
        it.setXuid(1, xuid)
        it.setString(2, kind.name)
        it.setString(3, payload.toString())
        it.executeUpdate()
        it.generatedKeys.use { keys ->
            check(keys.next()) { "game_events : aucun identifiant rendu" }
            keys.getLong(1)
        }
    }

/** Lecture de `game_events` derrière une interface, pour que les tests de la route tournent sans base. */
interface EventStore {
    /** Les événements d'identifiant STRICTEMENT supérieur à [after], du plus ancien au plus récent, [limit] au plus. */
    fun readAfter(connection: Connection, after: Long, limit: Int): List<GameEvent>
}

class JdbcEventStore : EventStore {
    override fun readAfter(connection: Connection, after: Long, limit: Int): List<GameEvent> =
        connection.prepareStatement("SELECT id, xuid, kind, payload FROM game_events WHERE id > ? ORDER BY id ASC LIMIT ?").use {
            it.setLong(1, after)
            it.setInt(2, limit)
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            GameEvent(
                                id = rows.getLong("id"),
                                xuid = rows.getString("xuid"),
                                kind = rows.getString("kind"),
                                payload = SiloooJson.parseToJsonElement(rows.getString("payload")).jsonObject,
                            ),
                        )
                    }
                }
            }
        }
}
