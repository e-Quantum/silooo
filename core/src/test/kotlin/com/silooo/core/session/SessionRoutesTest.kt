package com.silooo.core.session

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.ErrorBody
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.FactoryState
import com.silooo.core.api.Idempotency
import com.silooo.core.api.JoinResponse
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.RefusedBody
import com.silooo.core.api.Role
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.NeverUsedApiRequestStore
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.db.VersionConflict
import com.silooo.core.db.Xuid
import com.silooo.core.server.ServerPresence
import com.silooo.core.siloooModule
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.sql.SQLException
import java.sql.SQLTransactionRollbackException
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `join` et `leave` avec des faux (pas de base). Le SQL est dans `SessionSqlTest`, le contrat dans `ContractTest`.
 */
class SessionRoutesTest {
    private val key = "cle-de-test"
    private val kr = "2535428000000001"
    private val other = "2535428000000002"

    private class Harness(
        val database: PassThroughDatabase,
        val store: InMemorySessionStore,
        val economy: TestEconomy,
        val clock: MutableClock,
        /** La présence que `join` interroge, sur l'horloge du test : un signal de vie se périme quand le test avance le temps. */
        val presence: ServerPresence,
    )

    private fun withApp(block: suspend ApplicationTestBuilder.(Harness) -> Unit) = testApplication {
        val database = PassThroughDatabase()
        val store = InMemorySessionStore()
        val economy = TestEconomy()
        val clock = MutableClock(Instant.parse("2026-11-05T12:00:00Z"))
        val presence = ServerPresence(clock = clock)
        application {
            siloooModule(
                Dependencies(
                    database = database,
                    definitions = TestDefinitions.loaded,
                    apiKey = key,
                    // join et leave n'ont pas de requestId : si l'idempotence servait, ce magasin le dirait.
                    idempotency = Idempotency(database, NeverUsedApiRequestStore),
                    economyFigures = economy,
                    companyBootstrap = TestBootstrap,
                    sessionStore = store,
                    serverPresence = presence,
                ),
            )
        }
        block(Harness(database, store, economy, clock, presence))
    }

