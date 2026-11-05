package com.silooo.core.db

import com.silooo.core.api.RefusalCodes
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Le verrou optimiste de `companies.version` sur la vraie MariaDB. Ignoré sans Docker. */
class OptimisticLockSqlTest {
    private val database get() = MariaDbSupport.database
    private var companyId = 0L

    @BeforeEach
    fun prepare() {
        MariaDbSupport.prepare()
        companyId = MariaDbSupport.insertCompany()
    }

    private fun version() = MariaDbSupport.long("SELECT version FROM companies WHERE id = ?", companyId)
    private fun donar() = MariaDbSupport.string("SELECT donar FROM companies WHERE id = ?", companyId)!!.toDouble()

    @Test
    @DisplayName("updateCompany écrit les colonnes et fait passer la version à la suivante")
    fun writesAndIncrements() = runBlocking {
        val newVersion = database.transaction { updateCompany(it, companyId, 0, CompanyColumn.DONAR to 12.5, CompanyColumn.STARS to 3) }

        assertEquals(1, newVersion)
        assertEquals(1L, version())
        assertEquals(12.5, donar())
        assertEquals(3L, MariaDbSupport.long("SELECT stars FROM companies WHERE id = ?", companyId))
    }

    @Test
    @DisplayName("Sans colonne à changer, updateCompany ne fait que passer la version")
    fun versionOnly() = runBlocking {
        assertEquals(1, database.transaction { updateCompany(it, companyId, 0) })
        assertEquals(2, database.transaction { updateCompany(it, companyId, 1) })
        assertEquals(2L, version())
    }

    @Test
    @DisplayName("Un Instant s'écrit en UTC dans une colonne DATETIME(3)")
    fun instantIsWrittenInUtc() = runBlocking {
        database.transaction { updateCompany(it, companyId, 0, CompanyColumn.LAST_COLLECT_AT to Instant.parse("2026-11-05T12:34:56.789Z")) }
        assertEquals(
            "2026-11-05 12:34:56.789",
            MariaDbSupport.string("SELECT DATE_FORMAT(last_collect_at, '%Y-%m-%d %H:%i:%s.%f') FROM companies WHERE id = ?", companyId)!!.dropLast(3),
        )
    }

    @Test
    @DisplayName("Une version dépassée touche zéro ligne : CONFLIT_REESSAYER (409), et rien n'est écrit")
    fun staleVersionIsRefused() = runBlocking {
        database.transaction { updateCompany(it, companyId, 0, CompanyColumn.DONAR to 10.0) }

        val conflict = assertThrows<VersionConflict> {
            runBlocking { database.transaction { updateCompany(it, companyId, 0, CompanyColumn.DONAR to 99.0) } }
        }

        assertEquals(RefusalCodes.CONFLICT_RETRY, conflict.reason)
        assertEquals(409, conflict.status.value)
        assertEquals(10.0, donar(), "la valeur de l'écriture perdante n'est pas là")
        assertEquals(1L, version())
    }

    @Test
    @DisplayName("Une entreprise inconnue touche zéro ligne aussi")
    fun unknownCompany() {
        assertThrows<VersionConflict> { runBlocking { database.transaction { updateCompany(it, companyId + 100, 0) } } }
    }

    @Test
    @DisplayName("Deux écrivains sur la même version : un seul gagne, l'autre reçoit CONFLIT_REESSAYER")
    fun twoConcurrentWriters() = runBlocking {
        repeat(5) { round ->
            val start = version().toInt()
            // Les deux ont lu la version avant que l'un écrive : le cas exact de deux associés qui jouent ensemble.
            val bothRead = CyclicBarrier(2)
            val outcomes = (1..2).map { writer ->
                async(Dispatchers.IO) {
                    database.transaction { connection ->
                        bothRead.await(30, TimeUnit.SECONDS)
                        try {
                            updateCompany(connection, companyId, start, CompanyColumn.DONAR to writer * 100.0 + round)
                            "gagne"
                        } catch (e: VersionConflict) {
                            "perd"
                        }
                    }
                }
            }.awaitAll()

            assertEquals(listOf("gagne", "perd"), outcomes.sorted(), "tour $round")
            assertEquals(start + 1L, version(), "la version n'avance qu'une fois")
        }
    }

    @Test
    @DisplayName("Six écrivains qui relisent et rejouent sur conflit : aucune mise à jour perdue")
    fun noLostUpdate() = runBlocking {
        val writers = 6
        val perWriter = 5
        val ready = CountDownLatch(writers)
        (1..writers).map {
            async(Dispatchers.IO) {
                ready.countDown()
                ready.await(30, TimeUnit.SECONDS)
                repeat(perWriter) {
                    while (true) {
                        try {
                            database.transaction { connection ->
                                val (version, donar) = connection.prepareStatement("SELECT version, donar FROM companies WHERE id = ?").use { statement ->
                                    statement.setLong(1, companyId)
                                    statement.executeQuery().use { rows ->
                                        rows.next()
                                        rows.getInt(1) to rows.getDouble(2)
                                    }
                                }
                                updateCompany(connection, companyId, version, CompanyColumn.DONAR to donar + 1)
                            }
                            break
                        } catch (e: VersionConflict) {
                            // perdu contre un autre associé : on relit et on rejoue
                        }
                    }
                }
            }
        }.awaitAll()

        assertEquals((writers * perWriter).toDouble(), donar(), "chaque incrément est compté une fois")
        assertEquals((writers * perWriter).toLong(), version())
    }
}
