package com.silooo.core.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Le corps d'erreur du contrat : le schéma `Refused` de l'OpenAPI, `{"reason": "<CODE>"}`.
 * L'add-on lit `reason` et choisit l'icône et la bulle d'Oooscar ; `missing` ne sert qu'aux refus
 * qui portent une somme et viendra avec eux.
 */
@Serializable
data class ErrorBody(val reason: String)

/**
 * Les codes que le service renvoie sans que le jeu y soit pour rien. Les codes de refus du jeu
 * (`PAS_ASSEZ_DE_DONAR`, `CONFLIT_REESSAYER`…) sont dans l'OpenAPI, réponse `Refused`.
 *
 * Ces quatre-là sont dans l'OpenAPI (réponse `Erreur`, déclarée par chaque opération pour 400, 401, 500 et 501) ;
 * `ContractTest` vérifie qu'ils y restent identiques. Même convention que l'ADR 0005 : capitales françaises sans accent.
 */
object ErrorCodes {
    /** 401 : l'en-tête `X-Silooo-Key` manque ou n'est pas la bonne clé. */
    const val INVALID_KEY = "CLE_INVALIDE"

    /** 501 : la route existe dans le contrat mais pas encore dans le code. */
    const val NOT_DONE = "PAS_FAIT"

    /** 400 : le corps ou un paramètre n'a pas la forme du contrat. */
    const val BAD_REQUEST = "REQUETE_INVALIDE"

    /** 500 : une erreur que personne n'a prévue. Le détail va dans les journaux, jamais dans la réponse. */
    const val INTERNAL = "ERREUR_INTERNE"
}

/** Le JSON de l'API. `ignoreUnknownKeys` : un champ de plus côté add-on ne casse pas le service. */
val SiloooJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Répond avec le corps d'erreur du contrat. Le texte est écrit à la main en JSON plutôt que passé
 * par la négociation de contenu : un client qui envoie un `Accept` étrange reçoit quand même son
 * erreur, et ce code marche même dans le plugin de clé, avant toute autre étape.
 */
suspend fun ApplicationCall.respondError(status: HttpStatusCode, reason: String) {
    respondText(SiloooJson.encodeToString(ErrorBody.serializer(), ErrorBody(reason)), ContentType.Application.Json, status)
}
