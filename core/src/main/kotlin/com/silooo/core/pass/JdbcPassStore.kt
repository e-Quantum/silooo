package com.silooo.core.pass

import com.silooo.core.db.Currency
import com.silooo.core.db.Xuid
import com.silooo.core.db.creditBalance
import com.silooo.core.db.setXuid
import java.sql.Connection
import java.sql.SQLException

/** `ER_NO_REFERENCED_ROW_2` : la clé étrangère ne trouve pas la ligne parente (le joueur). */
private const val NO_REFERENCED_ROW = 1452

/**
 * Le SQL de [PassStore] sur `season_pass`, `season_claims` et `player_cosmetics` (V1, aucune migration de plus). Testé sur MariaDB 12.3.3 par
 * Testcontainers (`PassSqlTest`).
 */
class JdbcPassStore : PassStore {
    override fun isKnownPlayer(connection: Connection, xuid: Xuid): Boolean =
        // LOCK IN SHARE MODE : une lecture verrouillée n'ouvre pas la vue cohérente de la transaction (MariaDB, `innodb_snapshot_isolation`).
        // Si cette première lecture était une lecture simple, la vue s'ouvrirait ici, et le verrou des soldes pris juste après sur une ligne
        // qu'une autre transaction vient de changer échouerait en ER_CHECKREAD (`TransientConflict.kt`) : deux appels du même joueur se
        // suivraient par un conflit à rejouer au lieu de se suivre par le verrou.
        connection.prepareStatement("SELECT 1 FROM players WHERE xuid = ? LOCK IN SHARE MODE").use {
            it.setXuid(1, xuid)
            it.executeQuery().use { rows -> rows.next() }
        }

    override fun load(connection: Connection, xuid: Xuid, seasonId: String): PassProgress {
        val row = readRow(connection, xuid, seasonId, forUpdate = false) ?: return PassProgress.NOTHING
        return PassProgress(row.first, row.second, readClaims(connection, xuid, seasonId))
    }

    override fun lock(connection: Connection, xuid: Xuid, seasonId: String): PassProgress {
        var row = readRow(connection, xuid, seasonId, forUpdate = true)
        if (row == null) {
            // INSERT IGNORE : une autre transaction a pu créer la ligne entre-temps ; la relecture verrouillée tranche.
            // Joueur inconnu : la clé étrangère refuse la ligne (en erreur, ou en simple avertissement avec IGNORE selon
            // la version) ; dans les deux cas la relecture ne trouve rien.
            try {
                connection.prepareStatement("INSERT IGNORE INTO season_pass (xuid, season_id) VALUES (?, ?)").use {
                    it.setXuid(1, xuid)
                    it.setString(2, seasonId)
                    it.executeUpdate()
                }
            } catch (e: SQLException) {
                if (e.errorCode != NO_REFERENCED_ROW) throw e
            }
            row = readRow(connection, xuid, seasonId, forUpdate = true) ?: throw IllegalArgumentException("Joueur inconnu : $xuid")
        }
        return PassProgress(row.first, row.second, readClaims(connection, xuid, seasonId))
    }

    override fun addPoints(connection: Connection, xuid: Xuid, seasonId: String, points: Int) {
        require(points >= 0) { "Des points ne se retirent pas : $points" }
        if (points == 0) return
        connection.prepareStatement(
            "INSERT INTO season_pass (xuid, season_id, points) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE points = points + ?",
        ).use {
            it.setXuid(1, xuid)
            it.setString(2, seasonId)
            it.setInt(3, points)
            it.setInt(4, points)
            it.executeUpdate()
        }
    }

    override fun grantPremium(connection: Connection, xuid: Xuid, seasonId: String): Boolean =
        connection.prepareStatement("UPDATE season_pass SET premium = TRUE WHERE xuid = ? AND season_id = ? AND premium = FALSE").use {
            it.setXuid(1, xuid)
            it.setString(2, seasonId)
            it.executeUpdate() > 0
        }

    override fun recordClaim(connection: Connection, xuid: Xuid, seasonId: String, claim: PassClaim): Boolean =
        connection.prepareStatement("INSERT IGNORE INTO season_claims (xuid, season_id, tier, track) VALUES (?, ?, ?, ?)").use {
            it.setXuid(1, xuid)
            it.setString(2, seasonId)
            it.setInt(3, claim.tier)
            it.setString(4, claim.track.wire)
            it.executeUpdate() > 0
        }

    override fun grantCosmetic(connection: Connection, xuid: Xuid, cosmeticId: String): Boolean =
        connection.prepareStatement("INSERT IGNORE INTO player_cosmetics (xuid, cosmetic_id, source) VALUES (?, ?, ?)").use {
            it.setXuid(1, xuid)
            it.setString(2, cosmeticId)
            it.setString(3, COSMETIC_SOURCE)
            it.executeUpdate() > 0
        }

    override fun lockBalances(connection: Connection, xuid: Xuid) {
        // Un mouvement de zéro ne change rien et n'écrit pas de journal : il ne fait que prendre le verrou de la ligne de soldes.
        creditBalance(connection, xuid, Currency.LINGOTS, 0L, PassService.REASON)
    }

    override fun creditLingots(connection: Connection, xuid: Xuid, amount: Long, reason: String, refId: String): Long =
        creditBalance(connection, xuid, Currency.LINGOTS, amount, reason, refId)

    /** Les points et la piste premium de la ligne, ou `null` si elle n'existe pas. */
    private fun readRow(connection: Connection, xuid: Xuid, seasonId: String, forUpdate: Boolean): Pair<Int, Boolean>? =
        connection.prepareStatement(
            "SELECT points, premium FROM season_pass WHERE xuid = ? AND season_id = ?" + if (forUpdate) " FOR UPDATE" else "",
        ).use {
            it.setXuid(1, xuid)
            it.setString(2, seasonId)
            it.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) to rows.getBoolean(2) else null }
        }

    private fun readClaims(connection: Connection, xuid: Xuid, seasonId: String): Set<PassClaim> =
        connection.prepareStatement("SELECT tier, track FROM season_claims WHERE xuid = ? AND season_id = ? ORDER BY tier, track").use {
            it.setXuid(1, xuid)
            it.setString(2, seasonId)
            it.executeQuery().use { rows ->
                buildSet {
                    while (rows.next()) add(PassClaim(rows.getInt(1), checkNotNull(PassTrack.parse(rows.getString(2))) { "season_claims.track inconnue" }))
                }
            }
        }

    private companion object {
        /** La valeur de `player_cosmetics.source` pour un cosmétique du pass. */
        const val COSMETIC_SOURCE = "pass"
    }
}
