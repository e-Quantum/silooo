package com.silooo.core.session

import com.silooo.core.api.Balances
import com.silooo.core.api.FactoryState
import com.silooo.core.api.Role
import com.silooo.core.db.Xuid
import java.sql.Connection
import java.time.Instant

/**
 * Le faux en mémoire de [SessionStore], pour les tests de `join` et `leave` sans base. Il rejoue les
 * règles que le SQL tient (une seule entreprise par joueur, nom unique avec suffixe) ; le SQL lui-même,
 * les verrous et la concurrence se testent sur MariaDB (`SessionSqlTest`).
 */
class InMemorySessionStore(var now: Instant = Instant.parse("2026-11-05T12:00:00Z")) : SessionStore {

    class PlayerRow(var gamertag: String, var tutorialStep: Int, var lastSeenAt: Instant)

    val players = LinkedHashMap<Xuid, PlayerRow>()
    val members = LinkedHashMap<Xuid, Membership>()
    val companies = LinkedHashMap<Long, CompanyRecord>()
    val factories = LinkedHashMap<Long, MutableList<FactoryRecord>>()
    val balances = LinkedHashMap<Xuid, Balances>()
    val zones = LinkedHashMap<Long, List<Int>>()
    val profiles = LinkedHashMap<Xuid, PlayerProfile>()
    val leaves = mutableListOf<Xuid>()

    /** Une panne à jouer à la place de l'inscription du joueur (tests de refus et d'échec). */
    var failOnEnter: (() -> Throwable?)? = null

    /** Une panne à jouer à la place du départ du joueur (tests de reprise de `leave`). */
    var failOnLeave: (() -> Throwable?)? = null

    private var nextCompanyId = 1L

    override fun enterPlayer(connection: Connection, xuid: Xuid, gamertag: String): PlayerEntry {
        failOnEnter?.invoke()?.let { throw it }
        val known = players[xuid]
        balances.putIfAbsent(xuid, Balances(0, 0, 0, 0))
        if (known == null) {
            players[xuid] = PlayerRow(gamertag, tutorialStep = 0, lastSeenAt = now)
            return PlayerEntry(created = true, tutorialStep = 0, previousLastSeenAt = null)
        }
        val before = known.lastSeenAt
        known.gamertag = gamertag
        known.lastSeenAt = now
        return PlayerEntry(created = false, tutorialStep = known.tutorialStep, previousLastSeenAt = before)
    }

    override fun membershipOf(connection: Connection, xuid: Xuid): Membership? = members[xuid]

    override fun createCompany(connection: Connection, owner: Xuid, baseName: String, bootstrap: CompanyBootstrap): Membership {
        val taken = companies.values.map { it.name.lowercase() }.toSet()
        val name = generateSequence(1) { it + 1 }.map { CompanyNaming.candidate(baseName, it) }.first { it.lowercase() !in taken }
        val id = nextCompanyId++
        companies[id] = CompanyRecord(
            id = id, name = name, era = 1, stars = 0, courseNumber = 1, donar = 0.0, donarEarned = 0.0,
            courseStartedAt = now, lastCollectAt = now, mairieLevel = 1, siloLevel = 1, offlineCapHours = bootstrap.startingOfflineCapHours(),
            reputation = 0, version = 0,
        )
        factories[id] = bootstrap.startingFactories()
            .map { FactoryRecord(it.factoryId, it.state, it.restoreStep, it.level, openIncidentId = null) }
            .toMutableList()
        val membership = Membership(id, Role.PDG)
        members[owner] = membership
        return membership
    }

    override fun loadCompany(connection: Connection, companyId: Long): CompanyRecord = companies.getValue(companyId)

    override fun loadFactories(connection: Connection, companyId: Long): List<FactoryRecord> = factories[companyId].orEmpty().toList()

    override fun loadZones(connection: Connection, companyId: Long): List<Int> = zones[companyId].orEmpty()

    override fun loadBalances(connection: Connection, xuid: Xuid): Balances = balances.getValue(xuid)

    override fun loadProfile(connection: Connection, xuid: Xuid): PlayerProfile = profiles[xuid] ?: PlayerProfile(null, null)

    override fun loadMemberLingots(connection: Connection, companyId: Long): List<Long> =
        members.filterValues { it.companyId == companyId }.keys.map { balances[it]?.lingots ?: 0L }

    override fun loadMemberXuids(connection: Connection, companyId: Long): List<Xuid> =
        members.filterValues { it.companyId == companyId }.keys.toList()

    override fun markLeft(connection: Connection, xuid: Xuid): Boolean {
        failOnLeave?.invoke()?.let { throw it }
        leaves += xuid
        val player = players[xuid] ?: return false
        player.lastSeenAt = now
        return true
    }

    /** Remplace une usine ou l'ajoute, pour poser un état de départ dans un test. */
    fun putFactory(companyId: Long, factory: FactoryRecord) {
        val list = factories.getValue(companyId)
        list.removeAll { it.factoryId == factory.factoryId }
        list += factory
    }
}

/** Une usine de départ inventée pour les tests : les vrais identifiants vivent dans `definitions/`. */
val TEST_STARTING_FACTORY = StartingFactory("usine-de-test", FactoryState.ACTIVE, level = 1)

/** Un [CompanyBootstrap] de test : une seule usine fictive. */
object TestBootstrap : CompanyBootstrap {
    override fun startingFactories(): List<StartingFactory> = listOf(TEST_STARTING_FACTORY)
    override fun startingOfflineCapHours(): Double = 8.0
    override fun slotOrigin(slotIndex: Int): SlotOrigin = SlotOrigin(slotIndex, 0)
}

/** Des chiffres d'économie fictifs, reconnaissables, pour vérifier que `join` les recopie sans les calculer. */
class TestEconomy : EconomyFigures {
    /** Un versement demandé au moteur : l'entreprise, le régime, et l'heure de départ précédente que `join` passe. */
    data class Settle(val companyId: Long, val regime: Regime, val playerLastSeenAt: Instant?)

    /** Les versements demandés, dans l'ordre : ceux de `join` portent l'ancienne heure de départ du joueur, ceux de `leave` aucune. */
    val settled = mutableListOf<Settle>()

    /** Ce que le faux rend comme Donar versé. */
    var offline = 0.0

    override fun forCompany(connection: Connection, company: CompanyRecord, factories: List<FactoryRecord>): CompanyFigures = CompanyFigures(
        ratePerHour = 11.0,
        multiplier = 22.0,
        threshold = 33.0,
        factories = factories.associate { it.factoryId to FactoryFigures(ratePerHour = 44.0, nextCost = 55.0, nextMilestone = 66, stoppedNoPower = true) },
    )

    override fun settle(connection: Connection, company: CompanyRecord, regime: Regime, playerLastSeenAt: Instant?): Double {
        settled += Settle(company.id, regime, playerLastSeenAt)
        return offline
    }

    /** La production en attente que les routes d'économie verront ; `null` : rien n'est dû, la date de dernier calcul ne bouge pas. */
    var pending: Production? = null

    override fun pendingProduction(connection: Connection, company: CompanyRecord, factories: List<FactoryRecord>, regime: Regime): Production =
        pending ?: Production(earned = 0.0, countedHours = 0.0, at = company.lastCollectAt)
}
