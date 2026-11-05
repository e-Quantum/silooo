package com.silooo.core.db

import com.silooo.core.api.Refusal
import com.silooo.core.api.RefusalCodes
import io.ktor.http.HttpStatusCode
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * L'écriture a perdu contre une autre : la version de l'entreprise n'est plus celle qu'on avait lue.
 * C'est le refus `CONFLIT_REESSAYER` (409, corps `Refused`) : l'add-on relit l'état et rejoue.
 */
class VersionConflict : Refusal(HttpStatusCode.Conflict, RefusalCodes.CONFLICT_RETRY)

/**
 * Les colonnes de `companies` qu'une action peut changer. Une liste fermée plutôt qu'un nom de
 * colonne libre : le SQL de [updateCompany] ne contient jamais un texte venu d'ailleurs. `id`, `slot_index`,
 * `created_at` et `version` n'y sont pas : le premier trio ne change jamais, et `version` est le
 * verrou lui-même.
 */
enum class CompanyColumn(val sql: String) {
    NAME("name"),
    ERA("era"),
    STARS("stars"),
    COURSE_NUMBER("course_number"),
    DONAR("donar"),
    DONAR_EARNED("donar_earned"),
    COURSE_STARTED_AT("course_started_at"),
    LAST_COLLECT_AT("last_collect_at"),
    MAIRIE_LEVEL("mairie_level"),
    SILO_LEVEL("silo_level"),
    OFFLINE_CAP_HOURS("offline_cap_hours"),
    REPUTATION("reputation"),
    SLOGAN("slogan"),
    LOGO_ID("logo_id"),
    COLORS("colors"),
}

/**
 * Le verrou optimiste de l'entreprise (règle 4 du socle). Écrit les [changes] et fait passer
 * `companies.version` de [expectedVersion] à la suivante, en un seul `UPDATE ... WHERE version = ?`.
 * Si la ligne n'a plus cette version (un autre associé a écrit entre-temps), zéro ligne est touchée :
 * la fonction lève [VersionConflict] et rien n'est écrit.
 * MariaDB 12 refuse parfois l'écriture elle-même ([ER_CHECKREAD], `innodb_snapshot_isolation`) au lieu de
 * toucher zéro ligne : c'est le même conflit, et la même exception.
 *
 * Sans [changes], elle ne fait que passer la version : c'est ce que fait une action qui écrit dans
 * une autre table (les niveaux d'une usine, par exemple) et doit quand même prévenir les autres associés.
 *
 * Les valeurs possibles : nombres, chaînes, `null`, [LocalDateTime] (UTC) et [Instant] (converti en UTC).
 * Rend la nouvelle version.
 */
fun updateCompany(
    connection: Connection,
    companyId: Long,
    expectedVersion: Int,
    vararg changes: Pair<CompanyColumn, Any?>,
): Int {
    val assignments = changes.joinToString("") { (column, _) -> "${column.sql} = ?, " }
    val sql = "UPDATE companies SET ${assignments}version = version + 1 WHERE id = ? AND version = ?"
    val touched = try {
        connection.prepareStatement(sql).use { statement ->
            var index = 1
            for ((_, value) in changes) {
                statement.setObject(index++, if (value is Instant) LocalDateTime.ofInstant(value, ZoneOffset.UTC) else value)
            }
            statement.setLong(index++, companyId)
            statement.setInt(index, expectedVersion)
            statement.executeUpdate()
        }
    } catch (e: SQLException) {
        // MariaDB 12 (innodb_snapshot_isolation) : la ligne a changé depuis notre lecture, la base refuse au lieu de rendre 0 ligne.
        if (e.isSnapshotConflict()) throw VersionConflict()
        throw e
    }
    if (touched == 0) throw VersionConflict()
    return expectedVersion + 1
}
