package com.silooo.core.members

import com.silooo.core.Dependencies
import com.silooo.core.api.CompanyUpdated
import com.silooo.core.api.ErrorBody
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.Idempotency
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.RefusedBody
import com.silooo.core.api.Role
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.InMemoryApiRequestStore
import com.silooo.core.db.RecordingDatabase
import com.silooo.core.db.Xuid
import com.silooo.core.economy.EngineFixture
import com.silooo.core.economy.EngineFixture.Companion.hours
import com.silooo.core.economy.InMemoryCompanyStore
import com.silooo.core.events.GameEventKind
import com.silooo.core.server.ServerPresence
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.Membership
import com.silooo.core.siloooModule
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
import java.time.Instant
import kotlin.math.abs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `invite`, `acceptInvite` et `setShares` avec des faux (pas de base) : le moteur d'économie réel sur les `definitions/` du dépôt,
 * le faux dépôt de session (qui porte aussi les membres et leurs parts), un faux dépôt des invitations, une horloge que le test
 * avance et une connexion qui note les `UPDATE companies` du verrou optimiste. Le nombre de membres au plus et la durée d'une
 * invitation sortent de `definitions/monde.json`, jamais d'un littéral. Les règles de « entreprise déjà lancée » sont dans
 * `MemberServiceTest`, le SQL dans `MemberSqlTest`, le contrat dans `ContractTest`.
 */
class MemberRoutesTest {
    private val key = "cle-de-test"
    private val kr = EngineFixture.KR
    private val other = EngineFixture.OTHER
    private val stranger = EngineFixture.STRANGER

    private class Harness(
        val fixture: EngineFixture,
        val companies: InMemoryCompanyStore,
        val members: InMemoryMemberStore,
        val presence: ServerPresence,
    ) {
        val store get() = fixture.store
        val engine get() = fixture.engine
        val recording get() = fixture.recording
        val limits get() = fixture.definitions.world.company

        fun companyUpdates() = recording.statements.filter { it.sql.startsWith("UPDATE companies") }

        /** Le joueur [xuid] s'inscrit sous [name] et reçoit son entreprise de départ, comme à son premier `join`. */
        fun company(xuid: Xuid, name: String): CompanyRecord {
            store.enterPlayer(fixture.connection, xuid, name)
            return fixture.newCompany(xuid)
        }

        /** Un associé de plus dans l'entreprise [companyId] : inscrit sous [name], sans entreprise à lui, 0 % des parts. */
        fun partner(companyId: Long, xuid: Xuid, name: String) {
            store.enterPlayer(fixture.connection, xuid, name)
            fixture.addMember(companyId, xuid, lingots = 0)
        }

        /** Des associés sans visage jusqu'à ce que l'entreprise [companyId] ait [count] membres. */
        fun fill(companyId: Long, count: Int) {
            var number = 100L
            while (store.loadMemberXuids(fixture.connection, companyId).size < count) {
                number += 1
                partner(companyId, Xuid.parse("2535428000000$number")!!, "Associé $number")
            }
        }

        /** [inviter] (PDG de [companyId]) a invité [invitee] ; l'invitation vaut encore [validHours] heures. */
        fun invitation(companyId: Long, invitee: Xuid, inviter: Xuid, validHours: Long = limits.invitationHours.toLong()): Invitation =
            Invitation(companyId, invitee, inviter, fixture.clock.instant().plusSeconds(validHours * 3600)).also {
                members.invitations[companyId to invitee] = it
            }

        fun shares(companyId: Long): Map<Xuid, Int> = store.loadMembers(fixture.connection, companyId).associate { it.xuid to it.sharePercent }
    }

