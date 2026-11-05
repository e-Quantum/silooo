package com.silooo.core.session

import com.silooo.core.api.CompanyState
import com.silooo.core.api.Factory
import com.silooo.core.api.JoinResponse
import com.silooo.core.db.Database
import com.silooo.core.db.VersionConflict
import com.silooo.core.db.Xuid
import java.sql.Connection
import java.sql.SQLTransactionRollbackException
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * `join` et `leave`, sans HTTP : les routes valident l'entrée et répondent, ce service tient la transaction.
 *
 * Aucune de ces deux opérations n'a de `requestId` : elles sont idempotentes par nature. Un `join` rejoué
 * retrouve le joueur et son entreprise et rend le même état ; il ne crée rien de plus. Un `leave` rejoué
 * remet `last_seen_at` à maintenant, et c'est voulu : le dernier départ gagne.
 */
class SessionService(
    private val database: Database,
    private val store: SessionStore,
    private val economy: EconomyFigures,
    private val bootstrap: CompanyBootstrap,
) {
    /**
     * Inscrit le joueur, crée son entreprise au premier passage, encaisse l'absence (via [EconomyFigures])
     * et rend `JoinResponse`. Si le moteur perd contre un autre associé (version de l'entreprise dépassée) ou
     * si la base annule la transaction (blocage), on recommence : tout est relu à neuf. Après
     * [MAX_ATTEMPTS] essais, le refus `CONFLIT_REESSAYER` remonte (409).
     */
    suspend fun join(xuid: Xuid, gamertag: String): JoinResponse {
        repeat(MAX_ATTEMPTS - 1) {
            try {
                return database.transaction { joinIn(it, xuid, gamertag) }
            } catch (_: VersionConflict) {
                // un autre associé a écrit entre la lecture et l'écriture : on relit
            } catch (_: SQLTransactionRollbackException) {
                // blocage détecté par la base : elle a annulé notre transaction, on recommence
            }
        }
        try {
            return database.transaction { joinIn(it, xuid, gamertag) }
        } catch (_: SQLTransactionRollbackException) {
            throw VersionConflict()
        }
    }

    /** Fige l'heure de départ. Un joueur inconnu ne fait pas d'erreur : le serveur peut dire adieu à un XUID jamais vu. */
    suspend fun leave(xuid: Xuid) {
        database.transaction { store.markLeft(it, xuid) }
    }

    private fun joinIn(connection: Connection, xuid: Xuid, gamertag: String): JoinResponse {
        // L'ordre compte : la ligne `players` est verrouillée en premier, ce qui range en file les appels du même XUID.
        val entry = store.enterPlayer(connection, xuid, gamertag)

        var membership = store.membershipOf(connection, xuid)
        var companyCreated = false
        var offlineCollected = 0.0
        if (membership == null) {
            membership = store.createCompany(connection, xuid, CompanyNaming.provisional(gamertag), bootstrap)
            companyCreated = true
        } else {
            val before = store.loadCompany(connection, membership.companyId)
            offlineCollected = economy.settleOffline(connection, before, entry.previousLastSeenAt)
        }

        // Relu après l'encaissement : la version et le Donar ont pu changer.
        val company = store.loadCompany(connection, membership.companyId)
        val factories = store.loadFactories(connection, company.id)
        val figures = economy.forCompany(company, factories)
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
            state = CompanyState(
                companyId = company.id,
                name = company.name,
                era = company.era,
                stars = company.stars,
                courseNumber = company.courseNumber,
                donar = company.donar,
                donarEarned = company.donarEarned,
                ratePerHour = figures.ratePerHour,
                multiplier = figures.multiplier,
                threshold = figures.threshold,
                factories = factories.map { factory ->
                    val numbers = figures.forFactory(factory.factoryId)
                    Factory(
                        factoryId = factory.factoryId,
                        state = factory.state,
                        restoreStep = factory.restoreStep,
                        level = factory.level,
                        ratePerHour = numbers.ratePerHour,
                        nextCost = numbers.nextCost,
                        nextMilestone = numbers.nextMilestone,
                        incidentId = factory.openIncidentId,
                        stoppedNoPower = numbers.stoppedNoPower,
                    )
                },
                zonesOwned = store.loadZones(connection, company.id),
                version = company.version,
            ),
            offlineCollected = offlineCollected,
        )
    }

    private fun isoUtc(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)

    private companion object {
        /** Essais de `join` avant de rendre `CONFLIT_REESSAYER` : un conflit de version est rare, trois essais suffisent. */
        const val MAX_ATTEMPTS = 3
    }
}
