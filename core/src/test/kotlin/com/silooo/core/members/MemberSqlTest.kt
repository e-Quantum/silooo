package com.silooo.core.members

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.CompanyState
import com.silooo.core.api.CompanyUpdated
import com.silooo.core.api.ErrorBody
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.Idempotency
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.RefusedBody
import com.silooo.core.api.Role
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.MariaDbSupport
import com.silooo.core.db.Xuid
import com.silooo.core.economy.CompanyStore
import com.silooo.core.economy.DefinitionsBootstrap
import com.silooo.core.economy.EconomyEngine
import com.silooo.core.economy.EngineFigures
import com.silooo.core.economy.EngineFixture.Companion.KR
import com.silooo.core.economy.EngineFixture.Companion.OTHER
import com.silooo.core.economy.EngineFixture.Companion.START
import com.silooo.core.economy.EngineFixture.Companion.STRANGER
import com.silooo.core.economy.JdbcCompanyStore
import com.silooo.core.events.GameEventKind
import com.silooo.core.server.ServerPresence
import com.silooo.core.session.JdbcSessionStore
import com.silooo.core.session.SessionService
import com.silooo.core.siloooModule
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.math.BigInteger
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * L'entreprise partagée sur la vraie MariaDB : `JdbcMemberStore` ligne à ligne, puis `invite`, `acceptInvite` et `setShares`
 * par HTTP avec le vrai moteur, le vrai dépôt SQL et la vraie idempotence. Ce que les tests de route (`MemberRoutesTest`) ne
 * peuvent pas voir : ce qui est vraiment écrit dans `company_members`, `company_invites`, `companies` (`version`) et
 * `game_events`, dans une seule transaction, et ce que donne une concurrence réelle (le plafond de membres, un invité pris par
 * deux entreprises). Ignoré sans Docker (voir [MariaDbSupport]) : la CI les rejoue.
 */
class MemberSqlTest {
    private val database get() = MariaDbSupport.database
    private val definitions = TestDefinitions.loaded
    private val limits = definitions.world.company
    private val engine = EconomyEngine(definitions)
    private val store = JdbcSessionStore()
    private val clock = MutableClock(START)
    private val presence = ServerPresence(clock = clock)
    private val jdbc = JdbcMemberStore()
    private val key = "cle-de-test"

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    // --- outils de la couche SQL

    private fun player(xuid: Xuid, gamertag: String = "J${xuid.text}") = MariaDbSupport.insertPlayer(xuid.text, gamertag)

    private fun member(companyId: Long, xuid: Xuid, role: Role, share: Int, joinedAt: String = "2026-01-01 00:00:00") {
        MariaDbSupport.update(
            "INSERT INTO company_members (company_id, xuid, role, share_percent, joined_at) VALUES (?, ?, ?, ?, ?)",
            companyId,
            BigInteger(xuid.text),
            role.name,
            share,
            LocalDateTime.parse(joinedAt.replace(' ', 'T')),
        )
    }

    private fun invitation(companyId: Long, invitee: Xuid, inviter: Xuid, hours: Long = 48) =
        Invitation(companyId, invitee, inviter, clock.instant().plusSeconds(hours * 3600))

    private fun xuidOf(n: Int): Xuid = Xuid.parse("2535428000000%03d".format(n))!!

    // --- JdbcMemberStore

    @Test
    @DisplayName("saveInvitation et findInvitation : l'invitation écrite est relue telle quelle (date en UTC) ; une autre entreprise ou un autre joueur n'en a pas")
    fun invitationRoundTrip() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val b = MariaDbSupport.insertCompany("B", 1)
        player(KR)
        player(OTHER)
        val saved = Invitation(a, OTHER, KR, START.plusSeconds(172_800))

        MariaDbSupport.raw { jdbc.saveInvitation(it, saved) }

