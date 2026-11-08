package com.silooo.core

import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.Idempotency
import com.silooo.core.api.Refusal
import com.silooo.core.api.ServerKeyConfig
import com.silooo.core.api.ServerKeyPlugin
import com.silooo.core.api.SiloooJson
import com.silooo.core.api.launchApiRequestPurge
import com.silooo.core.api.notDoneRoutes
import com.silooo.core.api.respondError
import com.silooo.core.api.respondRefusal
import com.silooo.core.city.CityFigures
import com.silooo.core.city.CityRules
import com.silooo.core.city.CityStore
import com.silooo.core.city.JdbcCityStore
import com.silooo.core.city.cityRoutes
import com.silooo.core.db.Database
import com.silooo.core.definitions.Definitions
import com.silooo.core.economy.CompanyStore
import com.silooo.core.economy.DefinitionsBootstrap
import com.silooo.core.economy.EconomyEngine
import com.silooo.core.economy.EngineFigures
import com.silooo.core.economy.JdbcCompanyStore
import com.silooo.core.economy.economyRoutes
import com.silooo.core.events.EventStore
import com.silooo.core.events.JdbcEventStore
import com.silooo.core.events.eventsRoutes
import com.silooo.core.findings.FindingStore
import com.silooo.core.findings.JdbcFindingStore
import com.silooo.core.findings.findingRoutes
import com.silooo.core.incident.IncidentStore
import com.silooo.core.incident.IncidentTicker
import com.silooo.core.incident.JdbcIncidentStore
import com.silooo.core.incident.incidentRoutes
import com.silooo.core.incident.incidentService
import com.silooo.core.incident.launchIncidentTicker
import com.silooo.core.members.JdbcMemberStore
import com.silooo.core.members.MemberStore
import com.silooo.core.members.memberRoutes
import com.silooo.core.mission.JdbcMissionStore
import com.silooo.core.mission.MissionStore
import com.silooo.core.mission.missionRoutes
import com.silooo.core.orders.JdbcOrderStore
import com.silooo.core.orders.OrderStore
import com.silooo.core.orders.orderRoutes
import com.silooo.core.pass.PassService
import com.silooo.core.pass.passRoutes
import com.silooo.core.server.ServerPresence
import com.silooo.core.server.serverRoutes
import com.silooo.core.shop.DeliveryRelief
import com.silooo.core.shop.JdbcShopStore
import com.silooo.core.shop.ShopFigures
import com.silooo.core.shop.ShopRules
import com.silooo.core.shop.ShopStore
import com.silooo.core.shop.launchDeliveryPoll
import com.silooo.core.shop.shopRoutes
import com.silooo.core.session.CompanyBootstrap
import com.silooo.core.session.EconomyFigures
import com.silooo.core.session.JdbcSessionStore
import com.silooo.core.session.SessionStore
import com.silooo.core.session.PendingTitles
import com.silooo.core.session.PendingVotes
import com.silooo.core.session.sessionRoutes
import com.silooo.core.staff.JdbcStaffStore
import com.silooo.core.sitedata.JdbcShopDeliveryStore
import com.silooo.core.sitedata.JdbcSiteStatsStore
import com.silooo.core.sitedata.ShopDeliveryStore
import com.silooo.core.sitedata.SiteStatsStore
import com.silooo.core.staff.StaffStore
import com.silooo.core.registration.BetaAccessStore
import com.silooo.core.registration.DiscordGuildJoiner
import com.silooo.core.registration.DiscordIdentity
import com.silooo.core.registration.DiscordLinkStore
import com.silooo.core.registration.JdbcBetaAccessStore
import com.silooo.core.registration.JdbcDiscordLinkStore
import com.silooo.core.registration.JdbcWaitlistStore
import com.silooo.core.registration.JdbcWebAccountStore
import com.silooo.core.registration.JdbcWebSessionStore
import com.silooo.core.registration.WaitlistStore
import com.silooo.core.registration.WebAccountStore
import com.silooo.core.registration.WebSessionStore
import com.silooo.core.registration.XboxIdentity
import com.silooo.core.registration.JdbcSiteRegistrationStore
import com.silooo.core.registration.SiteRegistrationStore
import com.silooo.core.site.SiteCaches
import com.silooo.core.site.SiteMode
import com.silooo.core.site.SiteSettings
import com.silooo.core.staff.staffRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.application.log
import io.ktor.server.request.ContentTransformationException
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import com.silooo.core.vote.JdbcVoteStore
import com.silooo.core.vote.VoteService
import com.silooo.core.vote.VoteStore
import com.silooo.core.vote.VotifierSettings
import com.silooo.core.vote.launchVotifier
import com.silooo.core.vote.voteRoutes
import com.silooo.core.discord.linking.GameDiscordLinks
import com.silooo.core.discord.linking.GameLinkCode
import com.silooo.core.discord.linking.gameDiscordLinkRoutes
import com.silooo.core.lobby.lobbyRoutes
import com.silooo.core.db.Xuid
import java.sql.Connection
import java.time.Clock
import java.security.SecureRandom
import java.time.Duration

