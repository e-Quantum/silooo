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
import com.silooo.core.mission.JdbcMissionStore
import com.silooo.core.mission.MissionStore
import com.silooo.core.mission.missionRoutes
import com.silooo.core.orders.JdbcOrderStore
import com.silooo.core.orders.OrderStore
import com.silooo.core.orders.orderRoutes
import com.silooo.core.server.ServerPresence
import com.silooo.core.server.serverRoutes
import com.silooo.core.session.CompanyBootstrap
import com.silooo.core.session.EconomyFigures
import com.silooo.core.session.JdbcSessionStore
import com.silooo.core.session.SessionStore
import com.silooo.core.session.sessionRoutes
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
import java.time.Clock
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
    /** Les chiffres de `join` (revenu, multiplicateur, seuil, hors ligne) : le moteur d'économie, sur [definitions]. */
    val economyFigures: EconomyFigures = EngineFigures(economy, sessionStore),
    /** Ce que la création d'une entreprise tire de `definitions/` : usines de départ, plafond hors ligne, grille des îles. */
    val companyBootstrap: CompanyBootstrap = DefinitionsBootstrap(economy, definitions),
    val eventStore: EventStore = JdbcEventStore(),
    /** Les accès SQL des routes d'économie (existence d'une entreprise, écriture d'une usine, événements). */
    val companyStore: CompanyStore = JdbcCompanyStore(),
    /** Les accès SQL des missions (`player_missions`), de leur récompense en Lingots et de leur événement. */
    val missionStore: MissionStore = JdbcMissionStore(),
    /** Les accès SQL de la commande mondiale du jour (`world_orders`, `world_order_contributions`), de sa prime et de ses événements. */
    val orderStore: OrderStore = JdbcOrderStore(),
    /** L'horloge qui donne le jour de la commande mondiale (date UTC) ; les tests la remplacent par une horloge qu'ils avancent. */
    val ordersClock: Clock = Clock.systemUTC(),
    /** Les accès SQL du Carnet de trouvailles (`player_findings`), de leur prime en Lingots et de leur événement. */
    val findingStore: FindingStore = JdbcFindingStore(),
    /** Durée de garde des lignes de `api_requests` ; `null` : pas de purge (les tests). */
    val apiRequestRetention: Duration? = null,
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

    routing {
        route("/v1") {
            // Toutes les routes de /v1, y compris celles qui ne sont pas écrites, exigent la clé :
            // un appel sans clé ne doit même pas apprendre quelles routes existent.
            install(ServerKeyPlugin) { apiKey = dependencies.apiKey }

            serverRoutes(dependencies)
            sessionRoutes(dependencies)
            eventsRoutes(dependencies)
            economyRoutes(dependencies)
            missionRoutes(dependencies)
            orderRoutes(dependencies)
            findingRoutes(dependencies)

            notDoneRoutes() // en dernier : le filet 501 pour tout ce qui n'est pas écrit
        }
    }
}
