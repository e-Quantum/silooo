package com.silooo.core.db

import java.sql.Connection

/**
 * La base de silooo-core, vue par le reste du code : un pool et des transactions, rien d'autre.
 *
 * C'est une dépendance qu'on passe au module Ktor ([com.silooo.core.Dependencies]), jamais un
 * singleton. Un test sans Docker donne au module un faux (`FakeDatabase`, dans les tests) ; un
 * test avec Docker donne un [HikariDatabase] branché sur le conteneur MariaDB.
 *
 * Règles pour les sessions qui écrivent ici :
 *  - Dates en UTC (`DATETIME(3)`), comme le schéma de V1.
 *  - Un solde personnel et son écriture dans `currency_ledger` se font dans la même transaction.
 *  - Une écriture sur une entreprise teste `companies.version` (verrou optimiste).
 */
interface Database : AutoCloseable {
    /**
     * Exécute [block] dans une transaction : validée si [block] rend sa valeur, annulée s'il lève
     * une exception (qui est relancée). JDBC bloque : l'appel passe par le pool de threads d'E/S.
     * Garde [block] court et sans appel réseau.
     */
    suspend fun <T> transaction(block: (Connection) -> T): T
}
