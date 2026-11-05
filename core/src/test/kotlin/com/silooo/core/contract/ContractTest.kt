package com.silooo.core.contract

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.FactoryState
import com.silooo.core.api.GameEvent
import com.silooo.core.api.Idempotency
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.Role
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.InMemoryApiRequestStore
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.events.EVENTS_DEFAULT_LIMIT
import com.silooo.core.events.EVENTS_MAX_LIMIT
import com.silooo.core.events.GameEventKind
import com.silooo.core.events.InMemoryEventStore
import com.silooo.core.session.GAMERTAG_MAX_LENGTH
import com.silooo.core.session.InMemorySessionStore
import com.silooo.core.session.TestBootstrap
import com.silooo.core.session.TestEconomy
import com.silooo.core.siloooModule
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.lang.reflect.Modifier
import java.net.URLEncoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Le service contre `api/silooo-core.openapi.yaml`, lu à chaque exécution : le contrat est la référence, le code
 * doit lui obéir, et ce test casse quand l'un des deux bouge sans l'autre.
 *
 * Pour CHACUNE des opérations du contrat :
 *  - sans clé (ou avec une mauvaise) : 401 et un corps `Erreur` conforme ;
 *  - avec la clé et une requête minimale valide (fabriquée depuis le schéma) : un statut déclaré par
 *    l'opération, et un corps conforme à son schéma. 501 `PAS_FAIT` compte tant que la route n'est pas écrite ;
 *    500 ne compte jamais : une requête valide qui plante est un défaut, ou des faux pas branchés.
 *
 * Les routes écrites ([WRITTEN]) sont en plus vérifiées sur le fond : réponse de succès attendue, requêtes
 * hors contrat refusées en 400. Quand une session écrit une route, elle l'ajoute à [WRITTEN] et branche ses
 * faux dans [contractDependencies] : c'est le seul endroit à toucher.
 *
 * Aucune base ici : tout passe par des faux en mémoire. Le SQL a ses propres tests (`*SqlTest`).
 */
class ContractTest {
    private val contract = OpenApi.load()
    private val operations get() = contract.operations
    private val erreur by lazy { contract.responseSchema("Erreur") }
    private val refused by lazy { contract.responseSchema("Refused") }

    // --- le service tel que les tests le montent

    private val krXuid = "2535428000000001"

    /** Les événements que `pollEvents` trouve : l'un pour un joueur, l'autre pour tout le serveur. */
    private val events = listOf(
        GameEvent(1, krXuid, GameEventKind.GEMMES_RECUES.name, buildJsonObject { put("gemmes", 50) }),
        GameEvent(2, null, GameEventKind.ANNONCE.name, buildJsonObject { put("texte", "maintenance") }),
    )

    /** Le seul endroit où brancher les faux d'une route nouvellement écrite. */
    private fun contractDependencies(): Dependencies {
        val database = PassThroughDatabase()
        return Dependencies(
            database = database,
            definitions = TestDefinitions.loaded,
            apiKey = KEY,
            idempotency = Idempotency(database, InMemoryApiRequestStore()),
            economyFigures = TestEconomy(),
            companyBootstrap = TestBootstrap,
            sessionStore = InMemorySessionStore(),
            eventStore = InMemoryEventStore(events),
        )
    }

    private fun withService(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { siloooModule(contractDependencies()) }
        block()
    }