/**
 * Tout ce que les routes ont le droit d'utiliser, passé au module Ktor en paramètre : pas de
 * singleton, pas de variable globale. Un test sans Docker construit son propre [Dependencies]
 * avec un faux [Database] ; la production construit le sien dans [main].
 *
 * Une session qui ajoute un service (Redis, relevé des votes, horloge de jeu…) l'ajoute ici, avec une
 * valeur par défaut si elle veut ménager les tests existants.
 */
class Dependencies(
    val database: Database,
    /** La clé attendue dans `X-Silooo-Key`. */
    val apiKey: String,
    val serverPresence: ServerPresence = ServerPresence(),
    /** Les définitions du jeu, chargées une fois au démarrage (usines, économie, boutique, monde). */
    val definitions: Definitions,
    /** Les formules du jeu (prix, production, prestige, notation), bâties sur [definitions]. */
    val economy: EconomyEngine = EconomyEngine(definitions),
    /** L'idempotence des routes qui modifient quelque chose (`call.idempotent(...)`). */
    val idempotency: Idempotency = Idempotency(database),
    /** Les accès SQL de `join` et `leave` ; déclaré avant [economyFigures], qui y lit les Lingots des membres. */
    val sessionStore: SessionStore = JdbcSessionStore(),
    /** L'horloge du service : le jour de l'agence d'emploi et le calcul de la production s'y lisent. Un test la remplace pour avancer le temps. */
    val clock: Clock = Clock.systemUTC(),
    /** Les accès SQL de la ville (`city_zones`, `city_buildings`) et le paiement des zones et des bâtiments ; déclaré avant [economyFigures], qui y lit les bonus. */
    val cityStore: CityStore = JdbcCityStore(),
    /** Les accès SQL de la boutique (`shop_purchases`, `player_ranks`, `player_subscriptions`, `shop_deliveries`) ; déclaré avant [economyFigures], qui y lit les bonus du PDG. */
    val shopStore: ShopStore = JdbcShopStore(),
    /** Les chiffres de `join` (revenu, multiplicateur, seuil, hors ligne) : le moteur d'économie, sur [definitions], avec les bonus de la ville et de la boutique. */
    val economyFigures: EconomyFigures = EngineFigures(
        economy, sessionStore, clock,
        CityFigures(CityRules(definitions), sessionStore, cityStore),
        ShopFigures(ShopRules(definitions), shopStore, clock),
    ),
    /** Ce que la création d'une entreprise tire de `definitions/` : usines de départ, plafond hors ligne, grille des îles. */
    val companyBootstrap: CompanyBootstrap = DefinitionsBootstrap(economy, definitions),
    val eventStore: EventStore = JdbcEventStore(),
    /** Les accès SQL des routes d'économie (existence d'une entreprise, écriture d'une usine, événements). */
    val companyStore: CompanyStore = JdbcCompanyStore(),
    /** Les accès SQL des missions (`player_missions`), de leur récompense en Lingots et de leur événement. */
    val missionStore: MissionStore = JdbcMissionStore(),
    /** Les accès SQL de la commande mondiale du jour (`world_orders`, `world_order_contributions`), de sa prime et de ses événements. */
    val orderStore: OrderStore = JdbcOrderStore(),
    /** Les accès SQL du Carnet de trouvailles (`player_findings`), de leur prime en Lingots et de leur événement. */
    val findingStore: FindingStore = JdbcFindingStore(),
    /** Les accès SQL du personnel et de l'agence d'emploi (`staff`, `agency_candidates`). */
    val staffStore: StaffStore = JdbcStaffStore(),
    /** Les accès SQL de l'entreprise partagée (`company_members`, `company_invites`). */
    val memberStore: MemberStore = JdbcMemberStore(),
    /** Les accès SQL des incidents d'usine (`factory_incidents`). */
    val incidentStore: IncidentStore = JdbcIncidentStore(),
    /** Durée de garde des lignes de `api_requests` ; `null` : pas de purge (les tests). */
    val apiRequestRetention: Duration? = null,
    /** Intervalle du minuteur des incidents d'usine ; `null` : pas de minuteur, aucun incident ne s'ouvre (les tests). */
    val incidentTick: Duration? = null,
    /**
     * Le relevé des livraisons de Gemmes (`shop_deliveries`) : il verse celles des joueurs que le service connaît, à `join` et à
     * intervalle ([deliveryPoll]). `null` : pas de relevé du tout, ni à `join` ni en tâche de fond (les tests).
     */
    val deliveries: DeliveryRelief? = null,
    /** Intervalle du relevé des livraisons ; `null` : pas de tâche de fond (le rattrapage à `join` reste actif si [deliveries] est posé). */
    val deliveryPoll: Duration? = null,
    /** Le mode du site public (`SILOOO_SITE_MODE`) ; le site n'est ouvert que si Kr l'a décidé. */
    val siteMode: SiteMode = SiteMode.ATTENTE,
    /** L'adresse publique du site (`SILOOO_SITE_URL`), pour les liens absolus de S1. */
    val siteUrl: String = Config.DEFAULT_SITE_URL,
    /** Le lien Discord du site (`SILOOO_SITE_URL_DISCORD`) ; `null` : pas de lien. */
    val discordUrl: String? = null,
    /** Ce que les pages du site affichent du jeu : adresse, port, boutique, sites de vote. */
    val siteSettings: SiteSettings = SiteSettings(),
    /** Les accès SQL des inscriptions du site (`site_registrations`) : la seule table où le site écrit. */
    val siteRegistrationStore: SiteRegistrationStore = JdbcSiteRegistrationStore(),
    /** Le fournisseur de « Se connecter avec Discord » ; `null` : l'application Discord n'est pas configurée, la porte dit « bientôt ». */
    val discordIdentity: DiscordIdentity? = null,
    /** Le fournisseur de « Se connecter avec Xbox » ; `null` : l'application Azure n'est pas configurée, la porte dit « bientôt ». */
    val xboxIdentity: XboxIdentity? = null,
    /** L'entrée sur le serveur Discord avec `guilds.join` (la case « Rejoindre aussi le Discord ») ; `null` tant que le bot ne l'a pas branché. */
    val guildJoiner: DiscordGuildJoiner? = null,
    /** Les accès SQL des comptes du site (`web_accounts`, `waitlist`, `discord_links`, `beta_access`, `web_sessions`). */
    val webAccountStore: WebAccountStore = JdbcWebAccountStore(),
    val waitlistStore: WaitlistStore = JdbcWaitlistStore(),
    val discordLinkStore: DiscordLinkStore = JdbcDiscordLinkStore(),
    val betaAccessStore: BetaAccessStore = JdbcBetaAccessStore(),
    val webSessionStore: WebSessionStore = JdbcWebSessionStore(),
    /** `SILOOO_SITE_TRUSTED_PROXY` : l'adresse du visiteur se lit dans `X-Forwarded-For` (le Caddy de SileneMC la pose). */
    val siteTrustedProxy: Boolean = false,
    /** Signe les cookies et les jetons du site. Tiré au démarrage : il change à chaque redémarrage, une inscription en cours recommence. */
    val siteSessionSecret: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
    /** Les accès SQL des votes pour le serveur (`server_votes`), de leur récompense en Lingots et de leurs événements. */
    val voteStore: VoteStore = JdbcVoteStore(),
    /**
     * Les votes, sans HTTP : `todayVotes`, la réception de Votifier et le rattrapage de `join`. Il n'y en a qu'un par processus : son
     * verrou range les réceptions en file (la Vote party n'a lieu qu'une fois). La présence vient des signaux de vie.
     */
    val voteService: VoteService = VoteService(definitions, voteStore, clock) { serverPresence.onlineXuids() },
    /** Le rattrapage des votes d'un pseudo inconnu au moment du vote, à `join`. `null` : pas de rattrapage (les tests). */
    val pendingVotes: PendingVotes? = null,
    /** Le titre Pionnier, donné à `join` d'après `site_registrations` (`shop/PioneerTitle`). `null` : aucun titre donné (les tests). */
    val pendingTitles: PendingTitles? = null,
    /** La réception des votes de Hubyy (`SILOOO_VOTIFIER_TOKEN`). `null` : éteinte, aucun port n'est ouvert. */
    val votifier: VotifierSettings? = null,
    /** Les lectures du site sur la base du jeu (classements, fiches d'entreprise), en lecture seule. */
    val siteStatsStore: SiteStatsStore = JdbcSiteStatsStore(),
    /** Les caches d'une minute de ces lectures, partagés entre les requêtes ; ils lisent l'horloge [clock]. */
    val siteCaches: SiteCaches = SiteCaches(clock),
    /** L'écriture du webhook de PayNow dans `shop_deliveries`. */
    val shopDeliveryStore: ShopDeliveryStore = JdbcShopDeliveryStore(),
    /** `SILOOO_PAYNOW_WEBHOOK_SECRET` : la clé de la signature du webhook ; `null` : le webhook répond 503. Ne s'affiche jamais. */
    val payNowWebhookSecret: String? = null,
    /**
     * Le pass de saison : `getPass`, `claimPass`, les points que le prestige, la commande du jour et les missions rapportent, et la piste
     * premium de la boutique. `null` : le pass n'est pas branché (les tests qui n'en ont pas besoin) ; ses routes ne sont pas posées, la
     * boutique ne le vend pas et aucune action ne compte de points.
     */
    val pass: PassService? = null,
    /** Émission du code dans la transaction idempotente ; remplacée par un faux dans les tests de contrat. */
    val gameLinkIssuer: (Connection, Xuid) -> GameLinkCode = { connection, actor -> GameDiscordLinks(database, clock).issue(connection, actor) },
    /**
     * La salle d'attente : inscrit le visiteur pionnier (ligne `players`, `site_registrations`) puis émet son code, dans la
     * transaction idempotente ; remplacée par un faux dans les tests de contrat.
     */
    /** `definitions/attente.json`, lu à part comme discord.json ; `null` : les routes des records de la salle d'attente répondent 501. */
    val lobby: com.silooo.core.lobby.LobbyDefinitions? = null,
    /** Les accès SQL des records de la salle d'attente (`lobby_records`, `lobby_week_bests`). */
    val lobbyStore: com.silooo.core.lobby.LobbyStore = com.silooo.core.lobby.JdbcLobbyStore(),
    /** Les actus publiées du site, la plus récente en tête : la même source que silooo.com/actus/. */
    val lobbyNews: () -> List<com.silooo.core.site.NewsArticle> = {
        com.silooo.core.site.NewsArticles.published(com.silooo.core.site.NewsArticles.fromClasspath())
    },
    val lobbyLinkIssuer: (Connection, Xuid, String) -> GameLinkCode = { connection, actor, gamertag ->
        GameDiscordLinks(database, clock).enrollLobbyVisitor(connection, actor, gamertag)
    },
)

