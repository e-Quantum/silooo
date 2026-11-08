package com.silooo.core.registration

import com.silooo.core.MutableClock
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** La règle d'admission en bêta (décision 21), sur les faux : les trois chemins, ce que chacun écrit, et ce qui n'admet pas. */
class SiteAdmissionTest {
    private val start = Instant.parse("2026-11-08T10:00:00Z")
    private val clock = MutableClock(start)
    private val betaAccess = InMemoryBetaAccessStore()
    private val registrations = InMemorySiteRegistrationStore()
    private val admission = SiteAdmission(betaAccess, registrations, clock)

    /** Les faux n'ouvrent jamais la connexion : si la règle s'en sert, le test échoue au lieu de passer à vide. */
    private val connection: Connection = Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
        InvocationHandler { _, method, _ -> throw UnsupportedOperationException("la connexion n'est pas utilisable (${method.name})") },
    ) as Connection

    private fun register(gamertag: String, pioneer: Boolean = true) =
        registrations.insert(connection, SiteRegistrations.key(gamertag), gamertag, admitted = false, pioneer = pioneer)

    @Test
    @DisplayName("Chaque chemin écrit son propre admitted_by : discord-jeu (a), pionnier-salle (b), microsoft (c)")
    fun everyPathHasItsOwnName() {
        assertEquals(
            listOf("regle:discord-jeu", "regle:pionnier-salle", "regle:microsoft"),
            AdmissionPath.entries.map { it.admittedBy },
        )
        assertEquals(AdmissionPath.entries.size, AdmissionPath.entries.map { it.admittedBy }.toSet().size)
    }

    @Test
    @DisplayName("Chemin a, Discord relié en jeu : beta_access et admitted_at sont posés, le joueur n'est pas un Pionnier pour autant")
    fun discordInGame() {
        register("Ana", pioneer = false)
        assertTrue(admission.admit(connection, "Ana", "111", AdmissionPath.DISCORD_IN_GAME))
        assertEquals("regle:discord-jeu", betaAccess.find(connection, "111")?.admittedBy)
        assertEquals(start, betaAccess.find(connection, "111")?.admittedAt)
        assertTrue(registrations.rows.getValue("ana").admitted)
        assertFalse(registrations.rows.getValue("ana").pioneer)
    }

    @Test
    @DisplayName("Chemin b, Pionnier de la salle d'attente : admis sans Discord relié")
    fun lobbyPioneer() {
        register("Ben")
        assertTrue(admission.admit(connection, "Ben", "222", AdmissionPath.LOBBY_PIONEER))
        assertEquals("regle:pionnier-salle", betaAccess.find(connection, "222")?.admittedBy)
        assertTrue(registrations.rows.getValue("ben").admitted)
        assertNull(registrations.rows.getValue("ben").discord)
    }

    @Test
    @DisplayName("Chemin c, inscrit par Microsoft : admis, la clé du gamertag est celle de V7 (espaces et casse)")
    fun microsoft() {
        register("Cléo  Xbox")
        assertTrue(admission.admit(connection, "  cléo xbox ", "333", AdmissionPath.MICROSOFT))
        assertEquals("regle:microsoft", betaAccess.find(connection, "333")?.admittedBy)
        assertTrue(registrations.rows.getValue("cléo xbox").admitted)
    }

    @Test
    @DisplayName("Une admission n'est écrite qu'une fois : le premier chemin et la première date restent, la seconde rend faux")
    fun admissionIsWrittenOnce() {
        register("Dan")
        assertTrue(admission.admit(connection, "Dan", "444", AdmissionPath.LOBBY_PIONEER))
        clock.advance(Duration.ofDays(1))
        assertFalse(admission.admit(connection, "Dan", "444", AdmissionPath.DISCORD_IN_GAME))
        assertEquals("regle:pionnier-salle", betaAccess.find(connection, "444")?.admittedBy)
        assertEquals(start, betaAccess.find(connection, "444")?.admittedAt)
        assertEquals(1L, betaAccess.count(connection))
    }

    @Test
    @DisplayName("Sans passer par la règle, rien n'est admis : une inscription seule n'écrit ni beta_access ni admitted_at")
    fun registrationAloneAdmitsNobody() {
        register("Eve")
        assertEquals(0L, betaAccess.count(connection))
        assertFalse(registrations.rows.getValue("eve").admitted)
    }
}
