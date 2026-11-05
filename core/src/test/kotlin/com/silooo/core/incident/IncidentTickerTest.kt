package com.silooo.core.incident

import com.silooo.core.Dependencies
import com.silooo.core.api.Idempotency
import com.silooo.core.db.InMemoryApiRequestStore
import com.silooo.core.db.VersionConflict
import com.silooo.core.db.Xuid
import com.silooo.core.economy.EngineFixture
import com.silooo.core.siloooModule
import io.ktor.server.testing.testApplication
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le minuteur des incidents ([IncidentTicker]) avec des faux (pas de base) : qui il tire (seulement les membres connectés depuis deux
 * passages), ce qu'il laisse tranquille (les absents, les inconnus), et ce qu'il fait d'une entreprise en panne. Le service lui-même
 * est testé dans `IncidentServiceTest`.
 */
class IncidentTickerTest {
    private val kr = EngineFixture.KR
    private val other = EngineFixture.OTHER
    private val stranger = EngineFixture.STRANGER

    /** Un minuteur dont la liste des joueurs en ligne est celle que le test pose dans [online]. */
    private class Harness(val rig: IncidentRig, val flaky: FlakyIncidentStore?) {
        var online: Set<Xuid> = emptySet()
        val ticker = IncidentTicker(rig.database, rig.fx.store, rig.service) { online }

        fun tick() = runBlocking { ticker.tick() }
    }

    private fun harness(flaky: Boolean = false, chancePercent: Double = 100.0): Harness {
        var wrapper: FlakyIncidentStore? = null
        val rig = IncidentRig(chancePercent) { store -> if (flaky) FlakyIncidentStore(store).also { wrapper = it } else store }
        return Harness(rig, wrapper)
    }

    @Test
    @DisplayName("Un joueur n'est tiré qu'une fois vu à deux passages de suite : le premier passage ne tire rien, le second ouvre l'incident")
    fun aPlayerMustBeSeenTwice() {
        val app = harness()
        app.rig.company()
        app.online = setOf(kr)

        assertTrue(app.tick().isEmpty(), "le join n'a peut-être pas encore versé l'absence")
        assertTrue(app.rig.incidents.rows.isEmpty())

        val opened = app.tick()
        assertEquals(1, opened.size)
        assertEquals(1, app.rig.incidents.rows.size)
    }

    @Test
    @DisplayName("Un joueur qui vient d'arriver, ou qui vient de partir, n'est pas tiré ; une entreprise sans personne de stable en ligne non plus")
    fun arrivalsAndDeparturesAreNotTicked() {
        val app = harness()
        app.rig.company()
        app.online = setOf(kr)
        app.tick()

        app.online = emptySet() // il part
        assertTrue(app.tick().isEmpty())
        app.online = setOf(kr) // il revient : à nouveau, un passage pour rien
        assertTrue(app.tick().isEmpty())
        assertTrue(app.rig.incidents.rows.isEmpty(), "jamais deux passages de suite")

        assertEquals(1, app.tick().size)
    }

    @Test
    @DisplayName("Une entreprise dont personne n'est en ligne n'a jamais d'incident : pas pendant une absence, aussi longue soit-elle")
    fun anAbsenceIsNeverTicked() {
        val app = harness()
        val absent = app.rig.company()
        val present = app.rig.company(owner = other)
        app.online = setOf(other)
        app.rig.fx.clock.advance(Duration.ofHours(30))

        app.tick()
        val opened = app.tick()

        assertEquals(1, opened.size)
        assertEquals(setOf(present.id), app.rig.incidents.rows.map { it.companyId }.toSet())
        assertTrue(app.rig.incidents.rows.none { it.companyId == absent.id })
    }

    @Test
    @DisplayName("Un joueur en ligne qui n'est d'aucune entreprise est ignoré, sans faire tomber le passage")
    fun aStrangerIsIgnored() {
        val app = harness()
        val company = app.rig.company()
        app.online = setOf(stranger, kr)

        app.tick()
        val opened = app.tick()

        assertEquals(listOf(company.id), app.rig.incidents.rows.map { it.companyId })
        assertEquals(1, opened.size)
    }

    @Test
    @DisplayName("Plusieurs membres d'une même entreprise en ligne : un seul tirage pour l'entreprise, l'événement part vers chacun")
    fun oneDrawPerCompany() {
        val app = harness()
        val company = app.rig.company()
        app.rig.fx.addMember(company.id, other, 0)
        app.online = setOf(kr, other)

        app.tick()
        val opened = app.tick()

        assertEquals(1, opened.size)
        assertEquals(1, app.rig.incidents.rows.size)
        assertEquals(setOf(kr, other), app.rig.companies.published.map { it.xuid }.toSet())
        assertEquals(2, app.rig.companies.published.size)
    }

