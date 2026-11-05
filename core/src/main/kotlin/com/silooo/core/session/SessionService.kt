package com.silooo.core.session

import com.silooo.core.api.JoinResponse
import com.silooo.core.db.Database
import com.silooo.core.db.VersionConflict
import com.silooo.core.db.Xuid
import com.silooo.core.db.isTransientConflict
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Essais de `join` avant de rendre `CONFLIT_REESSAYER`. Un conflit de version est rare ; ce sont surtout les
 * premiers passages simultanés (du même joueur, ou de deux joueurs qui prennent la toute première case)
 * qui se bloquent : la base en annule un, et son rejeu trouve le travail de l'autre déjà validé.
 */
internal const val MAX_JOIN_ATTEMPTS = 8

/**
 * `join` et `leave`, sans HTTP : les routes valident l'entrée et répondent, ce service tient la transaction.
 *
 * Aucune de ces deux opérations n'a de `requestId` : elles sont idempotentes par nature. Un `join` rejoué
 * retrouve le joueur et son entreprise et rend le même état ; il ne crée rien de plus. Un `leave` rejoué
 * remet `last_seen_at` à maintenant, et c'est voulu : le dernier départ gagne.
 *
 * Les deux versent la production écoulée depuis `companies.last_collect_at` et remettent cette date à maintenant :
 * `leave` au revenu plein (le partant était connecté), `join` au revenu plein si un AUTRE membre est connecté d'après
 * [online], au régime hors ligne sinon. Ainsi la période hors ligne commence au départ du dernier membre connecté.
 */
class SessionService(
    private val database: Database,
    private val store: SessionStore,
    private val economy: EconomyFigures,
    private val bootstrap: CompanyBootstrap,
    private val online: OnlinePlayers = OnlinePlayers.NOBODY,
    private val deliveries: PendingDeliveries = PendingDeliveries.NONE,
) {
    /**
     * Inscrit le joueur, verse les livraisons de Gemmes qui l'attendaient (via [PendingDeliveries]), crée son entreprise au
     * premier passage, encaisse l'absence (via [EconomyFigures]) et rend `JoinResponse`. Si le moteur perd contre un autre
     * associé (version de l'entreprise dépassée) ou
     * si la base annule la transaction (blocage), on recommence : tout est relu à neuf. Après
     * [MAX_JOIN_ATTEMPTS] essais, le refus `CONFLIT_REESSAYER` remonte (409).
     */
    suspend fun join(xuid: Xuid, gamertag: String): JoinResponse = withRetries { joinIn(it, xuid, gamertag) }

    /**
     * Fige l'heure de départ, verse la production en attente au revenu plein et remet `last_collect_at` à maintenant,
     * dans la même transaction. Un joueur inconnu, ou sans entreprise, ne fait pas d'erreur : le serveur peut dire
     * adieu à un XUID jamais vu. Comme `join`, recommence si un autre associé a écrit entre la lecture et l'écriture.
     */
    suspend fun leave(xuid: Xuid) {
        withRetries { leaveIn(it, xuid) }
    }

    /** Une transaction rejouée sur un conflit de version ou un blocage ; après [MAX_JOIN_ATTEMPTS] essais, `CONFLIT_REESSAYER`. */
    private suspend fun <T> withRetries(action: (Connection) -> T): T {
        repeat(MAX_JOIN_ATTEMPTS - 1) { attempt ->
            try {
                return database.transaction(action)
            } catch (_: VersionConflict) {
                // un autre associé a écrit entre la lecture et l'écriture : on relit
            } catch (e: SQLException) {
                // blocage détecté par la base, ou ligne changée depuis notre lecture : la transaction est annulée, on recommence
                if (!e.isTransientConflict()) throw e
            }
            // Une courte pause au hasard, de plus en plus longue : deux appels qui se bloquent ne repartent pas au même instant.
            delay(Random.nextLong(5L, 20L) * (attempt + 1))
        }
        try {
            return database.transaction(action)
        } catch (e: SQLException) {
            if (e.isTransientConflict()) throw VersionConflict()
            throw e
        }
    }

    private fun leaveIn(connection: Connection, xuid: Xuid) {
        // Comme `join`, la ligne `players` est touchée en premier : l'ordre des verrous est le même.
        if (!store.markLeft(connection, xuid)) return
        val membership = store.membershipOf(connection, xuid) ?: return
        economy.settle(connection, store.loadCompany(connection, membership.companyId), Regime.ONLINE)
    }

    private fun joinIn(connection: Connection, xuid: Xuid, gamertag: String): JoinResponse {
        // L'ordre compte : la ligne `players` est verrouillée en premier, ce qui range en file les appels du même XUID.
        val entry = store.enterPlayer(connection, xuid, gamertag)
        // Les Gemmes que le site a vendues à ce joueur avant qu'il ne vienne : versées tout de suite, le solde de la réponse les montre.
        deliveries.relieve(connection, xuid, gamertag)

        var membership = store.membershipOf(connection, xuid)
        var companyCreated = false
        var offlineCollected = 0.0
        if (membership == null) {
            membership = store.createCompany(connection, xuid, CompanyNaming.provisional(gamertag), bootstrap)
            companyCreated = true
        } else {
            val before = store.loadCompany(connection, membership.companyId)
            // Un autre membre connecté : la période s'est jouée en ligne. Sinon elle est hors ligne, plafond et efficacité.
            val othersOnline = store.loadMemberXuids(connection, before.id).any { it != xuid && online.isOnline(it) }
            val regime = if (othersOnline) Regime.ONLINE else Regime.OFFLINE
            val paid = economy.settle(connection, before, regime, entry.previousLastSeenAt)
            // `offlineCollected` ne dit que l'absence : la production en ligne va au Donar de l'entreprise, que `state` montre.
            if (regime == Regime.OFFLINE) offlineCollected = paid
        }

        // Relu après l'encaissement : la version et le Donar ont pu changer.
        val company = store.loadCompany(connection, membership.companyId)
        val factories = store.loadFactories(connection, company.id)
        val figures = economy.forCompany(connection, company, factories)
        val profile = store.loadProfile(connection, xuid)

        return JoinResponse(
            xuid = xuid.text,
            firstJoin = entry.created || companyCreated,
            tutorialStep = entry.tutorialStep,
            companyId = company.id,
            role = membership.role,
            rank = profile.rank,
            pilierUntil = profile.pilierUntil?.let(::isoUtc),
            balances = store.loadBalances(connection, xuid),
            state = buildCompanyState(company, factories, figures, store.loadZones(connection, company.id)),
            offlineCollected = offlineCollected,
        )
    }

    private fun isoUtc(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)
}
