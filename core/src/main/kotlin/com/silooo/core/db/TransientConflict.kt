package com.silooo.core.db

import java.sql.SQLException
import java.sql.SQLTransactionRollbackException

/**
 * `ER_CHECKREAD` de MariaDB : « Record has changed since last read ». Depuis MariaDB 11.6, le réglage
 * `innodb_snapshot_isolation` vaut ON par défaut (c'est le cas de la 12.3 de SileneMC) : en REPEATABLE READ, une
 * écriture ou une lecture verrouillée (`UPDATE`, `SELECT ... FOR UPDATE`) qui tombe sur une ligne modifiée
 * par une autre transaction APRÈS la première lecture de la nôtre ne lit plus la version fraîche en silence,
 * elle échoue avec cette erreur. Pour le service, c'est un conflit comme un autre : la transaction est annulée,
 * l'appelant relit à neuf et recommence.
 */
const val ER_CHECKREAD = 1020

/** Vrai pour l'erreur [ER_CHECKREAD] (une version plus récente de la ligne existe). */
fun SQLException.isSnapshotConflict(): Boolean = errorCode == ER_CHECKREAD

/**
 * Vrai pour une erreur que la base résout en demandant de recommencer la transaction : blocage mortel
 * (`SQLTransactionRollbackException`, état SQL 40xxx) ou ligne changée depuis la lecture ([ER_CHECKREAD]).
 * Cherche aussi dans les causes, au cas où un pilote ou un pool l'aurait enveloppée.
 */
fun Throwable.isTransientConflict(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth++ < MAX_CAUSE_DEPTH) {
        if (current is SQLTransactionRollbackException) return true
        if (current is SQLException && (current.isSnapshotConflict() || current.sqlState?.startsWith("40") == true)) return true
        current = current.cause
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 8
