package com.silooo.core.shop

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.Idempotency
import com.silooo.core.api.JoinResponse
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.NeverUsedApiRequestStore
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.db.RecordingConnection
import com.silooo.core.db.Xuid
import com.silooo.core.economy.EngineFixture
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.InMemorySessionStore
import com.silooo.core.session.TestBootstrap
import com.silooo.core.session.TestEconomy
import com.silooo.core.shop.InMemoryShopStore.CosmeticRow
import com.silooo.core.siloooModule
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le titre Pionnier ([PioneerTitle]) avec des faux (pas de base) : à qui le service le donne, une seule fois, avec son annonce, et
 * ce que `join` en montre. La lecture de `site_registrations` et la clé unique de `player_cosmetics` se jouent sur MariaDB dans
 * `ShopSqlTest`.
 */
class PioneerTitleTest {
    private val kr = EngineFixture.KR
    private val friend = EngineFixture.OTHER
    private val noon = EngineFixture.START
    private val title = ShopRules.PIONEER_TITLE_ID

    private class Harness {
        val clock = MutableClock(EngineFixture.START)
        val sessions = InMemorySessionStore(EngineFixture.START)
        val shop = InMemoryShopStore(sessions)
        val database = PassThroughDatabase()
        val pioneers = PioneerTitle(shop, clock)

        /** Ce que `join` fait pour [gamertag] : le service regarde les inscriptions du site. */
        fun join(xuid: Xuid, gamertag: String) = pioneers.relieve(RecordingConnection().connection, xuid, gamertag)

        fun dependencies(titles: PioneerTitle? = pioneers) = Dependencies(
            database = database,
            definitions = TestDefinitions.loaded,
            apiKey = KEY,
            // join n'a pas de requestId : si l'idempotence servait, ce magasin le dirait.
            idempotency = Idempotency(database, NeverUsedApiRequestStore),
            economyFigures = TestEconomy(),
            companyBootstrap = TestBootstrap,
            sessionStore = sessions,
            clock = clock,
            shopStore = shop,
            pendingTitles = titles,
        )
    }

    private fun harness(block: Harness.() -> Unit) = Harness().block()

