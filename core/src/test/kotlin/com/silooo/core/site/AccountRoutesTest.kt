package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.registration.DiscordGuildJoiner
import com.silooo.core.registration.DiscordLinks
import com.silooo.core.registration.DiscordUser
import com.silooo.core.registration.FakeDiscordIdentity
import com.silooo.core.registration.FakeXboxIdentity
import com.silooo.core.registration.InMemoryBetaAccessStore
import com.silooo.core.registration.InMemoryDiscordLinkStore
import com.silooo.core.registration.InMemorySiteRegistrationStore
import com.silooo.core.registration.InMemoryWaitlistStore
import com.silooo.core.registration.InMemoryWebAccountStore
import com.silooo.core.registration.InMemoryWebSessionStore
import com.silooo.core.registration.LinkCodes
import com.silooo.core.registration.RedeemOutcome
import com.silooo.core.registration.XboxFailure
import com.silooo.core.registration.XboxProfile
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
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les comptes du site sans base ni Docker : faux dépôts en mémoire, faux fournisseurs Xbox et Discord, horloge à la main.
 * Chaque page reçue est gardée : aucune ne doit porter l'identifiant Discord, le XUID Xbox ni l'empreinte d'une session.
 */
class AccountRoutesTest {
    private val discordId = "413000000000000001"
    private val xboxXuid = "2535400000000001"
    private val gamertag = "Kr Test"

    /** Le faux bot : il note qui a rejoint, ou refuse. */
    private class RecordingJoiner : DiscordGuildJoiner {
        val joined = mutableListOf<String>()
        var failing = false

        override suspend fun join(user: DiscordUser) {
            if (failing) throw IllegalStateException("le bot refuse (test)")
            joined += user.id
        }
    }

