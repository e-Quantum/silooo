package com.silooo.core.api

import com.silooo.core.Dependencies
import com.silooo.core.db.StoredResponse
import com.silooo.core.db.Xuid
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.sql.Connection
import java.util.UUID

/** `requestId` du contrat : un UUID. Rend sa forme canonique (minuscules), ou `null` s'il n'en est pas un. */
fun parseRequestId(raw: String): String? =
    if (UUID_SHAPE.matches(raw)) raw.lowercase() else null

private val UUID_SHAPE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** Envoie une réponse gardée (ou fraîche) comme le contrat la veut : statut, et corps JSON s'il y en a un. */
suspend fun ApplicationCall.respondStored(stored: StoredResponse) {
    val status = HttpStatusCode.fromValue(stored.status)
    val body = stored.body
    if (body == null) respond(status) else respondJsonElement(body, status)
}

/**
 * Le helper des routes qui modifient quelque chose : `call.idempotent(dependencies, action, "buyLevels") { connection -> ... }`.
 *
 * Il vérifie la forme de [action] (`requestId` UUID, `actor` XUID : sinon 400 `REQUETE_INVALIDE`, sans rien écrire),
 * puis passe par [Idempotency.run] : première fois, l'action s'exécute dans une transaction et sa réponse est
 * gardée ; rejeu, la réponse gardée repart sans refaire l'action ; même requête en cours, 409 `CONFLIT_REESSAYER`.
 *
 * Dans [block], on écrit avec la `connection` reçue et on rend un [ActionResult]. Pour refuser :
 * `throw Refusal(...)` (ou [refuse]) ; pour une écriture sur une entreprise : `updateCompany(...)` (verrou
 * optimiste) ; pour une monnaie personnelle : `creditBalance(...)` (journal dans la même transaction).
 * L'XUID de l'acteur, déjà validé, est le second argument.
 *
 * Un `DELETE` n'a pas de corps : il passe `requestId` et `actor` en paramètres (`RequestId`, `ActorXuid`),
 * et appelle la variante ci-dessous avec les deux valeurs lues.
 */
suspend fun ApplicationCall.idempotent(
    dependencies: Dependencies,
    action: ActionBase,
    endpoint: String,
    block: (connection: Connection, actor: Xuid) -> ActionResult,
) = idempotent(dependencies, action.requestId, action.actor, endpoint, block)

suspend fun ApplicationCall.idempotent(
    dependencies: Dependencies,
    requestId: String,
    actor: String,
    endpoint: String,
    block: (connection: Connection, actor: Xuid) -> ActionResult,
) {
    val id = parseRequestId(requestId)
    val actorXuid = Xuid.parse(actor)
    if (id == null || actorXuid == null) {
        respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
        return
    }
    respondStored(dependencies.idempotency.run(id, endpoint) { connection -> block(connection, actorXuid) })
}
