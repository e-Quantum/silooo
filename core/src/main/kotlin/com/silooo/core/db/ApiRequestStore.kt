package com.silooo.core.db

import com.silooo.core.api.SiloooJson
import java.sql.Connection
import java.time.Duration
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Une réponse gardée dans `api_requests` : le statut HTTP et le corps JSON (`null` : pas de corps). */
data class StoredResponse(val status: Int, val body: JsonElement?)

/** Ce que [ApiRequestStore.claim] a trouvé pour un `requestId`. */
sealed interface Claim {
    /** Personne ne le traitait (ou le marqueur d'un traitement abandonné a été repris) : à nous de jouer. */
    data object Acquired : Claim

    /** Déjà traité : voici la réponse gardée. L'action ne se refait pas. */
    data class Replay(val response: StoredResponse) : Claim

    /** Un autre appel le traite en ce moment. */
    data object InProgress : Claim

    /** Ce `requestId` a servi à une autre opération : une erreur de l'appelant, pas un rejeu. */
    data object OtherEndpoint : Claim
}

/**
 * La table `api_requests` (clé `request_id`) derrière une interface : les tests de logique de
 * l'idempotence tournent avec un faux en mémoire, le SQL avec la vraie base (Testcontainers).
 *
 * Toutes les méthodes travaillent dans la transaction de [Connection] reçue. [Idempotency] s'en sert
 * en trois temps : [claim] (transaction courte, validée tout de suite pour que les autres appels voient
 * le marqueur), puis [complete] dans la transaction de l'action elle-même (la réponse gardée et les
 * effets de l'action partent ensemble), ou [release] si l'action n'a rien produit de rejouable.
 */
interface ApiRequestStore {
    /**
     * Insère d'abord la ligne : `endpoint` et un marqueur « en cours » qui porte [owner]. Si la ligne
     * existe déjà, regarde ce qu'elle contient. Un marqueur « en cours » plus vieux que [staleAfter]
     * est celui d'un traitement qui n'a jamais fini (le processus est tombé) : il est repris.
     */
    fun claim(connection: Connection, requestId: String, endpoint: String, owner: String, staleAfter: Duration): Claim

    /**
     * Remplace le marqueur par la réponse. Rend faux si le marqueur n'est plus à [owner] (repris par un
     * autre appel parce que celui-ci a traîné au-delà de `staleAfter`) : l'appelant annule alors son action.
     */
    fun complete(connection: Connection, requestId: String, owner: String, response: StoredResponse): Boolean

    /** Retire le marqueur de [owner] : la requête n'a rien produit de rejouable, un nouvel essai pourra l'exécuter. */
    fun release(connection: Connection, requestId: String, owner: String)

    /** Efface au plus [batch] lignes plus vieilles que [keep]. Rend le nombre effacé. */
    fun purge(connection: Connection, keep: Duration, batch: Int): Int
}

/**
 * Forme de la colonne `response` (JSON NOT NULL) : un petit objet qui dit où en est la requête.
 *
 *   en cours : {"state":"EN_COURS","owner":"<jeton>"}
 *   fait     : {"state":"FAIT","status":200,"body":{...}}   (pas de `body` : réponse sans corps)
 *
 * V1 n'a pas de colonne de statut : l'enveloppe se range dans la colonne JSON, sans migration.
 */
internal object ResponseEnvelope {
    const val IN_PROGRESS = "EN_COURS"
    const val DONE = "FAIT"

    fun inProgress(owner: String): String = buildJsonObject {
        put("state", IN_PROGRESS)
        put("owner", owner)
    }.toString()

    fun done(response: StoredResponse): String = buildJsonObject {
        put("state", DONE)
        put("status", response.status)
        response.body?.let { put("body", it) }
    }.toString()

