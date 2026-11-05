package com.silooo.core.incident

import com.silooo.core.TestDefinitions
import com.silooo.core.definitions.ElectricityDefinition
import com.silooo.core.definitions.IncidentFrequency
import com.silooo.core.definitions.IncidentKindDefinition
import com.silooo.core.definitions.IncidentReward
import com.silooo.core.definitions.IncidentsDefinitions
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le tirage des incidents ([IncidentDraw]) : un créneau donne toujours le même résultat, rien d'autre que l'entreprise, le jour et
 * le rang du créneau ne le fait changer, et les fréquences suivent `incidents.json`. Les tests de forme (créneaux, suite figée)
 * prennent des définitions écrites ici, pour que changer un réglage de `definitions/` ne casse pas un test du tirage ; les tests
 * de fréquence lisent celles du dépôt.
 */
class IncidentDrawTest {
    private val repo = TestDefinitions.loaded.incidents

    private fun definitions(chance: Double, slotMinutes: Int = 30, kinds: List<Triple<String, Double, Int>> = KINDS): IncidentsDefinitions =
        IncidentsDefinitions(
            version = 1,
            frequency = IncidentFrequency(slotMinutes, chance),
            kinds = kinds.map { (id, slowdown, weight) -> IncidentKindDefinition(id, slowdown, weight) },
            reward = IncidentReward(10.0),
            electricity = ElectricityDefinition(3, "centrale"),
        )

    private fun at(text: String): Instant = Instant.parse(text)

    @Test
    @DisplayName("Un créneau est une tranche de creneauMinutes minutes, UTC, numérotée depuis minuit : 0 à minuit, 24 à midi pour des créneaux de 30 minutes")
    fun slots() {
        val draw = IncidentDraw(definitions(chance = 50.0))

        val midnight = draw.slotAt(at("2026-11-05T00:00:00Z"))
        assertEquals(IncidentSlot(at("2026-11-05T00:00:00Z"), LocalDate.parse("2026-11-05"), 0), midnight)

        val noon = draw.slotAt(at("2026-11-05T12:00:00Z"))
        assertEquals(24, noon.ordinal)
        assertEquals(at("2026-11-05T12:00:00Z"), noon.start)
        assertEquals(noon, draw.slotAt(at("2026-11-05T12:29:59.999Z")), "toute la tranche est le même créneau")
        assertEquals(25, draw.slotAt(at("2026-11-05T12:30:00Z")).ordinal)

        val last = draw.slotAt(at("2026-11-05T23:59:59Z"))
        assertEquals(47, last.ordinal)
        assertEquals(IncidentSlot(at("2026-11-06T00:00:00Z"), LocalDate.parse("2026-11-06"), 0), draw.slotAt(at("2026-11-06T00:00:00Z")))
    }

    @Test
    @DisplayName("La durée d'un créneau vient des définitions : des créneaux d'une heure donnent 24 rangs par jour")
    fun slotLengthComesFromTheDefinitions() {
        val draw = IncidentDraw(definitions(chance = 50.0, slotMinutes = 60))
        assertEquals(12, draw.slotAt(at("2026-11-05T12:59:00Z")).ordinal)
        assertEquals(at("2026-11-05T12:00:00Z"), draw.slotAt(at("2026-11-05T12:59:00Z")).start)
        assertEquals(23, draw.slotAt(at("2026-11-05T23:59:00Z")).ordinal)
    }

    @Test
    @DisplayName("Le même créneau de la même entreprise donne toujours le même résultat, d'un appel à l'autre et d'un tirage à l'autre")
    fun sameSlotSameResult() {
        val slot = IncidentDraw(definitions(50.0)).slotAt(at("2026-11-05T12:10:00Z"))
        val results = (1..5).map { IncidentDraw(definitions(50.0)).roll(7, slot) }
        assertEquals(1, results.toSet().size, "cinq tirages indépendants, un seul résultat")
        val draw = IncidentDraw(definitions(50.0))
        assertEquals(draw.roll(7, slot), draw.roll(7, slot))
    }

    @Test
    @DisplayName("La suite du tirage est figée : à 100 % de chance, les sortes et les nombres bruts des douze premiers créneaux du 5 novembre 2026 de l'entreprise 1 ne changent pas")
    fun frozenSequence() {
        val draw = IncidentDraw(definitions(chance = 100.0))
        val rolls = (0 until 12).map { draw.roll(1, draw.slotAt(at("2026-11-05T00:00:00Z").plusSeconds(it * 1800L))) }
        assertTrue(rolls.all { it != null }, "à 100 %, chaque créneau tire un incident")
        assertEquals(FROZEN_KINDS, rolls.map { it!!.kind.id })
        assertEquals(FROZEN_PICKS, rolls.map { it!!.factoryPick })
    }

