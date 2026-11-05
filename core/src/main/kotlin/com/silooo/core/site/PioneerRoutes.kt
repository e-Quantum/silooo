package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.registration.AdmissionMoment
import com.silooo.core.registration.LinkOutcome
import com.silooo.core.registration.SiteAdmission
import com.silooo.core.registration.SiteRegistration
import com.silooo.core.registration.SiteRegistrations
import com.silooo.core.site.pages.PioneerFormView
import com.silooo.core.site.pages.PioneerMessage
import com.silooo.core.site.pages.PioneerStateView
import com.silooo.core.site.pages.SiteShell
import com.silooo.core.site.pages.pionniers
import com.silooo.core.site.pages.pionniersEtat
import com.silooo.core.site.pages.pionniersMessage
import com.silooo.core.site.pages.problemText
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.html.respondHtml
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveParameters
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CancellationException

/** Le nom du contenu qui porte le texte de `/pionniers/` : ses routes sont écrites ici, pas dans `SiteRoutes`. */
internal const val PIONEERS_PAGE = "pionniers"

private const val COOKIE_NAME = "silooo_pionnier"
private const val COOKIE_PATH = "/pionniers/"
private const val STATE_URL = "/pionniers/etat/"
private const val RETURN_PATH = "/pionniers/discord/retour/"
private const val MAX_FORM_BYTES = 2048L
private const val FORWARDED_FOR = "X-Forwarded-For"
private const val MAX_ADDRESS_LENGTH = 64

/** Le nombre de pionniers inscrits, relu dans la base au plus une fois par minute. */
private class CachedCount(private val clock: Clock) {
    private var value: Long? = null
    private var readAt = 0L

    @Synchronized
    fun cached(): Long? = value?.takeIf { clock.millis() - readAt < Duration.ofMinutes(1).toMillis() }

    @Synchronized
    fun forget() {
        value = null
    }

    @Synchronized
    fun keep(count: Long) {
        value = count
        readAt = clock.millis()
    }
}

/**
 * Les pages des pionniers : l'inscription, son état, « Relier Discord » et son retour. Rendues dans les deux modes. Le
 * site n'écrit que `site_registrations`. Aucun identifiant Discord n'entre dans une page, un journal ou un message.
 */
