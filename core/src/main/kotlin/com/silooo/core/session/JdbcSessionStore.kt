package com.silooo.core.session

import com.silooo.core.api.Balances
import com.silooo.core.api.FactoryState
import com.silooo.core.api.Role
import com.silooo.core.db.VersionConflict
import com.silooo.core.db.Xuid
import com.silooo.core.db.isDuplicateKey
import com.silooo.core.db.setXuid
import com.silooo.core.definitions.StaffRarity
import com.silooo.core.definitions.StaffRole
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Le SQL de `join` et `leave` sur les tables de V1. Testé sur MariaDB 12.3.3 par Testcontainers
 * (`SessionSqlTest`), jamais avec un faux.
 *
 * Deux règles de verrou tiennent tout l'ensemble :
 *  - Tout part de la ligne `players` du joueur ([enterPlayer], lecture `FOR UPDATE`) : deux appels pour le même XUID se
 *    suivent, donc un seul crée l'entreprise.
 *  - Les lectures qui suivent n'ouvrent la vue cohérente d'InnoDB (REPEATABLE READ) qu'APRÈS ce verrou ;
 *    ce que l'appel précédent a validé est donc visible.
 */
class JdbcSessionStore : SessionStore {

    override fun enterPlayer(connection: Connection, xuid: Xuid, gamertag: String): PlayerEntry {
        // Lecture verrouillée d'abord : pour un joueur connu, les appels du même XUID font la queue sur le verrou
        // de la ligne, sans interblocage. (Un INSERT qui échoue sur la clé primaire prendrait un verrou partagé que
        // deux appels simultanés ne pourraient plus échanger contre un verrou exclusif.)
        val known = connection
            .prepareStatement("SELECT tutorial_step, last_seen_at FROM players WHERE xuid = ? FOR UPDATE")
            .use {
                it.setXuid(1, xuid)
                it.executeQuery().use { rows ->
                    if (rows.next()) rows.getInt("tutorial_step") to rows.instantOrNull("last_seen_at") else null
                }
            }

        val entry = if (known == null) {
            insertPlayer(connection, xuid, gamertag)
            PlayerEntry(created = true, tutorialStep = 0, previousLastSeenAt = null)
        } else {
            connection.prepareStatement("UPDATE players SET gamertag = ?, last_seen_at = UTC_TIMESTAMP(3) WHERE xuid = ?").use {
                it.setString(1, gamertag)
                it.setXuid(2, xuid)
                it.executeUpdate()
            }
            PlayerEntry(created = false, tutorialStep = known.first, previousLastSeenAt = known.second)
        }
        // À zéro, une seule fois : IGNORE si la ligne existe déjà (le verrou de `players` ci-dessus la protège).
        connection.prepareStatement("INSERT IGNORE INTO player_balances (xuid) VALUES (?)").use {
            it.setXuid(1, xuid)
            it.executeUpdate()
        }
        return entry
    }

    /**
     * Deux premiers passages du même XUID en même temps se bloquent l'un l'autre sur l'insertion : la base
     * en annule un (`SQLTransactionRollbackException`), que `SessionService` rejoue, et le rejeu trouve la
     * ligne. Une clé déjà prise malgré le verrou veut dire qu'un autre appel vient de la créer : même issue.
     */
    private fun insertPlayer(connection: Connection, xuid: Xuid, gamertag: String) {
        try {
            connection.prepareStatement("INSERT INTO players (xuid, floodgate_uuid, gamertag) VALUES (?, ?, ?)").use {
                it.setXuid(1, xuid)
                it.setString(2, xuid.floodgateUuid.toString())
                it.setString(3, gamertag)
                it.executeUpdate()
            }
        } catch (e: SQLException) {
            if (e.isDuplicateKey()) throw VersionConflict()
            throw e
        }
    }

