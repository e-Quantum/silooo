package com.silooo.core.economy

import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.Balances
import com.silooo.core.api.Role
import com.silooo.core.db.RecordingConnection
import com.silooo.core.db.Xuid
import com.silooo.core.session.CompanyFigures
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.InMemorySessionStore
import com.silooo.core.session.Membership
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Ce qu'il faut pour essayer [EngineFigures] sans base : le moteur et les définitions du dépôt, un faux dépôt en
 * mémoire, une horloge que le test avance et une connexion qui note ses écritures. Les entreprises se créent
 * avec le vrai [DefinitionsBootstrap], donc avec la Carrière de départ de `definitions/`.
 */
class EngineFixture {
    val definitions = TestDefinitions.loaded
    val engine = EconomyEngine(definitions)
    val clock = MutableClock(START)
    val store = InMemorySessionStore(START)
    val figures = EngineFigures(engine, store, clock)
    val bootstrap = DefinitionsBootstrap(engine, definitions)
    val recording = RecordingConnection()
    val connection: Connection get() = recording.connection

    /** L'usine de départ de la course, telle que `prestige.departDeCourse` la donne. */
    val starting = definitions.economy.prestige.courseStart

    /** Crée l'entreprise de [owner], qui détient [ownerLingots] Lingots, et la rend telle que la base la garde. */
    fun newCompany(owner: Xuid = KR, ownerLingots: Long = 0L): CompanyRecord {
        val membership = store.createCompany(connection, owner, "Entreprise de test", bootstrap)
        store.balances[owner] = Balances(ownerLingots, 0, 0, 0)
        return store.loadCompany(connection, membership.companyId)
    }

    /** Ajoute un associé qui détient [lingots] Lingots. */
    fun addMember(companyId: Long, xuid: Xuid, lingots: Long) {
        store.members[xuid] = Membership(companyId, Role.ASSOCIE)
        store.balances[xuid] = Balances(lingots, 0, 0, 0)
    }

    /** Remplace la ligne de l'entreprise (étoiles, course, plafond… posés par le test) et la rend. */
    fun update(company: CompanyRecord): CompanyRecord {
        store.companies[company.id] = company
        return company
    }

    /** Les chiffres que `join` afficherait pour l'entreprise, avec ce que le faux dépôt contient maintenant. */
    fun figuresOf(companyId: Long): CompanyFigures =
        figures.forCompany(connection, store.loadCompany(connection, companyId), store.loadFactories(connection, companyId))

    fun putFactory(companyId: Long, factory: FactoryRecord) = store.putFactory(companyId, factory)

    /** Les identifiants d'usines autres que celle de départ, dans l'ordre d'usines.json. */
    fun otherFactories(): List<String> = engine.factoryIds().filter { it != starting.factoryId }

    companion object {
        val START: Instant = Instant.parse("2026-11-05T12:00:00Z")
        val KR: Xuid = Xuid.parse("2535428000000001")!!
        val OTHER: Xuid = Xuid.parse("2535428000000002")!!
        val STRANGER: Xuid = Xuid.parse("2535428000000003")!!

        private val MILLIS_PER_HOUR = TimeUnit.HOURS.toMillis(1).toDouble()

        /** Une durée de [hours] heures, à la milliseconde près (la précision de la base). */
        fun hours(hours: Double): Duration = Duration.ofMillis(Math.round(hours * MILLIS_PER_HOUR))
    }
}