internal fun Route.pioneerRoutes(dependencies: Dependencies, shell: SiteShell, page: ContentPage, rulesUrl: String) {
    val signer = SiteSigner(dependencies.siteSessionSecret, dependencies.clock)
    val limiter = RateLimiter(dependencies.clock)
    val store = dependencies.siteRegistrationStore
    val counter = CachedCount(dependencies.clock)
    val secure = dependencies.siteUrl.startsWith("https://")
    val discord = dependencies.discordIdentity
    val redirectUri = shell.siteUrl + RETURN_PATH

    suspend fun pioneerCount(call: ApplicationCall): Long? {
        counter.cached()?.let { return it }
        return try {
            dependencies.database.transaction { store.countPioneers(it) }.also(counter::keep)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Le compteur est un plus : sans lui le formulaire reste utilisable.
            call.application.log.warn("Compteur des pionniers illisible (${e.javaClass.simpleName})")
            null
        }
    }

    fun form(token: String, count: Long?, gamertag: String = "", error: String? = null) =
        PioneerFormView(token, rulesUrl, count, gamertag, error)

    fun stateView(registration: SiteRegistration, already: Boolean) = PioneerStateView(
        registration = registration,
        already = already,
        discordButton = discord != null && !registration.linked,
        discordPending = discord == null && !registration.linked && dependencies.siteAdmission == SiteAdmission.DISCORD && !registration.admitted,
    )

    fun ApplicationCall.setSession(session: PioneerSession) {
        response.cookies.append(
            Cookie(
                name = COOKIE_NAME,
                value = signer.sessionCookie(session),
                maxAge = SiteSigner.SESSION_LIFETIME.seconds.toInt(),
                path = COOKIE_PATH,
                secure = secure,
                httpOnly = true,
                extensions = mapOf("SameSite" to "Lax"),
            ),
        )
    }

    fun ApplicationCall.session(): PioneerSession? = signer.readSession(request.cookies[COOKIE_NAME])

    suspend fun ApplicationCall.see(location: String) {
        response.header(HttpHeaders.Location, location)
        respond(HttpStatusCode.SeeOther)
    }

    fun ApplicationCall.visitor(): String {
        if (dependencies.siteTrustedProxy) {
            // Le dernier maillon est celui que le mandataire de confiance a posé ; les précédents viennent du client.
            request.headers[FORWARDED_FOR]?.split(',')?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }?.let { return it.take(MAX_ADDRESS_LENGTH) }
        }
        return request.origin.remoteAddress
    }

    suspend fun ApplicationCall.refuse(status: HttpStatusCode, message: PioneerMessage) =
        respondHtml(status) { pionniersMessage(shell, message) }

    get(page.url) {
        val count = pioneerCount(call)
        call.respondHtml(HttpStatusCode.OK) { pionniers(shell, page, form(signer.formToken(), count)) }
    }
    get(page.url.trimEnd('/')) { call.response.header(HttpHeaders.Location, page.url); call.respond(HttpStatusCode.MovedPermanently) }

    post(page.url) {
        if (!limiter.allow(call.visitor())) {
            call.response.header(HttpHeaders.RetryAfter, "60")
            return@post call.refuse(HttpStatusCode.TooManyRequests, PioneerMessage.TOO_MANY)
        }
        if ((call.request.contentLength() ?: 0L) > MAX_FORM_BYTES) return@post call.refuse(HttpStatusCode.PayloadTooLarge, PioneerMessage.TOO_BIG)
        val fields = call.receiveParameters()
        val typed = fields["gamertag"].orEmpty()
        suspend fun again(error: String): Unit {
            val count = pioneerCount(call)
            call.respondHtml(HttpStatusCode.BadRequest) { pionniers(shell, page, form(signer.formToken(), count, typed.take(64), error)) }
        }

        if (!signer.isValidFormToken(fields["jeton"])) return@post again(PioneerMessage.FORM_EXPIRED.text)
        SiteRegistrations.problem(typed)?.let { return@post again(it.problemText()) }
        if (fields["regles"] != "oui") return@post again(PioneerMessage.RULES_UNCHECKED.text)

        val key = SiteRegistrations.key(typed)
        val admitted = dependencies.siteAdmission.admitsAt(AdmissionMoment.REGISTRATION)
        val (inserted, registration) = dependencies.database.transaction { connection ->
            val inserted = store.insert(connection, key, typed, admitted)
            inserted to checkNotNull(store.find(connection, key)) { "inscription introuvable après l'écriture" }
        }
        if (inserted) {
            counter.forget()
            call.setSession(PioneerSession(key))
            return@post call.see(STATE_URL)
        }
        // Déjà inscrit : la même page d'état, sans erreur. Une inscription dont le compte Discord est déjà relié n'a plus
        // rien à faire : elle ne rend pas de cookie à qui retape le gamertag.
        if (!registration.linked) call.setSession(PioneerSession(key))
        call.respondHtml(HttpStatusCode.OK) { pionniersEtat(shell, page, stateView(registration, already = true)) }
    }

    get(STATE_URL) {
        val session = call.session() ?: return@get call.see(page.url)
        val registration = dependencies.database.transaction { store.find(it, session.key) } ?: return@get call.see(page.url)
        call.respondHtml(HttpStatusCode.OK) { pionniersEtat(shell, page, stateView(registration, already = false)) }
    }

    if (discord == null) return

    get("/pionniers/discord/") {
        val session = call.session() ?: return@get call.see(page.url)
        val registration = dependencies.database.transaction { store.find(it, session.key) } ?: return@get call.see(page.url)
        if (registration.linked) return@get call.see(STATE_URL)
        val state = signer.newState()
        call.setSession(PioneerSession(session.key, state))
        call.see(discord.authorizeUrl(state, redirectUri))
    }

    get(RETURN_PATH) {
        val session = call.session() ?: return@get call.see(page.url)
        val expected = session.state
        val parameters = call.request.queryParameters
        // L'état est à usage unique : le cookie le perd, que le retour réussisse ou non.
        call.setSession(PioneerSession(session.key))
        if (expected == null || !signer.sameText(parameters["state"], expected)) {
            return@get call.refuse(HttpStatusCode.BadRequest, PioneerMessage.DISCORD_FAILED)
        }
        val code = parameters["code"]
        if (parameters["error"] != null || code.isNullOrBlank() || code.length > 512) {
            return@get call.refuse(HttpStatusCode.BadRequest, PioneerMessage.DISCORD_FAILED)
        }
        val userId = try {
            discord.userId(code, redirectUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ni le code, ni l'identifiant, ni la cause : seulement la sorte d'erreur.
            call.application.log.warn("Relier Discord : le fournisseur a échoué (${e.javaClass.simpleName})")
            return@get call.refuse(HttpStatusCode.BadGateway, PioneerMessage.DISCORD_DOWN)
        }
        val admit = dependencies.siteAdmission.admitsAt(AdmissionMoment.DISCORD_LINK)
        when (dependencies.database.transaction { store.link(it, session.key, userId, admit) }) {
            LinkOutcome.LINKED, LinkOutcome.ALREADY_LINKED -> call.see(STATE_URL)
            LinkOutcome.DISCORD_TAKEN -> call.refuse(HttpStatusCode.Conflict, PioneerMessage.DISCORD_TAKEN)
            LinkOutcome.NOT_REGISTERED -> call.see(page.url)
        }
    }
}