    override fun membershipOf(connection: Connection, xuid: Xuid): Membership? =
        connection.prepareStatement("SELECT company_id, role FROM company_members WHERE xuid = ?").use {
            it.setXuid(1, xuid)
            it.executeQuery().use { rows ->
                if (rows.next()) Membership(rows.getLong("company_id"), Role.valueOf(rows.getString("role"))) else null
            }
        }

    override fun createCompany(connection: Connection, owner: Xuid, baseName: String, bootstrap: CompanyBootstrap): Membership {
        val slotIndex = allocateSlot(connection, bootstrap)
        val companyId = insertCompany(connection, baseName, slotIndex, bootstrap.startingOfflineCapHours())

        connection.prepareStatement("INSERT INTO company_members (company_id, xuid, role, share_percent) VALUES (?, ?, ?, ?)").use {
            it.setLong(1, companyId)
            it.setXuid(2, owner)
            it.setString(3, Role.PDG.name)
            it.setInt(4, FULL_SHARE_PERCENT)
            it.executeUpdate()
        }
        for (factory in bootstrap.startingFactories()) {
            connection.prepareStatement(
                "INSERT INTO company_factories (company_id, factory_id, state, restore_step, level) VALUES (?, ?, ?, ?, ?)",
            ).use {
                it.setLong(1, companyId)
                it.setString(2, factory.factoryId)
                it.setString(3, factory.state.name)
                it.setInt(4, factory.restoreStep)
                it.setInt(5, factory.level)
                it.executeUpdate()
            }
        }
        return Membership(companyId, Role.PDG)
    }

    /** Une case libre d'abord (la plus petite), sinon une case neuve à la suite de la dernière. */
    private fun allocateSlot(connection: Connection, bootstrap: CompanyBootstrap): Long {
        val free = firstLong(connection, "SELECT slot_index FROM archipelago_slots WHERE allocated_at IS NULL ORDER BY slot_index LIMIT 1 FOR UPDATE")
        if (free != null) {
            connection.prepareStatement("UPDATE archipelago_slots SET allocated_at = UTC_TIMESTAMP(3) WHERE slot_index = ?").use {
                it.setLong(1, free)
                it.executeUpdate()
            }
            return free
        }
        // Verrouiller la dernière case fait attendre un autre créateur : il lira la suivante une fois la nôtre validée.
        val last = firstLong(connection, "SELECT slot_index FROM archipelago_slots ORDER BY slot_index DESC LIMIT 1 FOR UPDATE")
        val next = if (last == null) 0L else last + 1
        val origin = bootstrap.slotOrigin(next.toInt())
        connection.prepareStatement(
            "INSERT INTO archipelago_slots (slot_index, origin_x, origin_z, allocated_at) VALUES (?, ?, ?, UTC_TIMESTAMP(3))",
        ).use {
            it.setLong(1, next)
            it.setInt(2, origin.x)
            it.setInt(3, origin.z)
            it.executeUpdate()
        }
        return next
    }

    /** Le nom est unique : s'il est pris, on essaie « nom 2 », « nom 3 »… */
    private fun insertCompany(connection: Connection, baseName: String, slotIndex: Long, offlineCapHours: Double): Long {
        var lastDuplicate: SQLException? = null
        for (attempt in 1..MAX_NAME_ATTEMPTS) {
            try {
                return connection.prepareStatement(
                    "INSERT INTO companies (name, slot_index, offline_cap_hours) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS,
                ).use {
                    it.setString(1, CompanyNaming.candidate(baseName, attempt))
                    it.setLong(2, slotIndex)
                    it.setDouble(3, offlineCapHours)
                    it.executeUpdate()
                    it.generatedKeys.use { keys ->
                        check(keys.next()) { "companies : aucun identifiant rendu" }
                        keys.getLong(1)
                    }
                }
            } catch (e: SQLException) {
                if (!e.isDuplicateKey()) throw e
                lastDuplicate = e
            }
        }
        throw IllegalStateException("companies : aucun nom libre après $MAX_NAME_ATTEMPTS essais pour « $baseName »", lastDuplicate)
    }