    @Test
    @DisplayName("Une autre entreprise, un autre jour ou un autre rang donnent un autre tirage : rien n'est partagé par hasard d'arithmétique")
    fun differentInputsDiffer() {
        val draw = IncidentDraw(definitions(chance = 100.0))
        val base = draw.slotAt(at("2026-11-05T12:00:00Z"))
        val picks = setOf(
            draw.roll(1, base)!!.factoryPick,
            draw.roll(2, base)!!.factoryPick,
            draw.roll(1, draw.slotAt(at("2026-11-06T12:00:00Z")))!!.factoryPick,
            draw.roll(1, draw.slotAt(at("2026-11-05T12:30:00Z")))!!.factoryPick,
        )
        assertEquals(4, picks.size)
        // Les couples (jour, rang) et (jour + 1, rang - 1) ne se rejoignent jamais : la graine est une chaîne, pas une somme.
        val seen = HashSet<Long>()
        for (company in 1L..20L) for (day in 0L until 30L) for (ordinal in 0 until 48) {
            val slot = IncidentSlot(Instant.EPOCH, LocalDate.ofEpochDay(20_000 + day), ordinal)
            assertTrue(seen.add(draw.roll(company, slot)!!.factoryPick), "collision entre deux créneaux : $company $day $ordinal")
        }
    }

    @Test
    @DisplayName("0 % de chance : jamais d'incident ; 100 % : toujours")
    fun chanceBounds() {
        val never = IncidentDraw(definitions(chance = 0.0))
        val always = IncidentDraw(definitions(chance = 100.0))
        for (ordinal in 0 until 48) {
            val slot = IncidentSlot(Instant.EPOCH, LocalDate.parse("2026-11-05"), ordinal)
            assertNull(never.roll(3, slot))
            assertTrue(always.roll(3, slot) != null)
        }
    }

    @Test
    @DisplayName("Sur de nombreux créneaux, la part qui tire un incident suit chancePourcent de incidents.json")
    fun frequencyFollowsTheDefinitions() {
        val draw = IncidentDraw(repo)
        var hits = 0
        var total = 0
        for (company in 1L..50L) for (day in 0L until 20L) for (ordinal in 0 until 48) {
            total++
            if (draw.roll(company, IncidentSlot(Instant.EPOCH, LocalDate.ofEpochDay(20_000 + day), ordinal)) != null) hits++
        }
        val share = hits * 100.0 / total
        assertEquals(repo.frequency.chancePercent, share, 1.5, "$hits incidents sur $total créneaux")
    }

    @Test
    @DisplayName("Sur de nombreux tirages, la part de chaque sorte suit son poids, et toutes les sortes de incidents.json sont tirables")
    fun kindsFollowTheirWeights() {
        val draw = IncidentDraw(definitions(chance = 100.0))
        val counts = HashMap<String, Int>()
        var total = 0
        for (company in 1L..100L) for (day in 0L until 20L) for (ordinal in 0 until 48) {
            total++
            val kind = draw.roll(company, IncidentSlot(Instant.EPOCH, LocalDate.ofEpochDay(20_000 + day), ordinal))!!.kind.id
            counts.merge(kind, 1, Int::plus)
        }
        val weights = KINDS.sumOf { it.third }
        for ((id, _, weight) in KINDS) {
            assertEquals(weight * 100.0 / weights, counts.getValue(id) * 100.0 / total, 1.0, "$id : ${counts[id]} sur $total")
        }
        // Les sortes de définitions/ elles-mêmes sont toutes tirables.
        val real = IncidentDraw(
            repo.copy(frequency = repo.frequency.copy(chancePercent = 100.0)),
        )
        val drawn = (0 until 2000).mapNotNull { real.roll(it.toLong() + 1, IncidentSlot(Instant.EPOCH, LocalDate.parse("2026-11-05"), 5))?.kind?.id }.toSet()
        assertEquals(repo.kinds.map { it.id }.toSet(), drawn)
    }

    @Test
    @DisplayName("Le choix de l'usine ne dépend que du nombre brut du tirage et de la liste donnée ; sans usine éligible, rien n'est touché")
    fun factoryPick() {
        val draw = IncidentDraw(definitions(chance = 100.0))
        val roll = draw.roll(1, draw.slotAt(at("2026-11-05T12:00:00Z")))!!
        val eligible = listOf("a", "b", "c")
        assertEquals(draw.factoryFor(roll, eligible), draw.factoryFor(roll, eligible))
        assertTrue(draw.factoryFor(roll, eligible) in eligible)
        assertNull(draw.factoryFor(roll, emptyList()))
        assertEquals("seule", draw.factoryFor(roll, listOf("seule")))
        // Un nombre négatif (64 bits) tombe aussi dans la liste.
        assertEquals("c", draw.factoryFor(roll.copy(factoryPick = -1L), eligible))
        assertEquals("a", draw.factoryFor(roll.copy(factoryPick = 3L), eligible))
        // Toutes les usines sont atteignables.
        val reached = (0L until 30L).map { draw.factoryFor(roll.copy(factoryPick = it), eligible) }.toSet()
        assertEquals(eligible.toSet(), reached)
    }

    private companion object {
        val KINDS = listOf(Triple("incendie", 0.5, 1), Triple("panne", 0.6, 2), Triple("courroie", 0.7, 2))

        /** Les sortes drawnes aux douze premiers créneaux de l'entreprise 1 le 5 novembre 2026, à 100 % de chance et avec [KINDS]. */
        val FROZEN_KINDS = listOf("incendie", "incendie", "courroie", "incendie", "panne", "incendie", "courroie", "courroie", "panne", "courroie", "panne", "courroie")
        val FROZEN_PICKS = listOf(
            -8830154809978174783L, -8799623528897929001L, 899595400092433556L, -2998750310653988988L, 8530772654825019804L, 600604227903522133L,
            2539040277739983655L, 7026743991912483518L, -3105269896501274151L, 2282664604371021639L, 8370022361686578547L, 222036653061135109L,
        )
    }
}
