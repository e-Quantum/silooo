package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import com.silooo.core.registration.JdbcSiteRegistrationStore
import com.silooo.core.registration.SiteRegistrations
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `discord_admissions` et sa lecture de `site_registrations` sur la vraie MariaDB. Ignoré sans Docker : la CI le joue. */
class AdmissionSqlTest {
    private val store get() = JdbcAdmissionStore(MariaDbSupport.database)
    private val registrations = JdbcSiteRegistrationStore()
    private val start = Instant.parse("2026-11-05T12:00:00Z")

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    /** Inscrit [gamertag] par le dépôt du site, puis date l'inscription à [createdAt] ; rend sa clé. */
    private fun register(gamertag: String, createdAt: Instant): String = runBlocking {
        val key = SiteRegistrations.key(gamertag)
        MariaDbSupport.database.transaction { registrations.insert(it, key, gamertag, admitted = false) }
        MariaDbSupport.update("UPDATE site_registrations SET created_at = ? WHERE gamertag_key = ?", LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC), key)
        key
    }

    @Test
    @DisplayName("Les nouvelles inscriptions sont celles sans carte, les plus anciennes d'abord, dans la limite demandée")
    fun newRegistrationsAreTheOnesWithoutACard() = runBlocking {
        assertEquals(emptyList<Registration>(), store.newRegistrations(20))
        val second = register("Second", start + Duration.ofMinutes(5))
        val first = register("Premier", start)
        val third = register("Troisieme", start + Duration.ofMinutes(10))

        assertEquals(listOf(first, second, third), store.newRegistrations(20).map { it.key })
        assertEquals(listOf(first, second), store.newRegistrations(2).map { it.key })
        assertEquals(Registration(first, "Premier", null, start), store.newRegistrations(1).single())

        store.carded(first, "9001", start + Duration.ofHours(1))
        assertEquals(listOf(second, third), store.newRegistrations(20).map { it.key })
        store.carded(second, "9002", start + Duration.ofHours(1))
        store.carded(third, "9003", start + Duration.ofHours(1))
        assertEquals(emptyList<Registration>(), store.newRegistrations(20))
        assertEquals(3L, MariaDbSupport.count("discord_admissions"))
    }

    @Test
    @DisplayName("Une inscription se relit par sa clé, avec le compte Discord relié ; une clé inconnue rend rien")
    fun readsARegistration() = runBlocking {
        val key = register("Kr Test", start)
        assertEquals(Registration(key, "Kr Test", null, start), store.registration(key))
        MariaDbSupport.database.transaction { registrations.link(it, key, "413000000000000001", admit = false) }
        assertEquals(Registration(key, "Kr Test", "413000000000000001", start), store.registration(key))
        assertNull(store.registration("inconnu"))
    }

    @Test
    @DisplayName("Une carte écrit la ligne en attente, la décision la complète, une clé sans carte n'a pas de ligne")
    fun cardThenDecision() = runBlocking {
        val key = register("Kr Test", start)
        assertNull(store.admission(key))
        store.carded(key, "9001", start + Duration.ofMinutes(1))
        assertEquals(Admission(key, "9001", "attente", null, null), store.admission(key))

        store.decide(key, "plus-tard", "42", start + Duration.ofMinutes(5))
        assertEquals(Admission(key, "9001", "plus-tard", "42", start + Duration.ofMinutes(5)), store.admission(key))
        store.decide(key, "admis", "43", start + Duration.ofMinutes(9))
        assertEquals(Admission(key, "9001", "admis", "43", start + Duration.ofMinutes(9)), store.admission(key))
        assertEquals(1L, MariaDbSupport.count("discord_admissions"))
    }
}