    private fun withApp(block: suspend ApplicationTestBuilder.(Harness) -> Unit) = testApplication {
        val fixture = EngineFixture()
        val database = RecordingDatabase(fixture.recording)
        val companies = InMemoryCompanyStore(fixture.store)
        val members = InMemoryMemberStore(fixture.store)
        val presence = ServerPresence(clock = fixture.clock)
        application {
            siloooModule(
                Dependencies(
                    database = database,
                    definitions = fixture.definitions,
                    apiKey = key,
                    economy = fixture.engine,
                    idempotency = Idempotency(database, InMemoryApiRequestStore(EngineFixture.START)),
                    sessionStore = fixture.store,
                    clock = fixture.clock,
                    economyFigures = fixture.figures,
                    companyBootstrap = fixture.bootstrap,
                    companyStore = companies,
                    memberStore = members,
                    serverPresence = presence,
                ),
            )
        }
        block(Harness(fixture, companies, members, presence))
    }

    // --- outils

    private fun requestId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    private fun action(n: Int, actor: Xuid, extra: String = ""): String = """{"requestId":"${requestId(n)}","actor":"${actor.text}"$extra}"""

    private suspend fun ApplicationTestBuilder.invite(companyId: Long, target: Xuid, n: Int = 1, actor: Xuid = kr): HttpResponse =
        client.post("/v1/companies/$companyId/members") {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(action(n, actor, ""","targetXuid":"${target.text}""""))
        }

    private suspend fun ApplicationTestBuilder.accept(companyId: Long, n: Int = 1, actor: Xuid = kr): HttpResponse =
        client.post("/v1/companies/$companyId/members/accept") {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(action(n, actor))
        }

    private suspend fun ApplicationTestBuilder.setShares(companyId: Long, shares: List<Pair<Xuid, Int>>, n: Int = 1, actor: Xuid = kr): HttpResponse =
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

    private suspend fun assertError(response: HttpResponse, what: String = "") {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.BadRequest, response.status, "$what : $body")
        assertEquals(ErrorCodes.BAD_REQUEST, SiloooJson.decodeFromString(ErrorBody.serializer(), body).reason, what)
    }

    private suspend fun assertRefused(response: HttpResponse, reason: String, status: HttpStatusCode = HttpStatusCode.Conflict) {
        val body = response.bodyAsText()
        assertEquals(status, response.status, body)
        assertEquals(reason, SiloooJson.decodeFromString(RefusedBody.serializer(), body).reason)
    }

    private fun assertClose(expected: Double, actual: Double, what: String = "valeur") {
        assertTrue(abs(expected - actual) <= 1e-9 * maxOf(1.0, abs(expected)), "$what : attendu $expected, reçu $actual")
    }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    /** Rien n'a été écrit : ni entreprise, ni membre, ni part, ni invitation de plus, ni événement. */
    private fun assertNothingWritten(app: Harness, invitationsBefore: Map<Pair<Long, Xuid>, Invitation> = emptyMap()) {
        assertEquals(0, app.companyUpdates().size, "aucun UPDATE companies")
        assertTrue(app.members.writes.isEmpty(), "aucune écriture de membre : ${app.members.writes}")
        assertEquals(invitationsBefore, app.members.invitations.toMap(), "les invitations n'ont pas bougé")
        assertEquals(0, app.companies.published.size, "aucun événement")
    }

    // --- invite

    @Test
    @DisplayName("invite : une invitation valable invitationHeures est gardée et un événement INVITATION part ; l'entreprise, ses membres et sa version ne bougent pas")
    fun inviteKeepsAnInvitationAndSendsAnEvent() = withApp { app ->
        val mine = app.company(kr, "Kr")
        val theirs = app.company(other, "Autre")

        val result = updated(invite(mine.id, other))

        assertEquals(mine.id, result.state.companyId)
        assertEquals(listOf(kr.text), result.state.members.map { it.xuid }, "inviter ne fait entrer personne")
        assertEquals(setOf(mine.id, theirs.id), app.store.members.values.map { it.companyId }.toSet(), "chacun garde son entreprise")
        assertEquals(theirs.id, app.store.members.getValue(other).companyId)

        val saved = app.members.invitations.getValue(mine.id to other)
        assertEquals(Invitation(mine.id, other, kr, EngineFixture.START.plusSeconds(app.limits.invitationHours * 3600L)), saved)
        assertEquals(1, app.members.invitations.size)

        val event = app.companies.published.single()
        assertEquals(GameEventKind.INVITATION, event.kind)
        assertEquals(other, event.xuid, "l'événement est pour l'invité")
        assertEquals(mine.id.toString(), event.payload.text("companyId"))
        assertEquals(mine.name, event.payload.text("companyName"))
        assertEquals(kr.text, event.payload.text("invitedBy"))
        assertEquals("Kr", event.payload.text("invitedByName"))
        assertEquals(saved.expiresAt, Instant.parse(event.payload.text("expiresAt")))

        assertEquals(0, app.companyUpdates().size, "inviter n'écrit pas l'entreprise : pas de version de plus")
        assertTrue(app.members.writes.isEmpty())
    }

    @Test
    @DisplayName("invite : réinviter le même joueur renouvelle l'invitation (une seule ligne), un nouvel événement part")
    fun invitingAgainRenews() = withApp { app ->
        val mine = app.company(kr, "Kr")
        app.company(other, "Autre")
        updated(invite(mine.id, other, n = 1))
        val first = app.members.invitations.getValue(mine.id to other)

        app.fixture.clock.advance(hours(5.0))
        updated(invite(mine.id, other, n = 2))

        val second = app.members.invitations.getValue(mine.id to other)
        assertEquals(1, app.members.invitations.size)
        assertEquals(first.expiresAt.plusSeconds(5 * 3600L), second.expiresAt)
        assertEquals(2, app.companies.published.size)
    }

    @Test
    @DisplayName("invite rejoué avec le même requestId : la même réponse, une seule invitation, un seul événement")
    fun inviteReplayed() = withApp { app ->
        val mine = app.company(kr, "Kr")
        app.company(other, "Autre")

        val first = invite(mine.id, other, n = 7).bodyAsText()
        val second = invite(mine.id, other, n = 7).bodyAsText()

        assertEquals(first, second)
        assertEquals(1, app.companies.published.size)
        assertEquals(1, app.members.invitations.size)
    }

    @Test
    @DisplayName("invite : un associé reçoit 403 PAS_LE_PDG et rien n'est écrit")
    fun onlyThePdgInvites() = withApp { app ->
        val mine = app.company(kr, "Kr")
        app.partner(mine.id, stranger, "Associé")
        app.company(other, "Autre")

        assertRefused(invite(mine.id, other, actor = stranger), RefusalCodes.NOT_THE_CEO, HttpStatusCode.Forbidden)

        assertNothingWritten(app)
    }

    @Test
    @DisplayName("invite : un joueur déjà membre donne 409 DEJA_POSSEDE, une entreprise au maximum de membres 409 ENTREPRISE_PLEINE, rien d'écrit")
    fun inviteRefusals() = withApp { app ->
        val mine = app.company(kr, "Kr")
        val theirs = app.company(other, "Autre")
        app.partner(mine.id, stranger, "Associé")

        assertRefused(invite(mine.id, stranger, n = 1), RefusalCodes.ALREADY_OWNED)

        app.fill(mine.id, app.limits.maxMembers)
        assertEquals(app.limits.maxMembers, app.store.loadMemberXuids(app.fixture.connection, mine.id).size)
        assertRefused(invite(mine.id, other, n = 2), RefusalCodes.COMPANY_FULL)
        assertRefused(invite(mine.id, stranger, n = 3), RefusalCodes.ALREADY_OWNED)

        assertNothingWritten(app)
        assertEquals(theirs.id, app.store.members.getValue(other).companyId)
    }

    @Test
    @DisplayName("invite : l'entreprise de l'invité est déjà lancée (un associé, un prestige) : 409 ENTREPRISE_EN_COURS, rien d'écrit")
    fun inviteAnUnderwayPlayerIsRefused() = withApp { app ->
        val mine = app.company(kr, "Kr")
        val theirs = app.company(other, "Autre")
        app.partner(theirs.id, stranger, "Associé de l'autre")

        assertRefused(invite(mine.id, other, n = 1), RefusalCodes.ENTERPRISE_UNDERWAY)
        assertNothingWritten(app)

        app.store.members.remove(stranger)
        app.fixture.update(theirs.copy(stars = 1))
        assertRefused(invite(mine.id, other, n = 2), RefusalCodes.ENTERPRISE_UNDERWAY)
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("invite : un acteur qui n'est pas de l'entreprise, une entreprise ou un joueur inconnus, soi-même, un identifiant mal formé : 400")
    fun inviteBadRequests() = withApp { app ->
        val mine = app.company(kr, "Kr")
        val theirs = app.company(other, "Autre")

        assertError(invite(mine.id, other, n = 1, actor = stranger), "acteur inconnu du service")
        assertError(invite(mine.id, other, n = 2, actor = other), "acteur d'une autre entreprise")
        assertError(invite(99, other, n = 3), "entreprise inconnue")
        assertError(invite(mine.id, stranger, n = 4), "joueur inconnu du service")
        assertError(invite(mine.id, kr, n = 5), "le PDG ne s'invite pas")
        assertError(invite(0, other, n = 6), "identifiant 0")
        assertNothingWritten(app)
        assertEquals(theirs.id, app.store.members.getValue(other).companyId)
    }

    // --- acceptInvite

    @Test
    @DisplayName("acceptInvite : l'invité entre comme associé à 0 %, le PDG garde 100 ; son ancienne entreprise reste intacte, sans membre")
    fun acceptEntersAsAPartnerAtZero() = withApp { app ->
        val mine = app.company(kr, "Kr")
        val theirs = app.company(other, "Patron")
        app.invitation(theirs.id, kr, other)
        val factoriesBefore = app.store.loadFactories(app.fixture.connection, mine.id)

        val result = updated(accept(theirs.id))

        assertEquals(theirs.id, result.state.companyId, "l'état rendu est celui de l'entreprise rejointe")
        assertEquals(
            listOf(Triple(other.text, Role.PDG, 100), Triple(kr.text, Role.ASSOCIE, 0)),
            result.state.members.map { Triple(it.xuid, it.role, it.sharePercent) },
            "le PDG d'abord ; les parts font toujours 100",
        )
        assertEquals(listOf("Patron", "Kr"), result.state.members.map { it.name })
        assertEquals(Membership(theirs.id, Role.ASSOCIE), app.store.members.getValue(kr))
        assertEquals(mapOf(other to 100, kr to 0), app.shares(theirs.id))

        // L'entreprise de départ de l'invité n'est jamais supprimée : elle reste, avec ses usines, sans membre.
        assertEquals(mine, app.store.loadCompany(app.fixture.connection, mine.id))
        assertEquals(factoriesBefore, app.store.loadFactories(app.fixture.connection, mine.id))
        assertTrue(app.store.loadMemberXuids(app.fixture.connection, mine.id).isEmpty(), "plus personne dans l'entreprise laissée")
        assertEquals(listOf(InMemoryMemberStore.Write.Moved(kr, mine.id, theirs.id)), app.members.writes)
        assertNull(app.members.invitations[theirs.id to kr], "l'invitation est consommée")

        val event = app.companies.published.single()
        assertEquals(GameEventKind.ANNONCE, event.kind)
        assertEquals(kr, event.xuid)
        assertEquals("acceptInvite", event.payload.text("action"))
        assertEquals(theirs.id.toString(), event.payload.text("companyId"))
        assertEquals(mine.id.toString(), event.payload.text("fromCompanyId"))
    }

    @Test
    @DisplayName("acceptInvite : les autres invitations de l'invité tombent, il n'en a plus besoin")
    fun acceptDropsTheOtherInvitations() = withApp { app ->
        app.company(kr, "Kr")
        val first = app.company(other, "Patron")
        val second = app.company(stranger, "Autre patron")
        app.invitation(first.id, kr, other)
        val kept = app.invitation(second.id, other, stranger)

        updated(accept(first.id))

        assertEquals(mapOf((second.id to other) to kept), app.members.invitations.toMap(), "seules les invitations de l'invité ont disparu")
    }

    @Test
    @DisplayName("acceptInvite : la production de l'entreprise rejointe est versée avant l'arrivée, au régime d'avant (hors ligne : plafond et efficacité)")
    fun acceptPaysProductionOfflineFirst() = withApp { app ->
        app.company(kr, "Kr")
        val theirs = app.company(other, "Patron")
        app.invitation(theirs.id, kr, other, validHours = 1000)
        val away = theirs.offlineCapHours + 20.0
        app.fixture.clock.advance(hours(away))
        val rate = app.fixture.figuresOf(theirs.id).ratePerHour

        val result = updated(accept(theirs.id))

        val write = app.companyUpdates().single()
        val expected = app.engine.offlineEarnings(rate, away, theirs.offlineCapHours)
        assertTrue(expected > 0.0)
        assertClose(expected, (write.parameters.getValue(1) as Double) - theirs.donar, "Donar versé : plafond et efficacité")
        assertClose(theirs.donar + expected, result.state.donar, "Donar de l'état rendu")
        assertEquals(theirs.version, write.parameters.getValue(5), "écrit sous la version lue")
    }

    @Test
    @DisplayName("acceptInvite : si un membre de l'entreprise rejointe est connecté, la période est payée au revenu plein, sans plafond")
    fun acceptPaysProductionOnlineFirst() = withApp { app ->
        app.company(kr, "Kr")
        val theirs = app.company(other, "Patron")
        app.invitation(theirs.id, kr, other, validHours = 1000)
        val away = theirs.offlineCapHours + 20.0
        app.fixture.clock.advance(hours(away))
        app.presence.record("serveur-1", listOf(other.text))
        val rate = app.fixture.figuresOf(theirs.id).ratePerHour

        updated(accept(theirs.id))

        val write = app.companyUpdates().single()
        val expected = app.engine.onlineEarnings(rate, away)
        assertTrue(expected > app.engine.offlineEarnings(rate, away, theirs.offlineCapHours))
        assertClose(expected, (write.parameters.getValue(1) as Double) - theirs.donar, "Donar versé : revenu plein")
    }

    @Test
    @DisplayName("acceptInvite : l'invité connecté seul, lui, ne compte pas : seuls les membres déjà là décident du régime")
    fun theArrivingPlayerDoesNotChangeTheRegime() = withApp { app ->
        app.company(kr, "Kr")
        val theirs = app.company(other, "Patron")
        app.invitation(theirs.id, kr, other, validHours = 1000)
        val away = theirs.offlineCapHours + 20.0
        app.fixture.clock.advance(hours(away))
        app.presence.record("serveur-1", listOf(kr.text))
        val rate = app.fixture.figuresOf(theirs.id).ratePerHour

        updated(accept(theirs.id))

        val write = app.companyUpdates().single()
        assertClose(app.engine.offlineEarnings(rate, away, theirs.offlineCapHours), (write.parameters.getValue(1) as Double) - theirs.donar, "hors ligne")
    }

    @Test
    @DisplayName("acceptInvite : l'entreprise rejointe est pleine : 409 ENTREPRISE_PLEINE ; celle de l'invité est lancée : 409 ENTREPRISE_EN_COURS ; rien d'écrit, l'invitation reste")
    fun acceptRefusals() = withApp { app ->
        val mine = app.company(kr, "Kr")
        val theirs = app.company(other, "Patron")
        app.invitation(theirs.id, kr, other)
        app.fill(theirs.id, app.limits.maxMembers)
        val invitations = app.members.invitations.toMap()

        assertRefused(accept(theirs.id, n = 1), RefusalCodes.COMPANY_FULL)
        assertNothingWritten(app, invitations)

        app.store.members.keys.filter { it != kr && it != other }.forEach { app.store.members.remove(it) }
        app.fixture.update(mine.copy(courseNumber = 2))
        assertRefused(accept(theirs.id, n = 2), RefusalCodes.ENTERPRISE_UNDERWAY)
        assertNothingWritten(app, invitations)
        assertEquals(mine.id, app.store.members.getValue(kr).companyId)
    }

    @Test
    @DisplayName("acceptInvite : accepter l'invitation de sa propre entreprise donne 409 DEJA_POSSEDE")
    fun acceptOwnCompanyIsRefused() = withApp { app ->
        val mine = app.company(kr, "Kr")
        app.invitation(mine.id, kr, other)

        assertRefused(accept(mine.id), RefusalCodes.ALREADY_OWNED)

        assertNothingWritten(app, app.members.invitations.toMap())
    }

    @Test
    @DisplayName("acceptInvite : sans invitation, invitation périmée (à la seconde près), joueur sans entreprise, entreprise inconnue : 400")
    fun acceptBadRequests() = withApp { app ->
        val mine = app.company(kr, "Kr")
        val theirs = app.company(other, "Patron")

        assertError(accept(theirs.id, n = 1), "personne n'a invité Kr")

        app.invitation(theirs.id, kr, other, validHours = 1)
        app.fixture.clock.advance(hours(1.0))
        assertError(accept(theirs.id, n = 2), "l'invitation expire à l'heure dite")
        assertEquals(mine.id, app.store.members.getValue(kr).companyId)

        app.invitation(theirs.id, stranger, other)
        assertError(accept(theirs.id, n = 3, actor = stranger), "joueur inconnu du service")
        assertError(accept(99, n = 4), "entreprise inconnue")
        assertError(accept(0, n = 5), "identifiant 0")
        assertTrue(app.members.writes.isEmpty())
        assertEquals(0, app.companyUpdates().size)
        assertEquals(0, app.companies.published.size)
    }

    @Test
    @DisplayName("acceptInvite : une invitation encore valable une seconde avant l'heure passe")
    fun acceptJustBeforeExpiry() = withApp { app ->
        app.company(kr, "Kr")
        val theirs = app.company(other, "Patron")
        app.invitation(theirs.id, kr, other, validHours = 1)
        app.fixture.clock.advance(hours(1.0).minusSeconds(1))

        assertEquals(theirs.id, updated(accept(theirs.id)).state.companyId)
    }

    @Test
    @DisplayName("acceptInvite rejoué avec le même requestId : la même réponse, une seule arrivée, un seul événement")
    fun acceptReplayed() = withApp { app ->
        app.company(kr, "Kr")
        val theirs = app.company(other, "Patron")
        app.invitation(theirs.id, kr, other)

        val first = accept(theirs.id, n = 3).bodyAsText()
        val second = accept(theirs.id, n = 3).bodyAsText()

        assertEquals(first, second)
        assertEquals(1, app.members.writes.size)
        assertEquals(1, app.companies.published.size)
        assertEquals(1, app.companyUpdates().size)
    }

    @Test
    @DisplayName("acceptInvite : version dépassée (un autre associé a écrit) : 409 CONFLIT_REESSAYER, personne ne bouge, l'invitation reste, et le nouvel essai passe")
    fun acceptOnVersionConflict() = withApp { app ->
        val mine = app.company(kr, "Kr")
        val theirs = app.company(other, "Patron")
        app.invitation(theirs.id, kr, other)
        val invitations = app.members.invitations.toMap()

        app.recording.rowsTouched = 0
        assertRefused(accept(theirs.id, n = 9), RefusalCodes.CONFLICT_RETRY)
        assertTrue(app.members.writes.isEmpty(), "le verrou est pris avant le déplacement : un conflit ne déplace personne")
        assertEquals(invitations, app.members.invitations.toMap())
        assertEquals(0, app.companies.published.size)
        assertEquals(mine.id, app.store.members.getValue(kr).companyId)

        app.recording.rowsTouched = 1
        assertEquals(HttpStatusCode.OK, accept(theirs.id, n = 9).status, "le conflit n'est pas gardé : le nouvel essai passe")
        assertEquals(theirs.id, app.store.members.getValue(kr).companyId)
    }

    // --- setShares

    @Test
    @DisplayName("setShares : le PDG fixe les parts, la version monte d'un, CompanyState.members les montre, l'événement ANNONCE les porte")
    fun setSharesStoresAndAnnounces() = withApp { app ->
        val mine = app.company(kr, "Kr")
        app.partner(mine.id, other, "Associé")
        app.partner(mine.id, stranger, "Autre associé")

        val result = updated(setShares(mine.id, listOf(kr to 50, other to 30, stranger to 20)))

        assertEquals(listOf(50, 30, 20), result.state.members.map { it.sharePercent })
        assertEquals(listOf(kr.text, other.text, stranger.text), result.state.members.map { it.xuid })
        assertEquals(mapOf(kr to 50, other to 30, stranger to 20), app.shares(mine.id))
        assertEquals(mapOf(kr to 50, other to 30, stranger to 20), app.companies.loadShares(app.fixture.connection, mine.id).associate { it.xuid to it.sharePercent }, "le prestige lit ces parts")
        assertEquals(listOf(InMemoryMemberStore.Write.Shares(mine.id, mapOf(kr to 50, other to 30, stranger to 20))), app.members.writes)

        val update = app.companyUpdates().single()
        assertEquals(mine.version, update.parameters.getValue(2), "écrit sous la version lue")

        val event = app.companies.published.single()
        assertEquals(GameEventKind.ANNONCE, event.kind)
        assertEquals(kr, event.xuid)
        assertEquals("setShares", event.payload.text("action"))
        assertEquals(mine.id.toString(), event.payload.text("companyId"))
        val published: JsonArray = event.payload.getValue("shares").jsonArray
        assertEquals(
            listOf(kr.text to 50, other.text to 30, stranger.text to 20),
            published.map { it.jsonObject.text("xuid") to it.jsonObject.getValue("percent").jsonPrimitive.int },
        )
    }

    @Test
    @DisplayName("setShares : le PDG peut se donner 0 % (les parts font 100 en tout), et un associé à 0 % reste membre")
    fun setSharesAllowsZero() = withApp { app ->
        val mine = app.company(kr, "Kr")
        app.partner(mine.id, other, "Associé")

        val result = updated(setShares(mine.id, listOf(kr to 0, other to 100)))

        assertEquals(mapOf(kr to 0, other to 100), app.shares(mine.id))
        assertEquals(listOf(Role.PDG, Role.ASSOCIE), result.state.members.map { it.role })

        updated(setShares(mine.id, listOf(kr to 100, other to 0), n = 2))
        assertEquals(mapOf(kr to 100, other to 0), app.shares(mine.id))
    }

    @Test
    @DisplayName("setShares rejoué avec le même requestId : la même réponse, une seule écriture, un seul événement")
    fun setSharesReplayed() = withApp { app ->
        val mine = app.company(kr, "Kr")
        app.partner(mine.id, other, "Associé")

        val first = setShares(mine.id, listOf(kr to 60, other to 40), n = 5).bodyAsText()
        val second = setShares(mine.id, listOf(kr to 60, other to 40), n = 5).bodyAsText()

        assertEquals(first, second)
        assertEquals(1, app.members.writes.size)
        assertEquals(1, app.companyUpdates().size)
        assertEquals(1, app.companies.published.size)
    }

    @Test
    @DisplayName("setShares : un associé reçoit 403 PAS_LE_PDG et les parts ne bougent pas")
    fun setSharesPdgOnly() = withApp { app ->
        val mine = app.company(kr, "Kr")
        app.partner(mine.id, other, "Associé")

        assertRefused(setShares(mine.id, listOf(kr to 0, other to 100), actor = other), RefusalCodes.NOT_THE_CEO, HttpStatusCode.Forbidden)

        assertNothingWritten(app)
        assertEquals(mapOf(kr to 100, other to 0), app.shares(mine.id))
    }

    @Test
    @DisplayName("setShares : pas 100 en tout, un membre oublié, un inconnu, un doublon, hors de 0 à 100, un acteur étranger : 400 et rien d'écrit")
    fun setSharesBadRequests() = withApp { app ->
        val mine = app.company(kr, "Kr")
        app.partner(mine.id, other, "Associé")
        app.company(stranger, "Autre PDG")

        assertError(setShares(mine.id, listOf(kr to 60, other to 30), n = 1), "99 en tout")
        assertError(setShares(mine.id, listOf(kr to 60, other to 50), n = 2), "110 en tout")
        assertError(setShares(mine.id, listOf(kr to 100), n = 3), "l'associé est oublié")
        assertError(setShares(mine.id, emptyList(), n = 4), "personne n'est nommé")
        assertError(setShares(mine.id, listOf(kr to 50, other to 25, stranger to 25), n = 5), "un joueur d'une autre entreprise")
        assertError(setShares(mine.id, listOf(kr to 50, kr to 50), n = 6), "le même nommé deux fois")
        assertError(setShares(mine.id, listOf(kr to 50, kr to 25, other to 25), n = 7), "le même nommé deux fois, tout y est")
        assertError(setShares(mine.id, listOf(kr to 101, other to -1), n = 8), "hors de 0 à 100")
        assertError(setShares(mine.id, listOf(kr to -10, other to 110), n = 9), "hors de 0 à 100, somme juste")
        assertError(setShares(mine.id, listOf(kr to 100, other to 0), n = 10, actor = stranger), "acteur d'une autre entreprise")
        assertError(setShares(99, listOf(kr to 100), n = 11), "entreprise inconnue")

        assertNothingWritten(app)
        assertEquals(mapOf(kr to 100, other to 0), app.shares(mine.id))
    }

    @Test
    @DisplayName("setShares : version dépassée : 409 CONFLIT_REESSAYER, les parts ne bougent pas, et le même requestId passe au nouvel essai")
    fun setSharesOnVersionConflict() = withApp { app ->
        val mine = app.company(kr, "Kr")
        app.partner(mine.id, other, "Associé")

        app.recording.rowsTouched = 0
        assertRefused(setShares(mine.id, listOf(kr to 70, other to 30), n = 9), RefusalCodes.CONFLICT_RETRY)
        assertTrue(app.members.writes.isEmpty(), "le verrou est pris avant les parts")
        assertEquals(0, app.companies.published.size)
        assertEquals(mapOf(kr to 100, other to 0), app.shares(mine.id))

        app.recording.rowsTouched = 1
        assertEquals(HttpStatusCode.OK, setShares(mine.id, listOf(kr to 70, other to 30), n = 9).status)
        assertEquals(mapOf(kr to 70, other to 30), app.shares(mine.id))
    }

    @Test
    @DisplayName("Le parcours : le PDG invite, l'invité accepte, le PDG partage 60/40 ; le prestige verra ces parts")
    fun fullJourney() = withApp { app ->
        val mine = app.company(kr, "Kr")
        app.company(other, "Invité")

        updated(invite(mine.id, other, n = 1))
        val joined = updated(accept(mine.id, n = 2, actor = other))
        assertEquals(mine.id, joined.state.companyId)
        assertEquals(listOf(100, 0), joined.state.members.map { it.sharePercent })

        val shared = updated(setShares(mine.id, listOf(kr to 60, other to 40), n = 3))
        assertEquals(listOf(kr.text to 60, other.text to 40), shared.state.members.map { it.xuid to it.sharePercent })
        assertEquals(listOf(60, 40), app.companies.loadShares(app.fixture.connection, mine.id).sortedBy { it.role }.map { it.sharePercent })
        assertNotNull(app.store.members[other])
    }
}
