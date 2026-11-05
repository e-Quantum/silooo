package com.silooo.core.registration

import com.silooo.core.db.MariaDbSupport
import java.sql.Connection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `site_registrations` (V7) sur MariaDB 12.3.3. Ignoré sans Docker, joué dans la CI. */
class SiteRegistrationSqlTest {
    private val store = JdbcSiteRegistrationStore()

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    private fun <T> inTransaction(block: (Connection) -> T): T = runBlocking { MariaDbSupport.database.transaction(block) }

    private fun register(gamertag: String, admitted: Boolean = false) =
        inTransaction { store.insert(it, SiteRegistrations.key(gamertag), gamertag, admitted) }

    @Test
    @DisplayName("Insertion : la clé est en minuscules, le gamertag reste tel qu'écrit, pioneer vaut vrai, admitted_at est nul")
    fun insertion() {
        assertTrue(register("Kr Test"))
        assertEquals(listOf("kr test"), MariaDbSupport.strings("SELECT gamertag_key FROM site_registrations"))
        assertEquals("Kr Test", MariaDbSupport.string("SELECT gamertag FROM site_registrations"))
        assertEquals(1L, MariaDbSupport.long("SELECT pioneer FROM site_registrations"))
        assertNull(MariaDbSupport.string("SELECT admitted_at FROM site_registrations"))
        assertNull(MariaDbSupport.string("SELECT discord_user_id FROM site_registrations"))
        assertNotNull(MariaDbSupport.string("SELECT created_at FROM site_registrations"))
    }

    @Test
    @DisplayName("Une clé qui existe déjà ne change rien et rend faux (casse et espaces compris)")
    fun duplicateKey() {
        assertTrue(register("Kr Test"))
        assertFalse(register("KR   test"))
        assertEquals(1L, MariaDbSupport.count("site_registrations"))
        assertEquals("Kr Test", MariaDbSupport.string("SELECT gamertag FROM site_registrations"))
        // Une inscription ne devient pas admise parce qu'on la retape avec la règle « tous ».
        assertFalse(register("Kr Test", admitted = true))
        assertNull(MariaDbSupport.string("SELECT admitted_at FROM site_registrations"))
    }

    @Test
    @DisplayName("La règle « tous » pose admitted_at dès l'inscription")
    fun admittedAtRegistration() {
        assertTrue(register("Kr Test", admitted = true))
        assertNotNull(MariaDbSupport.string("SELECT admitted_at FROM site_registrations"))
    }

    @Test
    @DisplayName("Lien Discord : pose l'identifiant, linked_at et, si la règle le dit, admitted_at ; le compteur compte les pionniers")
    fun link() {
        register("Kr Test")
        assertEquals(LinkOutcome.LINKED, inTransaction { store.link(it, "kr test", "413000000000000001", admit = true) })
        assertEquals("413000000000000001", MariaDbSupport.string("SELECT discord_user_id FROM site_registrations"))
        assertNotNull(MariaDbSupport.string("SELECT linked_at FROM site_registrations"))
        assertNotNull(MariaDbSupport.string("SELECT admitted_at FROM site_registrations"))
        assertEquals(SiteRegistration("kr test", "Kr Test", linked = true, admitted = true), inTransaction { store.find(it, "kr test") })
        assertEquals(1L, inTransaction { store.countPioneers(it) })
    }

    @Test
    @DisplayName("Lien Discord sans admission : admitted_at reste nul")
    fun linkWithoutAdmission() {
        register("Kr Test")
        assertEquals(LinkOutcome.LINKED, inTransaction { store.link(it, "kr test", "1", admit = false) })
        assertNull(MariaDbSupport.string("SELECT admitted_at FROM site_registrations"))
    }

    @Test
    @DisplayName("Un identifiant Discord n'est relié qu'à un gamertag : la seconde demande rend DISCORD_TAKEN et n'écrit rien")
    fun discordIsUnique() {
        register("Premier")
        register("Second")
        assertEquals(LinkOutcome.LINKED, inTransaction { store.link(it, "premier", "42", admit = true) })
        assertEquals(LinkOutcome.DISCORD_TAKEN, inTransaction { store.link(it, "second", "42", admit = true) })
        assertNull(MariaDbSupport.string("SELECT discord_user_id FROM site_registrations WHERE gamertag_key = 'second'"))
        assertNull(MariaDbSupport.string("SELECT admitted_at FROM site_registrations WHERE gamertag_key = 'second'"))
        assertEquals(1L, MariaDbSupport.long("SELECT COUNT(*) FROM site_registrations WHERE discord_user_id = '42'"))
        // Plusieurs inscriptions sans compte relié ne se heurtent pas : NULL n'est pas une valeur pour la clé unique.
        register("Troisieme")
        assertEquals(3L, MariaDbSupport.count("site_registrations"))
    }

    @Test
    @DisplayName("Une inscription déjà liée ne change pas, une clé inconnue est signalée, admitted_at n'est jamais retiré")
    fun linkEdgeCases() {
        register("Kr Test")
        assertEquals(LinkOutcome.NOT_REGISTERED, inTransaction { store.link(it, "inconnu", "7", admit = true) })
        assertEquals(LinkOutcome.LINKED, inTransaction { store.link(it, "kr test", "7", admit = true) })
        assertEquals(LinkOutcome.ALREADY_LINKED, inTransaction { store.link(it, "kr test", "8", admit = false) })
        assertEquals("7", MariaDbSupport.string("SELECT discord_user_id FROM site_registrations"))
        assertNotNull(MariaDbSupport.string("SELECT admitted_at FROM site_registrations"))
    }

    @Test
    @DisplayName("La requête de join : admis si admitted_at n'est pas nul pour LOWER(TRIM(gamertag)) ; la requête manuelle de Kr admet")
    fun admissionQueryAsJoinWillDo() {
        register("Kr Test")
        register("Admis Deja", admitted = true)
        fun admitted(gamertag: String): Boolean = MariaDbSupport.long(
            "SELECT COUNT(*) FROM site_registrations WHERE gamertag_key = ? AND admitted_at IS NOT NULL",
            SiteRegistrations.key(gamertag),
        ) == 1L
        assertFalse(admitted("Kr Test"))
        assertTrue(admitted("  admis   DEJA "))
        assertFalse(admitted("Personne"))
        // La requête que Kr peut lancer à la main (core/README.md).
        assertEquals(1, MariaDbSupport.update("UPDATE site_registrations SET admitted_at = UTC_TIMESTAMP(3) WHERE gamertag_key = ?", "kr test"))
        assertTrue(admitted("KR TEST"))
    }
}
