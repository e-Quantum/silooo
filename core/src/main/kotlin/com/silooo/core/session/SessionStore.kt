package com.silooo.core.session

import com.silooo.core.api.Balances
import com.silooo.core.api.FactoryState
import com.silooo.core.api.Role
import com.silooo.core.db.Xuid
import java.sql.Connection
import java.time.Instant

/** Ce que `join` apprend du joueur en l'inscrivant ou en le retrouvant. */
data class PlayerEntry(
    /** Vrai si cet appel a créé la ligne `players`. */
    val created: Boolean,
    /** `players.tutorial_step`. */
    val tutorialStep: Int,
    /**
     * `players.last_seen_at` AVANT cet appel (`null` au premier passage) : l'heure de départ figée par le
     * dernier `leave`, c'est-à-dire le début de l'absence que le moteur d'économie compte en hors ligne.
     */
    val previousLastSeenAt: Instant?,
)

/** L'entreprise d'un joueur et son rôle. */
data class Membership(val companyId: Long, val role: Role)

/** Une ligne de `companies`, telle que le moteur d'économie en a besoin pour calculer. */
data class CompanyRecord(
    val id: Long,
    val name: String,
    val era: Int,
    val stars: Int,
    val courseNumber: Int,
    val donar: Double,
    val donarEarned: Double,
    val courseStartedAt: Instant,
    val lastCollectAt: Instant,
    val mairieLevel: Int,
    val siloLevel: Int,
    val offlineCapHours: Double,
    val reputation: Int,
    /** `companies.version` : à repasser à `updateCompany` pour écrire (verrou optimiste). */
    val version: Int,
)

/** Une ligne de `company_factories`, avec l'incident ouvert s'il y en a un. */
data class FactoryRecord(
    val factoryId: String,
    val state: FactoryState,
    val restoreStep: Int,
    val level: Int,
    val openIncidentId: Long?,
)

/** Grade à vie et abonnement Pilier du joueur, pour `JoinResponse.rank` et `pilierUntil`. */
data class PlayerProfile(val rank: String?, val pilierUntil: Instant?)

/**
 * Les accès SQL de `join` et `leave`, derrière une interface : les tests de la route tournent avec un
 * faux en mémoire, le SQL avec la vraie base (Testcontainers). Chaque méthode travaille dans la
 * transaction de [Connection] ; l'ordre d'appel de `SessionService` compte pour les verrous.
 */
interface SessionStore {
    /**
     * Crée le joueur ou le met à jour (gamertag, `last_seen_at` à maintenant), et s'assure que sa ligne de
     * soldes existe, à zéro. Deux appels en même temps pour le même XUID se suivent : un seul crée.
     */
    fun enterPlayer(connection: Connection, xuid: Xuid, gamertag: String): PlayerEntry

    /** L'entreprise du joueur, ou `null` s'il n'en a pas encore. */
    fun membershipOf(connection: Connection, xuid: Xuid): Membership?

    /**
     * Crée l'entreprise [baseName] (suffixée si le nom est pris) avec sa case d'archipel, le joueur
     * comme PDG, le plafond hors ligne de départ et les usines de départ de [bootstrap].
     */
    fun createCompany(connection: Connection, owner: Xuid, baseName: String, bootstrap: CompanyBootstrap): Membership

    fun loadCompany(connection: Connection, companyId: Long): CompanyRecord
    fun loadFactories(connection: Connection, companyId: Long): List<FactoryRecord>
    fun loadZones(connection: Connection, companyId: Long): List<Int>
    fun loadBalances(connection: Connection, xuid: Xuid): Balances
    fun loadProfile(connection: Connection, xuid: Xuid): PlayerProfile

    /**
     * Les Lingots détenus par chaque membre de l'entreprise, un par membre : la matière de la moyenne qui fixe
     * le multiplicateur de production. Un membre sans ligne de soldes compte pour zéro.
     */
    fun loadMemberLingots(connection: Connection, companyId: Long): List<Long>

    /** Les XUID des membres de l'entreprise (PDG et associés), pour savoir si l'un d'eux est connecté. */
    fun loadMemberXuids(connection: Connection, companyId: Long): List<Xuid>

    /** Fige `players.last_seen_at` à maintenant. Rend faux si le joueur est inconnu. */
    fun markLeft(connection: Connection, xuid: Xuid): Boolean
}