    /** [StoredResponse] si l'enveloppe est « fait », `null` si elle est « en cours ». */
    fun parse(text: String): StoredResponse? {
        val envelope: JsonObject = SiloooJson.parseToJsonElement(text).jsonObject
        if (envelope["state"]?.jsonPrimitive?.contentOrNull != DONE) return null
        val status = checkNotNull(envelope["status"]?.jsonPrimitive?.intOrNull) { "api_requests : statut manquant" }
        val body = envelope["body"]?.takeUnless { it is JsonPrimitive && it.contentOrNull == null }
        return StoredResponse(status, body)
    }
}

/** La vraie table. Le SQL est testé sur MariaDB 12.3.3 par Testcontainers (`ApiRequestSqlTest`). */
class JdbcApiRequestStore : ApiRequestStore {

    override fun claim(connection: Connection, requestId: String, endpoint: String, owner: String, staleAfter: Duration): Claim {
        try {
            connection.prepareStatement("INSERT INTO api_requests (request_id, endpoint, response) VALUES (?, ?, ?)").use {
                it.setString(1, requestId)
                it.setString(2, endpoint)
                it.setString(3, ResponseEnvelope.inProgress(owner))
                it.executeUpdate()
            }
            return Claim.Acquired
        } catch (e: java.sql.SQLException) {
            if (!e.isDuplicateKey()) throw e
        }

        // La ligne existe : une autre transaction l'a validée (l'INSERT a attendu sa fin avant d'échouer).
        val (storedEndpoint, text) = connection
            .prepareStatement("SELECT endpoint, response FROM api_requests WHERE request_id = ?")
            .use {
                it.setString(1, requestId)
                it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
            }
            // Purgée entre l'INSERT raté et la lecture : très improbable, un nouvel essai tranchera.
            ?: return Claim.InProgress
        if (storedEndpoint != endpoint) return Claim.OtherEndpoint
        ResponseEnvelope.parse(text)?.let { return Claim.Replay(it) }

        // « En cours » : à reprendre seulement si le marqueur est abandonné. Une seule instruction, donc un seul gagnant.
        val taken = connection.prepareStatement(
            "UPDATE api_requests SET response = ?, created_at = UTC_TIMESTAMP(3) " +
                "WHERE request_id = ? AND JSON_VALUE(response, '\$.state') = ? " +
                "AND created_at < UTC_TIMESTAMP(3) - INTERVAL ? SECOND",
        ).use {
            it.setString(1, ResponseEnvelope.inProgress(owner))
            it.setString(2, requestId)
            it.setString(3, ResponseEnvelope.IN_PROGRESS)
            it.setLong(4, staleAfter.seconds)
            it.executeUpdate()
        }
        return if (taken == 1) Claim.Acquired else Claim.InProgress
    }

    override fun complete(connection: Connection, requestId: String, owner: String, response: StoredResponse): Boolean =
        connection.prepareStatement(
            "UPDATE api_requests SET response = ? " +
                "WHERE request_id = ? AND JSON_VALUE(response, '\$.state') = ? AND JSON_VALUE(response, '\$.owner') = ?",
        ).use {
            it.setString(1, ResponseEnvelope.done(response))
            it.setString(2, requestId)
            it.setString(3, ResponseEnvelope.IN_PROGRESS)
            it.setString(4, owner)
            it.executeUpdate() == 1
        }

    override fun release(connection: Connection, requestId: String, owner: String) {
        connection.prepareStatement(
            "DELETE FROM api_requests WHERE request_id = ? " +
                "AND JSON_VALUE(response, '\$.state') = ? AND JSON_VALUE(response, '\$.owner') = ?",
        ).use {
            it.setString(1, requestId)
            it.setString(2, ResponseEnvelope.IN_PROGRESS)
            it.setString(3, owner)
            it.executeUpdate()
        }
    }

    override fun purge(connection: Connection, keep: Duration, batch: Int): Int =
        connection.prepareStatement("DELETE FROM api_requests WHERE created_at < UTC_TIMESTAMP(3) - INTERVAL ? SECOND LIMIT ?").use {
            it.setLong(1, keep.seconds)
            it.setInt(2, batch)
            it.executeUpdate()
        }
}