    private suspend fun ApplicationTestBuilder.join(body: String, withKey: String? = key): HttpResponse =
        client.post("/v1/session/join") {
            if (withKey != null) header(SERVER_KEY_HEADER, withKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.leave(body: String): HttpResponse =
        client.post("/v1/session/leave") {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.joined(xuid: String = kr, gamertag: String = "Kr"): JoinResponse {
        val response = join("""{"xuid":"$xuid","gamertag":"$gamertag"}""")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        return SiloooJson.decodeFromString(JoinResponse.serializer(), response.bodyAsText())
    }

    private suspend fun assertError(response: HttpResponse, status: HttpStatusCode, reason: String) {
        assertEquals(status, response.status)
        assertEquals(reason, SiloooJson.decodeFromString(ErrorBody.serializer(), response.bodyAsText()).reason)
    }

    // --- join

    @Test
    @DisplayName("join au premier passage : profil, entreprise PDG, usine de départ, soldes à zéro")
    fun firstJoin() = withApp { app ->
        val response = joined()

        assertTrue(response.firstJoin)
        assertEquals(kr, response.xuid)
        assertEquals(Role.PDG, response.role)
        assertEquals(0, response.tutorialStep)
        assertNull(response.rank)
        assertNull(response.pilierUntil)
        assertEquals(0L, response.balances.lingots + response.balances.gemmes + response.balances.jetons + response.balances.cles)
        assertEquals(0.0, response.offlineCollected)

        assertEquals(1, app.store.companies.size)
        assertEquals(response.companyId, app.store.members.getValue(Xuid.parse(kr)!!).companyId)
        assertEquals(listOf(TEST_STARTING_FACTORY.factoryId), response.state.factories.map { it.factoryId })
        assertEquals(FactoryState.ACTIVE, response.state.factories.single().state)
        assertEquals("Entreprise de Kr", response.state.name)
        assertEquals(emptyList<Int>(), response.state.zonesOwned)
        assertTrue(app.economy.settled.isEmpty(), "rien à encaisser à la création")
    }

    @Test
    @DisplayName("Les chiffres du moteur viennent de EconomyFigures, recopiés tels quels")
    fun figuresComeFromTheEngine() = withApp {
        val response = joined()
        assertEquals(11.0, response.state.ratePerHour)
        assertEquals(22.0, response.state.multiplier)
        assertEquals(33.0, response.state.threshold)
        val factory = response.state.factories.single()
        assertEquals(44.0, factory.ratePerHour)
        assertEquals(55.0, factory.nextCost)
        assertEquals(66, factory.nextMilestone)
        assertTrue(factory.stoppedNoPower)
    }

    @Test
    @DisplayName("join rejoué : même entreprise, même état, firstJoin faux, rien de créé en plus")
    fun joinIsIdempotentByNature() = withApp { app ->
        val first = joined()
        val second = joined()
        val third = joined()

        assertTrue(first.firstJoin)
        assertEquals(false, second.firstJoin)
        assertEquals(false, third.firstJoin)
        assertEquals(first.copy(firstJoin = false), second)
        assertEquals(second, third)
        assertEquals(1, app.store.players.size)
        assertEquals(1, app.store.companies.size)
        assertEquals(1, app.store.members.size)
    }

    @Test
    @DisplayName("Au retour, le moteur encaisse l'absence depuis l'heure du dernier départ, et offlineCollected le dit")
    fun returningPlayerSettlesOffline() = withApp { app ->
        joined()
        app.store.now = Instant.parse("2026-11-05T13:00:00Z")
        assertEquals(HttpStatusCode.NoContent, leave("""{"xuid":"$kr"}""").status)
        app.store.now = Instant.parse("2026-11-05T21:00:00Z")
        app.economy.offline = 1234.5

        val back = joined()

        assertEquals(1234.5, back.offlineCollected)
        assertEquals(
            listOf(
                TestEconomy.Settle(back.companyId, Regime.ONLINE, null),
                TestEconomy.Settle(back.companyId, Regime.OFFLINE, Instant.parse("2026-11-05T13:00:00Z")),
            ),
            app.economy.settled,
        )
    }

    /** [other] devient associé de l'entreprise [companyId], déjà créée par le premier `join` de [kr]. */
    private fun Harness.addPartner(companyId: Long) {
        store.members[Xuid.parse(other)!!] = Membership(companyId, Role.ASSOCIE)
    }

    @Test
    @DisplayName("join d'un associé pendant qu'un autre membre est connecté : la période se verse en ligne, et offlineCollected reste à zéro")
    fun joinSettlesOnlineWhenAnotherMemberIsConnected() = withApp { app ->
        val first = joined()
        app.addPartner(first.companyId)
        app.presence.record("serveur-1", listOf(kr, other))
        app.economy.offline = 777.0

        val back = joined(other, "Autre")

        assertEquals(listOf(TestEconomy.Settle(first.companyId, Regime.ONLINE, null)), app.economy.settled)
        assertEquals(0.0, back.offlineCollected, "le gain en ligne va au Donar de l'entreprise, pas à l'écran de retour")
    }

    @Test
    @DisplayName("join d'un associé qui est seul dans le signal de vie : la période se verse hors ligne, offlineCollected la rapporte")
    fun joinSettlesOfflineWhenOnlyTheJoinerIsConnected() = withApp { app ->
        val first = joined()
        app.addPartner(first.companyId)
        app.presence.record("serveur-1", listOf(other))
        app.economy.offline = 777.0

        val back = joined(other, "Autre")

        assertEquals(listOf(TestEconomy.Settle(first.companyId, Regime.OFFLINE, null)), app.economy.settled)
        assertEquals(777.0, back.offlineCollected)
    }

    @Test
    @DisplayName("join : un joueur connecté qui n'est pas de l'entreprise ne change pas le régime")
    fun joinIgnoresConnectedStrangers() = withApp { app ->
        val first = joined()
        app.addPartner(first.companyId)
        app.presence.record("serveur-1", listOf(other, "2535428000000003"))

        joined(other, "Autre")

        assertEquals(listOf(Regime.OFFLINE), app.economy.settled.map { it.regime })
    }

    @Test
    @DisplayName("join : le signal de vie compte jusqu'à 30 secondes, pas au-delà (un serveur qui ne bat plus n'a plus de joueurs en ligne)")
    fun joinDoesNotTrustAStaleHeartbeat() = withApp { app ->
        val first = joined()
        app.addPartner(first.companyId)
        app.presence.record("serveur-1", listOf(kr))

        app.clock.advance(Duration.ofSeconds(29))
        joined(other, "Autre")
        app.clock.advance(Duration.ofSeconds(2))
        joined(other, "Autre")

        assertEquals(listOf(Regime.ONLINE, Regime.OFFLINE), app.economy.settled.map { it.regime }, "frais à 29 s, périmé à 31 s")
    }

    @Test
    @DisplayName("Le gamertag est mis à jour à chaque passage")
    fun gamertagIsRefreshed() = withApp { app ->
        joined(gamertag = "AncienNom")
        joined(gamertag = "NouveauNom")
        assertEquals("NouveauNom", app.store.players.getValue(Xuid.parse(kr)!!).gamertag)
        assertEquals("Entreprise de AncienNom", app.store.companies.values.single().name, "le nom de l'entreprise ne change pas")
    }

    @Test
    @DisplayName("Deux joueurs de même gamertag : deux entreprises, au nom suffixé")
    fun sameGamertagGetsSuffix() = withApp {
        val a = joined(kr, "Kr")
        val b = joined(other, "Kr")
        assertNotEquals(a.companyId, b.companyId)
        assertEquals("Entreprise de Kr", a.state.name)
        assertEquals("Entreprise de Kr 2", b.state.name)
    }

    @Test
    @DisplayName("Le grade, le Pilier et l'incident ouvert d'une usine sont recopiés")
    fun profileAndIncidentAreCopied() = withApp { app ->
        val first = joined()
        val xuid = Xuid.parse(kr)!!
        app.store.profiles[xuid] = PlayerProfile(rank = "bronze", pilierUntil = Instant.parse("2026-12-01T10:00:00.250Z"))
        app.store.putFactory(first.companyId, FactoryRecord("usine-de-test", FactoryState.ACTIVE, 0, 3, openIncidentId = 42))
        app.store.zones[first.companyId] = listOf(0, 2)

        val again = joined()

        assertEquals("bronze", again.rank)
        assertEquals("2026-12-01T10:00:00.250Z", again.pilierUntil)
        assertEquals(42L, again.state.factories.single().incidentId)
        assertEquals(3, again.state.factories.single().level)
        assertEquals(listOf(0, 2), again.state.zonesOwned)
    }

    @Test
    @DisplayName("join : le corps hors contrat donne 400 REQUETE_INVALIDE et n'écrit rien")
    fun joinRejectsBadBodies() = withApp { app ->
        val bodies = listOf(
            "pas du json",
            """{"gamertag":"Kr"}""",
            """{"xuid":"$kr"}""",
            """{"xuid":"abc","gamertag":"Kr"}""",
            """{"xuid":"123456789012345678901","gamertag":"Kr"}""",
            """{"xuid":"18446744073709551616","gamertag":"Kr"}""", // 2^64 : ne tient pas dans un BIGINT UNSIGNED
            """{"xuid":2535428000000001,"gamertag":"Kr"}""", // un nombre : le XUID voyage en chaîne
            """{"xuid":"$kr","gamertag":""}""",
            """{"xuid":"$kr","gamertag":"   "}""",
            """{"xuid":"$kr","gamertag":"${"G".repeat(33)}"}""",
        )
        for (body in bodies) assertError(join(body), HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
        assertEquals(0, app.database.transactions)
        assertTrue(app.store.players.isEmpty())
    }

    @Test
    @DisplayName("Un gamertag de 32 caractères passe, même fait de caractères hors du plan de base")
    fun gamertagAtTheLimit() = withApp {
        assertEquals(HttpStatusCode.OK, join("""{"xuid":"$kr","gamertag":"${"G".repeat(32)}"}""").status)
        assertEquals(HttpStatusCode.OK, join("""{"xuid":"$other","gamertag":"${"😀".repeat(32)}"}""").status)
    }

    @Test
    @DisplayName("join sans clé : 401, et rien n'est lu")
    fun joinNeedsTheKey() = withApp { app ->
        assertError(join("""{"xuid":"$kr","gamertag":"Kr"}""", withKey = null), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_KEY)
        assertEquals(0, app.database.transactions)
    }

    @Test
    @DisplayName("Un conflit de version se retente, puis rend 409 CONFLIT_REESSAYER avec le corps Refused")
    fun persistentConflictGivesRefused() = withApp { app ->
        var attempts = 0
        app.store.failOnEnter = { attempts++; VersionConflict() }

        val response = join("""{"xuid":"$kr","gamertag":"Kr"}""")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(RefusalCodes.CONFLICT_RETRY, SiloooJson.decodeFromString(RefusedBody.serializer(), response.bodyAsText()).reason)
        assertEquals(MAX_JOIN_ATTEMPTS, attempts, "tous les essais sont faits avant de rendre le conflit")
    }

    @Test
    @DisplayName("Un conflit passager (un seul échec) est absorbé : la réponse est un succès")
    fun transientConflictIsAbsorbed() = withApp { app ->
        var attempts = 0
        app.store.failOnEnter = { if (attempts++ == 0) VersionConflict() else null }

        val response = join("""{"xuid":"$kr","gamertag":"Kr"}""")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(2, attempts, "un échec, puis la réussite au second essai")
    }

    @Test
    @DisplayName("Un blocage de la base (transaction annulée) qui persiste devient aussi CONFLIT_REESSAYER")
    fun deadlockBecomesConflict() = withApp { app ->
        app.store.failOnEnter = { SQLTransactionRollbackException("Deadlock found when trying to get lock", "40001", 1213) }
        val response = join("""{"xuid":"$kr","gamertag":"Kr"}""")
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(RefusalCodes.CONFLICT_RETRY, SiloooJson.decodeFromString(RefusedBody.serializer(), response.bodyAsText()).reason)
    }

    @Test
    @DisplayName("MariaDB 12 : une ligne changée depuis la lecture (erreur 1020) est reprise, puis devient CONFLIT_REESSAYER si elle persiste")
    fun snapshotConflictIsRetriedThenBecomesConflict() = withApp { app ->
        val checkRead = { SQLException("Record has changed since last read in table 'archipelago_slots'", "HY000", 1020) }
        var attempts = 0
        app.store.failOnEnter = { if (attempts++ < 2) checkRead() else null }
        assertEquals(HttpStatusCode.OK, join("""{"xuid":"$kr","gamertag":"Kr"}""").status)
        assertEquals(3, attempts, "deux échecs 1020, puis la réussite")

        app.store.failOnEnter = { checkRead() }
        val response = join("""{"xuid":"$other","gamertag":"Autre"}""")
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(RefusalCodes.CONFLICT_RETRY, SiloooJson.decodeFromString(RefusedBody.serializer(), response.bodyAsText()).reason)
    }

    @Test
    @DisplayName("Une panne inattendue donne 500 ERREUR_INTERNE sans détail")
    fun unexpectedFailureIs500() = withApp { app ->
        app.store.failOnEnter = { IllegalStateException("détail interne") }
        val response = join("""{"xuid":"$kr","gamertag":"Kr"}""")
        assertError(response, HttpStatusCode.InternalServerError, ErrorCodes.INTERNAL)
        assertTrue(!response.bodyAsText().contains("détail interne"))
    }

    // --- leave

    @Test
    @DisplayName("leave fige l'heure de départ et répond 204 sans corps")
    fun leaveStampsTheDeparture() = withApp { app ->
        joined()
        app.store.now = Instant.parse("2026-11-05T18:30:00Z")

        val response = leave("""{"xuid":"$kr"}""")

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("", response.bodyAsText())
        assertEquals(Instant.parse("2026-11-05T18:30:00Z"), app.store.players.getValue(Xuid.parse(kr)!!).lastSeenAt)
    }

    @Test
    @DisplayName("leave d'un joueur inconnu : 204 quand même, rien n'est créé")
    fun leaveUnknownPlayer() = withApp { app ->
        assertEquals(HttpStatusCode.NoContent, leave("""{"xuid":"$other"}""").status)
        assertTrue(app.store.players.isEmpty())
        assertTrue(app.economy.settled.isEmpty(), "rien à verser pour un joueur jamais vu")
    }

    @Test
    @DisplayName("leave rejoué : 204 à chaque fois, le dernier départ gagne")
    fun leaveIsIdempotentByNature() = withApp { app ->
        joined()
        app.store.now = Instant.parse("2026-11-05T18:30:00Z")
        assertEquals(HttpStatusCode.NoContent, leave("""{"xuid":"$kr"}""").status)
        app.store.now = Instant.parse("2026-11-05T18:45:00Z")
        assertEquals(HttpStatusCode.NoContent, leave("""{"xuid":"$kr"}""").status)
        assertEquals(Instant.parse("2026-11-05T18:45:00Z"), app.store.players.getValue(Xuid.parse(kr)!!).lastSeenAt)
        assertEquals(1, app.store.companies.size)
    }

    @Test
    @DisplayName("leave verse la production de l'entreprise au régime en ligne, une seule fois, sans heure de départ précédente")
    fun leaveSettlesOnline() = withApp { app ->
        val first = joined()

        assertEquals(HttpStatusCode.NoContent, leave("""{"xuid":"$kr"}""").status)

        assertEquals(listOf(TestEconomy.Settle(first.companyId, Regime.ONLINE, null)), app.economy.settled)
    }

    @Test
    @DisplayName("leave d'un joueur connu mais sans entreprise : 204, l'heure est figée, rien n'est versé")
    fun leaveWithoutCompanySettlesNothing() = withApp { app ->
        app.store.players[Xuid.parse(kr)!!] = InMemorySessionStore.PlayerRow("Kr", tutorialStep = 0, lastSeenAt = app.store.now)

        assertEquals(HttpStatusCode.NoContent, leave("""{"xuid":"$kr"}""").status)

        assertEquals(listOf(Xuid.parse(kr)!!), app.store.leaves)
        assertTrue(app.economy.settled.isEmpty())
    }

    @Test
    @DisplayName("leave : un conflit de version se retente comme join ; s'il persiste, 409 CONFLIT_REESSAYER")
    fun leaveRetriesOnConflict() = withApp { app ->
        val first = joined()
        var attempts = 0
        app.store.failOnLeave = { if (attempts++ == 0) VersionConflict() else null }

        assertEquals(HttpStatusCode.NoContent, leave("""{"xuid":"$kr"}""").status)
        assertEquals(2, attempts, "un échec, puis la réussite au second essai")
        assertEquals(listOf(TestEconomy.Settle(first.companyId, Regime.ONLINE, null)), app.economy.settled)

        attempts = 0
        app.store.failOnLeave = { attempts++; VersionConflict() }
        val response = leave("""{"xuid":"$kr"}""")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(RefusalCodes.CONFLICT_RETRY, SiloooJson.decodeFromString(RefusedBody.serializer(), response.bodyAsText()).reason)
        assertEquals(MAX_JOIN_ATTEMPTS, attempts)
    }

    @Test
    @DisplayName("leave : un corps hors contrat donne 400 REQUETE_INVALIDE")
    fun leaveRejectsBadBodies() = withApp { app ->
        for (body in listOf("pas du json", "{}", """{"xuid":"abc"}""", """{"xuid":12}""", """{"xuid":""}""")) {
            assertError(leave(body), HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST)
        }
        assertTrue(app.store.leaves.isEmpty())
    }

    @Test
    @DisplayName("Le JSON de join porte les noms du contrat")
    fun joinBodyUsesContractNames() = withApp {
        val body = SiloooJson.parseToJsonElement(join("""{"xuid":"$kr","gamertag":"Kr"}""").bodyAsText()).jsonObject
        for (name in listOf("xuid", "firstJoin", "tutorialStep", "companyId", "balances", "state", "role", "rank", "pilierUntil", "offlineCollected")) {
            assertTrue(name in body, "JoinResponse devrait porter $name")
        }
        assertEquals(kr, body.getValue("xuid").toString().trim('"'), "le XUID reste une chaîne")
    }
}
