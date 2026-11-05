package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.db.Database
import com.silooo.core.db.FakeDatabase
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.server.ServerPresence
import com.silooo.core.session.InMemorySessionStore
import com.silooo.core.vote.InMemoryVoteStore
import com.silooo.core.vote.VoteRow
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `GET /site/direct.json`, ce que le bandeau direct relit toutes les trente secondes : les joueurs en ligne, l'état du serveur et
 * la Vote party du jour. Sans Docker : la présence est celle du service, les votes un faux en mémoire, la base un faux
 * ([FakeDatabase] refuse toute transaction, [PassThroughDatabase] les exécute). L'objectif vient des définitions.
 */
class SiteDirectTest {
    private val now = Instant.parse("2026-11-05T12:00:00Z")
    private val objective = TestDefinitions.loaded.vote.party.votesPerDay
    private val route = "/site/direct.json"

    /** Un XUID et un pseudo reconnaissables : aucun ne doit sortir dans la réponse. */
    private val xuid = "2535409999999991"
    private val gamertag = "Pseudo Secret"

    private class Rig(val clock: MutableClock, val presence: ServerPresence, val votes: InMemoryVoteStore)

    private fun withSite(
        mode: SiteMode = SiteMode.OUVERT,
        database: Database = PassThroughDatabase(),
        block: suspend ApplicationTestBuilder.(Rig, HttpClient) -> Unit,
    ) = testApplication {
        val clock = MutableClock(now)
        val rig = Rig(clock, ServerPresence(clock), InMemoryVoteStore(InMemorySessionStore()))
        application {
            siteModule(
                Dependencies(
                    database, "cle", serverPresence = rig.presence, definitions = TestDefinitions.loaded, clock = clock,
                    siteMode = mode, voteStore = rig.votes,
                ),
            )
        }
        block(rig, createClient { followRedirects = false })
    }

    private fun InMemoryVoteStore.vote(at: Instant = now) {
        rows += VoteRow(rows.size + 1L, null, gamertag, "premier", at, false)
    }

    private suspend fun HttpResponse.direct(): JsonObject {
        assertEquals(HttpStatusCode.OK, status)
        return Json.parseToJsonElement(bodyAsText()).jsonObject
    }

    private fun assertShape(direct: JsonObject) {
        assertEquals(setOf("joueurs", "etat", "voteParty", "version", "dernierPrestige"), direct.keys, "les champs sont toujours présents")
        assertEquals(JsonNull, direct["version"], "le signal de vie ne porte pas encore la version")
        assertEquals(JsonNull, direct["dernierPrestige"], "aucune lecture n'expose encore le dernier prestige")
    }

    @Test
    @DisplayName("Fermé, sans base : pas de joueurs, état « ferme », pas de Vote party ; la base qui refuse ne casse pas la réponse")
    fun closedWithoutDatabase() = withSite(database = FakeDatabase()) { rig, client ->
        rig.votes.vote()
        val direct = client.get(route).direct()
        assertShape(direct)
        assertEquals(JsonNull, direct["joueurs"])
        assertEquals("ferme", direct.getValue("etat").jsonPrimitive.content)
        assertEquals(JsonNull, direct["voteParty"], "FakeDatabase refuse la transaction : la Vote party manque")
    }

    @Test
    @DisplayName("Ouvert : trois joueurs, les votes du jour UTC (pas ceux d'hier) et l'objectif des définitions")
    fun openWithPlayersAndVotes() = withSite { rig, client ->
        rig.presence.record("bds-1", listOf("1", "2", "3"))
        rig.votes.vote()
        rig.votes.vote()
        rig.votes.vote(Instant.parse("2026-11-04T23:59:59Z"))
        val direct = client.get(route).direct()
        assertShape(direct)
        assertEquals(3, direct.getValue("joueurs").jsonPrimitive.int)
        assertEquals("ouvert", direct.getValue("etat").jsonPrimitive.content)
        val party = direct.getValue("voteParty").jsonObject
        assertEquals(setOf("votes", "objectif", "atteinte"), party.keys)
        assertEquals(2, party.getValue("votes").jsonPrimitive.int)
        assertEquals(objective, party.getValue("objectif").jsonPrimitive.int)
        assertFalse(party.getValue("atteinte").jsonPrimitive.boolean)
    }

