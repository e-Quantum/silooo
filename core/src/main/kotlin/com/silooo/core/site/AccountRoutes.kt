package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.registration.AdmissionMoment
import com.silooo.core.registration.DiscordLinks
import com.silooo.core.registration.DiscordUser
import com.silooo.core.registration.LinkOutcome
import com.silooo.core.registration.PlayerOutcome
import com.silooo.core.registration.SiteRegistrations
import com.silooo.core.registration.WebAccount
import com.silooo.core.registration.WebSession
import com.silooo.core.registration.WebSessions
import com.silooo.core.registration.XboxException
import com.silooo.core.registration.XboxFailure
import com.silooo.core.registration.XboxProfile
import com.silooo.core.site.pages.AccountMessage
import com.silooo.core.site.pages.AccountUrls
import com.silooo.core.site.pages.CompteView
import com.silooo.core.site.pages.ConnexionView
import com.silooo.core.site.pages.InscritView
import com.silooo.core.site.pages.LierKind
import com.silooo.core.site.pages.LierView
import com.silooo.core.site.pages.SiteShell
import com.silooo.core.site.pages.accountMessage
import com.silooo.core.site.pages.compte
import com.silooo.core.site.pages.connexion
import com.silooo.core.site.pages.inscrit
import com.silooo.core.site.pages.lier
import com.silooo.core.site.pages.microsoftRefused
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.renderSetCookieHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.date.GMTDate
import java.sql.Connection
import java.time.Duration
import kotlinx.coroutines.CancellationException

/** Les noms des contenus dont les routes sont écrites ici, pas dans `SiteRoutes`. */
internal const val CONNEXION_PAGE = "connexion"
internal const val PIONEERS_PAGE = "pionniers"
internal const val ACCOUNT_PAGE = "compte"

/** Le cookie de session : un jeton aléatoire, dont `web_sessions` ne garde que l'empreinte. Sur tout le site. */
internal const val SESSION_COOKIE = "silooo_session"

/** Le cookie d'une connexion en cours (l'`state` OAuth2), sur `/connexion/` seulement, dix minutes. */
internal const val START_COOKIE = "silooo_porte"
private const val START_COOKIE_PATH = "/connexion/"
private const val MAX_CODE_LENGTH = 512
private const val MAX_TOKEN_LENGTH = 128
private const val MAX_FORM_BYTES = 2048L
private const val FORWARDED_FOR = "X-Forwarded-For"
private const val MAX_ADDRESS_LENGTH = 64

/** Un visiteur connecté : sa session et son compte. */
private class Signed(val session: WebSession, val account: WebAccount)

/**
 * Les comptes du site (kit « Les comptes ») : les deux portes (`/connexion/xbox/`, `/connexion/discord/`) et leurs
 * retours, la connexion, l'inscription d'avant l'ouverture (`/pionniers/`, « C'est noté ! »), « Mon compte »,
 * « Lier mon compte » et la déconnexion. Le site écrit `web_accounts`, `waitlist`, `discord_links`, `web_sessions`,
 * `beta_access` (par la règle automatique) et `site_registrations` (V7, que le jeu et le bot lisent) dès qu'un gamertag
 * est connu. Aucun identifiant Discord ni XUID Xbox n'entre dans une page, un journal ou un message.
 */
