package com.silooo.core.orders

import com.silooo.core.Dependencies
import com.silooo.core.api.ActionResult
import com.silooo.core.api.ErrorBody
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.OrderDelivered
import com.silooo.core.api.SiloooJson
import com.silooo.core.api.idempotent
import com.silooo.core.api.respondError
import com.silooo.core.api.respondJson
import com.silooo.core.db.Xuid
import com.silooo.core.economy.RequestRejected
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** Corps de `deliverOrder` : `ActionBase` et `quantity`, un nombre entier d'unités, au moins 1. */
@Serializable
data class DeliverOrderRequest(val requestId: String, val actor: String, val quantity: Long)

/**
 * Domaine « commandes » : la commande mondiale du jour.
 *
 *  - `GET /v1/orders/today` (`todayOrder`) : la commande du jour et sa progression. Avec `?xuid=`, `mine` donne la part de
 *    ce joueur. Un `xuid` mal formé : 400 `REQUETE_INVALIDE`. Rien n'est écrit.
 *  - `POST /v1/orders/today/deliveries` (`deliverOrder`) : livre `quantity` unités. Passe par `call.idempotent` : un `requestId`
 *    rejoué rend la même réponse sans livrer deux fois. Un refus du jeu est un 409 `Refused` (`PAS_ASSEZ_DE_DONAR`) ;
 *    une version d'entreprise dépassée, un 409 `CONFLIT_REESSAYER`. Sur une commande déjà terminée, rien n'est pris : 200 avec
 *    la commande finie. Une `quantity` sous 1, un acteur sans entreprise : 400 `REQUETE_INVALIDE`. Le travail est dans [OrderService].
 */
fun Route.orderRoutes(dependencies: Dependencies) {
    val service = OrderService(
        definitions = dependencies.definitions,
        sessions = dependencies.sessionStore,
        orders = dependencies.orderStore,
        figures = dependencies.economyFigures,
        clock = dependencies.ordersClock,
    )

    get("/orders/today") {
        val raw = call.request.queryParameters["xuid"]
        val viewer = raw?.let { Xuid.parse(it) }
        if (raw != null && viewer == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@get
        }
        call.respondJson(dependencies.database.transaction { service.today(it, viewer) })
    }

    post("/orders/today/deliveries") {
        val request = call.receive<DeliverOrderRequest>()
        if (request.quantity < 1) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        call.idempotent(dependencies, request.requestId, request.actor, "deliverOrder") { connection, actor ->
            try {
                val delivered = service.deliver(connection, actor, request.quantity)
                ActionResult.ok(SiloooJson.encodeToJsonElement(OrderDelivered.serializer(), delivered))
            } catch (_: RequestRejected) {
                // Rien n'a été écrit avant : la réponse 400 est gardée et rejouée comme n'importe quelle réponse de l'action.
                BAD_REQUEST
            }
        }
    }
}

private val BAD_REQUEST = ActionResult(
    HttpStatusCode.BadRequest,
    SiloooJson.encodeToJsonElement(ErrorBody.serializer(), ErrorBody(ErrorCodes.BAD_REQUEST)),
)
