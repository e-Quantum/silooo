package com.silooo.core.shop

import com.silooo.core.Dependencies
import com.silooo.core.api.ActionResult
import com.silooo.core.api.Balances
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.SiloooJson
import com.silooo.core.api.idempotent
import com.silooo.core.api.parseRequestId
import com.silooo.core.api.respondError
import com.silooo.core.api.respondJson
import com.silooo.core.db.Xuid
import com.silooo.core.economy.RequestRejected
import com.silooo.core.economy.rejectable
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** Corps de `buyItem` : `ActionBase`, l'article, et le destinataire d'un cadeau (absent : l'acheteur lui-même). */
@Serializable
data class BuyItemRequest(val requestId: String, val actor: String, val itemId: String, val forXuid: String? = null)

/** `itemId.maxLength` de l'OpenAPI : la longueur de `shop_purchases.item_id` (VARCHAR(48)). */
internal const val ITEM_ID_MAX_LENGTH = 48

/**
 * Domaine « boutique » : ce qui s'achète en Gemmes en jeu.
 *
 *  - `GET /v1/shop/catalog?actor=` (`catalog`) : les grades dans l'ordre, le Pilier et le Perso, les cosmétiques en vente, puis le boost
 *    collectif, avec ce que l'acteur en possède. Un `actor` absent ou mal formé, ou un joueur que le service ne connaît pas : 400
 *    `REQUETE_INVALIDE`. Rien n'est écrit.
 *  - `POST /v1/shop/purchases` (`buyItem`) : achète un article, pour l'acteur ou en cadeau (`forXuid`). Passe par `call.idempotent` :
 *    un `requestId` rejoué rend la même réponse sans payer deux fois. Les refus du jeu sont des 409 `Refused` (`DEJA_POSSEDE`,
 *    `GRADE_PRECEDENT_REQUIS`, `PAS_ASSEZ_DE_GEMMES`) ; un `itemId` inconnu ou pas encore vendu (le titre Pionnier ne se vend pas), le
 *    boost collectif offert, un acteur ou un destinataire que le service ne connaît pas : 400. La réponse est le solde de l'acheteur.
 *    Le travail est dans [ShopService].
 */
fun Route.shopRoutes(dependencies: Dependencies) {
    val service = ShopService(
        rules = ShopRules(dependencies.definitions),
        store = dependencies.shopStore,
        sessions = dependencies.sessionStore,
        clock = dependencies.clock,
    )

    get("/shop/catalog") {
        val actor = call.request.queryParameters["actor"]?.let { Xuid.parse(it) }
        if (actor == null) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@get
        }
        val catalog = try {
            dependencies.database.transaction { service.catalog(it, actor) }
        } catch (_: RequestRejected) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@get
        }
        call.respondJson(catalog)
    }

    post("/shop/purchases") {
        val request = call.receive<BuyItemRequest>()
        val gift = request.forXuid?.let { Xuid.parse(it) }
        val shapeOk = request.itemId.isNotBlank() && request.itemId.length <= ITEM_ID_MAX_LENGTH && (request.forXuid == null || gift != null)
        if (!shapeOk) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
            return@post
        }
        call.idempotent(dependencies, request.requestId, request.actor, "buyItem") { connection, actor ->
            rejectable {
                // `idempotent` a déjà vérifié la forme du requestId : la forme canonique (minuscules) est celle de `shop_purchases.request_id`.
                val requestId = checkNotNull(parseRequestId(request.requestId))
                val balances = service.buy(connection, actor, requestId, request.itemId, gift)
                ActionResult.ok(SiloooJson.encodeToJsonElement(Balances.serializer(), balances))
            }
        }
    }
}