    override fun loadCompany(connection: Connection, companyId: Long): CompanyRecord =
        connection.prepareStatement(
            "SELECT c.id, c.name, c.era, c.stars, c.course_number, c.donar, c.donar_earned, c.course_started_at, " +
                "c.last_collect_at, c.mairie_level, c.silo_level, c.offline_cap_hours, c.reputation, c.version, " +
                "s.origin_x, s.origin_z " +
                "FROM companies c JOIN archipelago_slots s ON s.slot_index = c.slot_index WHERE c.id = ?",
        ).use {
            it.setLong(1, companyId)
            it.executeQuery().use { rows ->
                check(rows.next()) { "companies : entreprise $companyId introuvable" }
                CompanyRecord(
                    id = rows.getLong("id"),
                    name = rows.getString("name"),
                    era = rows.getInt("era"),
                    stars = rows.getInt("stars"),
                    courseNumber = rows.getInt("course_number"),
                    donar = rows.getDouble("donar"),
                    donarEarned = rows.getDouble("donar_earned"),
                    courseStartedAt = rows.instant("course_started_at"),
                    lastCollectAt = rows.instant("last_collect_at"),
                    mairieLevel = rows.getInt("mairie_level"),
                    siloLevel = rows.getInt("silo_level"),
                    offlineCapHours = rows.getDouble("offline_cap_hours"),
                    reputation = rows.getInt("reputation"),
                    island = SlotOrigin(rows.getInt("origin_x"), rows.getInt("origin_z")),
                    version = rows.getInt("version"),
                )
            }
        }

