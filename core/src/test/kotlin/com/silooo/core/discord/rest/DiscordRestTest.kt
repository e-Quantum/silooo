package com.silooo.core.discord.rest

import com.silooo.core.discord.BotToken
import com.silooo.core.discord.DiscordRefusal
import com.silooo.core.discord.OnboardingOption
import com.silooo.core.discord.OnboardingPrompt
import com.silooo.core.discord.OnboardingState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** L'accueil guidé en REST : la requête telle que Discord l'attend, la réponse relue, les refus sans le jeton. */
class DiscordRestTest {
    private val token = BotToken("jeton-secret")
    private val wanted = OnboardingState(
        prompts = listOf(
            OnboardingPrompt(
                id = "1",
                title = "Tu joues sur quoi ?",
                singleSelect = true,
                required = true,
                inOnboarding = true,
                options = listOf(OnboardingOption("1", "Console", "Xbox", "🎮", setOf("42"))),
            ),
        ),
        defaultChannelIds = listOf("7"),
        enabled = true,
    )

    private fun rest(handler: MockRequestHandler) = DiscordRest(token, HttpClient(MockEngine(handler)), api = "https://discord.test/api")

    @Test
    @DisplayName("Poser l'accueil guidé : un PUT avec le jeton en en-tête, le corps en snake_case, la réponse relue avec ses identifiants")
    fun putOnboarding() {
        var request: HttpRequestData? = null
        var body = ""
        val rest = rest { incoming ->
            request = incoming
            body = (incoming.body as TextContent).text
            respond(RESPONSE, HttpStatusCode.OK, JSON)
        }
        val result = runBlocking { rest.setOnboarding("99", wanted) }

        val seen = checkNotNull(request)
        assertEquals(HttpMethod.Put, seen.method)
        assertEquals("https://discord.test/api/guilds/99/onboarding", seen.url.toString())
        assertEquals("Bot jeton-secret", seen.headers[HttpHeaders.Authorization])
        assertEquals("Le plan du serveur, par Oooscar", seen.headers["X-Audit-Log-Reason"])
        val expected = listOf(
            "\"single_select\":true", "\"required\":true", "\"in_onboarding\":true", "\"type\":0",
            "\"emoji_name\":\"🎮\"", "\"role_ids\":[\"42\"]", "\"channel_ids\":[]", "\"description\":\"Xbox\"",
            "\"default_channel_ids\":[\"7\"]", "\"enabled\":true", "\"mode\":0",
        )
        for (piece in expected) assertTrue(body.contains(piece), body)
        assertFalse(body.contains("emoji_id"), body)

        assertEquals("1234567890123456789", result.prompts.single().id)
        val option = result.prompts.single().options.single()
        assertEquals("1234567890123456790", option.id)
        assertEquals("🎮", option.emoji)
        assertEquals("Xbox", option.description)
        assertEquals(setOf("42"), option.roleIds)
        assertTrue(result.enabled)
    }

    @Test
    @DisplayName("Un serveur sans accueil guidé se lit comme un état vide")
    fun readsAnEmptyServer() {
        val rest = rest { incoming ->
            assertEquals(HttpMethod.Get, incoming.method)
            respond("""{"guild_id":"99","prompts":[],"default_channel_ids":[],"enabled":false,"mode":0}""", HttpStatusCode.OK, JSON)
        }
        assertEquals(OnboardingState.NONE, runBlocking { rest.onboarding("99") })
    }

    @Test
    @DisplayName("Un refus de Discord donne son code HTTP, son message et les champs en cause, jamais le jeton")
    fun refusal() {
        val rest = rest { respond(ERROR, HttpStatusCode.BadRequest, JSON) }
        val e = assertThrows(DiscordRefusal::class.java) { runBlocking { rest.setOnboarding("99", wanted) } }
        assertEquals(
            "Discord a refusé de poser l'accueil guidé (HTTP 400) : Invalid Form Body (code 50035) ; default_channel_ids : Must be between 7 and 50 in length.",
            e.message,
        )
        assertFalse(e.message.orEmpty().contains("jeton-secret"))

        val bare = rest { respond("<html>403</html>", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "text/html")) }
        val forbidden = assertThrows(DiscordRefusal::class.java) { runBlocking { bare.onboarding("99") } }
        assertEquals("Discord a refusé de lire l'accueil guidé (HTTP 403)", forbidden.message)
    }

    @Test
    @DisplayName("Une réponse illisible ou une panne de réseau se disent sans le jeton")
    fun failures() {
        val broken = rest { respond("pas du json", HttpStatusCode.OK, JSON) }
        val unreadable = assertThrows(DiscordRefusal::class.java) { runBlocking { broken.onboarding("99") } }
        assertTrue(unreadable.message.orEmpty().startsWith("la réponse de Discord pour lire l'accueil guidé est illisible"), unreadable.message)

        val down = rest { throw IOException("coupé") }
        val e = assertThrows(DiscordRefusal::class.java) { runBlocking { down.onboarding("99") } }
        assertEquals("Discord n'a pas répondu pour lire l'accueil guidé (IOException)", e.message)
    }

    private companion object {
        val JSON = headersOf(HttpHeaders.ContentType, "application/json")
        const val RESPONSE =
            """{"guild_id":"99","prompts":[{"id":"1234567890123456789","type":0,"title":"Tu joues sur quoi ?","single_select":true,""" +
                """"required":true,"in_onboarding":true,"options":[{"id":"1234567890123456790","title":"Console","description":"Xbox",""" +
                """"emoji":{"id":null,"name":"🎮","animated":false},"role_ids":["42"],"channel_ids":[]}]}],""" +
                """"default_channel_ids":["7"],"enabled":true,"mode":0}"""
        const val ERROR =
            """{"message": "Invalid Form Body", "code": 50035, "errors": {"default_channel_ids": {"_errors": """ +
                """[{"code": "BASE_TYPE_BAD_LENGTH", "message": "Must be between 7 and 50 in length."}]}}}"""
    }
}
