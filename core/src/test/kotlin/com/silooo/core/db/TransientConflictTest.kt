package com.silooo.core.db

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.SQLTransactionRollbackException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Ce que le service tient pour un conflit passager, et la façon dont `updateCompany` le rend. Sans base. */
class TransientConflictTest {
    private val checkRead = SQLException("Record has changed since last read in table 'companies'", "HY000", ER_CHECKREAD)

    @Test
    @DisplayName("Blocage mortel, état SQL 40xxx et ligne changée depuis la lecture (1020) sont des conflits passagers")
    fun transientErrors() {
        assertTrue(SQLTransactionRollbackException("Deadlock found", "40001", 1213).isTransientConflict())
        assertTrue(SQLException("Serialization failure", "40001", 0).isTransientConflict())
        assertTrue(checkRead.isTransientConflict())
        assertTrue(checkRead.isSnapshotConflict())
    }

    @Test
    @DisplayName("Une erreur enveloppée est reconnue par sa cause")
    fun wrappedErrors() {
        assertTrue(RuntimeException("enveloppe", checkRead).isTransientConflict())
        assertTrue(IllegalStateException("a", RuntimeException("b", SQLTransactionRollbackException("c", "40001", 1213))).isTransientConflict())
    }

    @Test
    @DisplayName("Clé dupliquée, clé étrangère, délai d'attente et pannes ordinaires ne sont PAS des conflits passagers")
    fun otherErrors() {
        assertFalse(SQLIntegrityConstraintViolationException("Duplicate entry", "23000", 1062).isTransientConflict())
        assertFalse(SQLException("Cannot add or update a child row", "23000", 1452).isTransientConflict())
        assertFalse(SQLException("Lock wait timeout exceeded", "HY000", 1205).isTransientConflict())
        assertFalse(IllegalStateException("autre").isTransientConflict())
        assertFalse(checkRead.isDuplicateKey())
    }

    /** Une connexion dont le seul UPDATE fait [update] (rend un nombre de lignes, ou lève). */
    private fun connectionRunning(update: () -> Int): Connection {
        val statement = Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "executeUpdate" -> update()
                    "close", "setObject", "setLong", "setInt" -> null
                    else -> throw UnsupportedOperationException(method.name)
                }
            },
        ) as PreparedStatement
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
            InvocationHandler { _, method, _ ->
                if (method.name == "prepareStatement") statement else throw UnsupportedOperationException(method.name)
            },
        ) as Connection
    }

    @Test
    @DisplayName("updateCompany : l'erreur 1020 de MariaDB 12 devient VersionConflict, comme zéro ligne touchée")
    fun updateCompanyMapsCheckRead() {
        assertThrows<VersionConflict> { updateCompany(connectionRunning { throw checkRead }, 1, 3) }
        assertThrows<VersionConflict> { updateCompany(connectionRunning { 0 }, 1, 3) }
        assertEquals(4, updateCompany(connectionRunning { 1 }, 1, 3))
    }

    @Test
    @DisplayName("updateCompany : une autre erreur SQL remonte telle quelle")
    fun updateCompanyKeepsOtherErrors() {
        val other = SQLException("Cannot add or update a child row", "23000", 1452)
        val thrown = assertThrows<SQLException> { updateCompany(connectionRunning { throw other }, 1, 3) }
        assertEquals(1452, thrown.errorCode)
    }
}
