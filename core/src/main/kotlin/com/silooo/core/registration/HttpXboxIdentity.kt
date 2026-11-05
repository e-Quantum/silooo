package com.silooo.core.registration

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * L'implémentation réelle de [XboxIdentity], sur le client HTTP de Ktor (moteur CIO). Trois appels, dans cet ordre :
 *
 * 1. Microsoft (`login.microsoftonline.com/consumers`) échange le code OAuth contre un jeton d'accès, portée `XboxLive.signin` ;
 * 2. Xbox Live (`user.auth.xboxlive.com`) échange ce jeton (`RpsTicket` « d=… ») contre un jeton utilisateur ;
 * 3. XSTS (`xsts.auth.xboxlive.com`, partie de confiance `http://xboxlive.com`) échange le jeton utilisateur contre le
 *    jeton XSTS, dont les revendications portent le XUID (`xid`) et le gamertag (`gtg`).
 *
 * Aucun message d'exception ne cite le code, un jeton, le XUID ni le gamertag : un message peut aller dans un journal.
 * Le client ne dépend d'aucun plugin de sérialisation : les corps JSON sont écrits et lus ici, ce qui laisse un test
 * poser un simple `HttpClient(MockEngine)`.
 */
class HttpXboxIdentity(
    private val config: XboxConfig,
    private val client: HttpClient = defaultClient(),
) : XboxIdentity, AutoCloseable {

    override fun authorizeUrl(state: String, redirectUri: String): String =
        URLBuilder(AUTHORIZE_URL).apply {
            parameters.append("client_id", config.clientId)
            parameters.append("response_type", "code")
            parameters.append("scope", SCOPE)
            parameters.append("response_mode", "query")
            parameters.append("state", state)
            parameters.append("redirect_uri", redirectUri)
        }.buildString()

    override suspend fun profile(code: String, redirectUri: String): XboxProfile {
        try {
            val accessToken = microsoftToken(code, redirectUri)
            val userToken = userToken(accessToken)
            return xstsProfile(userToken)
        } catch (e: XboxException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Pas de cause jointe : le message d'une erreur de client peut citer l'adresse demandée.
            throw XboxException(XboxFailure.UNAVAILABLE, "Microsoft ou Xbox Live n'a pas répondu comme prévu (${e.javaClass.simpleName})")
        }
    }

    /** (a) Le code OAuth contre le jeton d'accès Microsoft. */
    private suspend fun microsoftToken(code: String, redirectUri: String): String {
        val response = client.submitForm(
            url = TOKEN_URL,
            formParameters = Parameters.build {
                append("client_id", config.clientId)
                append("client_secret", config.clientSecret)
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri)
                append("scope", SCOPE)
            },
        )
        if (!response.status.isSuccess()) throw failed(response.status, "Microsoft", "l'échange du code")
        return text(readObject(response, "Microsoft"), "access_token", MAX_TOKEN_LENGTH, "Microsoft")
    }

    /** (b) Le jeton Microsoft contre le jeton utilisateur Xbox Live. */
    private suspend fun userToken(accessToken: String): String {
        val response = postJson(
            USER_AUTH_URL,
            buildJsonObject {
                putJsonObject("Properties") {
                    put("AuthMethod", "RPS")
                    put("SiteName", "user.auth.xboxlive.com")
                    put("RpsTicket", "d=$accessToken")
                }
                put("RelyingParty", "http://auth.xboxlive.com")
                put("TokenType", "JWT")
            },
        )
        if (!response.status.isSuccess()) throw failed(response.status, "Xbox Live", "le jeton Microsoft")
        return text(readObject(response, "Xbox Live"), "Token", MAX_TOKEN_LENGTH, "Xbox Live")
    }

    /** (c) Le jeton utilisateur contre le jeton XSTS, d'où l'on lit le XUID et le gamertag. */
    private suspend fun xstsProfile(userToken: String): XboxProfile {
        val response = postJson(
            XSTS_URL,
            buildJsonObject {
                putJsonObject("Properties") {
                    put("SandboxId", "RETAIL")
                    putJsonArray("UserTokens") { add(userToken) }
                }
                put("RelyingParty", "http://xboxlive.com")
                put("TokenType", "JWT")
            },
        )
        if (response.status == HttpStatusCode.Unauthorized) throw xstsRefusal(response)
        if (!response.status.isSuccess()) throw failed(response.status, "XSTS", "le jeton utilisateur")
        val body = readObject(response, "XSTS")
        val claims = ((body["DisplayClaims"] as? JsonObject)?.get("xui") as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw XboxException(XboxFailure.UNAVAILABLE, "La réponse de XSTS n'a pas le champ attendu (DisplayClaims.xui)")
        val xuid = text(claims, "xid", MAX_XUID_LENGTH, "XSTS")
        if (!xuid.all { it in '0'..'9' }) {
            throw XboxException(XboxFailure.UNAVAILABLE, "La réponse de XSTS n'a pas le champ attendu (xid)")
        }
        return XboxProfile(xuid, text(claims, "gtg", MAX_GAMERTAG_LENGTH, "XSTS"))
    }

    /** Un statut d'échec : une panne ou une limite de débit du fournisseur (5xx, 429) est indisponible, le reste est un refus. */
    private fun failed(status: HttpStatusCode, who: String, what: String): XboxException {
        val outage = status.value >= 500 || status == HttpStatusCode.TooManyRequests
        val failure = if (outage) XboxFailure.UNAVAILABLE else XboxFailure.REFUSED
        return XboxException(failure, "$who a ${if (outage) "échoué sur" else "refusé"} $what (HTTP ${status.value})")
    }

    /** Un 401 de XSTS porte `XErr` : les trois cas que la page sait expliquer, tout autre code est un refus. */
    private suspend fun xstsRefusal(response: HttpResponse): XboxException {
        val code = try {
            (readObject(response, "XSTS")["XErr"] as? JsonPrimitive)?.content?.toLongOrNull()
        } catch (e: XboxException) {
            null
        }
        val failure = when (code) {
            XERR_CHILD_ACCOUNT -> XboxFailure.CHILD_ACCOUNT
            XERR_NO_XBOX_PROFILE -> XboxFailure.NO_XBOX_PROFILE
            XERR_NOT_IN_COUNTRY -> XboxFailure.NOT_IN_COUNTRY
            else -> XboxFailure.REFUSED
        }
        return XboxException(failure, "XSTS a refusé le compte (HTTP 401, XErr ${code ?: "absent"})")
    }

    private suspend fun postJson(url: String, body: JsonObject): HttpResponse =
        client.post(url) {
            header("x-xbl-contract-version", "1")
            header(HttpHeaders.Accept, "application/json")
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }

    private suspend fun readObject(response: HttpResponse, who: String): JsonObject {
        val raw = response.bodyAsText()
        val parsed = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        }
        return parsed ?: throw XboxException(XboxFailure.UNAVAILABLE, "La réponse de $who n'est pas lisible")
    }

    private fun text(body: JsonObject, field: String, max: Int, who: String): String =
        (body[field] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= max }
            ?: throw XboxException(XboxFailure.UNAVAILABLE, "La réponse de $who n'a pas le champ attendu ($field)")

    override fun close() = client.close()

    companion object {
        private const val AUTHORIZE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"
        private const val TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
        private const val USER_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate"
        private const val XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
        private const val SCOPE = "XboxLive.signin"

        /** Les codes `XErr` d'un 401 de XSTS (documentés par Microsoft). */
        private const val XERR_NO_XBOX_PROFILE = 2148916233L
        private const val XERR_NOT_IN_COUNTRY = 2148916235L
        private const val XERR_CHILD_ACCOUNT = 2148916238L

        /** Un jeton Microsoft ou Xbox Live tient en quelques milliers de caractères ; au-delà, la réponse n'est pas celle qu'on attend. */
        private const val MAX_TOKEN_LENGTH = 16_384

        /** Un XUID tient en seize chiffres ; la colonne `BIGINT UNSIGNED` en accepte vingt. */
        private const val MAX_XUID_LENGTH = 20

        /** Un gamertag tient en une vingtaine de caractères. */
        private const val MAX_GAMERTAG_LENGTH = 64

        private const val TIMEOUT_MILLIS = 10_000L

        fun defaultClient(): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = TIMEOUT_MILLIS
                connectTimeoutMillis = TIMEOUT_MILLIS
                socketTimeoutMillis = TIMEOUT_MILLIS
            }
            expectSuccess = false
        }
    }
}