        assertEquals(saved, MariaDbSupport.raw { jdbc.findInvitation(it, a, OTHER) })
        assertNull(MariaDbSupport.raw { jdbc.findInvitation(it, b, OTHER) })
        assertNull(MariaDbSupport.raw { jdbc.findInvitation(it, a, KR) })
    }

    @Test
    @DisplayName("saveInvitation deux fois : une seule ligne, l'invitation est renouvelée (nouvelle échéance, nouvel inviteur)")
    fun invitingAgainRenewsTheRow() {
        val a = MariaDbSupport.insertCompany("A", 0)
        player(KR)
        player(OTHER)
        player(STRANGER)
        MariaDbSupport.raw { jdbc.saveInvitation(it, Invitation(a, OTHER, KR, START.plusSeconds(3_600))) }

        val renewed = Invitation(a, OTHER, STRANGER, START.plusSeconds(7_200))
        MariaDbSupport.raw { jdbc.saveInvitation(it, renewed) }

        assertEquals(1L, MariaDbSupport.count("company_invites"))
        assertEquals(renewed, MariaDbSupport.raw { jdbc.findInvitation(it, a, OTHER) })
    }

    @Test
    @DisplayName("deleteInvitations : toutes les invitations du joueur partent, de toutes les entreprises ; celles des autres restent")
    fun deleteInvitationsOfOnePlayer() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val b = MariaDbSupport.insertCompany("B", 1)
        player(KR)
        player(OTHER)
        player(STRANGER)
        MariaDbSupport.raw {
            jdbc.saveInvitation(it, invitation(a, OTHER, KR))
            jdbc.saveInvitation(it, invitation(b, OTHER, STRANGER))
            jdbc.saveInvitation(it, invitation(a, STRANGER, KR))
        }

        MariaDbSupport.raw { jdbc.deleteInvitations(it, OTHER) }

        assertEquals(1L, MariaDbSupport.count("company_invites"))
        assertEquals(invitation(a, STRANGER, KR), MariaDbSupport.raw { jdbc.findInvitation(it, a, STRANGER) })
    }

    @Test
    @DisplayName("moveMember : la ligne change d'entreprise, devient ASSOCIE à 0 %, arrive en dernier ; l'entreprise quittée reste ; un autre départ donne faux")
    fun moveMemberChangesTheRow() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val b = MariaDbSupport.insertCompany("B", 1)
        player(KR)
        player(OTHER)
        member(a, KR, Role.PDG, 100, joinedAt = "2026-02-01 00:00:00")
        member(b, OTHER, Role.PDG, 100, joinedAt = "2026-01-01 00:00:00")

        assertTrue(MariaDbSupport.raw { jdbc.moveMember(it, KR, a, b) })

        assertEquals(
            listOf("${OTHER.text}|PDG|100", "${KR.text}|ASSOCIE|0"),
            MariaDbSupport.strings(
                "SELECT CONCAT(CAST(xuid AS CHAR), '|', role, '|', share_percent) FROM company_members WHERE company_id = ? ORDER BY joined_at, xuid",
                b,
            ),
        )
        assertEquals(0L, MariaDbSupport.long("SELECT COUNT(*) FROM company_members WHERE company_id = ?", a))
        assertEquals(1L, MariaDbSupport.long("SELECT COUNT(*) FROM companies WHERE id = ?", a), "l'entreprise quittée n'est pas supprimée")
        assertEquals(1L, MariaDbSupport.long("SELECT joined_at > '2026-02-02' FROM company_members WHERE xuid = ?", BigInteger(KR.text)), "arrivé maintenant")

        assertFalse(MariaDbSupport.raw { jdbc.moveMember(it, KR, a, b) }, "il n'est plus dans a : rien à déplacer")
        assertFalse(MariaDbSupport.raw { jdbc.moveMember(it, STRANGER, a, b) }, "un joueur sans entreprise non plus")
    }

    @Test
    @DisplayName("saveShares : la part de chaque membre nommé est écrite ; un joueur qui n'est pas de l'entreprise est une erreur de programmation ; 101 % viole la contrainte")
    fun saveSharesWritesEachMember() {
        val a = MariaDbSupport.insertCompany("A", 0)
        val b = MariaDbSupport.insertCompany("B", 1)
        player(KR)
        player(OTHER)
        player(STRANGER)
        member(a, KR, Role.PDG, 100)
        member(a, OTHER, Role.ASSOCIE, 0)
        member(b, STRANGER, Role.PDG, 100)

        MariaDbSupport.raw { jdbc.saveShares(it, a, mapOf(KR to 25, OTHER to 75)) }

        assertEquals(listOf("25", "75"), MariaDbSupport.strings("SELECT CAST(share_percent AS CHAR) FROM company_members WHERE company_id = ? ORDER BY role = 'PDG' DESC", a))
        assertEquals("100", MariaDbSupport.string("SELECT CAST(share_percent AS CHAR) FROM company_members WHERE company_id = ?", b), "l'autre entreprise ne bouge pas")
        assertThrows(IllegalStateException::class.java) { MariaDbSupport.raw { jdbc.saveShares(it, a, mapOf(STRANGER to 50)) } }
        assertThrows(SQLException::class.java) { MariaDbSupport.raw { jdbc.saveShares(it, a, mapOf(KR to 101)) } }
    }

    @Test
    @DisplayName("loadMembers : le PDG d'abord, puis les associés dans l'ordre d'arrivée, avec le gamertag et la part")
    fun loadMembersOrder() {
        val a = MariaDbSupport.insertCompany("A", 0)
        player(KR, "Kr")
        player(OTHER, "Autre")
        player(STRANGER, "Etranger")
        member(a, OTHER, Role.ASSOCIE, 10, joinedAt = "2026-01-01 00:00:00")
        member(a, KR, Role.PDG, 60, joinedAt = "2026-03-01 00:00:00")
        member(a, STRANGER, Role.ASSOCIE, 30, joinedAt = "2026-02-01 00:00:00")

        val members = MariaDbSupport.raw { store.loadMembers(it, a) }

        assertEquals(listOf(KR, STRANGER, OTHER), members.map { it.xuid })
        assertEquals(listOf("Kr", "Etranger", "Autre"), members.map { it.name })
        assertEquals(listOf(Role.PDG, Role.ASSOCIE, Role.ASSOCIE), members.map { it.role })
        assertEquals(listOf(60, 30, 10), members.map { it.sharePercent })
    }

    // --- le service

    private fun sessions() = SessionService(database, store, EngineFigures(engine, store, clock), DefinitionsBootstrap(engine, definitions))

    /** Le premier `join` du joueur : il reçoit son entreprise de départ, que le dernier calcul de production date de l'horloge du test. */
    private fun joined(xuid: Xuid, name: String): Long {
        val id = runBlocking { sessions().join(xuid, name) }.companyId
        MariaDbSupport.update("UPDATE companies SET last_collect_at = ? WHERE id = ?", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), id)
        return id
    }

    private fun dependencies(companyStore: CompanyStore): Dependencies = Dependencies(
        database = database,
        definitions = definitions,
        apiKey = key,
        economy = engine,
        idempotency = Idempotency(database),
        sessionStore = store,
        clock = clock,
        economyFigures = EngineFigures(engine, store, clock),
        companyBootstrap = DefinitionsBootstrap(engine, definitions),
        companyStore = companyStore,
        memberStore = jdbc,
        serverPresence = presence,
    )

    private fun withApp(companyStore: CompanyStore = JdbcCompanyStore(), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { siloooModule(dependencies(companyStore)) }
        block()
    }

    /** Un dépôt qui écrit comme le vrai, sauf la publication d'événement : la dernière écriture d'une action échoue. */
    private class FailingPublish(private val real: CompanyStore = JdbcCompanyStore()) : CompanyStore by real {
        override fun publish(connection: Connection, kind: GameEventKind, xuid: Xuid?, payload: JsonObject): Long =
            throw IllegalStateException("publication impossible (test)")
    }

    // --- HTTP

    private fun requestId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    private fun action(n: Int, actor: Xuid, extra: String = ""): String = """{"requestId":"${requestId(n)}","actor":"${actor.text}"$extra}"""

    private suspend fun ApplicationTestBuilder.invite(companyId: Long, target: Xuid, n: Int = 1, actor: Xuid = KR): HttpResponse =
        client.post("/v1/companies/$companyId/members") {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(action(n, actor, ""","targetXuid":"${target.text}""""))
        }

    private suspend fun ApplicationTestBuilder.accept(companyId: Long, n: Int = 1, actor: Xuid = KR): HttpResponse =
        client.post("/v1/companies/$companyId/members/accept") {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(action(n, actor))
        }

    private suspend fun ApplicationTestBuilder.setShares(companyId: Long, shares: List<Pair<Xuid, Int>>, n: Int = 1, actor: Xuid = KR): HttpResponse =
        client.put("/v1/companies/$companyId/members") {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(action(n, actor, ""","shares":[${shares.joinToString(",") { """{"xuid":"${it.first.text}","percent":${it.second}}""" }}]"""))
        }

    private suspend fun updated(response: HttpResponse): CompanyUpdated {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        return SiloooJson.decodeFromString(CompanyUpdated.serializer(), body)
    }

    private suspend fun refusalOf(response: HttpResponse): String {
        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        return SiloooJson.decodeFromString(RefusedBody.serializer(), response.bodyAsText()).reason
    }

    // --- lectures en base

    private fun version(companyId: Long) = MariaDbSupport.long("SELECT version FROM companies WHERE id = ?", companyId)

    private fun factoryCount(companyId: Long) = MariaDbSupport.long("SELECT COUNT(*) FROM company_factories WHERE company_id = ?", companyId)

    /** Les membres de l'entreprise, le PDG d'abord : `xuid|rôle|part`. */
    private fun memberRows(companyId: Long): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(CAST(xuid AS CHAR), '|', role, '|', share_percent) FROM company_members WHERE company_id = ? ORDER BY role = 'PDG' DESC, joined_at, xuid",
        companyId,
    )

    /** Les invitations : `entreprise|invité|inviteur`. */
    private fun inviteRows(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(company_id, '|', CAST(xuid AS CHAR), '|', CAST(invited_by AS CHAR)) FROM company_invites ORDER BY company_id, xuid",
    )

    /** Les événements dans l'ordre : `xuid|type|action`. */
    private fun events(): List<String?> = MariaDbSupport.strings(
        "SELECT CONCAT(IFNULL(CAST(xuid AS CHAR), '-'), '|', kind, '|', IFNULL(JSON_VALUE(payload, '\$.action'), '-')) FROM game_events ORDER BY id",
    )

    @Test
    @DisplayName("Le parcours en base : invite, acceptInvite, setShares écrivent leurs lignes, leurs événements et la version ; l'entreprise quittée reste avec ses usines")
    fun theWholeJourney() = withApp {
        val a = joined(KR, "Kr")
        val b = joined(OTHER, "Autre")
        val factoriesOfA = factoryCount(a)
        val versionB = version(b)

        updated(invite(b, KR, n = 1, actor = OTHER))

        assertEquals(listOf("$b|${KR.text}|${OTHER.text}"), inviteRows())
        assertEquals(listOf("${KR.text}|INVITATION|-"), events())
        assertEquals(versionB, version(b), "inviter ne touche pas la version")
        assertEquals(listOf("${OTHER.text}|PDG|100"), memberRows(b))

        val result = updated(accept(b, n = 2, actor = KR))

        assertEquals(b, result.state.companyId)
        assertEquals(listOf("Autre", "Kr"), result.state.members.map { it.name })
        assertEquals(listOf(100, 0), result.state.members.map { it.sharePercent })
        assertEquals(listOf("${OTHER.text}|PDG|100", "${KR.text}|ASSOCIE|0"), memberRows(b))
        assertEquals(emptyList<String?>(), memberRows(a), "l'entreprise laissée n'a plus de membre")
        assertEquals(1L, MariaDbSupport.long("SELECT COUNT(*) FROM companies WHERE id = ?", a), "et elle n'est pas supprimée")
        assertEquals(factoriesOfA, factoryCount(a), "ses usines non plus")
        assertEquals(emptyList<String?>(), inviteRows(), "l'invitation est consommée")
        assertEquals(versionB + 1, version(b))
        assertEquals(listOf("${KR.text}|INVITATION|-", "${KR.text}|ANNONCE|acceptInvite"), events())

        updated(setShares(b, listOf(OTHER to 70, KR to 30), n = 3, actor = OTHER))

        assertEquals(listOf("${OTHER.text}|PDG|70", "${KR.text}|ASSOCIE|30"), memberRows(b))
        assertEquals(versionB + 2, version(b))
        assertEquals(
            "70",
            MariaDbSupport.string("SELECT JSON_VALUE(payload, '\$.shares[0].percent') FROM game_events WHERE JSON_VALUE(payload, '\$.action') = 'setShares'"),
        )
        assertEquals(
            listOf("${KR.text}|INVITATION|-", "${KR.text}|ANNONCE|acceptInvite", "${OTHER.text}|ANNONCE|setShares"),
            events(),
        )

        // Au prochain join de Kr, c'est l'entreprise de l'autre qui s'ouvre.
        val again = runBlocking { sessions().join(KR, "Kr") }
        assertEquals(b, again.companyId)
        assertEquals(Role.ASSOCIE, again.role)
    }

    @Test
    @DisplayName("Si la publication de l'événement échoue, tout est annulé : invite ne garde pas d'invitation, acceptInvite ne déplace personne")
    fun failedPublishRollsBackEverything() {
        val a = joined(KR, "Kr")
        val b = joined(OTHER, "Autre")

        withApp(FailingPublish()) {
            assertEquals(HttpStatusCode.InternalServerError, invite(b, KR, n = 1, actor = OTHER).status)
            assertEquals(emptyList<String?>(), inviteRows())
        }

        MariaDbSupport.raw { jdbc.saveInvitation(it, invitation(b, KR, OTHER)) }
        val versionB = version(b)
        withApp(FailingPublish()) {
            assertEquals(HttpStatusCode.InternalServerError, accept(b, n = 2, actor = KR).status)
        }
        assertEquals(listOf("${OTHER.text}|PDG|100"), memberRows(b))
        assertEquals(listOf("${KR.text}|PDG|100"), memberRows(a))
        assertEquals(listOf("$b|${KR.text}|${OTHER.text}"), inviteRows(), "l'invitation reste")
        assertEquals(versionB, version(b))
        assertEquals(emptyList<String?>(), events())
    }

    @Test
    @DisplayName("Deux acceptations simultanées pour le dernier siège : un seul entre, le plafond de membres tient, l'autre est refusé")
    fun theCapHoldsUnderParallelAccepts() = withApp {
        val a = joined(KR, "Kr")
        val first = joined(OTHER, "Autre")
        val second = joined(STRANGER, "Etranger")
        // L'entreprise de Kr a un siège libre : elle a un membre de moins que le plafond du monde.
        for (n in 100 until 100 + limits.maxMembers - 2) {
            val filler = xuidOf(n)
            player(filler)
            member(a, filler, Role.ASSOCIE, 0)
        }
        assertEquals((limits.maxMembers - 1).toLong(), MariaDbSupport.long("SELECT COUNT(*) FROM company_members WHERE company_id = ?", a))
        MariaDbSupport.raw {
            jdbc.saveInvitation(it, invitation(a, OTHER, KR))
            jdbc.saveInvitation(it, invitation(a, STRANGER, KR))
        }

        val responses = coroutineScope {
            val go = CompletableDeferred<Unit>()
            val jobs = listOf(OTHER, STRANGER).mapIndexed { index, actor -> async(Dispatchers.IO) { go.await(); accept(a, n = 1 + index, actor = actor) } }
            go.complete(Unit)
            jobs.awaitAll()
        }

        assertEquals(1, responses.count { it.status == HttpStatusCode.OK }, "exactement une arrivée passe")
        for (response in responses.filter { it.status != HttpStatusCode.OK }) {
            val reason = refusalOf(response)
            assertTrue(reason == RefusalCodes.CONFLICT_RETRY || reason == RefusalCodes.COMPANY_FULL, "refus inattendu : $reason")
        }
        assertEquals(limits.maxMembers.toLong(), MariaDbSupport.long("SELECT COUNT(*) FROM company_members WHERE company_id = ?", a), "le plafond tient")
        assertEquals(1L, MariaDbSupport.long("SELECT COUNT(*) FROM company_members WHERE company_id IN (?, ?)", first, second), "l'autre est resté dans son entreprise")
    }

    @Test
    @DisplayName("Un invité qui accepte deux invitations à la fois : il n'entre que dans une entreprise, jamais dans deux")
    fun anInviteeJoinsOnlyOneCompany() = withApp {
        val own = joined(KR, "Kr")
        val b = joined(OTHER, "Autre")
        val c = joined(STRANGER, "Etranger")
        MariaDbSupport.raw {
            jdbc.saveInvitation(it, invitation(b, KR, OTHER))
            jdbc.saveInvitation(it, invitation(c, KR, STRANGER))
        }

        val responses = coroutineScope {
            val go = CompletableDeferred<Unit>()
            val jobs = listOf(b, c).mapIndexed { index, target -> async(Dispatchers.IO) { go.await(); accept(target, n = 1 + index, actor = KR) } }
            go.complete(Unit)
            jobs.awaitAll()
        }

        assertEquals(1, responses.count { it.status == HttpStatusCode.OK }, "exactement une arrivée passe")
        for (response in responses.filter { it.status != HttpStatusCode.OK }) {
            if (response.status == HttpStatusCode.BadRequest) {
                assertEquals(ErrorCodes.BAD_REQUEST, SiloooJson.decodeFromString(ErrorBody.serializer(), response.bodyAsText()).reason)
            } else {
                val reason = refusalOf(response)
                assertTrue(reason == RefusalCodes.CONFLICT_RETRY || reason == RefusalCodes.ENTERPRISE_UNDERWAY, "refus inattendu : $reason")
            }
        }
        assertEquals(1L, MariaDbSupport.long("SELECT COUNT(*) FROM company_members WHERE xuid = ?", BigInteger(KR.text)), "une seule ligne pour Kr")
        assertEquals(emptyList<String?>(), memberRows(own), "il a quitté son entreprise de départ, qui reste sans membre")
        assertEquals(1L, MariaDbSupport.long("SELECT COUNT(*) FROM companies WHERE id = ?", own))
        assertEquals(1, listOf(b, c).count { memberRows(it).size == 2 }, "l'invité est dans une seule des deux entreprises qui l'ont invité")
    }

    @Test
    @DisplayName("GET d'une entreprise partagée en base : CompanyState.members donne le PDG puis les associés, avec leurs parts")
    fun getCompanyShowsTheMembers() = withApp {
        val a = joined(KR, "Kr")
        player(OTHER, "Associe")
        member(a, OTHER, Role.ASSOCIE, 0, joinedAt = "2026-02-01 00:00:00")

        updated(setShares(a, listOf(KR to 55, OTHER to 45), n = 1))

        val response = client.get("/v1/companies/$a") { header(SERVER_KEY_HEADER, key) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val state = SiloooJson.decodeFromString(CompanyState.serializer(), response.bodyAsText())
        assertEquals(listOf(KR.text to 55, OTHER.text to 45), state.members.map { it.xuid to it.sharePercent })
        assertEquals(listOf("Kr", "Associe"), state.members.map { it.name })
        assertEquals(listOf(Role.PDG, Role.ASSOCIE), state.members.map { it.role })
    }
}
