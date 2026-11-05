package com.silooo.core.site

import com.silooo.core.Dependencies
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.io.readByteArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** L'adresse du webhook de PayNow : celle que Kr déclare dans la boutique (`<SILOOO_SITE_URL>/paynow/webhook`). */
internal const val PAYNOW_WEBHOOK_PATH = "/paynow/webhook"

/**
 * `POST /paynow/webhook`, rendu dans les deux modes : une commande de Gemmes payée devient une ligne de `shop_deliveries`. Le site n'écrit que
 * cette ligne ; le crédit des Gemmes est celui du relevé du jeu (`DeliveryRelief`), qui retrouve le joueur par son pseudo. Ni page ni cache.
 *
 * Dans l'ordre : pas de secret posé, 503 (journalisé une fois) ; corps annoncé trop gros, 413 ; signature absente ou fausse, 401 sans corps ;
 * corps illisible ou champ manquant, 400 avec le nom du champ ; sinon 200 `{"received": true}`, que la commande soit neuve ou déjà connue
 * (PayNow rejoue un webhook tant qu'il n'a pas reçu 200). Rien du corps n'est écrit dans une réponse ni dans un journal, hors l'identifiant de commande.
 */
internal fun Route.payNowRoutes(dependencies: Dependencies) {
    val webhook = dependencies.payNowWebhookSecret?.takeIf { it.isNotBlank() }?.let { PayNowWebhook(it) }
    val warned = AtomicBoolean(false)
    val store = dependencies.shopDeliveryStore

    post(PAYNOW_WEBHOOK_PATH) {
        if (webhook == null) {
            if (warned.compareAndSet(false, true)) call.application.log.warn("webhook PayNow non configuré : SILOOO_PAYNOW_WEBHOOK_SECRET n'est pas posée")
            return@post call.respond(HttpStatusCode.ServiceUnavailable)
        }
        val announced = call.request.contentLength()
        if (announced != null && announced > PayNowWebhook.MAX_BODY_BYTES) return@post call.respond(HttpStatusCode.PayloadTooLarge)
        // On ne lit jamais plus que la limite plus un octet : un corps sans longueur annoncée ne remplit pas la mémoire.
        val body = call.receiveChannel().readRemaining(PayNowWebhook.MAX_BODY_BYTES + 1).readByteArray()
        if (body.size > PayNowWebhook.MAX_BODY_BYTES) return@post call.respond(HttpStatusCode.PayloadTooLarge)
        if (!webhook.isAuthentic(body, call.request.headers[PayNowWebhook.SIGNATURE_HEADER])) return@post call.respond(HttpStatusCode.Unauthorized)

        when (val reading = webhook.read(body)) {
            is PayNowWebhook.Reading.Refused ->
                call.respondText(
                    buildJsonObject { put("error", reading.message) }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            is PayNowWebhook.Reading.Order -> {
                val inserted = dependencies.database.transaction { store.insert(it, reading.orderId, reading.gamertag, reading.gemmes) }
                call.application.log.info("Commande PayNow ${reading.orderId} : ${if (inserted) "livraison écrite" else "déjà connue, rien d'écrit"}")
                call.respondText(buildJsonObject { put("received", true) }.toString(), ContentType.Application.Json, HttpStatusCode.OK)
            }
        }
    }
}
