package com.silooo.core.members

import com.silooo.core.api.FactoryState
import com.silooo.core.api.Refusal
import com.silooo.core.api.RefusalCodes
import com.silooo.core.db.VersionConflict
import com.silooo.core.db.Xuid
import com.silooo.core.definitions.CompanyDefinition
import com.silooo.core.definitions.StaffRarity
import com.silooo.core.definitions.StaffRole
import com.silooo.core.economy.EngineFixture
import com.silooo.core.economy.EngineFixture.Companion.hours
import com.silooo.core.economy.InMemoryCompanyStore
import com.silooo.core.economy.RequestRejected
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.OnlinePlayers
import com.silooo.core.session.StaffRecord
import java.sql.Connection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [MemberService] appelé en direct, sans HTTP : la règle « entreprise déjà lancée » dans le détail, les chiffres qui viennent de
 * `definitions/` (et pas du code), et ce qui arrive quand un autre appel a déplacé l'invité entre-temps. Les routes, l'idempotence
 * et les réponses HTTP sont dans `MemberRoutesTest`.
 */
class MemberServiceTest {
    private val kr = EngineFixture.KR
    private val other = EngineFixture.OTHER

    /** Le service sur un [EngineFixture] : Kr est PDG de [mine], Autre PDG de [theirs]. */
    private class Rig(private val limits: CompanyDefinition? = null) {
        val fixture = EngineFixture()
        val companies = InMemoryCompanyStore(fixture.store)
        val members = InMemoryMemberStore(fixture.store)
        val connection: Connection get() = fixture.connection
        val mine: CompanyRecord
        val theirs: CompanyRecord

        init {
            fixture.store.enterPlayer(connection, EngineFixture.KR, "Kr")
            mine = fixture.newCompany(EngineFixture.KR)
            fixture.store.enterPlayer(connection, EngineFixture.OTHER, "Autre")
            theirs = fixture.newCompany(EngineFixture.OTHER)
        }

        fun service(store: MemberStore = members, online: OnlinePlayers = OnlinePlayers.NOBODY) = MemberService(
            sessions = fixture.store,
            companies = companies,
            members = store,
            figures = fixture.figures,
            bootstrap = fixture.bootstrap,
            limits = limits ?: fixture.definitions.world.company,
            online = online,
            clock = fixture.clock,
        )
    }

    private fun assertRefusal(reason: String, block: () -> Unit) {
        val refusal = assertThrows(Refusal::class.java, block)
        assertEquals(reason, refusal.reason)
    }

    @Test
    @DisplayName("Une entreprise toute neuve n'est pas lancée : son PDG peut recevoir une invitation")
    fun aFreshCompanyCanBeInvited() {
        val rig = Rig()
        rig.service().invite(rig.connection, rig.mine.id, other, kr)
        assertEquals(1, rig.members.invitations.size)
    }

    /** Chaque façon d'avoir lancé son entreprise, posée sur l'entreprise de l'invité. */
    private val underway: Map<String, (Rig) -> Unit> = mapOf(
        "un autre membre" to { rig ->
            rig.fixture.store.enterPlayer(rig.connection, Xuid.parse("2535428000000050")!!, "Associé")
            rig.fixture.addMember(rig.theirs.id, Xuid.parse("2535428000000050")!!, lingots = 0)
        },
        "une étoile" to { rig -> rig.fixture.update(rig.theirs.copy(stars = 1)) },
        "une course de plus" to { rig -> rig.fixture.update(rig.theirs.copy(courseNumber = 2)) },
        "une autre époque" to { rig -> rig.fixture.update(rig.theirs.copy(era = 2)) },
        "une mairie améliorée" to { rig -> rig.fixture.update(rig.theirs.copy(mairieLevel = 2)) },
        "un silo amélioré" to { rig -> rig.fixture.update(rig.theirs.copy(siloLevel = 2)) },
        "du personnel" to { rig ->
            rig.fixture.store.staff[rig.theirs.id] =
                mutableListOf(StaffRecord(1, null, "Recrue", "ouvrier-casque", StaffRole.SALARIE, StaffRarity.entries.first()))
        },
        "une zone de la ville" to { rig -> rig.fixture.store.zones[rig.theirs.id] = listOf(1) },
        "une usine de plus" to { rig ->
            rig.fixture.putFactory(rig.theirs.id, FactoryRecord(rig.fixture.otherFactories().first(), FactoryState.ACTIVE, 0, 1, null))
        },
        "un niveau gagné" to { rig ->
            val start = rig.fixture.store.loadFactories(rig.connection, rig.theirs.id).first()
            rig.fixture.putFactory(rig.theirs.id, start.copy(level = start.level + 1))
        },
        "une étape de remise en état faite" to { rig ->
            val start = rig.fixture.store.loadFactories(rig.connection, rig.theirs.id).first()
            rig.fixture.putFactory(rig.theirs.id, start.copy(restoreStep = start.restoreStep + 1))
        },
    )

