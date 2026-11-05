package com.silooo.core.incident

import com.silooo.core.api.FactoryState
import com.silooo.core.db.VersionConflict
import com.silooo.core.economy.EngineFixture
import com.silooo.core.economy.EngineFixture.Companion.hours
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.FactoryRecord
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * L'ouverture d'un incident ([IncidentService.tick]) avec des faux (pas de base) : ce que le service fait d'un tirage gagnant. Le
 * tirage joue à 100 % de chance (`IncidentRig`), sauf quand le test veut qu'il ne tombe pas. Les chiffres attendus sortent du
 * moteur et du tirage ([IncidentDraw]), jamais d'un littéral. La réparation est testée avec les routes (`IncidentRoutesTest`).
 */
class IncidentServiceTest {
    private val kr = EngineFixture.KR
    private val other = EngineFixture.OTHER

    private fun assertClose(expected: Double, actual: Double, what: String = "valeur") {
        assertTrue(abs(expected - actual) <= 1e-9 * maxOf(1.0, abs(expected)), "$what : attendu $expected, reçu $actual")
    }

    @Test
    @DisplayName("Le tirage du créneau choisit l'usine et la sorte : l'incident s'ouvre sur l'usine que IncidentDraw désigne parmi les usines atteignables, triées")
    fun theDrawPicksTheFactoryAndTheKind() {
        val rig = IncidentRig()
        val company = rig.company(extra = 3)
        val roll = rig.draw.roll(company.id, rig.draw.slotAt(rig.fx.clock.instant()))!!
        val eligible = (listOf(rig.starterId) + rig.ordinary.take(3)).sorted()

        val opened = rig.service.tick(rig.connection, company.id, listOf(kr))!!

        assertEquals(rig.draw.factoryFor(roll, eligible), opened.factoryId)
        assertEquals(roll.kind.id, opened.kind)
        assertEquals(roll.kind.slowdown, opened.slowdown)
        val row = rig.incidents.rows.single()
        assertEquals(opened.incidentId, row.id)
        assertEquals(company.id, row.companyId)
        assertEquals(opened.factoryId, row.factoryId)
        assertEquals(roll.kind.id, row.kind)
        assertEquals(roll.kind.slowdown, row.slowdown)
        assertEquals(rig.fx.clock.instant(), row.startedAt)
        assertNull(row.resolvedAt)
        val factory = rig.fx.store.loadFactories(rig.connection, company.id).single { it.factoryId == opened.factoryId }
        assertEquals(opened.incidentId, factory.openIncidentId)
        assertEquals(roll.kind.slowdown, factory.incidentSlowdown)
        assertEquals(roll.kind.id, factory.incidentKind)
    }

    @Test
    @DisplayName("Le tirage est rejouable : deux services neufs, même entreprise, même créneau, ouvrent le même incident sur la même usine")
    fun theDrawIsReplayable() {
        val first = IncidentRig()
        val second = IncidentRig()
        val a = first.service.tick(first.connection, first.company().id, listOf(kr))
        val b = second.service.tick(second.connection, second.company().id, listOf(kr))

        assertNotNull(a)
        assertEquals(a, b)
    }

    @Test
    @DisplayName("L'ouverture verse d'abord la production en attente au rythme d'avant, en ligne, puis ralentit l'usine : le ralentissement ne touche que ce qui vient après")
    fun openingPaysTheProductionAtTheOldRateFirst() {
        val rig = IncidentRig()
        val company = rig.company(donar = 100.0)
        val before = rig.fx.figuresOf(company.id)
        rig.fx.clock.advance(hours(2.0))

        val opened = rig.service.tick(rig.connection, company.id, listOf(kr))!!

        val update = rig.companyUpdates().single()
        val earned = rig.engine.onlineEarnings(before.ratePerHour, 2.0)
        assertClose(100.0 + earned, update.parameters[1] as Double, "donar versé")
        assertClose(company.donarEarned + earned, update.parameters[2] as Double, "donar gagné")
        assertEquals(LocalDateTime.ofInstant(rig.fx.clock.instant(), ZoneOffset.UTC), update.parameters[3], "la production est payée jusqu'à maintenant")
        val after = rig.fx.figuresOf(company.id)
        assertTrue(after.forFactory(opened.factoryId).ratePerHour < before.forFactory(opened.factoryId).ratePerHour, "l'usine touchée est ralentie")
        assertTrue(after.ratePerHour < before.ratePerHour)
        assertTrue(after.ratePerHour > 0.0, "un incident ralentit, il n'arrête pas")
    }

