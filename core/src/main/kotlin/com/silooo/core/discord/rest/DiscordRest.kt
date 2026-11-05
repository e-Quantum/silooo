package com.silooo.core.discord.rest

import com.silooo.core.discord.BotToken
import com.silooo.core.discord.DiscordRefusal
import com.silooo.core.discord.OnboardingOption
import com.silooo.core.discord.OnboardingPrompt
import com.silooo.core.discord.OnboardingState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Les appels REST à Discord que JDA ne couvre pas, sur le client Ktor du service (ADR 0007) : l'accueil guidé.
 * Le jeton part dans l'en-tête `Authorization` et nulle part ailleurs ; un refus de Discord se rend avec son code
 * HTTP et son message, jamais avec le jeton. Le [client] se ferme avec [close].
 */
class DiscordRest(
    private val token: BotToken,
    private val client: HttpClient = defaultClient(),
    private val api: String = API,
) : AutoCloseable {

    /** L'accueil guidé du serveur [guildId] ; un serveur qui n'en a jamais eu rend un état vide, désactivé. */
    suspend fun onboarding(guildId: String): OnboardingState =
        exchange("lire l'accueil guidé") { client.get(api + "/guilds/" + guildId + "/onboarding") { asBot() } }

    /** Remplace l'accueil guidé de [guildId] par [state] et rend ce que Discord a gardé, identifiants compris. */
    suspend fun setOnboarding(guildId: String, state: OnboardingState): OnboardingState =
        exchange("poser l'accueil guidé") {
            client.put(api + "/guilds/" + guildId + "/onboarding") {
                asBot()
                header(AUDIT_REASON, "Le plan du serveur, par Oooscar")
                contentType(ContentType.Application.Json)
                setBody(OnboardingJson.write(state))
            }
        }

    private fun HttpRequestBuilder.asBot() {
        header(HttpHeaders.Authorization, "Bot " + token.reveal())
    }

    private suspend fun exchange(what: String, request: suspend () -> HttpResponse): OnboardingState {
        val response = try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Pas de message joint : celui d'une erreur de client peut citer la requête entière.
            throw DiscordRefusal("Discord n'a pas répondu pour " + what + " (" + e.javaClass.simpleName + ")")
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw DiscordRefusal("Discord a refusé de " + what + " (HTTP " + response.status.value + ")" + OnboardingJson.refusal(body))
        }
        return try {
            OnboardingJson.read(body)
        } catch (e: IllegalArgumentException) {
            throw DiscordRefusal("la réponse de Discord pour " + what + " est illisible (" + e.javaClass.simpleName + ")")
        }
    }

    override fun close() = client.close()

    companion object {
        const val API = "https://discord.com/api/v10"
        private const val AUDIT_REASON = "X-Audit-Log-Reason"
        private const val TIMEOUT_MILLIS = 15_000L

        fun defaultClient(): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = TIMEOUT_MILLIS
                connectTimeoutMillis = TIMEOUT_MILLIS
            }
            expectSuccess = false
        }
    }
}

/** La forme JSON de l'API de l'accueil guidé (clés en `snake_case`), lue et écrite ici seulement. */
internal object OnboardingJson {
    private const val MAX_MESSAGE = 200
    private const val MAX_DETAIL = 120
    private const val MAX_DETAILS = 5

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun read(text: String): OnboardingState {
        val state = json.decodeFromString(StateJson.serializer(), text)
        return OnboardingState(
            prompts = state.prompts.map { prompt ->
                OnboardingPrompt(
                    id = prompt.id,
                    title = prompt.title,
                    singleSelect = prompt.singleSelect,
                    required = prompt.required,
                    inOnboarding = prompt.inOnboarding,
                    options = prompt.options.map { option ->
                        OnboardingOption(
                            id = option.id,
                            title = option.title,
                            description = option.description,
                            emoji = option.emoji?.name ?: option.emojiName,
                            roleIds = option.roleIds.toSet(),
                            channelIds = option.channelIds.toSet(),
                        )
                    },
                )
            },
            defaultChannelIds = state.defaultChannelIds,
            enabled = state.enabled,
            mode = state.mode,
        )
    }

    fun write(state: OnboardingState): String = json.encodeToString(
        StateJson.serializer(),
        StateJson(
            prompts = state.prompts.map { prompt ->
                PromptJson(
                    id = prompt.id,
                    title = prompt.title,
                    singleSelect = prompt.singleSelect,
                    required = prompt.required,
                    inOnboarding = prompt.inOnboarding,
                    options = prompt.options.map { option ->
                        OptionJson(
                            id = option.id,
                            title = option.title,
                            description = option.description,
                            emojiName = option.emoji,
                            roleIds = option.roleIds.toList(),
                            channelIds = option.channelIds.toList(),
                        )
                    },
                )
            },
            defaultChannelIds = state.defaultChannelIds,
            enabled = state.enabled,
            mode = state.mode,
        ),
    )

    /**
     * Le message et le code d'un refus de Discord (`{"message": ..., "code": ..., "errors": ...}`), avec les champs
     * en cause ; rien si le corps n'a pas cette forme.
     */
    fun refusal(body: String): String {
        val error = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return ""
        val message = (error["message"] as? JsonPrimitive)?.contentOrNull?.take(MAX_MESSAGE) ?: return ""
        val code = (error["code"] as? JsonPrimitive)?.contentOrNull
        val details = (error["errors"] as? JsonObject)?.let { leaves(it) }.orEmpty().take(MAX_DETAILS)
        return " : " + message +
            (if (code == null) "" else " (code " + code + ")") +
            (if (details.isEmpty()) "" else " ; " + details.joinToString(" ; "))
    }

    /** Les feuilles `_errors[].message` de l'arbre d'erreurs de Discord, chacune avec le chemin du champ. */
    private fun leaves(node: JsonObject, path: String = ""): List<String> = node.entries.flatMap { (key, value) ->
        when {
            key == "_errors" && value is JsonArray -> value.mapNotNull { item ->
                ((item as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull?.let { path + " : " + it.take(MAX_DETAIL) }
            }
            value is JsonObject -> leaves(value, if (path.isEmpty()) key else path + "." + key)
            else -> emptyList()
        }
    }
}

@Serializable
private data class StateJson(
    val prompts: List<PromptJson> = emptyList(),
    @SerialName("default_channel_ids") val defaultChannelIds: List<String> = emptyList(),
    val enabled: Boolean = false,
    val mode: Int = OnboardingState.MODE_DEFAULT,
)

@Serializable
private data class PromptJson(
    val id: String,
    val type: Int = 0,
    val title: String,
    val options: List<OptionJson> = emptyList(),
    @SerialName("single_select") val singleSelect: Boolean = false,
    val required: Boolean = false,
    @SerialName("in_onboarding") val inOnboarding: Boolean = true,
)

@Serializable
private data class OptionJson(
    val id: String,
    val title: String,
    val description: String? = null,
    @SerialName("emoji_name") val emojiName: String? = null,
    val emoji: EmojiJson? = null,
    @SerialName("role_ids") val roleIds: List<String> = emptyList(),
    @SerialName("channel_ids") val channelIds: List<String> = emptyList(),
)

@Serializable
private data class EmojiJson(val id: String? = null, val name: String? = null)
