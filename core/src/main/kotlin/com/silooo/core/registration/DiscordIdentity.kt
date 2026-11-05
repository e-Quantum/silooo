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
 * Le fournisseur d'identité de « Se connecter avec Discord » : OAuth2 de Discord, portée `identify` seule, ou
 * `identify guilds.join` quand le visiteur a coché « Rejoindre aussi le Discord » (deux adresses d'autorisation : le
 * joueur voit ce qu'il accorde ; question T3 de `docs/site-revision.md`, recommandation appliquée). Une interface, pour
 * qu'un test pose un faux fournisseur et que rien d'autre que [HttpDiscordIdentity] ne parle à Discord.
 */
interface DiscordIdentity {
    /** L'adresse où envoyer le visiteur pour qu'il autorise ; [state] revient tel quel à [redirectUri]. */
    fun authorizeUrl(state: String, redirectUri: String, joinGuild: Boolean = false): String

    /** Échange le [code] de retour contre le compte. Lève [DiscordException] si le fournisseur refuse. */
    suspend fun identify(code: String, redirectUri: String): DiscordUser
}

/** Un compte Discord tel que l'OAuth2 le rend : l'identifiant, et le jeton d'accès (pour [DiscordGuildJoiner] seulement). Ni l'un ni l'autre ne s'affiche. */
class DiscordUser(val id: String, val accessToken: String) {
    override fun toString() = "DiscordUser(id=***, accessToken=***)"
}

/**
 * Fait entrer un compte sur le serveur Discord Silooo avec le jeton `guilds.join` que le visiteur a accordé. C'est le bot
 * (`discord/`) qui le fait, avec son propre jeton : le site ne porte que l'interface. `null` dans [com.silooo.core.Dependencies]
 * tant que le bot ne l'a pas branché : la case est alors sans effet, et le site ne le dit pas au visiteur.
 */
interface DiscordGuildJoiner {
    /** Lève [DiscordException] si Discord refuse ; le site ne le montre pas au visiteur (la connexion a réussi). */
    suspend fun join(user: DiscordUser)
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

    override fun authorizeUrl(state: String, redirectUri: String, joinGuild: Boolean): String =
        URLBuilder("$BASE/oauth2/authorize").apply {
            parameters.append("client_id", config.clientId)
            parameters.append("response_type", "code")
            parameters.append("scope", if (joinGuild) "identify guilds.join" else "identify")
            parameters.append("state", state)
            parameters.append("redirect_uri", redirectUri)
            // Avec `guilds.join`, Discord doit montrer ce que le visiteur accorde : pas de consentement silencieux.
            if (!joinGuild) parameters.append("prompt", "none")
        }.buildString()

    override suspend fun identify(code: String, redirectUri: String): DiscordUser {
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
            return DiscordUser(text(me.body<JsonObject>(), "id", MAX_ID_LENGTH), accessToken)
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
