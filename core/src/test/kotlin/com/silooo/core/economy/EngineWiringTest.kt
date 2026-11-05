package com.silooo.core.economy

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.api.FactoryState
import com.silooo.core.api.Idempotency
import com.silooo.core.api.JoinResponse
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.city.InMemoryCityStore
import com.silooo.core.db.NeverUsedApiRequestStore
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.session.InMemorySessionStore
import com.silooo.core.shop.InMemoryShopStore
import com.silooo.core.siloooModule
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le câblage de l'application : sans rien lui passer d'autre que les définitions et des dépôts en mémoire (la session, la ville et la
 * boutique que les chiffres de `join` lisent pour leurs bonus), `Dependencies` doit brancher le moteur d'économie. Il n'existe plus
 * de « tout à zéro » par défaut.
 */
class EngineWiringTest {
    private val key = "cle-de-test"

    @Test
    @DisplayName("Les dépendances par défaut rendent, au premier join, les chiffres du moteur et créent l'entreprise depuis definitions/")
    fun defaultDependenciesUseTheEngine() = testApplication {
        val definitions = TestDefinitions.loaded
        val engine = EconomyEngine(definitions)
        val database = PassThroughDatabase()
        val store = InMemorySessionStore()
        application {
            siloooModule(
                Dependencies(
                    database = database,
                    definitions = definitions,
                    apiKey = key,
                    idempotency = Idempotency(database, NeverUsedApiRequestStore),
                    sessionStore = store,
                    cityStore = InMemoryCityStore(store),
                    shopStore = InMemoryShopStore(store),
                ),
            )
        }

        val httpResponse = client.post("/v1/session/join") {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody("""{"xuid":"2535428000000001","gamertag":"Kr"}""")
        }
        assertEquals(HttpStatusCode.OK, httpResponse.status, httpResponse.bodyAsText())
        val response = SiloooJson.decodeFromString(JoinResponse.serializer(), httpResponse.bodyAsText())
        val start = definitions.economy.prestige.courseStart

        val factory = response.state.factories.single()
        assertEquals(start.factoryId, factory.factoryId)
        assertEquals(FactoryState.ACTIVE, factory.state)
        assertEquals(start.level, factory.level)
        assertEquals(engine.ratePerHour(engine.startingLevels(), engine.globalMultiplier(0.0, 0)), response.state.ratePerHour)
        assertTrue(response.state.ratePerHour > 0.0)
        assertEquals(engine.prestigeThreshold(1), response.state.threshold)
        assertEquals(engine.nextLevelCost(start.factoryId, start.level), factory.nextCost)
        assertEquals(definitions.economy.offline.capHours, store.companies.values.single().offlineCapHours)
    }
}