/**
 * Le module Ktor de silooo-core. Il ne lit ni l'environnement ni le disque : tout vient de
 * [dependencies].
 */
fun Application.siloooModule(dependencies: Dependencies) {
    install(ContentNegotiation) { json(SiloooJson) }

    install(StatusPages) {
        // Corps illisible ou qui n'a pas la forme du contrat (JSON cassé, champ obligatoire absent).
        exception<BadRequestException> { call, _ -> call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST) }
        exception<ContentTransformationException> { call, _ -> call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST) }
        // Un refus du jeu levé par une route ou par le verrou optimiste : corps `Refused` du contrat.
        exception<Refusal> { call, refusal -> call.respondRefusal(refusal) }
        // Le reste : le détail va dans les journaux, la réponse ne dit rien du code.
        exception<Throwable> { call, cause ->
            call.application.log.error("Erreur non prévue sur ${call.request.local.method.value} ${call.request.local.uri}", cause)
            call.respondError(HttpStatusCode.InternalServerError, ErrorCodes.INTERNAL)
        }
    }

    dependencies.apiRequestRetention?.let { launchApiRequestPurge(dependencies.idempotency, it) }
    dependencies.deliveries?.let { relief -> dependencies.deliveryPoll?.let { every -> launchDeliveryPoll(relief, every) } }
    dependencies.votifier?.let { settings -> launchVotifier(settings, dependencies.voteService, dependencies.database) }
    dependencies.incidentTick?.let { every ->
        val ticker = IncidentTicker(dependencies.database, dependencies.sessionStore, incidentService(dependencies)) {
            dependencies.serverPresence.onlineXuids()
        }
        launchIncidentTicker(ticker, every)
    }

    routing {
        route("/v1") {
            // Toutes les routes de /v1, y compris celles qui ne sont pas écrites, exigent la clé :
            // un appel sans clé ne doit même pas apprendre quelles routes existent.
            install(ServerKeyPlugin) { apiKey = dependencies.apiKey }

            serverRoutes(dependencies)
            sessionRoutes(dependencies)
            gameDiscordLinkRoutes(dependencies)
            lobbyRoutes(dependencies)
            eventsRoutes(dependencies)
            economyRoutes(dependencies)
            incidentRoutes(dependencies)
            missionRoutes(dependencies)
            orderRoutes(dependencies)
            findingRoutes(dependencies)
            staffRoutes(dependencies)
            cityRoutes(dependencies)
            memberRoutes(dependencies)
            shopRoutes(dependencies)
            voteRoutes(dependencies)
            passRoutes(dependencies)

            notDoneRoutes() // en dernier : le filet 501 pour tout ce qui n'est pas écrit
        }
    }
}