    @Test
    @DisplayName("La Vote party est atteinte quand les votes du jour égalent l'objectif, pas avant")
    fun partyThreshold() {
        for ((votes, reached) in listOf(0 to false, objective - 1 to false, objective to true, objective + 1 to true)) {
            withSite { rig, client ->
                repeat(votes) { rig.votes.vote() }
                val party = client.get(route).direct().getValue("voteParty").jsonObject
                assertEquals(votes, party.getValue("votes").jsonPrimitive.int)
                assertEquals(objective, party.getValue("objectif").jsonPrimitive.int)
                assertEquals(reached, party.getValue("atteinte").jsonPrimitive.boolean, "$votes vote(s) pour un objectif de $objective")
            }
        }
    }

    @Test
    @DisplayName("Le jour des votes est le jour UTC : 23:59:59 la veille ne compte pas, 00:00:00 le jour même compte")
    fun voteDayIsUtc() = withSite { rig, client ->
        rig.votes.vote(Instant.parse("2026-11-04T23:59:59.999Z"))
        rig.votes.vote(Instant.parse("2026-11-05T00:00:00Z"))
        rig.votes.vote(Instant.parse("2026-11-05T11:59:59Z"))
        assertEquals(2, client.get(route).direct().getValue("voteParty").jsonObject.getValue("votes").jsonPrimitive.int)
    }

    @Test
    @DisplayName("Serveur fermé, base joignable : la Vote party reste dans la réponse")
    fun closedServerKeepsTheParty() = withSite { rig, client ->
        repeat(4) { rig.votes.vote() }
        val direct = client.get(route).direct()
        assertEquals(JsonNull, direct["joueurs"])
        assertEquals("ferme", direct.getValue("etat").jsonPrimitive.content)
        assertEquals(4, direct.getValue("voteParty").jsonObject.getValue("votes").jsonPrimitive.int)
    }

    @Test
    @DisplayName("L'état suit la présence : ouvert tant qu'un signal est assez récent, fermé au-delà de deux minutes")
    fun stateFollowsThePresence() = withSite { rig, client ->
        rig.presence.record("bds-1", listOf("1", "2"))
        rig.clock.advance(Duration.ofSeconds(110))
        val open = client.get(route).direct()
        assertEquals("ouvert", open.getValue("etat").jsonPrimitive.content)
        assertEquals(2, open.getValue("joueurs").jsonPrimitive.int)
        rig.clock.advance(Duration.ofSeconds(20))
        val closed = client.get(route).direct()
        assertEquals("ferme", closed.getValue("etat").jsonPrimitive.content)
        assertEquals(JsonNull, closed["joueurs"])
    }

    @Test
    @DisplayName("En-têtes : 200, application/json, un seul Cache-Control « public, max-age=30 », et les en-têtes de sécurité du site")
    fun headers() = withSite { rig, client ->
        rig.presence.record("bds-1", listOf("1"))
        val response = client.get(route)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        assertEquals(listOf("public, max-age=30"), response.headers.getAll(HttpHeaders.CacheControl))
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("same-origin", response.headers["Referrer-Policy"])
        assertEquals(CONTENT_SECURITY_POLICY, response.headers["Content-Security-Policy"])
    }

    @Test
    @DisplayName("La route répond aussi en attente, sans changer de forme ni de contenu")
    fun answersInWaitingMode() {
        withSite(SiteMode.ATTENTE, database = FakeDatabase()) { _, client ->
            val response = client.get(route)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            assertEquals("public, max-age=30", response.headers[HttpHeaders.CacheControl])
            val direct = response.direct()
            assertShape(direct)
            assertEquals("ferme", direct.getValue("etat").jsonPrimitive.content)
            assertEquals(JsonNull, direct["voteParty"])
        }
        val bodies = SiteMode.entries.map { mode ->
            var body = ""
            withSite(mode) { rig, client ->
                rig.presence.record("bds-1", listOf("1", "2", "3"))
                repeat(2) { rig.votes.vote() }
                body = client.get(route).bodyAsText()
            }
            body
        }
        assertEquals(bodies[0], bodies[1], "le mode ne change rien à la réponse")
        val direct = Json.parseToJsonElement(bodies[0]).jsonObject
        assertEquals(3, direct.getValue("joueurs").jsonPrimitive.int)
        assertEquals(2, direct.getValue("voteParty").jsonObject.getValue("votes").jsonPrimitive.int)
    }

    @Test
    @DisplayName("La réponse ne contient ni XUID ni pseudo : seulement des nombres et l'état")
    fun noPersonalData() = withSite { rig, client ->
        rig.presence.record("bds-1", listOf(xuid))
        rig.votes.vote()
        val body = client.get(route).bodyAsText()
        assertFalse(body.contains(xuid), body)
        assertFalse(body.contains(gamertag), body)
        assertTrue(body.startsWith("{") && body.endsWith("}"), body)
    }
}
