package com.silooo.core.contract

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.Balances
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.FactoryState
import com.silooo.core.api.GameEvent
import com.silooo.core.api.Idempotency
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.Role
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.InMemoryApiRequestStore
import com.silooo.core.db.RecordingConnection
import com.silooo.core.db.RecordingDatabase
import com.silooo.core.db.Xuid
import com.silooo.core.definitions.DefinitionsChecks
import com.silooo.core.definitions.StaffRarity
import com.silooo.core.definitions.StaffRole
import com.silooo.core.economy.BuyAmount
import com.silooo.core.economy.CompanyService
import com.silooo.core.economy.EconomyEngine
import com.silooo.core.economy.FACTORY_ID_MAX_LENGTH
import com.silooo.core.economy.InMemoryCompanyStore
import com.silooo.core.events.EVENTS_DEFAULT_LIMIT
import com.silooo.core.events.EVENTS_MAX_LIMIT
import com.silooo.core.events.GameEventKind
import com.silooo.core.events.InMemoryEventStore
import com.silooo.core.findings.InMemoryFindingStore
import com.silooo.core.mission.InMemoryMissionStore
import com.silooo.core.orders.InMemoryOrderStore
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.GAMERTAG_MAX_LENGTH
import com.silooo.core.session.InMemorySessionStore
import com.silooo.core.session.Membership
import com.silooo.core.session.Production
import com.silooo.core.session.StaffRecord
import com.silooo.core.session.TestBootstrap
import com.silooo.core.session.TestEconomy
import com.silooo.core.siloooModule
import com.silooo.core.staff.InMemoryStaffStore
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

    /** Un joueur qui n'est membre d'aucune entreprise. */
    private val stranger = "2535428000000099"

    /** Un `requestId` bien formé, différent de [ID] et d'un numéro à l'autre : une action par numéro, jamais un rejeu par hasard. */
    private fun id(n: Int): String = "6f1c2b7e-0d4a-4a53-9c1e-3f6a8d2b5e%02d".format(n)

    /** Les événements que `pollEvents` trouve : l'un pour un joueur, l'autre pour tout le serveur. */
    private val events = listOf(
        GameEvent(1, krXuid, GameEventKind.GEMMES_RECUES.name, buildJsonObject { put("gemmes", 50) }),
        GameEvent(2, null, GameEventKind.ANNONCE.name, buildJsonObject { put("texte", "maintenance") }),
    )

    /**
     * Le seul endroit où brancher les faux d'une route nouvellement écrite. La base est une [RecordingDatabase] : les
     * routes d'économie écrivent l'entreprise par `updateCompany`, qu'une connexion qui note les écritures suffit à
     * satisfaire ; `join` et `pollEvents` n'y touchent jamais.
     */
    private fun contractDependencies(sessions: InMemorySessionStore, economy: TestEconomy, missions: InMemoryMissionStore): Dependencies {
        val database = RecordingDatabase()
        return Dependencies(
            database = database,
            definitions = TestDefinitions.loaded,
            apiKey = KEY,
            idempotency = Idempotency(database, InMemoryApiRequestStore()),
            economyFigures = economy,
            companyBootstrap = TestBootstrap,
            sessionStore = sessions,
            eventStore = InMemoryEventStore(events),
            companyStore = InMemoryCompanyStore(sessions),
            missionStore = missions,
            orderStore = InMemoryOrderStore(sessions),
            findingStore = InMemoryFindingStore(sessions),
            clock = MutableClock(sessions.now),
            staffStore = InMemoryStaffStore(sessions),
        )
    }

    /**
     * Monte le service. [operationId] pose l'état de départ dont la requête minimale de cette opération a besoin pour
     * réussir (voir [seedFor]) ; sans lui, la base de faux est vide.
     */
    private fun withService(
        operationId: String? = null,
        extraSeed: (InMemorySessionStore) -> Unit = {},
        inspect: (InMemorySessionStore, InMemoryMissionStore) -> Unit = { _, _ -> },
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        val sessions = InMemorySessionStore()
        val economy = TestEconomy(sessions)
        if (operationId != null) seedFor(operationId, sessions, economy)
        extraSeed(sessions)
        val missions = InMemoryMissionStore(sessions)
        inspect(sessions, missions)
        application { siloooModule(contractDependencies(sessions, economy, missions)) }
        block()
    }

    /**
     * L'état qui fait réussir la requête minimale des routes d'économie : l'entreprise 1 (le `companyId` d'exemple du
     * contrat), son PDG [krXuid], et l'usine `factoryId` d'exemple du contrat, dans l'état que chaque opération veut
     * (en ruine pour `restoreStep`, active et payable pour `buyLevels`). Le prix vient du moteur, pas d'un littéral.
     */
    private fun seedFor(operationId: String, sessions: InMemorySessionStore, economy: TestEconomy) {
        if (operationId == "getMissions" || operationId == "getFindings") {
            // Un joueur déjà inscrit, sans entreprise : les missions et le Carnet ne dépendent que de lui.
            sessions.enterPlayer(RecordingConnection().connection, Xuid.parse(krXuid)!!, "Kr")
            return
        }
        val onCompany = operationId in setOf("getCompany", "collect", "buyLevels", "restoreStep", "prestigePreview", "prestige", "getCandidates", "hire", "assignStaff", "fireStaff", "deliverOrder")
        if (!onCompany) return
        val owner = Xuid.parse(krXuid)!!
        val connection = RecordingConnection().connection
        val companyId = sessions.createCompany(connection, owner, "Entreprise de test", TestBootstrap).companyId
        sessions.balances[owner] = Balances(0, 0, 0, 0)

        val factoryId = text(contract.example(operations.single { it.id == "buyLevels" }.pathParameters.single { it.name == "factoryId" }.schema))
        when (operationId) {
            "collect" -> economy.pending = Production(earned = 1.0, countedHours = 1.0, at = sessions.now.plusSeconds(60))
            "buyLevels" -> {
                sessions.putFactory(companyId, FactoryRecord(factoryId, FactoryState.ACTIVE, 0, 1, null))
                val price = EconomyEngine(TestDefinitions.loaded).levelsCost(factoryId, 1, 1)
                sessions.companies[companyId] = sessions.companies.getValue(companyId).copy(donar = price)
            }
            "restoreStep" -> sessions.putFactory(companyId, FactoryRecord(factoryId, FactoryState.EN_RUINE, 0, 0, null))
            // De quoi payer la livraison minimale (une unité) quel que soit le prix que le moteur en demande.
            "deliverOrder" -> sessions.companies[companyId] = sessions.companies.getValue(companyId).copy(donar = 1_000_000.0)
            // Assez de Donar pour n'importe quelle embauche au revenu fictif de TestEconomy ; l'usine d'exemple du contrat est
            // active, avec un poste libre : la requête minimale n'en demande aucune, les tests qui affectent s'en servent.
            "hire" -> {
                sessions.putFactory(companyId, FactoryRecord(factoryId, FactoryState.ACTIVE, 0, 1, null))
                sessions.companies[companyId] = sessions.companies.getValue(companyId).copy(donar = RICH)
            }
            // Le membre 1, sans usine, que `assignStaff` envoie à l'usine d'exemple et que `fireStaff` renvoie.
            "assignStaff", "fireStaff" -> {
                sessions.putFactory(companyId, FactoryRecord(factoryId, FactoryState.ACTIVE, 0, 1, null))
                sessions.staff[companyId] = mutableListOf(StaffRecord(1, null, "Membre de test", "ouvrier-casque", StaffRole.SALARIE, StaffRarity.COMMUN))
            }
            // Le seuil de la course 1 vient du moteur ; un multiple le dépasse sans en dépendre au centime.
            "prestigePreview", "prestige" -> sessions.companies[companyId] = sessions.companies.getValue(companyId)
                .copy(donarEarned = EconomyEngine(TestDefinitions.loaded).prestigeThreshold(1) * 4)
        }
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
    @DisplayName("Le contrat compte 31 opérations, chacune déclare 400, 401, 500 et 501 avec le corps Erreur")
    fun thirtyOperations() {
        assertEquals(31, operations.size, "Le contrat gagne ou perd une opération : relire ce test et tous ceux qui comptent")
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
        assertEquals(contract.enumOf(contract.schema("Staff"), "rarity"), StaffRarity.entries.map { it.name }.toSet(), "StaffRarity et Staff.rarity")
        // Le contrat connaît aussi DIRECTEUR, que le jeu ne fait pas encore embaucher : tous les rôles du service y sont, pas l'inverse.
        assertTrue(contract.enumOf(contract.schema("Staff"), "role").containsAll(StaffRole.entries.map { it.name }), "StaffRole dans Staff.role")
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

        val restore = operations.single { it.id == "restoreStep" }.body!!
        assertEquals(1, (contract.propertyOf(restore, "step")["minimum"] as Number).toInt(), "restoreStep : la première étape")
        assertEquals(CompanyService.LAST_RESTORE_STEP, (contract.propertyOf(restore, "step")["maximum"] as Number).toInt(), "restoreStep : la dernière étape")
        val levels = operations.single { it.id == "buyLevels" }.body!!
        assertEquals(contract.enumOf(levels, "amount"), BuyAmount.entries.map { it.wire }.toSet(), "buyLevels : les valeurs de amount")

        val slot = operations.single { it.id == "hire" }.pathParameters.single { it.name == "slot" }.schema
        assertEquals(1, (slot["minimum"] as Number).toInt(), "hire : la première place")
        assertEquals(DefinitionsChecks.AGENCY_SLOTS_MAX, (slot["maximum"] as Number).toInt(), "hire : la dernière place")
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
        // avec requestId peut le rendre, et join aussi (conflits répétés). leave aussi depuis qu'il verse la production
        // de l'entreprise par le verrou optimiste. Le contrat le déclare sur chacune depuis le 5 novembre 2026, sauf leave :
        // une opération nouvelle avec requestId doit déclarer son 409, sinon ce test le dit.
        val canConflict = operations.filter { carriesRequestId(it) || it.id == "join" || it.id == "leave" }
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

            withService(op.id) {
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
            "collect" to listOf(
                "{}", "pas du json", """{"requestId":"$ID"}""", """{"actor":"$krXuid"}""",
                """{"requestId":"abc","actor":"$krXuid"}""", """{"requestId":"$ID","actor":"abc"}""",
            ),
            "prestige" to listOf(
                "{}", "pas du json", """{"requestId":"$ID"}""", """{"actor":"$krXuid"}""",
                """{"requestId":"abc","actor":"$krXuid"}""", """{"requestId":"$ID","actor":"abc"}""",
            ),
            "buyLevels" to listOf(
                "{}", """{"requestId":"$ID","actor":"$krXuid"}""", """{"requestId":"$ID","actor":"$krXuid","amount":"2"}""",
                """{"requestId":"$ID","actor":"$krXuid","amount":1}""", """{"requestId":"abc","actor":"$krXuid","amount":"1"}""",
            ),
            "deliverOrder" to listOf(
                "{}", "pas du json", """{"requestId":"$ID","actor":"$krXuid"}""", """{"actor":"$krXuid","quantity":1}""",
                """{"requestId":"$ID","quantity":1}""", """{"requestId":"abc","actor":"$krXuid","quantity":1}""",
                """{"requestId":"$ID","actor":"abc","quantity":1}""", """{"requestId":"$ID","actor":"$krXuid","quantity":0}""",
                """{"requestId":"$ID","actor":"$krXuid","quantity":-1}""", """{"requestId":"$ID","actor":"$krXuid","quantity":1.5}""",
                """{"requestId":"$ID","actor":"$krXuid","quantity":"un"}""",
            ),
            "restoreStep" to listOf(
                "{}", """{"requestId":"$ID","actor":"$krXuid"}""", """{"requestId":"$ID","actor":"$krXuid","step":0}""",
                """{"requestId":"$ID","actor":"$krXuid","step":5}""", """{"requestId":"$ID","actor":"$krXuid","step":"un"}""",
            ),
            "hire" to listOf(
                "{}", "pas du json", """{"requestId":"$ID"}""", """{"actor":"$krXuid"}""",
                """{"requestId":"abc","actor":"$krXuid"}""", """{"requestId":"$ID","actor":"abc"}""",
                """{"requestId":"$ID","actor":"$krXuid","factoryId":""}""",
                """{"requestId":"$ID","actor":"$krXuid","factoryId":"${"x".repeat(FACTORY_ID_MAX_LENGTH + 1)}"}""",
                """{"requestId":"$ID","actor":"$krXuid","factoryId":12}""",
                """{"requestId":"$ID","actor":"$krXuid","factoryId":"usine-inconnue"}""",
                """{"requestId":"$ID","actor":"$stranger"}""",
            ),
            "assignStaff" to listOf(
                "{}", "pas du json", """{"requestId":"$ID","actor":"$krXuid"}""",
                """{"requestId":"abc","actor":"$krXuid","factoryId":"scierie"}""", """{"requestId":"$ID","actor":"abc","factoryId":"scierie"}""",
                """{"requestId":"$ID","actor":"$krXuid","factoryId":""}""", """{"requestId":"$ID","actor":"$krXuid","factoryId":3}""",
                """{"requestId":"$ID","actor":"$krXuid","factoryId":"usine-inconnue"}""",
                """{"requestId":"$ID","actor":"$stranger","factoryId":"scierie"}""",
            ),
        )
        // getCompany n'a pas de corps : ses requêtes hors contrat sont dans l'URL, ou visent une entreprise qui n'existe pas.
        val badCompanyUrls = listOf("/v1/companies/0", "/v1/companies/-1", "/v1/companies/abc", "/v1/companies/99")
        // Un XUID mal formé, trop long, ou d'un joueur que le service ne connaît pas.
        val badMissionUrls = listOf("/v1/players/abc/missions", "/v1/players/-1/missions", "/v1/players/123456789012345678901/missions", "/v1/players/$krXuid/missions")
        val badFindingUrls = listOf("/v1/players/abc/findings", "/v1/players/-1/findings", "/v1/players/123456789012345678901/findings", "/v1/players/$krXuid/findings")
        val badOrderQueries = listOf("?xuid=abc", "?xuid=-1", "?xuid=123456789012345678901", "?xuid=")
        // Le personnel : une place hors 1 à 5, un membre ou une entreprise qui n'existent pas, un acteur étranger, un paramètre qui manque.
        val hireBody = """{"requestId":"$ID","actor":"$krXuid"}"""
        val assignBody = """{"requestId":"$ID","actor":"$krXuid","factoryId":"scierie"}"""
        val fireQuery = "requestId=$ID&actor=$krXuid"
        val staffRequests: List<Triple<String, String, String?>> =
            badCompanyUrls.map { Triple("getCandidates", "$it/agency", null) } +
                listOf("0", "6", "-1", "abc", "1.5").map { Triple("hire", "/v1/companies/1/agency/$it/hire", hireBody) } +
                listOf("/v1/companies/0", "/v1/companies/abc", "/v1/companies/99").map { Triple("hire", "$it/agency/1/hire", hireBody) } +
                listOf("0", "-1", "abc", "99").map { Triple("assignStaff", "/v1/companies/1/staff/$it", assignBody) } +
                listOf("/v1/companies/0", "/v1/companies/abc", "/v1/companies/99").map { Triple("assignStaff", "$it/staff/1", assignBody) } +
                listOf("0", "-1", "abc", "99").map { Triple("fireStaff", "/v1/companies/1/staff/$it?$fireQuery", null) } +
                listOf("/v1/companies/0", "/v1/companies/abc", "/v1/companies/99").map { Triple("fireStaff", "$it/staff/1?$fireQuery", null) } +
                listOf("", "?requestId=$ID", "?actor=$krXuid", "?requestId=abc&actor=$krXuid", "?requestId=$ID&actor=abc", "?requestId=$ID&actor=$stranger")
                    .map { Triple("fireStaff", "/v1/companies/1/staff/1$it", null) }
        val queries = listOf("", "?limit=10", "?after=abc", "?after=-1", "?after=0&limit=0", "?after=0&limit=${EVENTS_MAX_LIMIT + 1}", "?after=0&limit=abc")

        // Le personnel a son état posé : un 400 vient alors de la requête, jamais d'une entreprise absente.
        val seeded = setOf("hire", "assignStaff")
        return operations.filter { it.id in bad }.flatMap { op ->
            bad.getValue(op.id).map { body ->
                dynamicTest("${op.id} : ${body.take(50)}") { assertBadRequest(op, urlFor(op), body, seed = op.id.takeIf { it in seeded }) }
            }
        } + staffRequests.map { (opId, url, body) ->
            dynamicTest("$opId : $url ${body.orEmpty().take(40)}") { assertBadRequest(operations.single { it.id == opId }, url, body, seed = opId) }
        } + badCompanyUrls.map { url ->
            dynamicTest("getCompany : $url") { assertBadRequest(operations.single { it.id == "getCompany" }, url, null) }
        } + badCompanyUrls.map { url ->
            dynamicTest("prestigePreview : $url") { assertBadRequest(operations.single { it.id == "prestigePreview" }, "$url/prestige/preview", null) }
        } + badMissionUrls.map { url ->
            dynamicTest("getMissions : $url") { assertBadRequest(operations.single { it.id == "getMissions" }, url, null) }
        } + badFindingUrls.map { url ->
            dynamicTest("getFindings : $url") { assertBadRequest(operations.single { it.id == "getFindings" }, url, null) }
        } + badOrderQueries.map { query ->
            dynamicTest("todayOrder : $query") { assertBadRequest(operations.single { it.id == "todayOrder" }, "/v1/orders/today$query", null) }
        } + queries.map { query ->
            dynamicTest("pollEvents : $query") { assertBadRequest(operations.single { it.id == "pollEvents" }, "/v1/events$query", null) }
        }
    }

    private fun assertBadRequest(op: OpenApi.Operation, url: String, body: String?, seed: String? = null) = withService(seed) {
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
    @DisplayName("prestigePreview : le corps suit PrestigePreview, le PDG seul a toute la part, et l'aperçu ne change rien")
    fun prestigePreview() = withService("prestigePreview") {
        val response = send("GET", "/v1/companies/1/prestige/preview")
        val body = response.bodyAsText()
        assertEquals(200, response.status.value, body)
        assertConforms(contract.schema("PrestigePreview"), body, "prestigePreview")
        val json = parse(body).jsonObject
        assertEquals("true", json.getValue("allowed").jsonPrimitive.content)
        val shares = json.getValue("shares").jsonArray.map { it.jsonObject }
        assertEquals(listOf(krXuid), shares.map { it.getValue("xuid").jsonPrimitive.content })
        assertEquals(json.getValue("lingotsTotal"), shares.single().getValue("lingots"), "seul membre : il reçoit tout")
        assertEquals(body, send("GET", "/v1/companies/1/prestige/preview").bodyAsText(), "lu deux fois : la même réponse")
    }

    @Test
    @DisplayName("prestige : CompanyUpdated à la course suivante, rejoué à l'identique ; sous le seuil 409 SEUIL_NON_ATTEINT ; associé 403 PAS_LE_PDG, au corps Refused")
    fun prestige() {
        val url = "/v1/companies/1/prestige"
        val request = """{"requestId":"$ID","actor":"$krXuid"}"""
        withService("prestige") {
            val first = send("POST", url, request)
            val body = first.bodyAsText()
            assertEquals(200, first.status.value, body)
            assertConforms(contract.responseSchema("CompanyUpdated"), body, "prestige")
            assertEquals("2", parse(body).jsonObject.getValue("state").jsonObject.getValue("courseNumber").jsonPrimitive.content)
            assertEquals(body, send("POST", url, request).bodyAsText(), "rejoué avec le même requestId : la même réponse")
        }
        withService("getCompany") {
            val short = send("POST", url, request)
            val body = short.bodyAsText()
            assertEquals(409, short.status.value, body)
            assertConforms(refused, body, "prestige (409)")
            assertEquals(RefusalCodes.THRESHOLD_NOT_REACHED, reason(parse(body)))
        }
        val partner = "2535428000000002"
        withService("prestige", extraSeed = { sessions ->
            sessions.members[Xuid.parse(partner)!!] = Membership(1, Role.ASSOCIE)
            sessions.balances[Xuid.parse(partner)!!] = Balances(0, 0, 0, 0)
        }) {
            val notCeo = send("POST", url, """{"requestId":"$ID","actor":"$partner"}""")
            val body = notCeo.bodyAsText()
            assertEquals(403, notCeo.status.value, body)
            assertConforms(refused, body, "prestige (403)")
            assertEquals(RefusalCodes.NOT_THE_CEO, reason(parse(body)))
        }
    }

    @Test
    @DisplayName("getMissions : le corps suit MissionState ; au départ la mission de bienvenue à zéro, avec la cible et la récompense de definitions/")
    fun missions() = withService("getMissions") {
        val first = TestDefinitions.loaded.missions.missions.first()
        val reward = TestDefinitions.loaded.missionRewards.getValue(first.id)

        val response = send("GET", "/v1/players/$krXuid/missions")
        val body = response.bodyAsText()
        assertEquals(200, response.status.value, body)
        assertConforms(contract.schema("MissionState"), body, "getMissions")
        val json = parse(body).jsonObject
        val current = json.getValue("current").jsonObject
        assertEquals(first.id, current.getValue("id").jsonPrimitive.content)
        assertEquals("0", current.getValue("progress").jsonPrimitive.content)
        assertEquals(first.target.toString(), current.getValue("target").jsonPrimitive.content)
        assertEquals(reward.toString(), current.getValue("rewardLingots").jsonPrimitive.content)
        assertEquals(0, json.getValue("completed").jsonArray.size)
        assertEquals(body, send("GET", "/v1/players/$krXuid/missions").bodyAsText(), "lue deux fois : la même réponse, rien n'est écrit")
    }

    @Test
    @DisplayName("collect : un encaissement qui rapporte termine la mission de bienvenue, verse ses Lingots une seule fois et publie un événement")
    fun collectFinishesTheWelcomeMission() {
        val first = TestDefinitions.loaded.missions.missions.first()
        val reward = TestDefinitions.loaded.missionRewards.getValue(first.id)
        val owner = Xuid.parse(krXuid)!!
        lateinit var sessions: InMemorySessionStore
        lateinit var missions: InMemoryMissionStore
        withService("collect", inspect = { s, m -> sessions = s; missions = m }) {
            val url = "/v1/companies/1/collect"
            val collected = send("POST", url, """{"requestId":"$ID","actor":"$krXuid"}""")
            assertEquals(200, collected.status.value, collected.bodyAsText())

            val state = send("GET", "/v1/players/$krXuid/missions")
            val body = state.bodyAsText()
            assertConforms(contract.schema("MissionState"), body, "getMissions après collect")
            val json = parse(body).jsonObject
            val next = json.getValue("current").let { if (it is JsonObject) it.getValue("id").jsonPrimitive.content else null }
            assertEquals(TestDefinitions.loaded.missions.missions.getOrNull(1)?.id, next, "la mission en cours est la suivante du catalogue, ou aucune")
            assertEquals(listOf(first.id), json.getValue("completed").jsonArray.map { it.jsonPrimitive.content })

            assertEquals(reward, sessions.balances.getValue(owner).lingots)
            assertEquals(listOf(InMemoryMissionStore.Credit(owner, reward, "mission", first.id)), missions.credits)
            val event = missions.published.single()
            assertEquals(owner, event.xuid)
            assertEquals("mission", event.payload.getValue("action").jsonPrimitive.content)
            assertEquals(first.id, event.payload.getValue("missionId").jsonPrimitive.content)
            assertEquals(reward.toString(), event.payload.getValue("rewardLingots").jsonPrimitive.content)

            // Rejoué avec le même requestId, puis un second encaissement qui rapporte : une mission terminée ne se rejoue pas.
            assertEquals(200, send("POST", url, """{"requestId":"$ID","actor":"$krXuid"}""").status.value)
            assertEquals(200, send("POST", url, """{"requestId":"6f1c2b7e-0d4a-4a53-9c1e-3f6a8d2b5e91","actor":"$krXuid"}""").status.value)
            assertEquals(1, missions.credits.count { it.refId == first.id })
            assertEquals(1, missions.published.count { it.payload.getValue("missionId").jsonPrimitive.content == first.id })
        }
    }

    @Test
    @DisplayName("getCandidates : le corps suit Staff, une place par candidat du jour, un prix pour chaque candidat libre, la même liste à chaque lecture")
    fun candidates() = withService("getCandidates") {
        val response = send("GET", "/v1/companies/1/agency")
        val body = response.bodyAsText()
        assertEquals(200, response.status.value, body)
        assertConforms(operations.single { it.id == "getCandidates" }.responses.getValue(200)!!, body, "getCandidates")
        val list = parse(body).jsonArray.map { it.jsonObject }
        assertEquals((1..TestDefinitions.loaded.personnel.agency.candidatesPerDay).toList(), list.map { it.getValue("slot").jsonPrimitive.content.toInt() })
        assertTrue(list.all { "hireCost" in it && "staffId" !in it }, "des candidats libres : un prix, pas d'identifiant d'embauche")
        assertEquals(body, send("GET", "/v1/companies/1/agency").bodyAsText(), "lue deux fois : la même liste, rien n'est écrit")
    }

    @Test
    @DisplayName("hire : CompanyUpdated avec le membre affecté, rejoué à l'identique ; la place garde son staffId dans la liste ; la reprendre donne 409 DEJA_POSSEDE ; sans Donar 409 PAS_ASSEZ_DE_DONAR")
    fun hire() {
        val url = "/v1/companies/1/agency/1/hire"
        val request = """{"requestId":"${id(1)}","actor":"$krXuid","factoryId":"scierie"}"""
        withService("hire") {
            val hired = send("POST", url, request)
            val body = hired.bodyAsText()
            assertEquals(200, hired.status.value, body)
            assertConforms(contract.responseSchema("CompanyUpdated"), body, "hire")
            val member = parse(body).jsonObject.getValue("state").jsonObject.getValue("staff").jsonArray.single().jsonObject
            assertEquals("scierie", member.getValue("factoryId").jsonPrimitive.content)
            assertEquals(body, send("POST", url, request).bodyAsText(), "rejoué avec le même requestId : la même réponse")

            val slotOne = parse(send("GET", "/v1/companies/1/agency").bodyAsText()).jsonArray.first().jsonObject
            assertEquals(member.getValue("staffId"), slotOne.getValue("staffId"), "la place embauchée porte le staffId du membre")
            assertFalse("hireCost" in slotOne, "un candidat embauché n'a plus de prix")

            val again = send("POST", url, """{"requestId":"${id(2)}","actor":"$krXuid"}""")
            val answer = again.bodyAsText()
            assertEquals(409, again.status.value, answer)
            assertConforms(refused, answer, "hire (409)")
            assertEquals(RefusalCodes.ALREADY_OWNED, reason(parse(answer)))
        }
        withService("getCompany") {
            val poor = send("POST", url, """{"requestId":"${id(3)}","actor":"$krXuid"}""")
            val body = poor.bodyAsText()
            assertEquals(409, poor.status.value, body)
            assertConforms(refused, body, "hire (409)")
            assertEquals(RefusalCodes.NOT_ENOUGH_DONAR, reason(parse(body)))
            assertTrue(parse(body).jsonObject.getValue("missing").jsonPrimitive.content.toDouble() > 0.0, "la somme qui manque")
        }
    }

    @Test
    @DisplayName("assignStaff : le membre part dans l'usine, 409 USINE_VERROUILLEE pour une usine qui n'est pas à l'entreprise ; fireStaff : le PDG renvoie, un associé reçoit 403 PAS_LE_PDG")
    fun assignAndFire() {
        val url = "/v1/companies/1/staff/1"
        withService("assignStaff") {
            val assigned = send("PATCH", url, """{"requestId":"${id(4)}","actor":"$krXuid","factoryId":"scierie"}""")
            val body = assigned.bodyAsText()
            assertEquals(200, assigned.status.value, body)
            assertConforms(contract.responseSchema("CompanyUpdated"), body, "assignStaff")
            val member = parse(body).jsonObject.getValue("state").jsonObject.getValue("staff").jsonArray.single().jsonObject
            assertEquals("scierie", member.getValue("factoryId").jsonPrimitive.content)

            val notOwned = EconomyEngine(TestDefinitions.loaded).factoryIds().first { it != "scierie" }
            val locked = send("PATCH", url, """{"requestId":"${id(5)}","actor":"$krXuid","factoryId":"$notOwned"}""")
            val refusal = locked.bodyAsText()
            assertEquals(409, locked.status.value, refusal)
            assertConforms(refused, refusal, "assignStaff (409)")
            assertEquals(RefusalCodes.FACTORY_LOCKED, reason(parse(refusal)))

            val fired = send("DELETE", "$url?requestId=${id(6)}&actor=$krXuid")
            val left = fired.bodyAsText()
            assertEquals(200, fired.status.value, left)
            assertConforms(contract.responseSchema("CompanyUpdated"), left, "fireStaff")
            assertEquals(0, parse(left).jsonObject.getValue("state").jsonObject.getValue("staff").jsonArray.size)
            assertEquals(400, send("DELETE", "$url?requestId=${id(7)}&actor=$krXuid").status.value, "renvoyé : le membre n'existe plus")
        }
        val partner = "2535428000000002"
        withService("fireStaff", extraSeed = { sessions ->
            sessions.members[Xuid.parse(partner)!!] = Membership(1, Role.ASSOCIE)
            sessions.balances[Xuid.parse(partner)!!] = Balances(0, 0, 0, 0)
        }) {
            val notCeo = send("DELETE", "$url?requestId=${id(8)}&actor=$partner")
            val body = notCeo.bodyAsText()
            assertEquals(403, notCeo.status.value, body)
            assertConforms(refused, body, "fireStaff (403)")
            assertEquals(RefusalCodes.NOT_THE_CEO, reason(parse(body)))
        }
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

        /** Un `requestId` bien formé, pour les corps dont c'est un autre champ qui est faux. */
        const val ID = "6f1c2b7e-0d4a-4a53-9c1e-3f6a8d2b5e90"

        /** Les routes écrites et le succès qu'une requête minimale valide leur fait rendre. */
        val WRITTEN = mapOf(
            "heartbeat" to 204, "join" to 200, "leave" to 204, "pollEvents" to 200,
            "getCompany" to 200, "collect" to 200, "buyLevels" to 200, "restoreStep" to 200,
            "prestigePreview" to 200, "prestige" to 200, "getMissions" to 200,
            "todayOrder" to 200, "deliverOrder" to 200, "getFindings" to 200,
            "getCandidates" to 200, "hire" to 200, "assignStaff" to 200, "fireStaff" to 200,
        )

        /** Plus de Donar que n'importe quelle embauche n'en coûte au revenu fictif de `TestEconomy` (11 par heure). */
        const val RICH = 1.0e6

        /**
         * Les opérations qui peuvent répondre 409 CONFLIT_REESSAYER sans le déclarer dans le contrat. Les six trous
         * relevés par la session des routes ont été comblés dans l'OpenAPI par la direction. Reste `leave` : il verse la
         * production au revenu plein par le verrou optimiste de l'entreprise, donc deux départs ou un départ et une
         * arrivée simultanés peuvent se heurter, et le service rend alors 409 CONFLIT_REESSAYER après ses essais. Le
         * contrat de `leave` ne déclare que 204 : à signaler à la direction, jamais une tolérance durable.
         */
        val KNOWN_409_GAPS = emptySet<String>()
    }
}