    private suspend fun ApplicationTestBuilder.joinAs(gamertag: String, xuid: String = kr.text): JoinResponse {
        val response = client.post("/v1/session/join") {
            header(SERVER_KEY_HEADER, KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"xuid":"$xuid","gamertag":"$gamertag"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return SiloooJson.decodeFromString(JoinResponse.serializer(), response.bodyAsText())
    }

    // --- le service donne le titre

    @Test
    @DisplayName("Un pionnier inscrit sur le site reçoit le titre (source site, à l'instant du join) et une ANNONCE pionnier adressée à lui seul")
    fun pioneerGetsTheTitle() = harness {
        shop.seedPioneer("Kr")

        join(kr, "Kr")

        assertEquals(listOf(CosmeticRow(title, noon, "site")), shop.cosmeticsOf(kr))
        assertEquals(
            listOf(
                InMemoryShopStore.Published(
                    GameEventKind.ANNONCE,
                    kr,
                    buildJsonObject {
                        put("action", "pionnier")
                        put("cosmeticId", title)
                    },
                ),
            ),
            shop.published,
        )
    }

    @Test
    @DisplayName("Le titre n'est donné qu'une fois : un second join, ou dix, n'écrit rien de plus et ne republie rien")
    fun theTitleIsGivenOnce() = harness {
        shop.seedPioneer("Kr")

        repeat(10) { join(kr, "Kr") }

        assertEquals(1, shop.cosmeticsOf(kr).size)
        assertEquals(1, shop.published.size)
    }

    @Test
    @DisplayName("Un joueur qui n'est pas inscrit, ou pas comme pionnier, ne reçoit rien ; l'inscription d'un autre ne passe pas à lui")
    fun nobodyElseGetsTheTitle() = harness {
        shop.seedPioneer("Ami")

        join(kr, "Kr")

        assertTrue(shop.cosmeticsOf(kr).isEmpty())
        assertTrue(shop.published.isEmpty())

        join(friend, "Ami")
        assertEquals(listOf(title), shop.cosmeticsOf(friend).map { it.cosmeticId })
        assertTrue(shop.cosmeticsOf(kr).isEmpty(), "le titre de l'ami n'est pas celui de Kr")
        assertEquals(listOf(friend), shop.published.map { it.xuid })
    }

    @Test
    @DisplayName("Le gamertag se compare par la clé du site : casse, espaces des bords et espaces doublés sans effet, dans l'inscription comme dans le jeu")
    fun theKeyIgnoresCaseAndSpaces() = harness {
        shop.seedPioneer("  GRAND   Kr ")

        join(kr, "grand kr")

        assertEquals(listOf(title), shop.cosmeticsOf(kr).map { it.cosmeticId })

        // Un autre pseudo, même proche, n'a pas ce titre.
        join(friend, "grandkr")
        assertTrue(shop.cosmeticsOf(friend).isEmpty())
    }

    @Test
    @DisplayName("Un joueur qui a déjà le titre (donné autrement) ne le reçoit pas de nouveau : sa ligne reste telle quelle, aucune annonce")
    fun anAlreadyOwnedTitleIsLeftAlone() = harness {
        val earlier = noon.minusSeconds(86_400)
        shop.seedCosmetic(kr, title, at = earlier, source = "evenement")
        shop.seedPioneer("Kr")

        join(kr, "Kr")

        assertEquals(listOf(CosmeticRow(title, earlier, "evenement")), shop.cosmeticsOf(kr))
        assertTrue(shop.published.isEmpty())
    }

    @Test
    @DisplayName("Le titre ne touche ni Gemmes, ni achat, ni grade, ni boost : il n'écrit qu'un cosmétique et une annonce")
    fun theTitleWritesNothingElse() = harness {
        shop.seedPioneer("Kr")

        join(kr, "Kr")

        assertTrue(shop.purchases.isEmpty())
        assertTrue(shop.movements.isEmpty())
        assertTrue(shop.ranks.values.all { it.isEmpty() })
        assertTrue(shop.subscriptions.isEmpty())
        assertTrue(shop.boosts.isEmpty())
    }

    // --- ce que join montre

    @Test
    @DisplayName("join : le titre du pionnier est donné dans la transaction du join et figure dans cosmetics de la réponse, une fois annoncé ; un second join le montre toujours")
    fun joinShowsTheTitle() {
        val app = Harness()
        app.shop.seedPioneer("Kr")
        testApplication {
            application { siloooModule(app.dependencies()) }

            val first = joinAs("Kr")
            assertEquals(listOf(title), first.cosmetics)
            assertEquals(1, app.shop.published.size)

            val second = joinAs("Kr")
            assertEquals(listOf(title), second.cosmetics)
            assertEquals(1, app.shop.published.size, "pas de seconde annonce")
        }
    }

    @Test
    @DisplayName("join : cosmetics liste ce que le joueur possède, dans l'ordre où il l'a obtenu ; un joueur sans cosmétique reçoit une liste vide")
    fun joinListsTheCosmetics() {
        val app = Harness()
        val rules = ShopRules(TestDefinitions.loaded)
        val (older, newer) = rules.cosmetics
        app.shop.seedCosmetic(kr, newer.id, at = noon.minusSeconds(60))
        app.shop.seedCosmetic(kr, older.id, at = noon.minusSeconds(3600))
        testApplication {
            application { siloooModule(app.dependencies()) }

            assertEquals(listOf(older.id, newer.id), joinAs("Kr").cosmetics)
            assertEquals(emptyList<String>(), joinAs("Ami", xuid = friend.text).cosmetics)
        }
    }

    @Test
    @DisplayName("join sans source de titres (Dependencies.pendingTitles nul) : aucun titre donné, même à un pionnier inscrit")
    fun joinWithoutTitlesGivesNothing() {
        val app = Harness()
        app.shop.seedPioneer("Kr")
        testApplication {
            application { siloooModule(app.dependencies(titles = null)) }

            assertEquals(emptyList<String>(), joinAs("Kr").cosmetics)
            assertTrue(app.shop.cosmeticsOf(kr).isEmpty())
            assertTrue(app.shop.published.isEmpty())
        }
    }

    private companion object {
        const val KEY = "cle-de-test"
    }
}
