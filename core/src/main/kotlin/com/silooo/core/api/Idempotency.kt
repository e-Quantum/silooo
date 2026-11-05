package com.silooo.core.api

import com.silooo.core.db.ApiRequestStore
import com.silooo.core.db.Claim
import com.silooo.core.db.Database
import com.silooo.core.db.JdbcApiRequestStore
import com.silooo.core.db.StoredResponse
import com.silooo.core.db.isTransientConflict
import io.ktor.http.HttpStatusCode
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/** Ce qu'une action rend : le statut HTTP et le corps JSON (`null` : réponse sans corps, comme un 204). */
class ActionResult(val status: HttpStatusCode, val body: JsonElement?) {
    companion object {
        fun ok(body: JsonElement) = ActionResult(HttpStatusCode.OK, body)
        fun noContent() = ActionResult(HttpStatusCode.NoContent, null)
    }
}

/**
 * L'idempotence de toute route qui modifie quelque chose (règle 3 du socle). Chaque appel porte un
 * `requestId` ; la réponse est gardée dans `api_requests` et un rejeu la renvoie sans refaire l'action.
 *
 * Le déroulé, pour un `requestId` donné :
 *  1. **Marqueur.** Dans une transaction courte validée tout de suite, la ligne est insérée avec l'état
 *     « en cours ». Les autres appels le voient aussitôt.
 *  2. **Action.** Dans une seconde transaction : l'action, puis la réponse qui remplace le marqueur. La
 *     réponse gardée et les effets de l'action partent ensemble ou pas du tout.
 *  3. **Rejeu.** Le même `requestId` revient : la réponse gardée repart telle quelle (même statut, même corps).
 *     Pendant que le premier appel tourne encore, le second reçoit 409 `CONFLIT_REESSAYER`.
 *
 * Ce qui est gardé et ce qui ne l'est pas :
 *  - Un succès est gardé. Un refus du jeu ([Refusal], par exemple `PAS_ASSEZ_DE_DONAR`) est gardé aussi :
 *    l'action est annulée, mais le même `requestId` rejoué reçoit le même refus.
 *  - `CONFLIT_REESSAYER` (version dépassée, blocage de la base) n'est PAS gardé : le marqueur est retiré,
 *    pour que le nouvel essai du même `requestId` puisse s'exécuter avec l'état frais.
 *  - Une panne (exception) retire aussi le marqueur et remonte : 500, et le nouvel essai est permis.
 *  - Un marqueur « en cours » plus vieux que [staleAfter] est celui d'un processus tombé en route : il est repris.
 *
 * [endpoint] est l'`operationId` de la route (64 caractères au plus). Un `requestId` déjà utilisé sur une
 * autre opération est une erreur de l'appelant : 400 `REQUETE_INVALIDE`.
 */
class Idempotency(
    private val database: Database,
    private val store: ApiRequestStore = JdbcApiRequestStore(),
    private val staleAfter: Duration = DEFAULT_STALE_AFTER,
) {
    suspend fun run(requestId: String, endpoint: String, action: (Connection) -> ActionResult): StoredResponse {
        require(endpoint.length <= ENDPOINT_MAX) { "endpoint : $ENDPOINT_MAX caractères au plus" }
        val owner = UUID.randomUUID().toString()

        val claim = try {
            database.transaction { store.claim(it, requestId, endpoint, owner, staleAfter) }
        } catch (e: SQLException) {
            // Deux appels se sont disputé la reprise d'un marqueur abandonné : l'autre gagne.
            if (!e.isTransientConflict()) throw e
            return CONFLICT
        }
        when (claim) {
            is Claim.Replay -> return claim.response
            Claim.InProgress -> return CONFLICT
            Claim.OtherEndpoint -> return REQUEST_REUSED
            Claim.Acquired -> Unit
        }

        try {
            return database.transaction { connection ->
                val result = action(connection)
                val stored = StoredResponse(result.status.value, result.body)
                // Le marqueur a été repris par un autre appel : on annule notre action, c'est lui qui répond.
                if (!store.complete(connection, requestId, owner, stored)) throw MarkerLost()
                stored
            }
        } catch (refusal: Refusal) {
            if (refusal.isTransient) {
                release(requestId, owner)
            } else {
                // L'action est annulée ; son refus, lui, se garde et se rejoue.
                keep(requestId, owner, refusal.toStored())
            }
            return refusal.toStored()
        } catch (e: MarkerLost) {
            return CONFLICT
        } catch (e: Throwable) {
            if (e.isTransientConflict()) {
                // Blocage mortel, ou ligne changée depuis la lecture (innodb_snapshot_isolation) : transitoire, comme un conflit de version.
                release(requestId, owner)
                return CONFLICT
            }
            release(requestId, owner, suppressedInto = e)
            throw e
        }
    }

    /** Efface les réponses plus vieilles que [keep], par lots pour ne pas bloquer la table. Rend leur nombre. */
    suspend fun purge(keep: Duration): Int {
        var total = 0
        while (true) {
            val deleted = database.transaction { store.purge(it, keep, PURGE_BATCH) }
            total += deleted
            if (deleted < PURGE_BATCH) return total
        }
    }

    private suspend fun keep(requestId: String, owner: String, response: StoredResponse) {
        withContext(NonCancellable) {
            database.transaction { store.complete(it, requestId, owner, response) }
        }
    }

    /** Retire le marqueur. Au mieux : si la base est tombée, le marqueur sera repris après [staleAfter]. */
    private suspend fun release(requestId: String, owner: String, suppressedInto: Throwable? = null) {
        withContext(NonCancellable) {
            try {
                database.transaction { store.release(it, requestId, owner) }
            } catch (e: Exception) {
                suppressedInto?.addSuppressed(e)
            }
        }
    }

    /** Le marqueur n'est plus à nous : levée dans la transaction de l'action pour l'annuler. */
    private class MarkerLost : RuntimeException(null, null, false, false)

    private fun Refusal.toStored() = StoredResponse(status.value, SiloooJson.encodeToJsonElement(RefusedBody.serializer(), body()))

    companion object {
        /** Colonne `api_requests.endpoint` : VARCHAR(64). */
        const val ENDPOINT_MAX = 64

        /**
         * Au bout de combien un marqueur « en cours » est tenu pour abandonné. Une action dure quelques
         * millisecondes ; une minute laisse une marge très large avant de reprendre la main. Réglage technique.
         */
        val DEFAULT_STALE_AFTER: Duration = Duration.ofMinutes(1)

        /** Lignes effacées par transaction de purge. */
        const val PURGE_BATCH = 1000

        private val CONFLICT = Refusal(HttpStatusCode.Conflict, RefusalCodes.CONFLICT_RETRY).let {
            StoredResponse(it.status.value, SiloooJson.encodeToJsonElement(RefusedBody.serializer(), it.body()))
        }
        private val REQUEST_REUSED = StoredResponse(
            HttpStatusCode.BadRequest.value,
            SiloooJson.encodeToJsonElement(ErrorBody.serializer(), ErrorBody(ErrorCodes.BAD_REQUEST)),
        )
    }
}