    @Test
    @DisplayName("Au passage suivant, dans le même créneau, il n'y a pas de second incident ; au créneau suivant, il peut y en avoir un autre")
    fun oneIncidentPerSlotAcrossPasses() {
        val app = harness()
        app.rig.company()
        app.online = setOf(kr)
        app.tick()
        assertEquals(1, app.tick().size)

        app.rig.fx.clock.advance(Duration.ofSeconds(30))
        assertTrue(app.tick().isEmpty(), "même créneau")
        assertEquals(1, app.rig.incidents.rows.size)

        app.rig.fx.clock.advance(Duration.ofMinutes(31))
        assertEquals(1, app.tick().size, "créneau suivant")
        assertEquals(2, app.rig.incidents.rows.size)
    }

    @Test
    @DisplayName("Plusieurs entreprises en ligne : chacune a son tirage, par numéro d'entreprise croissant")
    fun everyOnlineCompanyIsDrawn() {
        val app = harness()
        val first = app.rig.company()
        val second = app.rig.company(owner = other)
        app.online = setOf(other, kr)

        app.tick()
        val opened = app.tick()

        assertEquals(2, opened.size)
        assertEquals(listOf(first.id, second.id), app.rig.incidents.rows.map { it.companyId })
    }

    @Test
    @DisplayName("Une entreprise qui échoue (panne de la base) est laissée à plus tard et ne retarde pas les autres ; au passage suivant, son tirage est rejoué à l'identique")
    fun aFailingCompanyDoesNotBlockTheOthers() {
        val app = harness(flaky = true)
        val broken = app.rig.company()
        val healthy = app.rig.company(owner = other)
        app.flaky!!.failing[broken.id] = IllegalStateException("panne de test")
        app.online = setOf(kr, other)

        app.tick()
        val opened = app.tick()

        assertEquals(1, opened.size)
        assertEquals(listOf(healthy.id), app.rig.incidents.rows.map { it.companyId })

        app.flaky.failing.clear()
        assertEquals(1, app.tick().size, "l'entreprise en panne est reprise")
        assertEquals(setOf(broken.id, healthy.id), app.rig.incidents.rows.map { it.companyId }.toSet())
    }

    @Test
    @DisplayName("Un conflit de verrou ou une erreur d'une entreprise ne remonte jamais du passage : il est noté et le passage continue")
    fun errorsDoNotEscape() {
        val app = harness(flaky = true)
        val first = app.rig.company()
        val second = app.rig.company(owner = other)
        app.flaky!!.failing[first.id] = VersionConflict()
        app.flaky.failing[second.id] = RuntimeException("autre panne")
        app.online = setOf(kr, other)

        app.tick()
        assertTrue(app.tick().isEmpty())
        assertTrue(app.rig.incidents.rows.isEmpty())
    }

    @Test
    @DisplayName("À 0 % de chance, le minuteur ne tire rien, même pour des joueurs connectés depuis longtemps")
    fun noChanceNoIncident() {
        val app = harness(chancePercent = 0.0)
        app.rig.company()
        app.online = setOf(kr)

        repeat(10) { app.tick(); app.rig.fx.clock.advance(Duration.ofMinutes(30)) }

        assertTrue(app.rig.incidents.rows.isEmpty())
        assertEquals(0, app.rig.fx.recording.statements.size)
    }

    @Test
    @DisplayName("Le module lance le minuteur quand Dependencies.incidentTick est posé : un joueur annoncé par le signal de vie du serveur voit son entreprise touchée")
    fun theModuleRunsTheTicker() {
        val rig = IncidentRig()
        rig.company()
        testApplication {
            application {
                siloooModule(
                    Dependencies(
                        database = rig.database,
                        definitions = rig.definitions,
                        apiKey = "cle-de-test",
                        economy = rig.engine,
                        idempotency = Idempotency(rig.database, InMemoryApiRequestStore(EngineFixture.START)),
                        sessionStore = rig.fx.store,
                        clock = rig.fx.clock,
                        economyFigures = rig.fx.figures,
                        companyBootstrap = rig.fx.bootstrap,
                        companyStore = rig.companies,
                        incidentStore = rig.incidents,
                        serverPresence = rig.presence,
                        incidentTick = Duration.ofMillis(20),
                    ),
                )
            }
            startApplication()
            // L'horloge du faux est figée : le signal de vie reste frais, le minuteur le voit à chaque passage.
            rig.presence.record("serveur-1", listOf(kr.text))
            val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
            while (rig.incidents.rows.isEmpty() && System.nanoTime() < deadline) delay(20)
        }
        assertEquals(1, rig.incidents.rows.size, "le minuteur a tiré une fois, puis le créneau était pris")
        assertEquals(listOf(kr), rig.companies.published.map { it.xuid })
    }
}
