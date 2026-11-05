package com.silooo.core.discord

import java.time.Instant

/**
 * [GameStore] en mémoire, avec les mêmes règles que le SQL : [states] tient la place de `discord_games`, [bests] celle de
 * `discord_records`, un jeu qui n'a jamais été écrit repart de [GameState.START] et n'a pas de record.
 */
class InMemoryGameStore : GameStore {
    val states = mutableMapOf<String, GameState>()

    /** Le moment du dernier [saveState] de chaque jeu. */
    val updatedAt = mutableMapOf<String, Instant>()

    val bests = mutableMapOf<String, GameRecord>()

    /** Combien de fois chaque jeu a écrit son état. */
    var stateWrites = 0

    override suspend fun state(game: String): GameState = states[game] ?: GameState.START

    override suspend fun saveState(game: String, state: GameState, at: Instant) {
        states[game] = state
        updatedAt[game] = at
        stateWrites++
    }

    override suspend fun record(game: String): GameRecord? = bests[game]

    override suspend fun saveRecord(game: String, record: GameRecord) {
        bests[game] = record
    }
}
