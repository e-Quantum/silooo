package com.silooo.core.db

import java.sql.Connection

/**
 * Le faux de [Database] pour les tests qui tournent sans Docker.
 *
 * Il refuse toute transaction et compte les essais : un test qui ne doit pas toucher à la base
 * (le heartbeat, la clé, le filet 501) peut vérifier que [attempts] reste à zéro. Une session qui
 * écrit une route avec de la base choisit selon le test : ce faux étendu (une base en mémoire
 * derrière un dépôt), ou la vraie base d'un conteneur Testcontainers (ignoré sans Docker).
 */
class FakeDatabase : Database {
    var attempts = 0
        private set

    override suspend fun <T> transaction(block: (Connection) -> T): T {
        attempts++
        throw UnsupportedOperationException("FakeDatabase : ce test ne devrait pas toucher à la base")
    }

    override fun close() = Unit
}
