package com.silooo.core.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonElement

/**
 * Répond en JSON sans passer par la négociation de contenu : comme [respondError], le corps part
 * même si le client envoie un `Accept` que le service ne connaît pas. Le serveur Bedrock n'en envoie
 * pas, mais une réponse du contrat ne doit pas dépendre d'un en-tête.
 */
suspend inline fun <reified T> ApplicationCall.respondJson(value: T, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(SiloooJson.encodeToString(kotlinx.serialization.serializer<T>(), value), ContentType.Application.Json, status)
}

/** La même chose pour un JSON déjà construit (la réponse gardée d'une requête rejouée). */
suspend fun ApplicationCall.respondJsonElement(value: JsonElement, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(value.toString(), ContentType.Application.Json, status)
}