    private suspend fun ApplicationTestBuilder.send(method: String, url: String, body: String? = null, key: String? = KEY): HttpResponse =
        client.request(url) {
            this.method = HttpMethod.parse(method)
            if (key != null) header(SERVER_KEY_HEADER, key)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    // --- fabriquer la requête minimale d'une opération

    private fun text(value: JsonElement): String = (value as JsonPrimitive).content

    private fun urlFor(op: OpenApi.Operation): String {
        val path = Regex("\\{(\\w+)}").replace(op.path) { match ->
            text(contract.example(op.pathParameters.single { it.name == match.groupValues[1] }.schema))
        }
        val query = op.queryParameters.filter { it.required }.joinToString("&") {
            "${it.name}=${URLEncoder.encode(text(contract.example(it.schema)), Charsets.UTF_8)}"
        }
        return if (query.isEmpty()) path else "$path?$query"
    }

    private fun bodyFor(op: OpenApi.Operation): String? = op.body?.let { contract.example(it).toString() }

    private fun parse(body: String): JsonElement = SiloooJson.parseToJsonElement(body)

    private fun reason(body: JsonElement): String = body.jsonObject.getValue("reason").jsonPrimitive.content

    private fun assertJson(response: HttpResponse, what: String) {
        val type = response.headers["Content-Type"].orEmpty()
        assertTrue(type.startsWith("application/json"), "$what : Content-Type « $type », application/json attendu")
    }

    private fun assertConforms(schema: Map<String, Any?>, body: String, what: String) {
        val found = contract.violations(schema, parse(body))
        assertTrue(found.isEmpty(), "$what s'écarte du contrat :\n  " + found.joinToString("\n  ") + "\ncorps : $body")
    }

    // --- le contrat lui-même

    @Test
    @DisplayName("Le contrat compte 29 opérations, chacune déclare 400, 401, 500 et 501 avec le corps Erreur")
    fun twentyNineOperations() {
        assertEquals(29, operations.size, "Le contrat gagne ou perd une opération : relire ce test et tous ceux qui comptent")
        assertEquals(operations.size, operations.map { it.id }.toSet().size, "operationId en double")
        for (op in operations) {
            for (code in listOf(400, 401, 500, 501)) {
                assertEquals(erreur, op.responses[code], "$op doit déclarer $code avec le corps Erreur")
            }
        }
    }

    @Test
    @DisplayName("Les refus 403 et 409 du contrat portent tous le corps Refused")
    fun refusalsUseTheRefusedBody() {
        for (op in operations) for (code in listOf(403, 409)) {
            op.responses[code]?.let { assertEquals(refused, it, "$op : le $code doit avoir le corps Refused") }
        }
    }

    @Test
    @DisplayName("L'en-tête de clé du contrat est celui que le service lit")
    fun keyHeader() {
        assertEquals(SERVER_KEY_HEADER, contract.at("components", "securitySchemes", "cleServeur", "name"))
        assertEquals("header", contract.at("components", "securitySchemes", "cleServeur", "in"))
    }

    @Test
    @DisplayName("Les codes du service sont exactement ceux du contrat : Erreur, Refused, événements, états, rôles")
    fun codesMatchTheContract() {
        fun constantsOf(type: Class<*>): Set<String> = type.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.apply { isAccessible = true }.get(null) as String }
            .toSet()

        assertEquals(contract.enumOf(erreur, "reason"), constantsOf(ErrorCodes::class.java), "ErrorCodes et le corps Erreur")
        assertEquals(contract.enumOf(refused, "reason"), RefusalCodes.ALL, "RefusalCodes.ALL et le corps Refused")
        assertEquals(contract.enumOf(contract.schema("GameEvent"), "kind"), GameEventKind.entries.map { it.name }.toSet(), "GameEventKind et GameEvent.kind")
        assertEquals(contract.enumOf(contract.schema("Factory"), "state"), FactoryState.entries.map { it.name }.toSet(), "FactoryState et Factory.state")
        assertEquals(contract.enumOf(contract.schema("JoinResponse"), "role"), Role.entries.map { it.name }.toSet(), "Role et JoinResponse.role")
    }

    @Test
    @DisplayName("Les bornes que le code recopie du contrat sont celles du contrat : limit de pollEvents, longueur du gamertag")
    fun limitsMatchTheContract() {
        val limit = operations.single { it.id == "pollEvents" }.queryParameters.single { it.name == "limit" }.schema
        assertEquals(EVENTS_DEFAULT_LIMIT, (limit["default"] as Number).toInt())
        assertEquals(EVENTS_MAX_LIMIT, (limit["maximum"] as Number).toInt())

        @Suppress("UNCHECKED_CAST")
        val properties = operations.single { it.id == "join" }.body!!["properties"] as Map<String, Map<String, Any?>>
        assertEquals(GAMERTAG_MAX_LENGTH, (properties.getValue("gamertag")["maxLength"] as Number).toInt())
    }

