package com.silooo.core.db

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.TreeMap

/**
 * Une [Connection] de test qui note ce qu'on lui demande d'écrire, sans base : chaque `prepareStatement` garde son
 * SQL et ses paramètres, `executeUpdate` rend [rowsTouched] lignes. Elle sert à vérifier l'écriture exacte d'une
 * fonction JDBC (`updateCompany`) sans Docker ; le SQL lui-même se teste sur MariaDB (`*SqlTest`).
 *
 * Tout ce qui n'est pas prévu ici (lecture, transaction…) lève une erreur : un code qui lirait la base par cette
 * connexion échouerait au lieu de passer à vide.
 */
class RecordingConnection(
    /** Ce que rend `executeUpdate` : 1 pour une écriture qui a touché sa ligne, 0 pour un verrou optimiste perdu. */
    var rowsTouched: Int = 1,
) {
    class Statement(val sql: String) {
        /** Les paramètres posés, par numéro (à partir de 1). */
        val parameters: MutableMap<Int, Any?> = TreeMap()
        var executions = 0
            internal set

        override fun toString() = "$sql $parameters"
    }

    val statements = mutableListOf<Statement>()

    val connection: Connection = Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
        InvocationHandler { _, method, args ->
            when (method.name) {
                "prepareStatement" -> statementFor(Statement(args[0] as String).also { statements += it })
                "close" -> null
                else -> unsupported(method)
            }
        },
    ) as Connection

    private fun statementFor(statement: Statement): PreparedStatement = Proxy.newProxyInstance(
        PreparedStatement::class.java.classLoader,
        arrayOf(PreparedStatement::class.java),
        InvocationHandler { _, method, args ->
            when (method.name) {
                "setObject", "setLong", "setInt", "setString", "setDouble" -> {
                    statement.parameters[args[0] as Int] = args[1]
                    null
                }
                "executeUpdate" -> {
                    statement.executions++
                    rowsTouched
                }
                "close" -> null
                else -> unsupported(method)
            }
        },
    ) as PreparedStatement

    private fun unsupported(method: Method): Nothing =
        throw UnsupportedOperationException("RecordingConnection : ${method.name} n'est pas prévu")
}

/** Un [Database] dont chaque transaction passe cette [RecordingConnection] : on lit ensuite ce qui a été écrit. */
class RecordingDatabase(val recording: RecordingConnection = RecordingConnection()) : Database {
    override suspend fun <T> transaction(block: (Connection) -> T): T = block(recording.connection)

    override fun close() = Unit
}