    @Test
    @DisplayName("Un incident ne fait rien perdre : ni Donar, ni niveau, ni usine, ni personnel")
    fun anIncidentLosesNothing() {
        val rig = IncidentRig()
        val company = rig.company(donar = 250.0)
        val levels = rig.fx.store.loadFactories(rig.connection, company.id).associate { it.factoryId to (it.state to it.level) }
        rig.fx.clock.advance(hours(1.0))

        rig.service.tick(rig.connection, company.id, listOf(kr))

        assertTrue(rig.companyUpdates().single().parameters[1] as Double >= 250.0, "le Donar ne baisse jamais")
        assertEquals(levels, rig.fx.store.loadFactories(rig.connection, company.id).associate { it.factoryId to (it.state to it.level) })
        assertEquals(0, rig.companies.saved.size, "aucune usine réécrite")
    }

    @Test
    @DisplayName("L'événement INCIDENT « opened » part vers chaque membre connecté, avec l'incident, l'usine, la sorte et le ralentissement")
    fun theOpeningIsPublishedToEveryOnlineMember() {
        val rig = IncidentRig()
        val company = rig.company()
        rig.fx.addMember(company.id, other, 0)

        val opened = rig.service.tick(rig.connection, company.id, listOf(kr, other))!!

        val events = rig.companies.published
        assertEquals(listOf(kr, other), events.map { it.xuid })
        for (event in events) {
            assertEquals(GameEventKind.INCIDENT, event.kind)
            assertEquals("opened", event.payload.getValue("action").jsonPrimitive.content)
            assertEquals(company.id.toString(), event.payload.getValue("companyId").jsonPrimitive.content)
            assertEquals(opened.incidentId.toString(), event.payload.getValue("incidentId").jsonPrimitive.content)
            assertEquals(opened.factoryId, event.payload.getValue("factoryId").jsonPrimitive.content)
            assertEquals(opened.kind, event.payload.getValue("kind").jsonPrimitive.content)
            assertEquals(opened.slowdown, event.payload.getValue("slowdown").jsonPrimitive.content.toDouble())
        }
    }

    @Test
    @DisplayName("Au plus un incident par créneau et par entreprise, même si le premier est déjà réparé ; au créneau suivant, un autre peut s'ouvrir")
    fun oneIncidentPerSlot() {
        val rig = IncidentRig()
        val company = rig.company(extra = 3)
        val slot = rig.draw.slotAt(rig.fx.clock.instant())

        val first = rig.service.tick(rig.connection, company.id, listOf(kr))!!
        val written = rig.fx.recording.statements.size
        rig.fx.clock.advance(Duration.ofMinutes(10))
        assertNull(rig.service.tick(rig.connection, company.id, listOf(kr)), "même créneau : rien de plus")
        rig.incidents.close(rig.connection, company.id, first.incidentId, rig.fx.clock.instant())
        assertNull(rig.service.tick(rig.connection, company.id, listOf(kr)), "même créneau, incident réparé : rien de plus non plus")
        assertEquals(written, rig.fx.recording.statements.size, "et rien n'est écrit")
        assertEquals(1, rig.companies.published.size)

        rig.clockAt(slot.start.plus(Duration.ofMinutes(rig.fx.definitions.incidents.frequency.slotMinutes.toLong())))
        val next = rig.service.tick(rig.connection, company.id, listOf(kr))
        assertNotNull(next, "le créneau suivant tire de nouveau")
        assertEquals(2, rig.incidents.rows.size)
    }

    @Test
    @DisplayName("Une usine qui a déjà un incident ouvert n'en reçoit pas un second : avec une seule usine, le créneau suivant ne tire rien tant qu'il n'est pas réparé")
    fun oneOpenIncidentPerFactory() {
        val rig = IncidentRig()
        val company = rig.company(extra = 0)
        val slotLength = Duration.ofMinutes(rig.fx.definitions.incidents.frequency.slotMinutes.toLong())

        val first = rig.service.tick(rig.connection, company.id, listOf(kr))!!
        rig.fx.clock.advance(slotLength)
        val writes = rig.fx.recording.statements.size
        assertNull(rig.service.tick(rig.connection, company.id, listOf(kr)), "la seule usine a déjà son incident")
        assertEquals(writes, rig.fx.recording.statements.size, "aucune usine atteignable : rien n'est écrit, pas même la production")

        rig.incidents.close(rig.connection, company.id, first.incidentId, rig.fx.clock.instant())
        rig.fx.clock.advance(slotLength)
        assertNotNull(rig.service.tick(rig.connection, company.id, listOf(kr)), "réparée, elle peut être frappée de nouveau")
    }

