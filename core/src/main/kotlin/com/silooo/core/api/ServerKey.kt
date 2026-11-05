package com.silooo.core.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import java.security.MessageDigest

/** L'en-tête du contrat (schéma de sécurité `cleServeur`). */
const val SERVER_KEY_HEADER = "X-Silooo-Key"

class ServerKeyConfig {
    /** La clé attendue, lue dans `SILOOO_API_KEY`. */
    var apiKey: String = ""
}

/**
 * Exige `X-Silooo-Key` sur toutes les routes où il est installé (le module l'installe sur `/v1`).
 * Clé absente ou fausse : 401 avec le corps d'erreur du contrat, et la route n'est pas exécutée.
 *
 * La comparaison est en temps constant : on compare les empreintes SHA-256 des deux clés avec
 * [MessageDigest.isEqual]. Avec l'empreinte, les deux côtés ont la même longueur, donc même la
 * longueur de la vraie clé ne fuit pas par le temps de réponse.
 */
val ServerKeyPlugin = createRouteScopedPlugin("SiloooServerKey", ::ServerKeyConfig) {
    require(pluginConfig.apiKey.isNotEmpty()) { "ServerKeyPlugin : la clé attendue est vide" }
    val expected = sha256(pluginConfig.apiKey)

    onCall { call ->
        val provided = call.request.headers[SERVER_KEY_HEADER]
        if (provided == null || !MessageDigest.isEqual(sha256(provided), expected)) {
            call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.INVALID_KEY)
        }
    }
}

private fun sha256(text: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
