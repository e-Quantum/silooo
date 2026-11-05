package com.silooo.core.discord

import com.silooo.core.db.Database
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Les noms des petits jeux dans la colonne `game` de `discord_games` et de `discord_records`. */
object Games {
    const val COUNTING = "comptage"
    const val ELEVATOR = "monte-charge"
}

/**
 * Où en est un petit jeu (`discord_games`, migration V10). [value] : le dernier nombre écrit au comptage, l'étage du
 * monte-charge. [lastUserId] : le dernier membre à avoir joué, ou `null` quand le jeu repart de zéro.
 */
data class GameState(val value: Long, val lastUserId: String?) {
    companion object {
        /** Un jeu qui n'a jamais commencé, ou qui vient de casser. */
        val START = GameState(0, null)
    }
}

/** Le record d'un petit jeu (`discord_records`). [holderId] : le membre qui l'a posé, `null` s'il n'est pas connu. */
data class GameRecord(val best: Long, val holderId: String?, val reachedAt: Instant)

/** L'état et le record de chaque petit jeu, tels que le bot les retient. Chaque appel est une transaction à lui. */
interface GameStore {
    /** L'état du jeu [game] ; [GameState.START] s'il n'a jamais été écrit. */
    suspend fun state(game: String): GameState

    /** Écrit l'état du jeu [game] ; [at] : le moment du geste. */
    suspend fun saveState(game: String, state: GameState, at: Instant)

    /** Le record du jeu [game], ou `null` s'il n'en a pas encore. */
    suspend fun record(game: String): GameRecord?

    /** Remplace le record du jeu [game] : c'est à l'appelant de s'assurer qu'il est battu. */
    suspend fun saveRecord(game: String, record: GameRecord)
}

/**
 * Le SQL de [GameStore] sur `discord_games` et `discord_records`, en minuscules : le garde anti-IA lit les chaînes de ce
 * paquet. Testé sur MariaDB par Testcontainers (`GameSqlTest`).
 */
class JdbcGameStore(private val database: Database) : GameStore {
    override suspend fun state(game: String): GameState =
        database.transaction { connection ->
            connection.prepareStatement("select current_value, last_user_id from discord_games where game = ?").use {
                it.setString(1, game)
                it.executeQuery().use { rows -> if (rows.next()) GameState(rows.getLong(1), rows.getString(2)) else GameState.START }
            }
        }

    override suspend fun saveState(game: String, state: GameState, at: Instant) {
        database.transaction { connection ->
            connection.prepareStatement(
                "insert into discord_games (game, current_value, last_user_id, updated_at) values (?, ?, ?, ?) " +
                    "on duplicate key update current_value = values(current_value), last_user_id = values(last_user_id), " +
                    "updated_at = values(updated_at)",
            ).use {
                it.setString(1, game)
                it.setLong(2, state.value)
                it.setString(3, state.lastUserId)
                it.setObject(4, utc(at))
                it.executeUpdate()
            }
        }
    }

    override suspend fun record(game: String): GameRecord? =
        database.transaction { connection ->
            connection.prepareStatement("select best, holder_id, reached_at from discord_records where game = ?").use {
                it.setString(1, game)
                it.executeQuery().use { rows ->
                    if (rows.next()) {
                        GameRecord(rows.getLong(1), rows.getString(2), rows.getObject(3, LocalDateTime::class.java).toInstant(ZoneOffset.UTC))
                    } else {
                        null
                    }
                }
            }
        }

    override suspend fun saveRecord(game: String, record: GameRecord) {
        database.transaction { connection ->
            connection.prepareStatement(
                "insert into discord_records (game, best, holder_id, reached_at) values (?, ?, ?, ?) " +
                    "on duplicate key update best = values(best), holder_id = values(holder_id), reached_at = values(reached_at)",
            ).use {
                it.setString(1, game)
                it.setLong(2, record.best)
                it.setString(3, record.holderId)
                it.setObject(4, utc(record.reachedAt))
                it.executeUpdate()
            }
        }
    }

    private fun utc(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
}