    override fun loadFactories(connection: Connection, companyId: Long): List<FactoryRecord> =
        connection.prepareStatement(
            "SELECT f.factory_id, f.state, f.restore_step, f.level, i.id AS incident_id, i.kind AS incident_kind, i.slowdown AS incident_slowdown " +
                "FROM company_factories f LEFT JOIN factory_incidents i ON i.id = " +
                "(SELECT MIN(o.id) FROM factory_incidents o " +
                "WHERE o.company_id = f.company_id AND o.factory_id = f.factory_id AND o.resolved_at IS NULL) " +
                "WHERE f.company_id = ? ORDER BY f.factory_id",
        ).use {
            it.setLong(1, companyId)
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val incident = rows.getLong("incident_id")
                        val open = !rows.wasNull()
                        add(
                            FactoryRecord(
                                factoryId = rows.getString("factory_id"),
                                state = FactoryState.valueOf(rows.getString("state")),
                                restoreStep = rows.getInt("restore_step"),
                                level = rows.getInt("level"),
                                openIncidentId = if (open) incident else null,
                                incidentSlowdown = if (open) rows.getDouble("incident_slowdown") else 1.0,
                                incidentKind = if (open) rows.getString("incident_kind") else null,
                            ),
                        )
                    }
                }
            }
        }

    override fun loadZones(connection: Connection, companyId: Long): List<Int> =
        connection.prepareStatement("SELECT zone_index FROM city_zones WHERE company_id = ? ORDER BY zone_index").use {
            it.setLong(1, companyId)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getInt(1)) } }
        }

    override fun loadStaff(connection: Connection, companyId: Long): List<StaffRecord> =
        // `DIRECTEUR` existe dans la colonne mais pas encore dans le jeu : aucune ligne n'en porte, on les écarte par prudence.
        connection.prepareStatement(
            "SELECT id, factory_id, name, skin_id, role, rarity FROM staff WHERE company_id = ? AND role IN ('SALARIE', 'CHEF') ORDER BY id",
        ).use {
            it.setLong(1, companyId)
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            StaffRecord(
                                id = rows.getLong("id"),
                                factoryId = rows.getString("factory_id"),
                                name = rows.getString("name"),
                                skinId = rows.getString("skin_id"),
                                role = StaffRole.valueOf(rows.getString("role")),
                                rarity = StaffRarity.valueOf(rows.getString("rarity")),
                            ),
                        )
                    }
                }
            }
        }

    override fun loadBalances(connection: Connection, xuid: Xuid): Balances =
        // LOCK IN SHARE MODE : lit la dernière version validée, même si la ligne est née après notre vue cohérente.
        connection.prepareStatement("SELECT lingots, gemmes, jetons, cles FROM player_balances WHERE xuid = ? LOCK IN SHARE MODE").use {
            it.setXuid(1, xuid)
            it.executeQuery().use { rows ->
                check(rows.next()) { "player_balances : ligne introuvable pour $xuid" }
                Balances(rows.getLong("lingots"), rows.getLong("gemmes"), rows.getLong("jetons"), rows.getLong("cles"))
            }
        }

    override fun loadProfile(connection: Connection, xuid: Xuid): PlayerProfile {
        // Les grades s'achètent dans un ordre imposé : le dernier obtenu est le plus haut.
        val rank = connection
            .prepareStatement("SELECT rank_id FROM player_ranks WHERE xuid = ? ORDER BY granted_at DESC, rank_id DESC LIMIT 1")
            .use {
                it.setXuid(1, xuid)
                it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        val pilierUntil = connection
            .prepareStatement("SELECT ends_at FROM player_subscriptions WHERE xuid = ? AND subscription_id = ? AND ends_at > UTC_TIMESTAMP(3)")
            .use {
                it.setXuid(1, xuid)
                it.setString(2, PILIER_SUBSCRIPTION_ID)
                it.executeQuery().use { rows -> if (rows.next()) rows.instant("ends_at") else null }
            }
        return PlayerProfile(rank, pilierUntil)
    }

    override fun loadMemberLingots(connection: Connection, companyId: Long): List<Long> =
        // Lecture simple, sans verrou : on ne bloque pas les soldes des autres associés pour un chiffre d'affichage.
        // LEFT JOIN : un membre sans ligne de soldes (pas encore passé par join) compte pour zéro.
        connection.prepareStatement(
            "SELECT COALESCE(b.lingots, 0) FROM company_members m LEFT JOIN player_balances b ON b.xuid = m.xuid " +
                "WHERE m.company_id = ? ORDER BY m.xuid",
        ).use {
            it.setLong(1, companyId)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getLong(1)) } }
        }

    override fun loadMemberXuids(connection: Connection, companyId: Long): List<Xuid> =
        // CAST en texte : un BIGINT UNSIGNED ne tient pas dans un Long, et `Xuid.parse` le relit sans perdre de chiffre.
        connection.prepareStatement("SELECT CAST(xuid AS CHAR) FROM company_members WHERE company_id = ? ORDER BY xuid").use {
            it.setLong(1, companyId)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(checkNotNull(Xuid.parse(rows.getString(1)))) } }
        }

    override fun markLeft(connection: Connection, xuid: Xuid): Boolean =
        connection.prepareStatement("UPDATE players SET last_seen_at = UTC_TIMESTAMP(3) WHERE xuid = ?").use {
            it.setXuid(1, xuid)
            it.executeUpdate() > 0
        }

    private fun firstLong(connection: Connection, sql: String): Long? =
        connection.prepareStatement(sql).use { it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null } }

    private fun ResultSet.instantOrNull(column: String): Instant? =
        getObject(column, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)

    private fun ResultSet.instant(column: String): Instant = checkNotNull(instantOrNull(column)) { "$column : date absente" }

    private companion object {
        /** Le PDG seul tient toute l'entreprise : les parts des membres font 100 (`setShares`, « somme = 100 »). */
        const val FULL_SHARE_PERCENT = 100

        /** Essais de nom avant d'abandonner : un nom pris par cent homonymes est un bug, pas un cas. */
        const val MAX_NAME_ATTEMPTS = 100

        /** `player_subscriptions.subscription_id` du Pilier (commentaire de V1 ; `id` dans `definitions/boutique.json`). */
        const val PILIER_SUBSCRIPTION_ID = "pilier"
    }
}
