package com.silooo.core.site

import com.silooo.core.Config
import com.silooo.core.ConfigException
import com.silooo.core.TestDefinitions
import com.silooo.core.registration.DiscordConfig
import com.silooo.core.registration.SiteRegistrations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Les variables S4 (admission, application Discord, mandataire) et les règles qu'elles pilotent. */
class SiteAdmissionConfigTest {
    private val complete = mapOf(
        Config.DB_URL to "jdbc:mariadb://mariadb:3306/silooo",
        Config.DB_USER to "silooo",
        Config.DB_PASSWORD to "mot-de-passe-de-test",
        Config.API_KEY to "cle-de-test",
        Config.DEFINITIONS_DIR to TestDefinitions.directory.toString(),
    )

    private fun service(vararg extra: Pair<String, String>) = Config.service(complete + extra)

    @Test
    @DisplayName("Sans variable : pas d'application Discord, pas de mandataire")
    fun defaults() {
        val config = service()
        assertNull(config.discord)
        assertFalse(config.siteTrustedProxy)
    }

    @Test
    @DisplayName("SILOOO_SITE_ADMISSION n'existe plus (décision 21) : une valeur posée dans un vieux fichier d'environnement est ignorée, quelle qu'elle soit")
    fun admissionVariableIsGone() {
        assertNull(service("SILOOO_SITE_ADMISSION" to "tous").discord)
        assertNull(service("SILOOO_SITE_ADMISSION" to "n'importe quoi").discord)
    }

    @Test
    @DisplayName("Les variables Discord vont ensemble : les deux ou aucune, et le secret ne s'affiche jamais")
    fun discordPair() {
        val config = service(Config.DISCORD_CLIENT_ID to "123", Config.DISCORD_CLIENT_SECRET to "secret-de-test")
        assertEquals(DiscordConfig("123", "secret-de-test"), config.discord)
        assertFalse(config.toString().contains("secret-de-test"), config.toString())
        assertFalse(config.discord.toString().contains("secret-de-test"))
        for (only in listOf(Config.DISCORD_CLIENT_ID, Config.DISCORD_CLIENT_SECRET)) {
            val error = assertThrows<ConfigException>(only) { service(only to "valeur") }
            assertTrue(error.message!!.contains(Config.DISCORD_CLIENT_ID) && error.message!!.contains(Config.DISCORD_CLIENT_SECRET), "${error.message}")
            assertFalse(error.message!!.contains("valeur"))
        }
        assertNull(service(Config.DISCORD_CLIENT_ID to " ", Config.DISCORD_CLIENT_SECRET to "").discord)
    }

    @Test
    @DisplayName("SILOOO_SITE_TRUSTED_PROXY : true ou false, rien d'autre")
    fun trustedProxy() {
        assertTrue(service(Config.SITE_TRUSTED_PROXY to "true").siteTrustedProxy)
        assertFalse(service(Config.SITE_TRUSTED_PROXY to "false").siteTrustedProxy)
        assertThrows<ConfigException> { service(Config.SITE_TRUSTED_PROXY to "oui") }
    }

    @Test
    @DisplayName("La clé du gamertag : minuscules, espaces réduits à un seul, bords retirés")
    fun key() {
        assertEquals("kr test", SiteRegistrations.key("Kr Test"))
        assertEquals("kr test", SiteRegistrations.key("  KR   test "))
        assertEquals("kr test", SiteRegistrations.key("Kr Test".replace(' ', ' ')))
        assertEquals("steve", SiteRegistrations.key("STEVE"))
    }
}
