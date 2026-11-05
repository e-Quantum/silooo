package com.silooo.core.registration

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull


/**
 * Le fournisseur d'identité de « Relier Discord » : OAuth2 de Discord, portée `identify` seule. Une interface, pour
 * qu'un test pose un faux fournisseur et que rien d'autre que [HttpDiscordIdentity] ne parle à Discord.
 */
interface DiscordIdentity {
    /** L'adresse où envoyer le visiteur pour qu'il autorise ; [state] revient tel quel à [redirectUri]. */
    fun authorizeUrl(state: String, redirectUri: String): String

    /** Échange le [code] de retour contre l'identifiant du compte. Lève [DiscordException] si le fournisseur refuse. */
    suspend fun userId(code: String, redirectUri: String): String
}

/**
 * Le fournisseur a refusé ou n'a pas répondu. Le message ne contient ni le code, ni le jeton, ni l'identifiant :
 * il peut aller dans un journal.
 */
class DiscordException(message: String) : Exception(message)

/** `SILOOO_DISCORD_CLIENT_ID` et `SILOOO_DISCORD_CLIENT_SECRET`, posés ensemble. Le secret ne s'affiche jamais. */
data class DiscordConfig(val clientId: String, val clientSecret: String) {
    override fun toString() = "DiscordConfig(clientId=$clientId, clientSecret=***)"
}

/** L'implémentation réelle, sur le client HTTP de Ktor (moteur CIO). */
class HttpDiscordIdentity(
    private val config: DiscordConfig,
    private val client: HttpClient = defaultClient(),
) : DiscordIdentity, AutoCloseable {

    override fun authorizeUrl(state: String, redirectUri: String): String =
        URLBuilder("$BASE/oauth2/authorize").apply {
            parameters.append("client_id", config.clientId)
            parameters.append("response_type", "code")
            parameters.append("scope", "identify")
            parameters.append("state", state)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("prompt", "none")
        }.buildString()

    override suspend fun userId(code: String, redirectUri: String): String {
        try {
            val token = client.submitForm(
                url = "$API/oauth2/token",
                formParameters = Parameters.build {
                    append("client_id", config.clientId)
                    append("client_secret", config.clientSecret)
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", redirectUri)
                },
            )
            if (!token.status.isSuccess()) throw DiscordException("Discord a refusé l'échange du code (HTTP ${token.status.value})")
            val accessToken = text(token.body<JsonObject>(), "access_token", MAX_TOKEN_LENGTH)
            val me = client.get("$API/users/@me") { header(HttpHeaders.Authorization, "Bearer $accessToken") }
            if (me.status != HttpStatusCode.OK) throw DiscordException("Discord a refusé la lecture du compte (HTTP ${me.status.value})")
            return text(me.body<JsonObject>(), "id", MAX_ID_LENGTH)
        } catch (e: DiscordException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Pas de cause jointe : le message d'une erreur de client peut citer l'adresse demandée.
            throw DiscordException("Discord n'a pas répondu comme prévu (${e.javaClass.simpleName})")
        }
    }

    private fun text(body: JsonObject, field: String, max: Int): String =
        (body[field] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= max }
            ?: throw DiscordException("La réponse de Discord n'a pas le champ attendu ($field)")

    override fun close() = client.close()

    companion object {
        private const val BASE = "https://discord.com"
        private const val API = "https://discord.com/api/v10"

        /** Un identifiant Discord tient en une vingtaine de chiffres ; la colonne en accepte 32. */
        private const val MAX_ID_LENGTH = 32

        private const val MAX_TOKEN_LENGTH = 512

        private const val TIMEOUT_MILLIS = 10_000L

        fun defaultClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json() }
            install(HttpTimeout) {
                requestTimeoutMillis = TIMEOUT_MILLIS
                connectTimeoutMillis = TIMEOUT_MILLIS
            }
            expectSuccess = false
        }
    }
}