    @Test
    @DisplayName("Toute opération qui modifie quelque chose porte requestId et actor, sauf heartbeat, join et leave")
    fun mutationsCarryARequestId() {
        val byNature = setOf("heartbeat", "join", "leave")
        val mutating = operations.filter { it.method != "GET" }
        val without = mutating.filter { !carriesRequestId(it) }.map { it.id }.toSet()
        assertEquals(byNature, without, "Les seules opérations sans requestId (idempotentes par nature) sont heartbeat, join et leave")

        for (op in mutating.filter { it.id !in byNature }) {
            val body = op.body?.let { contract.requiredProperties(it) }.orEmpty()
            val hasActor = "actor" in body || op.queryParameters.any { it.name == "actor" && it.required }
            assertTrue(hasActor, "$op : requestId sans actor")
        }
        for (op in operations.filter { it.method == "GET" }) {
            assertFalse(carriesRequestId(op), "$op : un GET ne porte pas de requestId")
        }
    }

    private fun carriesRequestId(op: OpenApi.Operation): Boolean =
        op.queryParameters.any { it.name == "requestId" && it.required } ||
            (op.body?.let { "requestId" in contract.requiredProperties(it) } ?: false)

    @Test
    @DisplayName("409 CONFLIT_REESSAYER : toute opération qui peut le rendre le déclare dans le contrat")
    fun knownGapsOf409() {
        // CONFLIT_REESSAYER sort de l'idempotence (même requestId en cours) et du verrou optimiste : toute opération
        // avec requestId peut le rendre, et join aussi (conflits répétés). Le contrat le déclare sur chacune depuis
        // le 5 novembre 2026 : une opération nouvelle avec requestId doit déclarer son 409, sinon ce test le dit.
        val canConflict = operations.filter { carriesRequestId(it) || it.id == "join" }
        val gaps = canConflict.filter { 409 !in it.responses }.map { it.id }.toSet()
        assertEquals(KNOWN_409_GAPS, gaps)
    }

    @Test
    @DisplayName("Le vérificateur de schémas voit les écarts : un contrôle qui ne trouve jamais rien ne prouve rien")
    fun validatorIsNotVacuous() {
        val join = contract.schema("JoinResponse")
        val valid = contract.example(join).jsonObject
        assertEquals(emptyList<String>(), contract.violations(join, valid))

        fun broken(change: (MutableMap<String, JsonElement>) -> Unit): List<String> =
            contract.violations(join, JsonObject(valid.toMutableMap().also(change)))

        assertTrue(broken { it.remove("balances") }.isNotEmpty(), "propriété obligatoire absente")
        assertTrue(broken { it["firstJoin"] = JsonPrimitive("oui") }.isNotEmpty(), "mauvais type")
        assertTrue(broken { it["xuid"] = JsonPrimitive("pas-un-xuid") }.isNotEmpty(), "motif")
        assertTrue(broken { it["role"] = JsonPrimitive("ROI") }.isNotEmpty(), "valeur hors enum")
        assertTrue(broken { it["pilierUntil"] = JsonPrimitive("demain") }.isNotEmpty(), "date")
        assertTrue(broken { it["inconnu"] = JsonPrimitive(1) }.isNotEmpty(), "propriété hors contrat")
        assertTrue(broken { it["companyId"] = JsonPrimitive("12") }.isNotEmpty(), "entier attendu")
        assertTrue(contract.violations(contract.schema("CompanyState"), JsonObject(emptyMap())).size >= 10, "toutes les propriétés obligatoires manquent")
        assertTrue(contract.violations(erreur, parse("""{"reason":"AUTRE"}""")).isNotEmpty(), "code hors enum")
    }

    // --- toutes les opérations, une par une

    @TestFactory
    @DisplayName("Sans clé : 401 CLE_INVALIDE et un corps Erreur")
    fun everyOperationRequiresTheKey(): List<DynamicTest> = operations.flatMap { op ->
        listOf(null, "mauvaise-cle").map { key ->
            dynamicTest("${op.id} (${if (key == null) "sans clé" else "mauvaise clé"})") {
                withService {
                    val response = send(op.method, urlFor(op), bodyFor(op), key)
                    val body = response.bodyAsText()
                    assertEquals(401, response.status.value, "$op sans clé valide : $body")
                    assertJson(response, "$op")
                    assertConforms(erreur, body, "$op (401)")
                    assertEquals(ErrorCodes.INVALID_KEY, reason(parse(body)))
                }
            }
        }
    }