internal fun Route.accountRoutes(dependencies: Dependencies, shell: SiteShell, pages: List<ContentPage>) {
    val signer = SiteSigner(dependencies.siteSessionSecret, dependencies.clock)
    val limiter = RateLimiter(dependencies.clock)
    val clock = dependencies.clock
    val accounts = dependencies.webAccountStore
    val waitlist = dependencies.waitlistStore
    val sessions = dependencies.webSessionStore
    val registrations = dependencies.siteRegistrationStore
    val betaAccess = dependencies.betaAccessStore
    val links = DiscordLinks(accounts, dependencies.discordLinkStore, registrations, clock)
    val secure = dependencies.siteUrl.startsWith("https://")
    val xbox = dependencies.xboxIdentity
    val discord = dependencies.discordIdentity
    val xboxRedirect = shell.siteUrl + AccountUrls.XBOX_RETURN
    val discordRedirect = shell.siteUrl + AccountUrls.DISCORD_RETURN
    // Sans contenu « connexion » (un test au contenu réduit), il n'y a pas de comptes.
    val connexionPage = pages.firstOrNull { it.name == CONNEXION_PAGE } ?: return
    val pioneersPage = pages.firstOrNull { it.name == PIONEERS_PAGE }
    val accountPage = pages.firstOrNull { it.name == ACCOUNT_PAGE }

    /** La liste d'attente est ouverte tant que le site n'est pas ouvert : une première connexion y inscrit. */
    val waitlistOpen = dependencies.siteMode != SiteMode.OUVERT

    /** Où mène un visiteur déjà connecté : son compte quand la page existe dans ce mode, sinon son inscription. */
    val accountHome = if (accountPage != null) AccountUrls.COMPTE else AccountUrls.INSCRIT

    fun landing(justRegistered: Boolean): String = if (justRegistered || accountPage == null) AccountUrls.INSCRIT else AccountUrls.COMPTE

    suspend fun ApplicationCall.see(location: String) {
        response.header(HttpHeaders.Location, location)
        respond(HttpStatusCode.SeeOther)
    }

    suspend fun ApplicationCall.moved(location: String) {
        response.header(HttpHeaders.Location, location)
        respond(HttpStatusCode.MovedPermanently)
    }

    fun ApplicationCall.visitor(): String {
        if (dependencies.siteTrustedProxy) {
            // Le dernier maillon est celui que le mandataire de confiance a posé ; les précédents viennent du client.
            request.headers[FORWARDED_FOR]?.split(',')?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }?.let { return it.take(MAX_ADDRESS_LENGTH) }
        }
        return request.origin.remoteAddress
    }

    suspend fun ApplicationCall.refuse(status: HttpStatusCode, message: AccountMessage) =
        respondHtml(status) { accountMessage(shell, message) }

    // Les valeurs sont en base64url ou en hexadécimal : rien à encoder, et pas de suffixe d'encodage (`$x-enc`) dans l'en-tête.
    fun ApplicationCall.cookie(name: String, value: String, path: String, maxAge: Int?, expires: GMTDate?) {
        val header = renderSetCookieHeader(
            name, value, CookieEncoding.RAW, maxAge = maxAge, expires = expires, path = path, secure = secure, httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"), includeEncoding = false,
        )
        response.headers.append(HttpHeaders.SetCookie, header)
    }

    fun ApplicationCall.setCookie(name: String, value: String, path: String, maxAge: Duration) = cookie(name, value, path, maxAge.seconds.toInt(), null)

    fun ApplicationCall.clearCookie(name: String, path: String) = cookie(name, "", path, null, GMTDate.START)

    /** Le visiteur connecté, d'après son cookie de session ; `null` sans session vivante. */
    suspend fun ApplicationCall.signed(): Signed? {
        val token = request.cookies[SESSION_COOKIE]?.takeIf { it.isNotEmpty() && it.length <= MAX_TOKEN_LENGTH } ?: return null
        val hash = signer.hash(token)
        val now = clock.instant()
        return dependencies.database.transaction { connection ->
            val session = sessions.find(connection, hash, now) ?: return@transaction null
            val account = accounts.find(connection, session.accountId) ?: return@transaction null
            Signed(session, account)
        }
    }

    /** Ouvre une session pour le compte, dans la transaction de l'appelant ; rend le jeton à poser dans le cookie. */
    fun openSession(connection: Connection, accountId: Long): String {
        val token = signer.newToken()
        val now = clock.instant()
        sessions.create(connection, WebSession(signer.hash(token), accountId, signer.newCsrf(), now, now.plus(WebSessions.LIFETIME)))
        // Le ménage des sessions et des codes expirés se fait ici, à chaque connexion : pas de minuteur de plus.
        sessions.purge(connection, now)
        dependencies.discordLinkStore.purge(connection, now)
        return token
    }

    fun ApplicationCall.setSession(token: String) = setCookie(SESSION_COOKIE, token, "/", WebSessions.LIFETIME)

    /** `true` si le formulaire porte le jeton anti-CSRF de la session ; sinon l'appelant refuse. */
    suspend fun ApplicationCall.formOk(signed: Signed): Boolean {
        if ((request.contentLength() ?: 0L) > MAX_FORM_BYTES) return false
        return signer.sameText(receiveParameters()["jeton"], signed.session.csrf)
    }

    /**
     * La règle automatique `SILOOO_SITE_ADMISSION`, tant que l'admin n'existe pas (R18) : elle dit si `admitted_at` (V7) se
     * pose à ce moment, et pose alors aussi `beta_access`, que le jeu lira en mode bêta.
     */
    fun admitByRule(connection: Connection, moment: AdmissionMoment, playerXuid: String): Boolean {
        val admit = dependencies.siteAdmission.admitsAt(moment)
        if (admit) betaAccess.admit(connection, playerXuid, "regle:${dependencies.siteAdmission.word}", clock.instant())
        return admit
    }

    /** Le départ vers une porte : un `state` neuf dans le cookie de connexion, puis l'adresse d'autorisation. */
    suspend fun ApplicationCall.start(door: Char, joinGuild: Boolean, authorize: (state: String) -> String) {
        if (!limiter.allow(visitor())) {
            response.header(HttpHeaders.RetryAfter, "60")
            return refuse(HttpStatusCode.TooManyRequests, AccountMessage.TOO_MANY)
        }
        val state = signer.newState()
        setCookie(START_COOKIE, signer.startCookie(OAuthStart(state, door, joinGuild)), START_COOKIE_PATH, SiteSigner.START_LIFETIME)
        see(authorize(state))
    }

    /** Le retour d'une porte : le départ attendu et le code, ou `null` quand la réponse est déjà partie. Le cookie du départ est effacé. */
    suspend fun ApplicationCall.returned(door: Char, failed: AccountMessage): Pair<OAuthStart, String>? {
        val start = signer.readStart(request.cookies[START_COOKIE])
        clearCookie(START_COOKIE, START_COOKIE_PATH)
        val parameters = request.queryParameters
        if (start == null || start.door != door || !signer.sameText(parameters["state"], start.state)) {
            refuse(HttpStatusCode.BadRequest, failed)
            return null
        }
        val code = parameters["code"]
        if (parameters["error"] != null || code.isNullOrBlank() || code.length > MAX_CODE_LENGTH) {
            refuse(HttpStatusCode.BadRequest, failed)
            return null
        }
        return start to code
    }

    /** Fait entrer le compte sur le serveur Discord si la case était cochée et que le bot est branché ; un refus ne gêne pas la connexion. */
    suspend fun ApplicationCall.joinGuild(start: OAuthStart, user: DiscordUser) {
        val joiner = dependencies.guildJoiner
        if (!start.joinGuild || joiner == null) return
        try {
            joiner.join(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            application.log.warn("Rejoindre le Discord : refusé (${e.javaClass.simpleName})")
        }
    }

    /**
     * Le compte d'un profil Xbox : celui qui porte ce XUID (le gamertag suit s'il a changé : un gamertag changé est un joueur
     * neuf, décision 25), ou celui entré par Discord dont le jeu porte déjà ce gamertag (Xbox le prouve), ou un compte neuf.
     * `null` : l'empreinte est à un autre compte. Le second élément dit si le gamertag vient de changer.
     */
    fun xboxAccount(connection: Connection, profile: XboxProfile, playerXuid: String): Pair<WebAccount, Boolean>? {
        val now = clock.instant()
        val byXbox = accounts.findByXbox(connection, profile.xuid)
        if (byXbox != null) {
            if (byXbox.gamertag == profile.gamertag && byXbox.playerXuid == playerXuid) return byXbox to false
            return when (accounts.setPlayer(connection, byXbox.id, profile.gamertag, playerXuid)) {
                PlayerOutcome.SET -> byXbox.copy(gamertag = profile.gamertag, playerXuid = playerXuid) to true
                PlayerOutcome.PLAYER_TAKEN, PlayerOutcome.NOT_FOUND -> null
            }
        }
        val byPlayer = accounts.findByPlayer(connection, playerXuid)
        if (byPlayer != null) {
            return if (!byPlayer.xbox && accounts.attachXbox(connection, byPlayer.id, profile.xuid)) byPlayer.copy(xbox = true) to false else null
        }
        return accounts.createXbox(connection, profile.xuid, profile.gamertag, playerXuid, now) to false
    }

    get(AccountUrls.CONNEXION) {
        if (call.signed() != null) return@get call.see(accountHome)
        call.respondHtml(HttpStatusCode.OK) { connexion(shell, connexionPage, ConnexionView(xbox != null, discord != null, inscription = false)) }
    }
    get(AccountUrls.CONNEXION.trimEnd('/')) { call.moved(AccountUrls.CONNEXION) }

    if (pioneersPage != null) {
        get(AccountUrls.PIONNIERS) {
            if (call.signed() != null) return@get call.see(AccountUrls.INSCRIT)
            call.respondHtml(HttpStatusCode.OK) { connexion(shell, pioneersPage, ConnexionView(xbox != null, discord != null, inscription = true)) }
        }
        get(AccountUrls.INSCRIT) {
            val signed = call.signed() ?: return@get call.see(AccountUrls.PIONNIERS)
            val account = signed.account
            val view = InscritView(account.gamertag, lier = accountPage != null && account.gamertag == null)
            call.respondHtml(HttpStatusCode.OK) { inscrit(shell, pioneersPage, view) }
        }
    } else {
        // Le site ouvert n'inscrit plus : les anciennes adresses mènent à la connexion et au compte.
        get(AccountUrls.PIONNIERS) { call.moved(AccountUrls.CONNEXION) }
        get(AccountUrls.INSCRIT) { call.moved(AccountUrls.COMPTE) }
    }
    get(AccountUrls.PIONNIERS.trimEnd('/')) { call.moved(AccountUrls.PIONNIERS) }

    post(AccountUrls.LOGOUT) {
        val signed = call.signed() ?: return@post call.see(AccountUrls.CONNEXION)
        if (!call.formOk(signed)) return@post call.refuse(HttpStatusCode.BadRequest, AccountMessage.FORM_EXPIRED)
        dependencies.database.transaction { sessions.delete(it, signed.session.tokenHash) }
        call.clearCookie(SESSION_COOKIE, "/")
        call.see("/")
    }

    if (xbox != null) {
        get(AccountUrls.XBOX) { call.start('x', joinGuild = false) { state -> xbox.authorizeUrl(state, xboxRedirect) } }

        get(AccountUrls.XBOX_RETURN) {
            val (_, code) = call.returned('x', AccountMessage.XBOX_FAILED) ?: return@get
            val profile = try {
                xbox.profile(code, xboxRedirect)
            } catch (e: CancellationException) {
                throw e
            } catch (e: XboxException) {
                // Ni le code, ni un jeton, ni le XUID : la sorte d'échec et le message du fournisseur, écrits pour un journal.
                call.application.log.warn("Connexion Xbox : ${e.failure} (${e.message})")
                return@get when (e.failure) {
                    XboxFailure.CHILD_ACCOUNT -> call.respondHtml(HttpStatusCode.Forbidden) { microsoftRefused(shell) }
                    XboxFailure.NO_XBOX_PROFILE -> call.refuse(HttpStatusCode.Forbidden, AccountMessage.XBOX_NO_PROFILE)
                    XboxFailure.NOT_IN_COUNTRY -> call.refuse(HttpStatusCode.Forbidden, AccountMessage.XBOX_NOT_IN_COUNTRY)
                    XboxFailure.REFUSED -> call.refuse(HttpStatusCode.BadRequest, AccountMessage.XBOX_FAILED)
                    XboxFailure.UNAVAILABLE -> call.refuse(HttpStatusCode.BadGateway, AccountMessage.XBOX_DOWN)
                }
            } catch (e: Exception) {
                call.application.log.warn("Connexion Xbox : le fournisseur a échoué (${e.javaClass.simpleName})")
                return@get call.refuse(HttpStatusCode.BadGateway, AccountMessage.XBOX_DOWN)
            }
            val playerXuid = Empreinte.duGamertag(profile.gamertag)
            val now = clock.instant()
            val outcome = dependencies.database.transaction { connection ->
                val (account, changed) = xboxAccount(connection, profile, playerXuid) ?: return@transaction null
                var registered = false
                if (waitlistOpen) {
                    registered = waitlist.insert(connection, account.id, now)
                    if (registered || changed) {
                        // Le jeu et le bot lisent V7 : l'inscription y est écrite sous le gamertag prouvé par Xbox.
                        val admitted = admitByRule(connection, AdmissionMoment.REGISTRATION, playerXuid)
                        registrations.insert(connection, SiteRegistrations.key(profile.gamertag), profile.gamertag, admitted, pioneer = true)
                    }
                }
                accounts.touch(connection, account.id, now)
                openSession(connection, account.id) to registered
            } ?: return@get call.refuse(HttpStatusCode.Conflict, AccountMessage.GAMERTAG_TAKEN)
            call.setSession(outcome.first)
            call.see(landing(outcome.second))
        }
    }

    if (discord != null) {
        get(AccountUrls.DISCORD) {
            val join = call.request.queryParameters["rejoindre"] == "oui"
            call.start('d', join) { state -> discord.authorizeUrl(state, discordRedirect, join) }
        }

        get(AccountUrls.DISCORD_RETURN) {
            val (start, code) = call.returned('d', AccountMessage.DISCORD_FAILED) ?: return@get
            val user = try {
                discord.identify(code, discordRedirect)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ni le code, ni l'identifiant, ni la cause : seulement la sorte d'erreur.
                call.application.log.warn("Connexion Discord : le fournisseur a échoué (${e.javaClass.simpleName})")
                return@get call.refuse(HttpStatusCode.BadGateway, AccountMessage.DISCORD_DOWN)
            }
            val now = clock.instant()
            val current = call.signed()
            if (current != null) {
                // Un visiteur déjà connecté (entré par Xbox, le plus souvent) relie ce Discord à son compte.
                val account = current.account
                val outcome = dependencies.database.transaction { connection ->
                    val outcome = accounts.setDiscord(connection, account.id, user.id)
                    val playerXuid = account.playerXuid
                    val gamertag = account.gamertag
                    if (outcome == LinkOutcome.LINKED && gamertag != null && playerXuid != null) {
                        val admit = admitByRule(connection, AdmissionMoment.DISCORD_LINK, playerXuid)
                        registrations.link(connection, SiteRegistrations.key(gamertag), user.id, admit)
                    }
                    outcome
                }
                return@get when (outcome) {
                    LinkOutcome.LINKED, LinkOutcome.ALREADY_LINKED -> {
                        call.joinGuild(start, user)
                        call.see(accountHome)
                    }
                    LinkOutcome.DISCORD_TAKEN -> call.refuse(HttpStatusCode.Conflict, AccountMessage.DISCORD_TAKEN)
                    LinkOutcome.NOT_REGISTERED -> call.see(AccountUrls.CONNEXION)
                }
            }
            val (token, registered) = dependencies.database.transaction { connection ->
                val account = accounts.findByDiscord(connection, user.id) ?: accounts.createDiscord(connection, user.id, now)
                val registered = waitlistOpen && waitlist.insert(connection, account.id, now)
                accounts.touch(connection, account.id, now)
                openSession(connection, account.id) to registered
            }
            call.joinGuild(start, user)
            call.setSession(token)
            call.see(landing(registered))
        }
    }

    if (accountPage == null) return

    get(AccountUrls.COMPTE) {
        val signed = call.signed() ?: return@get call.see(AccountUrls.CONNEXION)
        val account = signed.account
        val pionnier = dependencies.database.transaction { waitlist.find(it, account.id) != null }
        val view = CompteView(
            gamertag = account.gamertag,
            xbox = account.xbox,
            discord = account.discord,
            discordButton = discord != null && !account.discord,
            pionnier = pionnier,
            csrf = signed.session.csrf,
        )
        call.respondHtml(HttpStatusCode.OK) { compte(shell, accountPage, view) }
    }
    get(AccountUrls.COMPTE.trimEnd('/')) { call.moved(AccountUrls.COMPTE) }

    get(AccountUrls.LIER) {
        val signed = call.signed() ?: return@get call.see(AccountUrls.CONNEXION)
        val account = signed.account
        val csrf = signed.session.csrf
        val view = when {
            account.gamertag != null && account.xbox -> LierView(LierKind.XBOX, null, null, account.gamertag, discord != null && !account.discord, csrf)
            account.gamertag != null -> LierView(LierKind.LINKED, null, null, account.gamertag, false, csrf)
            else -> {
                val code = dependencies.database.transaction { links.issue(it, account.id) }
                val left = Duration.between(clock.instant(), code.expiresAt).seconds.coerceAtLeast(0)
                LierView(LierKind.CODE, code.code, left, null, false, csrf)
            }
        }
        call.respondHtml(HttpStatusCode.OK) { lier(shell, accountPage, view) }
    }
    get(AccountUrls.LIER.trimEnd('/')) { call.moved(AccountUrls.LIER) }

    // « Nouveau code » : l'ancien est effacé, un autre est tiré.
    post(AccountUrls.LIER) {
        val signed = call.signed() ?: return@post call.see(AccountUrls.CONNEXION)
        if (!call.formOk(signed)) return@post call.refuse(HttpStatusCode.BadRequest, AccountMessage.FORM_EXPIRED)
        if (signed.account.gamertag == null) {
            dependencies.database.transaction { connection ->
                dependencies.discordLinkStore.findByAccount(connection, signed.account.id)?.let { dependencies.discordLinkStore.delete(connection, it.code) }
                links.issue(connection, signed.account.id)
            }
        }
        call.see(AccountUrls.LIER)
    }

    // « Délier » : un compte entré par Discord perd son compte du jeu ; un compte entré par Xbox perd son Discord.
    post(AccountUrls.DELIER) {
        val signed = call.signed() ?: return@post call.see(AccountUrls.CONNEXION)
        if (!call.formOk(signed)) return@post call.refuse(HttpStatusCode.BadRequest, AccountMessage.FORM_EXPIRED)
        val account = signed.account
        dependencies.database.transaction { connection ->
            val gamertag = account.gamertag
            when {
                gamertag != null && !account.xbox -> {
                    accounts.clearPlayer(connection, account.id)
                    registrations.unlink(connection, SiteRegistrations.key(gamertag))
                }
                account.xbox && account.discord -> {
                    accounts.clearDiscord(connection, account.id)
                    if (gamertag != null) registrations.unlink(connection, SiteRegistrations.key(gamertag))
                }
            }
        }
        call.see(AccountUrls.LIER)
    }
}