    @Test
    @DisplayName("Seule une usine ACTIVE, de niveau 1 au moins et qui a du courant est frappée : ruine, remise en état, niveau 0 et usine sans courant ne le sont jamais")
    fun onlyRunningFactoriesAreHit() {
        val rig = IncidentRig()
        val era = rig.fx.definitions.incidents.electricity.fromEra
        val company = rig.company(extra = 0, era = era)
        val electric = rig.fx.definitions.factories.factories.first { it.needsElectricity }.id
        rig.fx.putFactory(company.id, FactoryRecord(rig.ordinary[0], FactoryState.EN_RUINE, 0, 0, null))
        rig.fx.putFactory(company.id, FactoryRecord(rig.ordinary[1], FactoryState.REMISE_EN_ETAT, 2, 3, null))
        rig.fx.putFactory(company.id, FactoryRecord(rig.ordinary[2], FactoryState.ACTIVE, 0, 0, null))
        rig.fx.putFactory(company.id, FactoryRecord(electric, FactoryState.ACTIVE, 0, 5, null))
        assertTrue(rig.fx.figuresOf(company.id).forFactory(electric).stoppedNoPower, "sans Centrale, l'usine électrique est à l'arrêt")

        for (day in 0L until 40L) {
            rig.clockAt(EngineFixture.START.plus(Duration.ofDays(day)))
            val opened = rig.service.tick(rig.connection, company.id, listOf(kr))!!
            assertEquals(rig.starterId, opened.factoryId, "jour $day : la seule usine atteignable")
            rig.incidents.close(rig.connection, company.id, opened.incidentId, rig.fx.clock.instant())
        }
    }

    @Test
    @DisplayName("Aucune usine atteignable : le tirage ne donne rien et rien n'est écrit, ni Donar, ni incident, ni événement")
    fun nothingToHitWritesNothing() {
        val rig = IncidentRig()
        val company = rig.company(extra = 0)
        rig.fx.putFactory(company.id, FactoryRecord(rig.starterId, FactoryState.EN_RUINE, 0, 0, null))
        rig.fx.clock.advance(hours(1.0))

        assertNull(rig.service.tick(rig.connection, company.id, listOf(kr)))

        assertEquals(0, rig.fx.recording.statements.size)
        assertTrue(rig.incidents.rows.isEmpty())
        assertTrue(rig.companies.published.isEmpty())
    }

    @Test
    @DisplayName("Quand le tirage du créneau ne tombe pas, rien n'est écrit : 0 % de chance sur toute une journée")
    fun aLosingDrawWritesNothing() {
        val rig = IncidentRig(chancePercent = 0.0)
        val company = rig.company()

        for (slot in 0 until 48) {
            rig.clockAt(EngineFixture.START.plus(Duration.ofMinutes(30L * slot)))
            assertNull(rig.service.tick(rig.connection, company.id, listOf(kr)))
        }

        assertEquals(0, rig.fx.recording.statements.size)
        assertTrue(rig.incidents.rows.isEmpty())
        assertTrue(rig.companies.published.isEmpty())
    }

    @Test
    @DisplayName("Un incident ne s'ouvre que pendant qu'un membre est connecté : sans personne en ligne, le service refuse de tirer")
    fun nobodyOnlineMeansNoDraw() {
        val rig = IncidentRig()
        val company = rig.company()

        assertThrows(IllegalArgumentException::class.java) { rig.service.tick(rig.connection, company.id, emptyList()) }

        assertTrue(rig.incidents.rows.isEmpty())
        assertEquals(0, rig.fx.recording.statements.size)
    }

    @Test
    @DisplayName("Un verrou perdu (une action de l'entreprise passe en même temps) : 409 CONFLIT_REESSAYER, aucun incident, et le même créneau se rejoue ensuite")
    fun aLostLockOpensNothing() {
        val rig = IncidentRig()
        val company = rig.company()
        rig.fx.clock.advance(hours(1.0))

        rig.fx.recording.rowsTouched = 0
        assertThrows(VersionConflict::class.java) { rig.service.tick(rig.connection, company.id, listOf(kr)) }
        assertTrue(rig.incidents.rows.isEmpty(), "le Donar est écrit d'abord : le conflit arrive avant l'incident")
        assertTrue(rig.companies.published.isEmpty())

        rig.fx.recording.rowsTouched = 1
        assertNotNull(rig.service.tick(rig.connection, company.id, listOf(kr)), "le tirage du créneau n'a pas changé")
    }

    @Test
    @DisplayName("Ouverture puis réparation : le revenu retrouve sa valeur d'avant, et le Donar gagné de la réparation est celui du revenu sans incident")
    fun openThenResolveRestoresTheRate() {
        val rig = IncidentRig()
        val company = rig.company(donar = 100.0)
        val before = rig.fx.figuresOf(company.id)

        val opened = rig.service.tick(rig.connection, company.id, listOf(kr))!!
        assertTrue(rig.fx.figuresOf(company.id).ratePerHour < before.ratePerHour)
        val result = rig.service.resolve(rig.connection, company.id, opened.incidentId, kr)

        assertEquals(before.ratePerHour, rig.fx.figuresOf(company.id).ratePerHour, "la production est de nouveau entière")
        assertEquals(before.ratePerHour, result.state.ratePerHour)
        assertNull(result.state.factories.single { it.factoryId == opened.factoryId }.incidentId)
        assertNotNull(rig.incidents.rows.single().resolvedAt)
        val reward = rig.engine.incidentReward(before.forFactory(opened.factoryId).ratePerHour)
        assertClose(reward, rig.companies.published.last().payload.getValue("reward").jsonPrimitive.content.toDouble(), "récompense")
    }
}
