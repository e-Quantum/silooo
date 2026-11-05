package com.silooo.core.registration

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Le fournisseur Xbox réel contre un faux moteur HTTP : les trois appels dans l'ordre, ce que chacun envoie, chaque refus
 * de XSTS, et la garantie qu'aucun message d'erreur ne cite le code, un jeton, le XUID ni le gamertag.
 */
class HttpXboxIdentityTest {

    @Test
    @DisplayName("Le chemin heureux : Microsoft, puis Xbox Live, puis XSTS, dans cet ordre, et le profil rendu")
    fun happyPath() {
        val setup = Setup()

        val profile = runBlocking { setup.identity.profile(CODE, REDIRECT) }

        assertEquals(XboxProfile(XUID, GAMERTAG), profile)
        assertEquals(
            listOf(
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                "https://user.auth.xboxlive.com/user/authenticate",
                "https://xsts.auth.xboxlive.com/xsts/authorize",
            ),
            setup.calls.map { it.url },
        )
        assertTrue(setup.calls.all { it.request.method == HttpMethod.Post })

        // (a) le formulaire de l'échange du code
        val form = checkNotNull(setup.calls[0].form)
        assertEquals("client-de-test", form["client_id"])
        assertEquals("secret-de-test", form["client_secret"])
        assertEquals("authorization_code", form["grant_type"])
        assertEquals(CODE, form["code"])
        assertEquals(REDIRECT, form["redirect_uri"])
        assertEquals("XboxLive.signin", form["scope"])

        // (b) le jeton Microsoft devient un RpsTicket « d=… »
        val user = setup.calls[1]
        val userBody = checkNotNull(user.json)
        val userProperties = userBody.getValue("Properties").jsonObject
        assertEquals("RPS", userProperties.getValue("AuthMethod").jsonPrimitive.content)
        assertEquals("user.auth.xboxlive.com", userProperties.getValue("SiteName").jsonPrimitive.content)
        assertEquals("d=$MICROSOFT_TOKEN", userProperties.getValue("RpsTicket").jsonPrimitive.content)
        assertEquals("http://auth.xboxlive.com", userBody.getValue("RelyingParty").jsonPrimitive.content)
        assertEquals("JWT", userBody.getValue("TokenType").jsonPrimitive.content)
        assertEquals("1", user.request.headers["x-xbl-contract-version"])
        assertEquals("application/json", user.request.headers[HttpHeaders.Accept])
        assertTrue((user.request.body as TextContent).contentType.match(ContentType.Application.Json))

        // (c) le jeton utilisateur part chez XSTS
        val xsts = setup.calls[2]
        val xstsBody = checkNotNull(xsts.json)
        val xstsProperties = xstsBody.getValue("Properties").jsonObject
        assertEquals("RETAIL", xstsProperties.getValue("SandboxId").jsonPrimitive.content)
        assertEquals(listOf(USER_TOKEN), xstsProperties.getValue("UserTokens").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("http://xboxlive.com", xstsBody.getValue("RelyingParty").jsonPrimitive.content)
        assertEquals("JWT", xstsBody.getValue("TokenType").jsonPrimitive.content)
        assertEquals("1", xsts.request.headers["x-xbl-contract-version"])
        assertEquals("application/json", xsts.request.headers[HttpHeaders.Accept])
    }

    @Test
    @DisplayName("Un 401 de XSTS : 2148916238 est un compte enfant, 2148916233 pas de profil Xbox, 2148916235 hors pays, tout autre code un refus")
    fun xstsErrors() {
        val cases = listOf(
            2148916238L to XboxFailure.CHILD_ACCOUNT,
            2148916233L to XboxFailure.NO_XBOX_PROFILE,
            2148916235L to XboxFailure.NOT_IN_COUNTRY,
            2148916236L to XboxFailure.REFUSED,
            1L to XboxFailure.REFUSED,
        )
        for ((xerr, expected) in cases) {
            val setup = Setup(xsts = Reply(HttpStatusCode.Unauthorized, xstsRefusal(xerr)))
            val error = assertThrows<XboxException>("XErr $xerr") { runBlocking { setup.identity.profile(CODE, REDIRECT) } }
            assertEquals(expected, error.failure, "XErr $xerr")
            assertEquals(3, setup.calls.size, "XErr $xerr : les trois appels ont eu lieu")
            assertNoLeak(error)
        }
    }

    @Test
    @DisplayName("Un 401 de XSTS sans XErr lisible est un refus")
    fun xstsRefusalWithoutXErr() {
        for (body in listOf("", "pas du json", "{}", """{"XErr":"abc"}""", """{"XErr":null}""", "[]")) {
            val setup = Setup(xsts = Reply(HttpStatusCode.Unauthorized, body))
            val error = assertThrows<XboxException>("corps « $body »") { runBlocking { setup.identity.profile(CODE, REDIRECT) } }
            assertEquals(XboxFailure.REFUSED, error.failure, "corps « $body »")
            assertNoLeak(error)
        }
    }

    @Test
    @DisplayName("Un refus de Microsoft (400 à l'échange du code) arrête tout : REFUSED, et aucun appel à Xbox Live")
    fun microsoftRefuses() {
        val setup = Setup(token = Reply(HttpStatusCode.BadRequest, """{"error":"invalid_grant","error_description":"AADSTS70008: le code $CODE a expiré"}"""))

        val error = assertThrows<XboxException> { runBlocking { setup.identity.profile(CODE, REDIRECT) } }

        assertEquals(XboxFailure.REFUSED, error.failure)
        assertEquals(1, setup.calls.size)
        assertNoLeak(error)
    }

    @Test
    @DisplayName("Un statut non 2xx de Xbox Live ou de XSTS (hors 401) est un refus ; un 5xx ou un 429 est une indisponibilité")
    fun otherStatusesAreRefusals() {
        val user = Setup(user = Reply(HttpStatusCode.Forbidden, "{}"))
        assertEquals(XboxFailure.REFUSED, assertThrows<XboxException> { runBlocking { user.identity.profile(CODE, REDIRECT) } }.failure)
        assertEquals(2, user.calls.size)

        val xsts = Setup(xsts = Reply(HttpStatusCode.InternalServerError, "{}"))
        val error = assertThrows<XboxException> { runBlocking { xsts.identity.profile(CODE, REDIRECT) } }
        assertEquals(XboxFailure.UNAVAILABLE, error.failure)
        assertEquals(3, xsts.calls.size)
        assertNoLeak(error)

        val busy = Setup(user = Reply(HttpStatusCode.TooManyRequests, "{}"))
        assertEquals(XboxFailure.UNAVAILABLE, assertThrows<XboxException> { runBlocking { busy.identity.profile(CODE, REDIRECT) } }.failure)
    }

    @Test
    @DisplayName("Un moteur qui lève : UNAVAILABLE, et le message de l'exception d'origine (qui cite le code) n'est pas repris")
    fun engineThrows() {
        val setup = Setup(failure = IOException("connexion à https://login.microsoftonline.com/token?code=$CODE refusée"))

        val error = assertThrows<XboxException> { runBlocking { setup.identity.profile(CODE, REDIRECT) } }

        assertEquals(XboxFailure.UNAVAILABLE, error.failure)
        assertNull(error.cause)
        assertTrue(error.message!!.contains("IOException"), "${error.message}")
        assertNoLeak(error)
    }

    @Test
    @DisplayName("Une réponse sans le champ attendu, vide ou illisible : UNAVAILABLE (sans gtg, sans xid, xid non numérique, pas de jeton)")
    fun unreadableResponses() {
        val xstsWithout = { claims: String -> """{"Token":"$XSTS_TOKEN","DisplayClaims":{"xui":[$claims]}}""" }
        val cases = mapOf(
            "sans gtg" to Setup(xsts = Reply(HttpStatusCode.OK, xstsWithout("""{"xid":"$XUID","uhs":"1"}"""))),
            "gtg vide" to Setup(xsts = Reply(HttpStatusCode.OK, xstsWithout("""{"xid":"$XUID","gtg":" "}"""))),
            "sans xid" to Setup(xsts = Reply(HttpStatusCode.OK, xstsWithout("""{"gtg":"$GAMERTAG"}"""))),
            "xid non numérique" to Setup(xsts = Reply(HttpStatusCode.OK, xstsWithout("""{"xid":"abc","gtg":"$GAMERTAG"}"""))),
            "xui vide" to Setup(xsts = Reply(HttpStatusCode.OK, xstsWithout(""))),
            "sans DisplayClaims" to Setup(xsts = Reply(HttpStatusCode.OK, """{"Token":"$XSTS_TOKEN"}""")),
            "XSTS pas du JSON" to Setup(xsts = Reply(HttpStatusCode.OK, "<html>pas du json</html>")),
            "sans access_token" to Setup(token = Reply(HttpStatusCode.OK, """{"token_type":"bearer"}""")),
            "access_token vide" to Setup(token = Reply(HttpStatusCode.OK, """{"access_token":""}""")),
            "sans Token utilisateur" to Setup(user = Reply(HttpStatusCode.OK, """{"DisplayClaims":{}}""")),
            "Xbox Live pas du JSON" to Setup(user = Reply(HttpStatusCode.OK, "")),
        )
        for ((name, setup) in cases) {
            val error = assertThrows<XboxException>(name) { runBlocking { setup.identity.profile(CODE, REDIRECT) } }
            assertEquals(XboxFailure.UNAVAILABLE, error.failure, name)
            assertNoLeak(error)
        }
    }

    @Test
    @DisplayName("L'adresse d'autorisation : l'autorité consumers, la portée XboxLive.signin, l'état et le retour intacts, jamais le secret")
    fun authorizeUrl() {
        val shown = Setup().identity.authorizeUrl("etat-a/b?c=d&e", REDIRECT)

        val url = Url(shown)
        assertEquals("https", url.protocol.name)
        assertEquals("login.microsoftonline.com", url.host)
        assertEquals("/consumers/oauth2/v2.0/authorize", url.encodedPath)
        assertEquals("client-de-test", url.parameters["client_id"])
        assertEquals("code", url.parameters["response_type"])
        assertEquals("XboxLive.signin", url.parameters["scope"])
        assertEquals("query", url.parameters["response_mode"])
        assertEquals("etat-a/b?c=d&e", url.parameters["state"])
        assertEquals(REDIRECT, url.parameters["redirect_uri"])
        assertNull(url.parameters["client_secret"])
        assertFalse(shown.contains("secret-de-test"), shown)
        assertFalse(shown.contains("client_secret"), shown)
    }

    private fun assertNoLeak(error: XboxException) {
        val shown = "${error.message} ${error.cause}"
        for (hidden in listOf(CODE, MICROSOFT_TOKEN, USER_TOKEN, XSTS_TOKEN, XUID, GAMERTAG, "secret-de-test")) {
            assertFalse(shown.contains(hidden), "« $hidden » apparaît dans « $shown »")
        }
    }

    private fun xstsRefusal(xerr: Long) = """{"Identity":"0","XErr":$xerr,"Message":"","Redirect":"https://start.ui.xboxlive.com/"}"""

    private class Reply(val status: HttpStatusCode, val body: String)

    /** Un appel vu par le faux moteur : son adresse, et son corps lu comme un formulaire ou comme du JSON. */
    private class Call(val request: HttpRequestData) {
        val url: String = request.url.toString()
        val form: Parameters? = (request.body as? FormDataContent)?.formData
        val json: JsonObject? = (request.body as? TextContent)?.let { Json.parseToJsonElement(it.text).jsonObject }
    }

    /** Le fournisseur réel branché sur un faux moteur qui répond par hôte, ou qui lève [failure]. */
    private class Setup(
        token: Reply = Reply(HttpStatusCode.OK, TOKEN_BODY),
        user: Reply = Reply(HttpStatusCode.OK, USER_BODY),
        xsts: Reply = Reply(HttpStatusCode.OK, XSTS_BODY),
        failure: Throwable? = null,
    ) {
        val calls = mutableListOf<Call>()
        val identity = HttpXboxIdentity(
            XboxConfig("client-de-test", "secret-de-test"),
            HttpClient(
                MockEngine { request ->
                    calls += Call(request)
                    if (failure != null) throw failure
                    val reply = when (request.url.host) {
                        "login.microsoftonline.com" -> token
                        "user.auth.xboxlive.com" -> user
                        "xsts.auth.xboxlive.com" -> xsts
                        else -> error("hôte inattendu : ${request.url.host}")
                    }
                    respond(reply.body, reply.status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
        )
    }

    private companion object {
        const val CODE = "code-de-retour-0123"
        const val REDIRECT = "https://silooo.test/connexion/xbox/retour/?a=1&b=2"
        const val MICROSOFT_TOKEN = "jeton-microsoft-opaque"
        const val USER_TOKEN = "jeton-utilisateur-xbox"
        const val XSTS_TOKEN = "jeton-xsts-final"
        const val XUID = "2535409876543210"
        const val GAMERTAG = "Kr Test"

        const val TOKEN_BODY = """{"token_type":"bearer","scope":"XboxLive.signin","expires_in":3600,"access_token":"$MICROSOFT_TOKEN"}"""
        const val USER_BODY =
            """{"IssueInstant":"2026-11-05T10:00:00.000Z","NotAfter":"2026-11-19T10:00:00.000Z","Token":"$USER_TOKEN","DisplayClaims":{"xui":[{"uhs":"1234567890"}]}}"""
        const val XSTS_BODY =
            """{"IssueInstant":"2026-11-05T10:00:01.000Z","NotAfter":"2026-11-05T22:00:01.000Z","Token":"$XSTS_TOKEN","DisplayClaims":{"xui":[{"gtg":"$GAMERTAG","xid":"$XUID","uhs":"1234567890","agg":"Adult"}]}}"""
    }
}
