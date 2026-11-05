package com.silooo.core.site

import com.silooo.core.Config
import com.silooo.core.ConfigException
import com.silooo.core.TestDefinitions
import com.silooo.core.registration.XboxConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Les variables de l'application Azure de la connexion Xbox : les deux, aucune, ou une erreur ; le secret ne s'affiche jamais. */
class XboxConfigTest {
    private val complete = mapOf(
        Config.DB_URL to "jdbc:mariadb://mariadb:3306/silooo",
        Config.DB_USER to "silooo",
        Config.DB_PASSWORD to "mot-de-passe-de-test",
        Config.API_KEY to "cle-de-test",
        Config.DEFINITIONS_DIR to TestDefinitions.directory.toString(),
    )

    private fun service(vararg extra: Pair<String, String>) = Config.service(complete + extra)

    @Test
    @DisplayName("Sans variable Xbox : pas de porte Xbox")
    fun none() {
        assertNull(service().xbox)
        assertNull(service(Config.XBOX_CLIENT_ID to " ", Config.XBOX_CLIENT_SECRET to "").xbox)
    }

    @Test
    @DisplayName("Les deux variables Xbox sont lues, et le secret ne s'affiche ni dans la configuration ni dans l'objet")
    fun both() {
        val config = service(Config.XBOX_CLIENT_ID to " 0f1e2d3c-test ", Config.XBOX_CLIENT_SECRET to "secret-xbox-de-test")

        assertEquals(XboxConfig("0f1e2d3c-test", "secret-xbox-de-test"), config.xbox)
        assertTrue(config.toString().contains("xbox=XboxConfig(clientId=0f1e2d3c-test"), config.toString())
        assertFalse(config.toString().contains("secret-xbox-de-test"), config.toString())
        assertFalse(config.xbox.toString().contains("secret-xbox-de-test"))
    }

    @Test
    @DisplayName("Une seule des deux variables Xbox arrête le démarrage, et l'erreur ne cite pas la valeur")
    fun onlyOne() {
        for (only in listOf(Config.XBOX_CLIENT_ID, Config.XBOX_CLIENT_SECRET)) {
            val error = assertThrows<ConfigException>(only) { service(only to "valeur-secrete") }
            assertTrue(error.message!!.contains(Config.XBOX_CLIENT_ID) && error.message!!.contains(Config.XBOX_CLIENT_SECRET), "${error.message}")
            assertFalse(error.message!!.contains("valeur-secrete"), "${error.message}")
        }
    }

    @Test
    @DisplayName("L'application Xbox et l'application Discord se lisent chacune de leur côté")
    fun independentOfDiscord() {
        val config = service(
            Config.DISCORD_CLIENT_ID to "123", Config.DISCORD_CLIENT_SECRET to "secret-discord-de-test",
            Config.XBOX_CLIENT_ID to "456", Config.XBOX_CLIENT_SECRET to "secret-xbox-de-test",
        )
        assertEquals("123", config.discord?.clientId)
        assertEquals("456", config.xbox?.clientId)
        assertNull(service(Config.DISCORD_CLIENT_ID to "123", Config.DISCORD_CLIENT_SECRET to "secret-discord-de-test").xbox)
        assertNull(service(Config.XBOX_CLIENT_ID to "456", Config.XBOX_CLIENT_SECRET to "secret-xbox-de-test").discord)
    }
}