    @TestFactory
    @DisplayName("Avec la clé et une requête minimale valide : un statut déclaré, un corps conforme")
    fun everyOperationAnswersWithinTheContract(): List<DynamicTest> = operations.map { op ->
        dynamicTest("${op.id} : ${op.method} ${op.path}") {
            // La requête fabriquée doit elle-même être conforme au contrat, sinon le test ne prouve rien.
            op.body?.let { assertConforms(it, bodyFor(op)!!, "la requête fabriquée pour $op") }

            withService {
                val response = send(op.method, urlFor(op), bodyFor(op))
                val status = response.status.value
                val body = response.bodyAsText()

                assertTrue(status in op.responses.keys, "$op : le statut $status n'est pas déclaré (déclarés : ${op.responses.keys}) ; corps : $body")
                assertNotEquals(500, status, "$op : une requête valide ne doit pas donner 500 ; corps : $body")

                val schema = op.responses.getValue(status)
                if (schema == null) {
                    assertEquals("", body, "$op : le $status est déclaré sans corps")
                } else {
                    assertJson(response, "$op")
                    assertConforms(schema, body, "$op ($status)")
                }

                val expected = WRITTEN[op.id]
                if (expected != null) {
                    assertEquals(expected, status, "$op est écrite : elle doit rendre son succès ; corps : $body")
                } else if (status == 501) {
                    assertEquals(ErrorCodes.NOT_DONE, reason(parse(body)))
                }
            }
        }
    }

    @TestFactory
    @DisplayName("Routes écrites : une requête hors contrat donne 400 REQUETE_INVALIDE avec un corps Erreur")
    fun writtenRoutesRefuseBadRequests(): List<DynamicTest> {
        val bad = mapOf(
            "heartbeat" to listOf(
                "{}", "pas du json", """{"serverId":"bds-1"}""", """{"serverId":"bds-1","onlineXuids":["abc"]}""",
                """{"serverId":"","onlineXuids":[]}""",
            ),
            "join" to listOf(
                "{}", "[]", """{"xuid":"$krXuid"}""", """{"xuid":"abc","gamertag":"Kr"}""", """{"xuid":"$krXuid","gamertag":""}""",
                """{"xuid":"$krXuid","gamertag":"${"x".repeat(GAMERTAG_MAX_LENGTH + 1)}"}""", """{"xuid":12,"gamertag":"Kr"}""",
            ),
            "leave" to listOf("{}", "pas du json", """{"xuid":"abc"}""", """{"xuid":"123456789012345678901"}"""),
        )
        val queries = listOf("", "?limit=10", "?after=abc", "?after=-1", "?after=0&limit=0", "?after=0&limit=${EVENTS_MAX_LIMIT + 1}", "?after=0&limit=abc")

        return operations.filter { it.id in bad }.flatMap { op ->
            bad.getValue(op.id).map { body ->
                dynamicTest("${op.id} : ${body.take(50)}") { assertBadRequest(op, urlFor(op), body) }
            }
        } + queries.map { query ->
            dynamicTest("pollEvents : $query") { assertBadRequest(operations.single { it.id == "pollEvents" }, "/v1/events$query", null) }
        }
    }

    private fun assertBadRequest(op: OpenApi.Operation, url: String, body: String?) = withService {
        val response = send(op.method, url, body)
        val answer = response.bodyAsText()
        assertEquals(400, response.status.value, "$op avec « $body » ($url) : $answer")
        assertJson(response, "$op")
        assertConforms(erreur, answer, "$op (400)")
        assertEquals(ErrorCodes.BAD_REQUEST, reason(parse(answer)))
    }

    // --- les routes écrites, sur le fond

    @Test
    @DisplayName("heartbeat : 204 sans corps")
    fun heartbeat() = withService {
        val response = send("POST", "/v1/server/heartbeat", """{"serverId":"bds-1","onlineXuids":["$krXuid"]}""")
        assertEquals(204, response.status.value)
        assertEquals("", response.bodyAsText())
        assertEquals(204, send("POST", "/v1/server/heartbeat", """{"serverId":"bds-1","onlineXuids":[]}""").status.value)
    }