    private class Rig(
        val accounts: InMemoryWebAccountStore,
        val waitlist: InMemoryWaitlistStore,
        val links: InMemoryDiscordLinkStore,
        val beta: InMemoryBetaAccessStore,
        val sessions: InMemoryWebSessionStore,
        val registrations: InMemorySiteRegistrationStore,
        val xbox: FakeXboxIdentity,
        val discord: FakeDiscordIdentity,
        val joiner: RecordingJoiner,
        val clock: MutableClock,
        val database: PassThroughDatabase,
        val client: HttpClient,
    ) {
        val seen = mutableListOf<String>()

        suspend fun page(response: HttpResponse): String = response.bodyAsText().replace("&#39;", "'").also { seen += it }

        /** Lit ou écrit dans les faux dépôts comme les routes le font : dans une transaction. */
        suspend fun <T> tx(block: (Connection) -> T): T = database.transaction(block)

        suspend fun get(path: String, cookie: String? = null, forwarded: String? = null): HttpResponse = client.get(path) {
            cookie?.let { header(HttpHeaders.Cookie, it) }
            forwarded?.let { header("X-Forwarded-For", it) }
        }

        suspend fun post(path: String, cookie: String?, vararg fields: Pair<String, String>): HttpResponse =
            client.submitForm(path, Parameters.build { fields.forEach { append(it.first, it.second) } }) {
                cookie?.let { header(HttpHeaders.Cookie, it) }
            }

        fun setCookie(response: HttpResponse, name: String): String? =
            response.headers.getAll(HttpHeaders.SetCookie)?.firstOrNull { it.startsWith("$name=") }

        fun cookie(response: HttpResponse, name: String): String? = setCookie(response, name)?.substringBefore(';')

        fun location(response: HttpResponse): String = response.headers[HttpHeaders.Location] ?: error("pas de Location")

        fun state(response: HttpResponse): String = Regex("state=([0-9a-f]+)").find(location(response))?.groupValues?.get(1) ?: error("pas d'état dans ${location(response)}")

        /** Le départ vers une porte : le cookie du départ et l'état attendu. */
        suspend fun start(path: String): Pair<String, String> {
            val response = get(path)
            assertEquals(HttpStatusCode.SeeOther, response.status, path)
            return checkNotNull(cookie(response, "silooo_porte")) { "pas de cookie de départ" } to state(response)
        }

        suspend fun back(door: String, session: String?, state: String, porte: String, query: String = "&code=code-de-test"): HttpResponse =
            get("/connexion/$door/retour/?state=$state$query", cookie = listOfNotNull(porte, session).joinToString("; "))

        suspend fun enterXbox(session: String? = null): HttpResponse {
            val (porte, state) = start("/connexion/xbox/")
            return back("xbox", session, state, porte)
        }

        suspend fun enterDiscord(join: Boolean = false, session: String? = null): HttpResponse {
            val (porte, state) = start(if (join) "/connexion/discord/?rejoindre=oui" else "/connexion/discord/")
            return back("discord", session, state, porte)
        }

        /** Une connexion réussie : le cookie de session. */
        suspend fun session(response: HttpResponse): String {
            assertEquals(HttpStatusCode.SeeOther, response.status)
            return checkNotNull(cookie(response, "silooo_session")) { "pas de cookie de session" }
        }

        suspend fun loginXbox(): String = session(enterXbox())
        suspend fun loginDiscord(join: Boolean = false): String = session(enterDiscord(join))

        /** Le jeton anti-CSRF lu sur une page du compte. */
        suspend fun csrf(session: String, path: String = "/compte/"): String {
            val html = page(get(path, session))
            return Regex("""name="jeton" value="([0-9a-f]+)"""").find(html)?.groupValues?.get(1) ?: error("pas de jeton sur $path")
        }
    }

    private fun withRig(
        mode: SiteMode = SiteMode.ATTENTE,
        withXbox: Boolean = true,
        withDiscord: Boolean = true,
        withJoiner: Boolean = true,
        trustedProxy: Boolean = false,
        siteUrl: String = "https://silooo.test",
        block: suspend ApplicationTestBuilder.(Rig) -> Unit,
    ) = testApplication {
        val accounts = InMemoryWebAccountStore()
        val waitlist = InMemoryWaitlistStore()
        val links = InMemoryDiscordLinkStore()
        val beta = InMemoryBetaAccessStore()
        val sessions = InMemoryWebSessionStore()
        val registrations = InMemorySiteRegistrationStore()
        val xbox = FakeXboxIdentity(XboxProfile(xboxXuid, gamertag))
        val discord = FakeDiscordIdentity(discordId)
        val joiner = RecordingJoiner()
        val clock = MutableClock(Instant.parse("2026-11-05T12:00:00Z"))
        val database = PassThroughDatabase()
        application {
            siteModule(
                Dependencies(
                    database, "cle", definitions = TestDefinitions.loaded, clock = clock, siteMode = mode, siteUrl = siteUrl,
                    siteRegistrationStore = registrations,
                    discordIdentity = if (withDiscord) discord else null, xboxIdentity = if (withXbox) xbox else null,
                    guildJoiner = if (withJoiner) joiner else null, siteTrustedProxy = trustedProxy,
                    webAccountStore = accounts, waitlistStore = waitlist, discordLinkStore = links, betaAccessStore = beta, webSessionStore = sessions,
                ),
            )
        }
        val rig = Rig(accounts, waitlist, links, beta, sessions, registrations, xbox, discord, joiner, clock, database, createClient { followRedirects = false })
        block(rig)
        for (html in rig.seen) {
            assertFalse(html.contains(discordId), "l'identifiant Discord est apparu dans une page")
            assertFalse(html.contains(xboxXuid), "le XUID Xbox est apparu dans une page")
        }
    }

    private val playerXuid = Empreinte.duGamertag("Kr Test")

    @Test
    @DisplayName("Les trois modes rendent /connexion/ : les deux portes, un seul bouton rouge (Xbox), la case « Rejoindre », seulement les scripts du site")
    fun everyModeRendersTheDoors() {
        for (mode in SiteMode.entries) {
            withRig(mode) { rig ->
                val response = rig.get("/connexion/")
                assertEquals(HttpStatusCode.OK, response.status, mode.word)
                val html = rig.page(response)
                assertTrue(html.contains("""<a href="/connexion/xbox/" class="sa-bouton">Avec Xbox</a>"""), mode.word)
                assertTrue(html.contains("""action="/connexion/discord/""""), mode.word)
                assertTrue(html.contains("""name="rejoindre""""), mode.word)
                assertEquals(1, Regex("""class="sa-bouton"""").findAll(html).count(), "un seul bouton rouge (${mode.word})")
                assertTrue(html.contains("Silooo ne te demande aucun mot de passe."), mode.word)
                assertOnlySiteScripts(html, "/connexion/ (${mode.word})")
                assertEquals(HttpStatusCode.MovedPermanently, rig.get("/connexion").status)
                assertEquals("/connexion/", rig.location(rig.get("/connexion")))
            }
        }
    }

    @Test
    @DisplayName("Une porte sans application n'est pas un lien mort : « arrive bientôt », et sa route n'existe pas")
    fun doorsWithoutApplication() = withRig(withXbox = false, withDiscord = false) { rig ->
        val html = rig.page(rig.get("/pionniers/"))
        assertTrue(html.contains("La connexion avec Xbox arrive bientôt."))
        assertTrue(html.contains("La connexion avec Discord arrive bientôt."))
        assertFalse(html.contains("/connexion/xbox/"))
        assertFalse(html.contains("""action="/connexion/discord/""""))
        assertEquals(HttpStatusCode.NotFound, rig.get("/connexion/xbox/").status)
        assertEquals(HttpStatusCode.NotFound, rig.get("/connexion/discord/retour/?state=x&code=y").status)
    }

    @Test
    @DisplayName("Le départ vers Xbox : 303 vers le fournisseur avec l'état et l'adresse de retour, cookie de départ signé sur /connexion/, dix minutes")
    fun xboxStart() = withRig { rig ->
        val response = rig.get("/connexion/xbox/")
        assertEquals(HttpStatusCode.SeeOther, response.status)
        val location = rig.location(response)
        assertTrue(location.startsWith("https://xbox.test/authorize?state="), location)
        assertTrue(location.endsWith("&redirect_uri=https://silooo.test/connexion/xbox/retour/"), location)
        val cookie = checkNotNull(rig.setCookie(response, "silooo_porte"))
        for (part in listOf("Max-Age=600", "Path=/connexion/", "Secure", "HttpOnly", "SameSite=Lax")) assertTrue(cookie.contains(part), "$part manque dans $cookie")
        assertFalse(cookie.contains("\$x-enc"), cookie)
    }

    @Test
    @DisplayName("Retour de Xbox en attente : compte, inscription (liste d'attente et V7 sous le gamertag prouvé), session de trente jours, « C'est noté ! »")
    fun xboxRegistration() = withRig { rig ->
        val response = rig.enterXbox()
        assertEquals(HttpStatusCode.SeeOther, response.status)
        assertEquals("/pionniers/etat/", rig.location(response))
        assertEquals("code-de-test", rig.xbox.lastCode)
        assertEquals("https://silooo.test/connexion/xbox/retour/", rig.xbox.lastRedirectUri)
        val session = checkNotNull(rig.setCookie(response, "silooo_session"))
        for (part in listOf("Max-Age=2592000", "Path=/", "Secure", "HttpOnly", "SameSite=Lax")) assertTrue(session.contains(part), "$part manque dans $session")
        assertTrue(checkNotNull(rig.setCookie(response, "silooo_porte")).contains("Expires=Thu, 01 Jan 1970"), "le cookie de départ est effacé")

        val account = checkNotNull(rig.tx { rig.accounts.findByXbox(it, xboxXuid) })
        assertEquals(gamertag, account.gamertag)
        assertEquals(playerXuid, account.playerXuid)
        assertTrue(account.xbox)
        assertFalse(account.discord)
        assertNotNull(rig.tx { rig.waitlist.find(it, account.id) })
        val row = checkNotNull(rig.tx { rig.registrations.find(it, "kr test") })
        assertEquals(gamertag, row.gamertag)
        assertTrue(row.admitted, "la connexion Microsoft admet dès l'inscription (c)")
        assertFalse(row.linked)
        assertEquals(1L, rig.tx { rig.registrations.countPioneers(it) })
        assertEquals("regle:microsoft", rig.tx { rig.beta.find(it, playerXuid) }?.admittedBy)

        val cookie = checkNotNull(rig.cookie(response, "silooo_session"))
        val noted = rig.page(rig.get("/pionniers/etat/", cookie))
        assertTrue(noted.contains("C'est noté !"))
        assertTrue(noted.contains("Ton gamertag <strong>Kr Test</strong> est réservé pour la bêta."), noted)
        assertFalse(noted.contains("/compte/lier/"), "en attente, le compte du jeu est déjà là : rien à relier")
        assertEquals(1, Regex("""class="sa-bouton"""").findAll(noted).count())
        assertOnlySiteScripts(noted, "/pionniers/etat/")

        // Connecté, les portes mènent à l'état ; une seconde connexion ne crée ni compte ni inscription de plus.
        assertEquals("/pionniers/etat/", rig.location(rig.get("/pionniers/", cookie)))
        assertEquals("/pionniers/etat/", rig.location(rig.get("/connexion/", cookie)))
        assertEquals("/pionniers/etat/", rig.location(rig.enterXbox()))
        assertEquals(1L, rig.tx { rig.waitlist.count(it) })
        assertEquals(1L, rig.tx { rig.registrations.countPioneers(it) })
    }

    @Test
    @DisplayName("Admission (c) : une seconde connexion Xbox n'écrit rien de plus ; la date et le chemin d'admission ne bougent pas")
    fun xboxAdmissionIsWrittenOnce() = withRig { rig ->
        rig.loginXbox()
        val first = checkNotNull(rig.tx { rig.beta.find(it, playerXuid) })
        rig.clock.advance(java.time.Duration.ofHours(2))
        rig.loginXbox()
        assertEquals(first, rig.tx { rig.beta.find(it, playerXuid) })
        assertEquals(1, rig.beta.rows.size)
    }

    @Test
    @DisplayName("Admission : inscrit par Discord seul après l'ouverture, sans Discord relié en jeu ni Xbox, personne n'est admis")
    fun discordAloneAfterOpeningIsNotAdmitted() = withRig(SiteMode.OUVERT) { rig ->
        val response = rig.enterDiscord()
        assertEquals("/compte/", rig.location(response))
        assertNotNull(rig.tx { rig.accounts.findByDiscord(it, discordId) })
        assertTrue(rig.beta.rows.isEmpty(), "aucune ligne beta_access")
        assertTrue(rig.registrations.rows.isEmpty(), "aucune inscription V7")
        assertEquals(0L, rig.tx { rig.waitlist.count(it) })
    }

    @Test
    @DisplayName("Admission : inscrit par Discord seul avant l'ouverture, Pionnier de la liste d'attente mais sans gamertag, il n'est pas admis")
    fun discordAloneBeforeOpeningIsNotAdmitted() = withRig { rig ->
        rig.enterDiscord()
        assertEquals(1L, rig.tx { rig.waitlist.count(it) })
        assertTrue(rig.beta.rows.isEmpty())
    }

    @Test
    @DisplayName("Retour de Xbox refusé : mauvais état, cookie absent, refus du fournisseur, compte enfant, sans profil, hors pays, panne")
    fun xboxFailures() = withRig { rig ->
        val (porte, state) = rig.start("/connexion/xbox/")
        assertEquals(HttpStatusCode.BadRequest, rig.back("xbox", null, "0000", porte).status)
        assertEquals(HttpStatusCode.BadRequest, rig.get("/connexion/xbox/retour/?state=$state&code=abc").status)
        val denied = rig.back("xbox", null, state, porte, query = "&error=access_denied")
        assertEquals(HttpStatusCode.BadRequest, denied.status)
        assertTrue(rig.page(denied).contains("La connexion Xbox n'a pas abouti."))
        assertNull(rig.xbox.lastCode, "le fournisseur n'est pas appelé sans état valable")

        val expected = mapOf(
            XboxFailure.CHILD_ACCOUNT to (HttpStatusCode.Forbidden to "Microsoft a dit non"),
            XboxFailure.NO_XBOX_PROFILE to (HttpStatusCode.Forbidden to "n'a pas de profil Xbox"),
            XboxFailure.NOT_IN_COUNTRY to (HttpStatusCode.Forbidden to "n'existe pas dans ton pays"),
            XboxFailure.REFUSED to (HttpStatusCode.BadRequest to "n'a pas abouti"),
            XboxFailure.UNAVAILABLE to (HttpStatusCode.BadGateway to "ne répond pas pour l'instant"),
        )
        for ((failure, outcome) in expected) {
            // Chaque essai est un départ de plus : la minute passe pour rester sous la limite de débit.
            rig.clock.now = rig.clock.now.plus(Duration.ofMinutes(1))
            rig.xbox.failure = failure
            val response = rig.enterXbox()
            assertEquals(outcome.first, response.status, failure.name)
            assertTrue(rig.page(response).contains(outcome.second), failure.name)
            assertNull(rig.cookie(response, "silooo_session"), failure.name)
        }
        assertEquals(0L, rig.tx { rig.waitlist.count(it) })
        rig.clock.now = rig.clock.now.plus(Duration.ofMinutes(1))
        rig.xbox.failure = XboxFailure.CHILD_ACCOUNT
        val child = rig.page(rig.enterXbox())
        assertTrue(child.contains("""<a href="/connexion/xbox/" class="sa-bouton">Réessayer</a>"""), child)
    }

    @Test
    @DisplayName("Un gamertag changé est un joueur neuf : le compte suit le XUID, l'inscription V7 est écrite sous le nouveau gamertag")
    fun gamertagChange() = withRig { rig ->
        rig.loginXbox()
        rig.xbox.profile = XboxProfile(xboxXuid, "Kr Neuf")
        rig.loginXbox()
        val account = checkNotNull(rig.tx { rig.accounts.findByXbox(it, xboxXuid) })
        assertEquals("Kr Neuf", account.gamertag)
        assertEquals(Empreinte.duGamertag("Kr Neuf"), account.playerXuid)
        assertNotNull(rig.tx { rig.registrations.find(it, "kr neuf") })
        assertEquals(1L, rig.tx { rig.waitlist.count(it) }, "une seule inscription pour ce compte")
    }

    @Test
    @DisplayName("Un gamertag déjà à un autre compte du site : 409, pas de session")
    fun gamertagTaken() = withRig { rig ->
        rig.loginXbox()
        rig.xbox.profile = XboxProfile("2535400000000002", gamertag)
        val response = rig.enterXbox()
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(rig.page(response).contains("Ce gamertag est déjà relié à un autre compte du site."))
        assertNull(rig.cookie(response, "silooo_session"))
    }

    @Test
    @DisplayName("Retour de Discord en attente : compte sans gamertag, liste d'attente sans V7, le bot fait entrer si la case est cochée, « C'est noté ! » sans lien à relier")
    fun discordRegistration() = withRig { rig ->
        val plain = rig.get("/connexion/discord/")
        assertTrue(rig.location(plain).contains("join=false"))
        val joined = rig.get("/connexion/discord/?rejoindre=oui")
        assertTrue(rig.location(joined).contains("join=true"))
        assertTrue(rig.location(joined).contains("redirect_uri=https://silooo.test/connexion/discord/retour/"))

        val response = rig.enterDiscord(join = true)
        assertEquals("/pionniers/etat/", rig.location(response))
        val cookie = rig.session(response)
        val account = checkNotNull(rig.tx { rig.accounts.findByDiscord(it, discordId) })
        assertNull(account.gamertag)
        assertTrue(account.discord)
        assertFalse(account.xbox)
        assertNotNull(rig.tx { rig.waitlist.find(it, account.id) })
        assertEquals(0L, rig.tx { rig.registrations.countPioneers(it) }, "sans gamertag, rien dans V7")
        assertEquals(listOf(discordId), rig.joiner.joined)

        val noted = rig.page(rig.get("/pionniers/etat/", cookie))
        assertTrue(noted.contains("Le bot te prévient le jour de l'ouverture."))
        assertTrue(noted.contains("Tu relieras ton compte du jeu à l'ouverture de la bêta."))
        assertFalse(noted.contains("/compte/lier/"), "en attente, la page du compte n'existe pas")

        // Sans la case, le bot n'est pas appelé ; son refus ne gêne pas la connexion.
        rig.clock.now = rig.clock.now.plus(Duration.ofMinutes(1))
        rig.discord.userId = "413000000000000002"
        rig.loginDiscord(join = false)
        assertEquals(1, rig.joiner.joined.size)
        rig.joiner.failing = true
        rig.discord.userId = "413000000000000003"
        assertEquals("/pionniers/etat/", rig.location(rig.enterDiscord(join = true)))
    }

    @Test
    @DisplayName("Discord qui échoue : 502 et pas de session ; mauvais état : 400")
    fun discordFailures() = withRig { rig ->
        rig.discord.failing = true
        val response = rig.enterDiscord()
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertTrue(rig.page(response).contains("Discord ne répond pas pour l'instant."))
        assertNull(rig.cookie(response, "silooo_session"))
        rig.discord.failing = false
        val (porte, _) = rig.start("/connexion/discord/")
        assertEquals(HttpStatusCode.BadRequest, rig.back("discord", null, "0000", porte).status)
        // Un départ Xbox ne vaut pas pour un retour Discord.
        val (xboxPorte, xboxState) = rig.start("/connexion/xbox/")
        assertEquals(HttpStatusCode.BadRequest, rig.back("discord", null, xboxState, xboxPorte).status)
    }

    @Test
    @DisplayName("Connecté par Xbox, relier Discord : le compte et l'inscription V7 sont reliés ; l'admission reste celle de Microsoft ; un Discord pris : 409")
    fun linkDiscordToXboxAccount() = withRig { rig ->
        val session = rig.loginXbox()
        val response = rig.enterDiscord(join = false, session = session)
        assertEquals("/pionniers/etat/", rig.location(response))
        val account = checkNotNull(rig.tx { rig.accounts.findByXbox(it, xboxXuid) })
        assertTrue(account.discord)
        assertEquals(account.id, rig.tx { rig.accounts.findByDiscord(it, discordId) }?.id)
        val row = checkNotNull(rig.tx { rig.registrations.find(it, "kr test") })
        assertTrue(row.linked)
        assertTrue(row.admitted)
        assertEquals("regle:microsoft", rig.tx { rig.beta.find(it, playerXuid) }?.admittedBy, "le Discord posé sur le site n'admet pas")

        // Un autre compte Xbox qui relie le même Discord est refusé.
        rig.xbox.profile = XboxProfile("2535400000000002", "Autre Joueur")
        val other = rig.loginXbox()
        val taken = rig.enterDiscord(session = other)
        assertEquals(HttpStatusCode.Conflict, taken.status)
        assertTrue(rig.page(taken).contains("Ce compte Discord est déjà relié à un autre joueur."))
        assertFalse(checkNotNull(rig.tx { rig.accounts.findByXbox(it, "2535400000000002") }).discord)
    }

    @Test
    @DisplayName("Bêta : « Mon compte » après Xbox (gamertag, Discord à relier, Pionnier), /compte/lier/ dit que le jeu est connu")
    fun betaAccountAfterXbox() = withRig(SiteMode.BETA) { rig ->
        val response = rig.enterXbox()
        assertEquals("/pionniers/etat/", rig.location(response), "la première connexion est une inscription")
        val session = rig.session(response)
        assertEquals("/compte/", rig.location(rig.enterXbox()), "les suivantes mènent au compte")
        assertEquals("/compte/", rig.location(rig.get("/connexion/", session)))

        val compte = rig.page(rig.get("/compte/", session))
        assertTrue(compte.contains("Kr Test"))
        assertTrue(compte.contains("Pas relié"))
        assertTrue(compte.contains("Pionnier"))
        assertTrue(compte.contains("""<a href="/connexion/discord/" class="sa-bouton">Relier Discord</a>"""), compte)
        assertTrue(compte.contains("""action="/deconnexion/""""))
        assertFalse(compte.contains("/compte/lier/"), "le compte du jeu est déjà relié")
        assertEquals(1, Regex("""class="sa-bouton"""").findAll(compte).count())
        assertOnlySiteScripts(compte, "/compte/")
        assertEquals(HttpStatusCode.MovedPermanently, rig.get("/compte", session).status)

        val lier = rig.page(rig.get("/compte/lier/", session))
        assertTrue(lier.contains("Tu es connecté avec Xbox."))
        assertTrue(lier.contains("Kr Test"))
        assertFalse(lier.contains("Nouveau code"))
        val account = checkNotNull(rig.tx { rig.accounts.findByXbox(it, xboxXuid) })
        assertNull(rig.tx { rig.links.findByAccount(it, account.id) }, "pas de code pour un compte Xbox")
    }

    @Test
    @DisplayName("Bêta : après Discord, /compte/lier/ montre un code de six caractères valable dix minutes ; « Nouveau code » le remplace ; le jeu le consomme ; « Délier » défait")
    fun betaLinkCodeAfterDiscord() = withRig(SiteMode.BETA) { rig ->
        val session = rig.loginDiscord()
        val accountId = checkNotNull(rig.tx { rig.accounts.findByDiscord(it, discordId) }).id
        val compte = rig.page(rig.get("/compte/", session))
        assertTrue(compte.contains("Pas encore relié"))
        assertTrue(compte.contains("""<a href="/compte/lier/" class="sa-bouton sa-bouton--fonte">Relier mon compte du jeu</a>"""), compte)
        assertTrue(compte.contains("Relié"), "le Discord est relié")

        val lier = rig.page(rig.get("/compte/lier/", session))
        val code = Regex("""aria-label="Code (([A-Z2-9] ?){6})"""").find(lier)?.groupValues?.get(1)?.replace(" ", "") ?: error("pas de code dans la page")
        assertEquals(6, code.length)
        assertTrue(code.all { it in LinkCodes.ALPHABET }, code)
        assertTrue(lier.contains("Valable encore 10 min."), lier)
        assertTrue(lier.contains("En attente du jeu"))
        assertEquals(0, Regex("""class="sa-bouton"""").findAll(lier).count(), "la page du code n'a pas de bouton rouge")
        assertOnlySiteScripts(lier, "/compte/lier/")

        // Le même code tant qu'il vit, puis la minute qui passe se lit.
        rig.clock.now = rig.clock.now.plus(Duration.ofSeconds(19))
        val again = rig.page(rig.get("/compte/lier/", session))
        assertTrue(again.contains(code.toList().joinToString(" ")))
        assertTrue(again.contains("Valable encore 9 min 41 s."), again)

        // « Nouveau code » : il faut le jeton ; l'ancien code disparaît.
        assertEquals(HttpStatusCode.BadRequest, rig.post("/compte/lier/", session, "jeton" to "faux").status)
        val csrf = rig.csrf(session, "/compte/lier/")
        val renewed = rig.post("/compte/lier/", session, "jeton" to csrf)
        assertEquals(HttpStatusCode.SeeOther, renewed.status)
        assertEquals("/compte/lier/", rig.location(renewed))
        assertNull(rig.tx { rig.links.findByCode(it, code) })
        val fresh = checkNotNull(rig.tx { rig.links.findByAccount(it, accountId) })
        assertNotEquals(code, fresh.code)

        // Un code expiré est remplacé à la visite suivante.
        rig.clock.now = rig.clock.now.plus(Duration.ofMinutes(11))
        rig.page(rig.get("/compte/lier/", session))
        val replaced = checkNotNull(rig.tx { rig.links.findByAccount(it, accountId) })
        assertNotEquals(fresh.code, replaced.code)

        // Le jeu consomme le code (la route du contrat viendra avec la direction du jeu) : la page dit « Relié ! ».
        val links = DiscordLinks(rig.accounts, rig.links, rig.registrations, rig.clock)
        val outcome = rig.tx { links.redeem(it, replaced.code.lowercase(), gamertag, admit = true, pioneer = false) }
        assertTrue(outcome is RedeemOutcome.Linked, outcome.toString())
        val linked = rig.page(rig.get("/compte/lier/", session))
        assertTrue(linked.contains("Relié !"))
        assertTrue(linked.contains("Kr Test"))
        assertTrue(linked.contains("""action="/compte/lier/delier/""""))
        assertEquals(1, Regex("""class="sa-bouton"""").findAll(linked).count())
        assertTrue(checkNotNull(rig.tx { rig.registrations.find(it, "kr test") }).linked)

        // « Délier » : le compte du jeu part, l'inscription V7 n'a plus de Discord, la page propose un code neuf.
        val unlinked = rig.post("/compte/lier/delier/", session, "jeton" to csrf)
        assertEquals("/compte/lier/", rig.location(unlinked))
        assertNull(checkNotNull(rig.tx { rig.accounts.find(it, accountId) }).gamertag)
        assertFalse(checkNotNull(rig.tx { rig.registrations.find(it, "kr test") }).linked)
        assertTrue(rig.page(rig.get("/compte/lier/", session)).contains("En attente du jeu"))
    }

    @Test
    @DisplayName("Déconnexion : avec le jeton, la session meurt et le cookie est effacé ; sans jeton, 400 ; sans session, retour à la connexion")
    fun logout() = withRig(SiteMode.BETA) { rig ->
        val session = rig.loginXbox()
        assertEquals(HttpStatusCode.BadRequest, rig.post("/deconnexion/", session, "jeton" to "faux").status)
        val csrf = rig.csrf(session)
        val response = rig.post("/deconnexion/", session, "jeton" to csrf)
        assertEquals(HttpStatusCode.SeeOther, response.status)
        assertEquals("/", rig.location(response))
        assertTrue(checkNotNull(rig.setCookie(response, "silooo_session")).contains("Expires=Thu, 01 Jan 1970"))
        assertEquals("/connexion/", rig.location(rig.get("/compte/", session)))
        assertEquals("/connexion/", rig.location(rig.post("/deconnexion/", null, "jeton" to csrf)))
    }

    @Test
    @DisplayName("Sans session : /compte/ et /compte/lier/ mènent à la connexion, /pionniers/etat/ aux pionniers ; une session de plus de trente jours ne vaut plus")
    fun sessionRequired() = withRig(SiteMode.BETA) { rig ->
        assertEquals("/connexion/", rig.location(rig.get("/compte/")))
        assertEquals("/connexion/", rig.location(rig.get("/compte/lier/")))
        assertEquals("/pionniers/", rig.location(rig.get("/pionniers/etat/")))
        assertEquals("/connexion/", rig.location(rig.get("/compte/", "silooo_session=n-importe-quoi")))
        val session = rig.loginXbox()
        assertEquals(HttpStatusCode.OK, rig.get("/compte/", session).status)
        rig.clock.now = rig.clock.now.plus(Duration.ofDays(31))
        assertEquals("/connexion/", rig.location(rig.get("/compte/", session)))
    }

    @Test
    @DisplayName("Ouvert : plus d'inscription (/pionniers/ mène à la connexion, l'état au compte), une connexion Xbox n'écrit pas de liste d'attente ni de Pionnier, mais admet (c)")
    fun openSiteDoesNotRegister() = withRig(SiteMode.OUVERT) { rig ->
        assertEquals(HttpStatusCode.MovedPermanently, rig.get("/pionniers/").status)
        assertEquals("/connexion/", rig.location(rig.get("/pionniers/")))
        assertEquals("/compte/", rig.location(rig.get("/pionniers/etat/")))
        val response = rig.enterXbox()
        assertEquals("/compte/", rig.location(response))
        val session = rig.session(response)
        assertEquals(0L, rig.tx { rig.waitlist.count(it) })
        assertEquals(0L, rig.tx { rig.registrations.countPioneers(it) })
        assertTrue(checkNotNull(rig.tx { rig.registrations.find(it, "kr test") }).admitted)
        assertEquals("regle:microsoft", rig.tx { rig.beta.find(it, playerXuid) }?.admittedBy)
        val compte = rig.page(rig.get("/compte/", session))
        assertFalse(compte.contains("Pionnier"))
        assertEquals("/compte/", rig.location(rig.enterDiscord(session = session)))
        assertTrue(rig.page(rig.get("/compte/", session)).contains("Relié"))
    }

    @Test
    @DisplayName("Cinq départs par minute et par adresse ; derrière un mandataire de confiance, l'adresse est celle de X-Forwarded-For")
    fun rateLimit() {
        withRig { rig ->
            repeat(5) { assertEquals(HttpStatusCode.SeeOther, rig.get("/connexion/xbox/").status) }
            val blocked = rig.get("/connexion/discord/")
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
            assertEquals("60", blocked.headers[HttpHeaders.RetryAfter])
            assertTrue(rig.page(blocked).contains("Trop d'essais."))
            rig.clock.now = rig.clock.now.plus(Duration.ofMinutes(1))
            assertEquals(HttpStatusCode.SeeOther, rig.get("/connexion/xbox/").status)
        }
        withRig(trustedProxy = true) { rig ->
            repeat(5) { rig.get("/connexion/xbox/", forwarded = "10.0.0.1") }
            assertEquals(HttpStatusCode.TooManyRequests, rig.get("/connexion/xbox/", forwarded = "10.0.0.1").status)
            assertEquals(HttpStatusCode.SeeOther, rig.get("/connexion/xbox/", forwarded = "10.0.0.2").status)
        }
    }

    @Test
    @DisplayName("Aucune empreinte de session dans une page, et la base ne garde pas le jeton du cookie")
    fun sessionTokenNeverStored() = withRig(SiteMode.BETA) { rig ->
        val session = rig.loginXbox()
        val token = session.substringAfter('=')
        val stored = rig.tx { rig.sessions.find(it, token, rig.clock.instant()) }
        assertNull(stored, "la base ne connaît que l'empreinte du jeton")
        val compte = rig.page(rig.get("/compte/", session))
        assertFalse(compte.contains(token))
    }
}