    @Test
    @DisplayName("Une entreprise est lancée dès qu'elle a un autre membre, un prestige, une course, de l'époque, de la mairie ou du silo, du personnel, une zone ou d'autres usines : invite refuse")
    fun inviteRefusesEveryWayOfHavingStarted() {
        for ((label, change) in underway) {
            val rig = Rig()
            change(rig)
            assertRefusal(RefusalCodes.ENTERPRISE_UNDERWAY) { rig.service().invite(rig.connection, rig.mine.id, other, kr) }
            assertTrue(rig.members.invitations.isEmpty(), "$label : pas d'invitation gardée")
            assertEquals(0, rig.companies.published.size, "$label : pas d'événement")
        }
    }

    @Test
    @DisplayName("Les mêmes cas à l'acceptation : accept refuse ENTREPRISE_EN_COURS quand c'est l'entreprise de l'invité qui est lancée, personne ne bouge")
    fun acceptRefusesEveryWayOfHavingStarted() {
        for ((label, change) in underway) {
            val rig = Rig()
            // Kr invite Autre dans `mine` ; le changement est posé sur `theirs`, l'entreprise de départ d'Autre.
            rig.members.invitations[rig.mine.id to other] = Invitation(rig.mine.id, other, kr, rig.fixture.clock.instant().plusSeconds(3600))
            change(rig)
            assertRefusal(RefusalCodes.ENTERPRISE_UNDERWAY) { rig.service().accept(rig.connection, rig.mine.id, other) }
            assertTrue(rig.members.writes.isEmpty(), "$label : personne n'a bougé")
            assertEquals(rig.theirs.id, rig.fixture.store.members.getValue(other).companyId, "$label : l'invité garde son entreprise")
            assertEquals(1, rig.members.invitations.size, "$label : l'invitation reste")
        }
    }

    @Test
    @DisplayName("Le nombre de membres au plus et la durée d'une invitation viennent de definitions/ : un autre monde.json les change sans toucher au code")
    fun limitsComeFromTheDefinitions() {
        val rig = Rig(CompanyDefinition(maxMembers = 2, invitationHours = 3))
        val service = rig.service()
        rig.fixture.store.enterPlayer(rig.connection, Xuid.parse("2535428000000051")!!, "Troisième")
        val third = rig.fixture.newCompany(Xuid.parse("2535428000000051")!!)

        // Une invitation vaut trois heures, pas plus.
        service.invite(rig.connection, rig.mine.id, other, kr)
        assertEquals(EngineFixture.START.plusSeconds(3 * 3600L), rig.members.invitations.getValue(rig.mine.id to other).expiresAt)

        // Le deuxième membre remplit l'entreprise : le troisième n'y entre plus.
        rig.fixture.clock.advance(hours(2.9))
        service.accept(rig.connection, rig.mine.id, other)
        assertEquals(2, rig.fixture.store.loadMemberXuids(rig.connection, rig.mine.id).size)
        assertRefusal(RefusalCodes.COMPANY_FULL) { service.invite(rig.connection, rig.mine.id, Xuid.parse("2535428000000051")!!, kr) }
        assertEquals(third.id, rig.fixture.store.members.getValue(Xuid.parse("2535428000000051")!!).companyId)

        // Passé trois heures, une invitation n'ouvre plus rien.
        val late = Rig(CompanyDefinition(maxMembers = 2, invitationHours = 3))
        late.service().invite(late.connection, late.mine.id, other, kr)
        late.fixture.clock.advance(hours(3.0))
        assertThrows(RequestRejected::class.java) { late.service().accept(late.connection, late.mine.id, other) }
    }

    @Test
    @DisplayName("Un autre appel a déplacé l'invité entre la lecture et l'écriture : VersionConflict, jamais deux entreprises ni une arrivée perdue")
    fun aMemberMovedByAnotherCallIsAConflict() {
        val rig = Rig()
        rig.members.invitations[rig.mine.id to other] = Invitation(rig.mine.id, other, kr, rig.fixture.clock.instant().plusSeconds(3600))
        val vanishing = object : MemberStore by rig.members {
            override fun moveMember(connection: Connection, xuid: Xuid, fromCompanyId: Long, toCompanyId: Long): Boolean = false
        }

        assertThrows(VersionConflict::class.java) { rig.service(vanishing).accept(rig.connection, rig.mine.id, other) }

        assertEquals(1, rig.members.invitations.size, "l'invitation n'est pas consommée")
        assertEquals(0, rig.companies.published.size, "pas d'événement pour une arrivée qui n'a pas eu lieu")
    }

    @Test
    @DisplayName("Le PDG invite, l'invité accepte, puis une seconde invitation du même PDG au même joueur est refusée : il est déjà membre")
    fun invitingAMemberTwiceIsRefused() {
        val rig = Rig()
        val service = rig.service()
        service.invite(rig.connection, rig.mine.id, other, kr)
        service.accept(rig.connection, rig.mine.id, other)

        assertRefusal(RefusalCodes.ALREADY_OWNED) { service.invite(rig.connection, rig.mine.id, other, kr) }
    }
}
