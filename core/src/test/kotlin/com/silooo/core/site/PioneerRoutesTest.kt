package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.registration.FakeDiscordIdentity
import com.silooo.core.registration.InMemorySiteRegistrationStore
import com.silooo.core.registration.SiteAdmission
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Les pages des pionniers sans base ni Docker : faux dépôt en mémoire, faux fournisseur Discord, horloge à la main. */
class PioneerRoutesTest {
    /** Un identifiant reconnaissable : s'il apparaît dans une page, le test le voit. */
    private val discordId = "413000000000000001"

    private class Rig(
        val store: InMemorySiteRegistrationStore,
        val discord: FakeDiscordIdentity,
        val clock: MutableClock,
        val client: HttpClient,
    ) {
        /** Tout le HTML reçu : aucun ne doit contenir l'identifiant Discord. */
        val seen = mutableListOf<String>()

        suspend fun page(response: HttpResponse): String = response.bodyAsText().replace("&#39;", "'").also { seen += it }

        suspend fun form(): String {
            val html = page(client.get("/pionniers/"))
            val input = Regex("""<input[^>]*name="jeton"[^>]*>""").find(html)?.value ?: error("pas de jeton dans la page")
            return Regex("""value="([^"]+)"""").find(input)!!.groupValues[1]
        }

        suspend fun register(
            gamertag: String,
            token: String? = null,
            rules: Boolean = true,
            forwarded: String? = null,
        ): HttpResponse = client.submitForm(
            "/pionniers/",
            Parameters.build {
                append("gamertag", gamertag)
                if (rules) append("regles", "oui")
                append("jeton", token ?: form())
            },
        ) { forwarded?.let { header("X-Forwarded-For", it) } }

        /** `nom=valeur` du cookie de session posé par la réponse. */
        fun cookie(response: HttpResponse): String? =
            response.headers.getAll(HttpHeaders.SetCookie)?.firstOrNull { it.startsWith("silooo_pionnier=") }?.substringBefore(';')

        suspend fun get(path: String, cookie: String? = null): HttpResponse = client.get(path) { cookie?.let { header(HttpHeaders.Cookie, it) } }
    }

    private fun withRig(
        mode: SiteMode = SiteMode.ATTENTE,
        admission: SiteAdmission = SiteAdmission.DISCORD,
        withDiscord: Boolean = true,
        trustedProxy: Boolean = false,
        siteUrl: String = "https://silooo.test",
        block: suspend ApplicationTestBuilder.(Rig) -> Unit,
    ) = testApplication {
        val store = InMemorySiteRegistrationStore()
        val discord = FakeDiscordIdentity(discordId)
        val clock = MutableClock(Instant.parse("2026-11-05T12:00:00Z"))
        application {
            siteModule(
                Dependencies(
                    PassThroughDatabase(), "cle", definitions = TestDefinitions.loaded, clock = clock, siteMode = mode, siteUrl = siteUrl,
                    siteRegistrationStore = store, siteAdmission = admission, discordIdentity = if (withDiscord) discord else null,
                    siteTrustedProxy = trustedProxy,
                ),
            )
        }
        val rig = Rig(store, discord, clock, createClient { followRedirects = false })
        block(rig)
        for (html in rig.seen) assertFalse(html.contains(discordId), "l'identifiant Discord est apparu dans une page")
    }

    private fun location(response: HttpResponse): String = response.headers[HttpHeaders.Location] ?: error("pas de Location")

    @Test
    @DisplayName("Les deux modes rendent /pionniers/ : texte, formulaire, un seul bouton rouge, seulement les scripts du site")
    fun bothModesRenderTheForm() {
        for (mode in SiteMode.entries) {
            withRig(mode) { rig ->
                val response = rig.get("/pionniers/")
                assertEquals(HttpStatusCode.OK, response.status, mode.word)
                val html = rig.page(response)
                assertTrue(html.contains("Tu t'inscris avant l'ouverture. Tu entres parmi les premiers.") || html.contains("Tu t'inscris avant l'ouverture. Tu entres parmi les premiers."), mode.word)
                assertTrue(html.contains("""action="/pionniers/""""), mode.word)
                assertTrue(html.contains("""method="post""""), mode.word)
                assertEquals(1, Regex("""class="sa-bouton"""").findAll(html).count(), "un seul bouton rouge")
                assertTrue(html.contains(">M'inscrire<") || html.contains(">M'inscrire<"))
                assertOnlySiteScripts(html, "/pionniers/ (${mode.word})")
                assertTrue(html.contains("Aucun pionnier inscrit pour l'instant.") || html.contains("Aucun pionnier inscrit pour l'instant."))
                assertEquals(HttpStatusCode.MovedPermanently, rig.get("/pionniers").status)
            }
        }
    }

    @Test
    @DisplayName("La case des règles mène aux mentions en attente et au wiki en ouvert")
    fun rulesLink() {
        withRig(SiteMode.ATTENTE) { rig -> assertTrue(rig.page(rig.get("/pionniers/")).contains("""<a href="/mentions/">règles</a>""")) }
        withRig(SiteMode.OUVERT) { rig -> assertTrue(rig.page(rig.get("/pionniers/")).contains("""<a href="/wiki/regles/">règles</a>""")) }
    }

    @Test
    @DisplayName("Inscription réussie : 303 vers l'état, cookie signé HttpOnly SameSite=Lax Secure d'une heure, ligne écrite, compteur")
    fun registration() = withRig { rig ->
        val response = rig.register("Kr Test")
        assertEquals(HttpStatusCode.SeeOther, response.status)
        assertEquals("/pionniers/etat/", location(response))
        val setCookie = response.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith("silooo_pionnier=") }
        assertTrue(setCookie.contains("HttpOnly"), setCookie)
        assertTrue(setCookie.contains("SameSite=Lax"), setCookie)
        assertTrue(setCookie.contains("Secure"), setCookie)
        assertTrue(setCookie.contains("Max-Age=3600"), setCookie)
        assertEquals(setOf("kr test"), rig.store.rows.keys)
        assertEquals("Kr Test", rig.store.rows["kr test"]!!.gamertag)
        assertFalse(rig.store.rows["kr test"]!!.admitted)
        assertTrue(rig.page(rig.get("/pionniers/")).contains("1 pionnier inscrit"))
    }

    @Test
    @DisplayName("Sur une adresse en http, le cookie n'est pas Secure")
    fun cookieNotSecureOnHttp() = withRig(siteUrl = "http://localhost:8081") { rig ->
        val setCookie = rig.register("Pionnier").headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith("silooo_pionnier=") }
        assertFalse(setCookie.contains("Secure"), setCookie)
        assertTrue(setCookie.contains("HttpOnly"))
    }

    @Test
    @DisplayName("Gamertag invalide : refusé avec le message, rien d'écrit, le gamertag tapé reste dans le champ")
    fun invalidGamertag() = withRig { rig ->
        val cases = mapOf(
            "ab" to "Ton gamertag a 3 caractères au moins.",
            " Kr" to "Retire l'espace au début ou à la fin de ton gamertag.",
            "Kr " to "Retire l'espace au début ou à la fin de ton gamertag.",
            "Kr_Test" to "Ton gamertag ne contient que des lettres sans accent, des chiffres et des espaces.",
            "Éric" to "Ton gamertag ne contient que des lettres sans accent, des chiffres et des espaces.",
            "Kr  Test" to "Mets un seul espace entre deux mots.",
            "A".repeat(17) to "Ton gamertag a 16 caractères au plus.",
        )
        for ((typed, message) in cases) {
            rig.clock.advance(Duration.ofMinutes(2)) // hors de la fenêtre de la limite de débit
            val response = rig.register(typed)
            assertEquals(HttpStatusCode.BadRequest, response.status, typed)
            val html = rig.page(response)
            assertTrue(html.contains(message), "$typed : $message\n$html")
            assertNull(rig.cookie(response), typed)
        }
        assertTrue(rig.store.rows.isEmpty())
        rig.clock.advance(Duration.ofMinutes(2))
        val noRules = rig.register("Kr Test", rules = false)
        assertEquals(HttpStatusCode.BadRequest, noRules.status)
        assertTrue(rig.page(noRules).contains("Coche la case pour dire que tu as lu les règles."))
        assertTrue(rig.store.rows.isEmpty())
        rig.clock.advance(Duration.ofMinutes(2))
        assertEquals(HttpStatusCode.SeeOther, rig.register("A".repeat(16)).status)
        assertEquals(HttpStatusCode.SeeOther, rig.register("abc").status)
    }

    @Test
    @DisplayName("Jeton absent, falsifié ou périmé (plus de dix minutes) : refusé, rien d'écrit")
    fun antiReplayToken() = withRig { rig ->
        val missing = rig.client.submitForm("/pionniers/", Parameters.build { append("gamertag", "Kr Test"); append("regles", "oui") })
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertTrue(rig.page(missing).contains("Le formulaire a expiré."))

        val token = rig.form()
        val forged = rig.register("Kr Test", token = token.dropLast(2) + "xx")
        assertEquals(HttpStatusCode.BadRequest, forged.status)

        rig.clock.advance(Duration.ofMinutes(9))
        assertEquals(HttpStatusCode.SeeOther, rig.register("Kr Test", token = token).status, "dix minutes de validité")
        rig.clock.advance(Duration.ofMinutes(2))
        val late = rig.register("Autre Test", token = token)
        assertEquals(HttpStatusCode.BadRequest, late.status)
        assertEquals(setOf("kr test"), rig.store.rows.keys)
    }

    @Test
    @DisplayName("Un jeton de formulaire ne vaut pas cookie de session")
    fun tokenIsNotACookie() = withRig { rig ->
        rig.register("Kr Test")
        val token = rig.form()
        val response = rig.get("/pionniers/etat/", cookie = "silooo_pionnier=$token")
        assertEquals(HttpStatusCode.SeeOther, response.status)
        assertEquals("/pionniers/", location(response))
    }

    @Test
    @DisplayName("Double inscription (même clé, casse ou espaces différents) : « Tu es déjà inscrit. », 200, une seule ligne")
    fun alreadyRegistered() = withRig { rig ->
        rig.register("Kr Test")
        val again = rig.register("kr TEST")
        assertEquals(HttpStatusCode.OK, again.status)
        val html = rig.page(again)
        assertTrue(html.contains("Tu es déjà inscrit."))
        assertTrue(html.contains("Inscrit."))
        assertEquals(1, rig.store.rows.size)
        assertEquals("Kr Test", rig.store.rows.values.single().gamertag)
        assertNotNull(rig.cookie(again))
    }

    @Test
    @DisplayName("Une inscription déjà liée à Discord ne rend pas de cookie à qui retape le gamertag")
    fun linkedRegistrationGivesNoCookie() = withRig { rig ->
        rig.store.rows["kr test"] = InMemorySiteRegistrationStore.Row("Kr Test", discord = "999")
        val again = rig.register("Kr Test")
        assertEquals(HttpStatusCode.OK, again.status)
        assertNull(rig.cookie(again))
        assertTrue(rig.page(again).contains("Tu es déjà inscrit."))
    }

    @Test
    @DisplayName("État : sans cookie, ou avec un cookie falsifié ou périmé, on retourne au formulaire")
    fun stateRequiresCookie() = withRig { rig ->
        assertEquals("/pionniers/", location(rig.get("/pionniers/etat/")))
        val cookie = rig.cookie(rig.register("Kr Test"))!!
        assertEquals(HttpStatusCode.OK, rig.get("/pionniers/etat/", cookie).status)
        assertEquals(HttpStatusCode.SeeOther, rig.get("/pionniers/etat/", cookie.dropLast(2) + "xx").status)
        rig.clock.advance(Duration.ofMinutes(61))
        assertEquals(HttpStatusCode.SeeOther, rig.get("/pionniers/etat/", cookie).status)
    }

    @Test
    @DisplayName("Discord non configuré : pas de bouton, pas de route, la phrase « arrive »")
    fun discordNotConfigured() = withRig(withDiscord = false) { rig ->
        val cookie = rig.cookie(rig.register("Kr Test"))!!
        val html = rig.page(rig.get("/pionniers/etat/", cookie))
        assertFalse(html.contains("Relier Discord"))
        assertTrue(html.contains("Relier ton compte Discord arrive."))
        assertEquals(HttpStatusCode.NotFound, rig.get("/pionniers/discord/", cookie).status)
        assertEquals(HttpStatusCode.NotFound, rig.get("/pionniers/discord/retour/?state=a&code=b", cookie).status)
    }

    @Test
    @DisplayName("Discord configuré : le bouton laiton, la redirection avec state, le retour relie et admet")
    fun discordFlow() = withRig { rig ->
        val registered = rig.register("Kr Test")
        val cookie = rig.cookie(registered)!!
        val state = rig.page(rig.get("/pionniers/etat/", cookie))
        assertTrue(state.contains("""class="sa-bouton sa-bouton--laiton">Relier Discord</a>"""))
        assertFalse(state.contains("Relier ton compte Discord arrive."))
        assertTrue(state.contains("Inscrit."))
        assertFalse(state.contains("Admis"))

        val start = rig.get("/pionniers/discord/", cookie)
        assertEquals(HttpStatusCode.SeeOther, start.status)
        val target = location(start)
        assertTrue(target.startsWith("https://discord.test/oauth2/authorize?state="), target)
        assertTrue(target.contains("redirect_uri=https://silooo.test/pionniers/discord/retour/"), target)
        val oauthState = target.substringAfter("state=").substringBefore('&')
        assertTrue(oauthState.length >= 32)
        val cookieWithState = rig.cookie(start)!!

        val back = rig.get("/pionniers/discord/retour/?state=$oauthState&code=le-code", cookieWithState)
        assertEquals(HttpStatusCode.SeeOther, back.status)
        assertEquals("/pionniers/etat/", location(back))
        assertEquals("le-code", rig.discord.lastCode)
        assertEquals("https://silooo.test/pionniers/discord/retour/", rig.discord.lastRedirectUri)
        val row = rig.store.rows["kr test"]!!
        assertEquals(discordId, row.discord)
        assertTrue(row.admitted)

        val after = rig.page(rig.get("/pionniers/etat/", rig.cookie(back)!!))
        assertTrue(after.contains("Compte Discord relié."))
        assertTrue(after.contains("Admis : tu peux entrer dès l'ouverture.") || after.contains("Admis : tu peux entrer dès l'ouverture."))
        assertFalse(after.contains("Relier Discord"))
        // Le state est à usage unique : le cookie du retour n'en porte plus.
        val replay = rig.get("/pionniers/discord/retour/?state=$oauthState&code=le-code", rig.cookie(back)!!)
        assertEquals(HttpStatusCode.BadRequest, replay.status)
    }

    @Test
    @DisplayName("Mauvais state, state absent, refus de l'utilisateur : page claire, rien d'écrit, le fournisseur n'est pas appelé")
    fun discordBadReturn() = withRig { rig ->
        val cookie = rig.cookie(rig.register("Kr Test"))!!
        val withState = rig.cookie(rig.get("/pionniers/discord/", cookie))!!
        val wrongState = rig.get("/pionniers/discord/retour/?state=autre&code=x", withState)
        assertEquals(HttpStatusCode.BadRequest, wrongState.status)
        assertTrue(rig.page(wrongState).contains("Relier Discord n'a pas abouti.") || rig.page(wrongState).contains("Relier Discord n'a pas abouti."))
        val noState = rig.get("/pionniers/discord/retour/?code=x", cookie)
        assertEquals(HttpStatusCode.BadRequest, noState.status)
        val start = rig.get("/pionniers/discord/", cookie)
        val oauthState = location(start).substringAfter("state=").substringBefore('&')
        val denied = rig.get("/pionniers/discord/retour/?state=$oauthState&error=access_denied", rig.cookie(start)!!)
        assertEquals(HttpStatusCode.BadRequest, denied.status)
        assertNull(rig.discord.lastCode)
        assertNull(rig.store.rows["kr test"]!!.discord)
        assertFalse(rig.store.rows["kr test"]!!.admitted)
    }

    @Test
    @DisplayName("Fournisseur en panne : 502 avec une page claire, rien d'écrit")
    fun discordProviderFails() = withRig { rig ->
        rig.discord.failing = true
        val start = rig.get("/pionniers/discord/", rig.cookie(rig.register("Kr Test"))!!)
        val oauthState = location(start).substringAfter("state=").substringBefore('&')
        val back = rig.get("/pionniers/discord/retour/?state=$oauthState&code=x", rig.cookie(start)!!)
        assertEquals(HttpStatusCode.BadGateway, back.status)
        assertTrue(rig.page(back).contains("Discord ne répond pas pour l'instant.") || rig.page(back).contains("Discord ne répond pas pour l'instant."))
        assertNull(rig.store.rows["kr test"]!!.discord)
    }

    @Test
    @DisplayName("Un identifiant Discord déjà relié à un autre gamertag : page claire, rien d'écrit")
    fun discordAlreadyTaken() = withRig { rig ->
        suspend fun link(gamertag: String): HttpResponse {
            val start = rig.get("/pionniers/discord/", rig.cookie(rig.register(gamertag))!!)
            val oauthState = location(start).substringAfter("state=").substringBefore('&')
            return rig.get("/pionniers/discord/retour/?state=$oauthState&code=x", rig.cookie(start)!!)
        }
        assertEquals(HttpStatusCode.SeeOther, link("Premier").status)
        val second = link("Second")
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertTrue(rig.page(second).contains("Ce compte Discord est déjà relié à un autre joueur."))
        assertNull(rig.store.rows["second"]!!.discord)
        assertFalse(rig.store.rows["second"]!!.admitted)
        assertTrue(rig.store.rows["premier"]!!.admitted)
    }

    @Test
    @DisplayName("Règle « tous » : admis dès l'inscription, et Discord reste proposé")
    fun admissionEveryone() = withRig(admission = SiteAdmission.TOUS) { rig ->
        val cookie = rig.cookie(rig.register("Kr Test"))!!
        assertTrue(rig.store.rows["kr test"]!!.admitted)
        val html = rig.page(rig.get("/pionniers/etat/", cookie))
        assertTrue(html.contains("Admis"))
        assertTrue(html.contains("Relier Discord"))
    }

    @Test
    @DisplayName("Règle « discord » : pas admis à l'inscription")
    fun admissionDiscord() = withRig { rig ->
        rig.register("Kr Test")
        assertFalse(rig.store.rows["kr test"]!!.admitted)
    }

    @Test
    @DisplayName("Limite de débit : cinq POST par minute et par adresse, le sixième reçoit 429 et une page Silooo")
    fun rateLimit() = withRig { rig ->
        repeat(5) { assertEquals(HttpStatusCode.BadRequest, rig.register("x", token = "faux").status) }
        val sixth = rig.register("Kr Test")
        assertEquals(HttpStatusCode.TooManyRequests, sixth.status)
        assertEquals("60", sixth.headers[HttpHeaders.RetryAfter])
        val html = rig.page(sixth)
        assertTrue(html.contains("Trop d'essais. Réessaie dans une minute.") || html.contains("Trop d'essais. Réessaie dans une minute."))
        assertTrue(html.contains("<footer"))
        assertTrue(rig.store.rows.isEmpty())
        rig.clock.advance(Duration.ofSeconds(61))
        assertEquals(HttpStatusCode.SeeOther, rig.register("Kr Test").status)
    }

    @Test
    @DisplayName("Mandataire de confiance : l'adresse vient de X-Forwarded-For ; sinon l'en-tête est ignoré")
    fun forwardedFor() {
        withRig(trustedProxy = true) { rig ->
            repeat(5) { rig.register("x", token = "faux", forwarded = "203.0.113.1") }
            assertEquals(HttpStatusCode.TooManyRequests, rig.register("x", token = "faux", forwarded = "203.0.113.1").status)
            assertEquals(HttpStatusCode.BadRequest, rig.register("x", token = "faux", forwarded = "203.0.113.2").status)
            // Le dernier maillon est celui du mandataire : un faux en tête ne change pas la file.
            assertEquals(HttpStatusCode.TooManyRequests, rig.register("x", token = "faux", forwarded = "198.51.100.9, 203.0.113.1").status)
        }
        withRig(trustedProxy = false) { rig ->
            repeat(5) { rig.register("x", token = "faux", forwarded = "203.0.113.${it + 1}") }
            assertEquals(HttpStatusCode.TooManyRequests, rig.register("x", token = "faux", forwarded = "203.0.113.99").status)
        }
    }

    @Test
    @DisplayName("Le compteur est lu au plus une fois par minute")
    fun counterCache() = withRig { rig ->
        assertTrue(rig.page(rig.get("/pionniers/")).contains("Aucun pionnier inscrit pour l'instant."))
        // Écrit à côté du site : seul le cache de la minute décide de ce que la page montre.
        rig.store.rows["premier"] = InMemorySiteRegistrationStore.Row("Premier")
        rig.store.rows["second"] = InMemorySiteRegistrationStore.Row("Second")
        assertTrue(rig.page(rig.get("/pionniers/")).contains("Aucun pionnier inscrit pour l'instant."), "encore le cache")
        rig.clock.advance(Duration.ofSeconds(61))
        assertTrue(rig.page(rig.get("/pionniers/")).contains("2 pionniers inscrits"))
        // Une inscription par le site vide le cache : le nouvel inscrit se compte tout de suite.
        rig.register("Troisieme")
        assertTrue(rig.page(rig.get("/pionniers/")).contains("3 pionniers inscrits"))
    }

    @Test
    @DisplayName("Les en-têtes du site restent : politique sans script écrit dans la page, pas de cache")
    fun headersKept() = withRig { rig ->
        val response = rig.get("/pionniers/")
        assertEquals(CONTENT_SECURITY_POLICY, response.headers["Content-Security-Policy"])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }
}