    @Test
    @DisplayName("join : le corps suit JoinResponse à la lettre ; rejoué, firstJoin passe à faux ; leave rend 204 sans corps")
    fun joinAndLeave() = withService {
        val request = """{"xuid":"$krXuid","gamertag":"Kr"}"""

        val first = send("POST", "/v1/session/join", request)
        val firstBody = first.bodyAsText()
        assertEquals(200, first.status.value, firstBody)
        assertConforms(contract.schema("JoinResponse"), firstBody, "join")
        val firstJson = parse(firstBody).jsonObject
        assertEquals(krXuid, firstJson.getValue("xuid").jsonPrimitive.content)
        assertEquals("true", firstJson.getValue("firstJoin").jsonPrimitive.content)
        assertEquals("PDG", firstJson.getValue("role").jsonPrimitive.content)

        val second = parse(send("POST", "/v1/session/join", request).bodyAsText()).jsonObject
        assertEquals("false", second.getValue("firstJoin").jsonPrimitive.content)
        assertEquals(firstJson.getValue("companyId"), second.getValue("companyId"))

        val leave = send("POST", "/v1/session/leave", """{"xuid":"$krXuid"}""")
        assertEquals(204, leave.status.value)
        assertEquals("", leave.bodyAsText())
        assertEquals(204, send("POST", "/v1/session/leave", """{"xuid":"2535428000000099"}""").status.value, "joueur inconnu : 204 quand même")
    }

    @Test
    @DisplayName("pollEvents : les événements du plus ancien au plus récent, xuid nul pour tout le serveur, curseur au dernier id")
    fun pollEvents() = withService {
        val page = send("GET", "/v1/events?after=0&limit=10")
        val body = page.bodyAsText()
        assertEquals(200, page.status.value, body)
        assertConforms(operations.single { it.id == "pollEvents" }.responses.getValue(200)!!, body, "pollEvents")
        val json = parse(body).jsonObject
        assertEquals(listOf("1", "2"), json.getValue("events").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals("2", json.getValue("cursor").jsonPrimitive.content)
        assertEquals(krXuid, json.getValue("events").jsonArray[0].jsonObject.getValue("xuid").jsonPrimitive.content)
        assertEquals("null", json.getValue("events").jsonArray[1].jsonObject.getValue("xuid").toString())

        val caughtUp = parse(send("GET", "/v1/events?after=2").bodyAsText()).jsonObject
        assertEquals(0, caughtUp.getValue("events").jsonArray.size)
        assertEquals("2", caughtUp.getValue("cursor").jsonPrimitive.content, "rien de neuf : le curseur ne bouge pas")

        assertEquals(200, send("GET", "/v1/events?after=0&limit=$EVENTS_MAX_LIMIT").status.value)
        assertEquals(200, send("GET", "/v1/events?after=0&limit=1").status.value)
    }

    @Test
    @DisplayName("Une route qui n'existe pas dans le contrat répond 501 PAS_FAIT avec la clé, 401 sans elle")
    fun unknownRoutes() = withService {
        val with = send("GET", "/v1/nulle-part")
        assertEquals(501, with.status.value)
        assertEquals(ErrorCodes.NOT_DONE, reason(parse(with.bodyAsText())))
        assertEquals(401, send("GET", "/v1/nulle-part", key = null).status.value)
    }

    private companion object {
        const val KEY = "cle-de-test"

        /** Les routes écrites et le succès qu'une requête minimale valide leur fait rendre. */
        val WRITTEN = mapOf("heartbeat" to 204, "join" to 200, "leave" to 204, "pollEvents" to 200)

        /**
         * Les opérations qui peuvent répondre 409 CONFLIT_REESSAYER sans le déclarer dans le contrat. Vide : les six
         * trous relevés par la session des routes ont été comblés dans l'OpenAPI par la direction. Une entrée ici est
         * un trou connu, à signaler à la direction, jamais une tolérance durable.
         */
        val KNOWN_409_GAPS = emptySet<String>()
    }
}
