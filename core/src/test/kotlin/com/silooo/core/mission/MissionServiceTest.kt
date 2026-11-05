package com.silooo.core.mission

import com.silooo.core.TestDefinitions
import com.silooo.core.api.Mission
import com.silooo.core.db.RecordingConnection
import com.silooo.core.db.Xuid
import com.silooo.core.definitions.Definitions
import com.silooo.core.definitions.DefinitionsLoader
import com.silooo.core.definitions.MissionEvent
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.InMemorySessionStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [MissionService] avec le faux [InMemoryMissionStore] : la chaîne de missions, l'avancement, la fin d'une mission
 * (Lingots et événement) et ce qu'elle ne refait jamais. Le catalogue du test est écrit ici (trois encaissements pour la
 * première mission, un pour la seconde) : les vraies définitions n'ont qu'une mission d'un seul encaissement. Les
 * récompenses sont des chemins de economie.json, jamais des littéraux. Le SQL est dans `MissionSqlTest`.
 */
class MissionServiceTest {
    private val kr = Xuid.parse("2535428000000001")!!
    private val other = Xuid.parse("2535428000000002")!!

    /** Le chemin d'un entier de economie.json qui n'est pas la mission de bienvenue : de quoi distinguer les deux récompenses. */
    private val secondRewardPath = "prestige.departDeCourse.niveau"

    private val chain = """
        {"version": 1, "missions": [
          {"id": "premiere", "evenement": "collect", "cible": 3, "recompense": "lingotsHorsPrestige.missionBienvenue"},
          {"id": "seconde", "evenement": "collect", "cible": 1, "recompense": "$secondRewardPath"}
        ]}
    """.trimIndent()

    private fun definitionsWith(missions: String, economy: String = TestDefinitions.text(DefinitionsLoader.ECONOMY_FILE)): Definitions =
        DefinitionsLoader.parse(
            economy,
            TestDefinitions.text(DefinitionsLoader.FACTORIES_FILE),
            TestDefinitions.text(DefinitionsLoader.SHOP_FILE),
            TestDefinitions.text(DefinitionsLoader.WORLD_FILE),
            missions,
        )

    private class Bench(val definitions: Definitions) {
        val sessions = InMemorySessionStore()
        val store = InMemoryMissionStore(sessions)
        val service = MissionService(definitions, store)
        val connection = RecordingConnection().connection

        fun player(xuid: Xuid) {
            sessions.enterPlayer(connection, xuid, "Joueur")
        }

        fun record(xuid: Xuid, times: Int = 1) = repeat(times) { service.record(connection, xuid, MissionEvent.COLLECT) }

        fun state(xuid: Xuid) = checkNotNull(service.stateOf(connection, xuid)) { "joueur inconnu" }

        fun lingots(xuid: Xuid): Long = sessions.balances.getValue(xuid).lingots
    }

    private fun bench(missions: String = chain, economy: String = TestDefinitions.text(DefinitionsLoader.ECONOMY_FILE)): Bench =
        Bench(definitionsWith(missions, economy)).also { it.player(kr) }

    @Test
    @DisplayName("Un joueur inconnu n'a pas d'état de missions")
    fun unknownPlayerHasNoState() {
        val bench = bench()
        assertNull(bench.service.stateOf(bench.connection, other))
    }

    @Test
    @DisplayName("Au départ : la première mission du catalogue, à zéro, avec sa cible et la récompense résolue dans economie.json")
    fun startsOnTheFirstMission() {
        val bench = bench()
        val state = bench.state(kr)

        assertEquals(Mission("premiere", 0, 3, bench.definitions.economy.lingotsOutsidePrestige.welcomeMission), state.current)
        assertEquals(emptyList<String>(), state.completed)
        assertTrue(bench.store.rows.isEmpty(), "lire l'état n'écrit rien")
    }

    @Test
    @DisplayName("Chaque encaissement avance la mission d'un cran ; avant la cible, ni Lingots ni événement")
    fun progressCountsOneByOne() {
        val bench = bench()

        bench.record(kr)
        assertEquals(Mission("premiere", 1, 3, bench.state(kr).current!!.rewardLingots), bench.state(kr).current)
        bench.record(kr)
        assertEquals(2, bench.state(kr).current!!.progress)

        assertTrue(bench.store.credits.isEmpty())
        assertTrue(bench.store.published.isEmpty())
        assertEquals(0L, bench.lingots(kr))
    }

    @Test
    @DisplayName("À la cible : la mission se termine, verse ses Lingots (motif mission, référence = la mission) et publie un événement ; la suivante prend le relais")
    fun reachingTheTargetCompletesTheMission() {
        val bench = bench()
        val reward = bench.definitions.economy.lingotsOutsidePrestige.welcomeMission

        bench.record(kr, times = 3)

        assertEquals(listOf(InMemoryMissionStore.Credit(kr, reward, "mission", "premiere")), bench.store.credits)
        assertEquals(reward, bench.lingots(kr))
        val event = bench.store.published.single()
        assertEquals(GameEventKind.ANNONCE, event.kind)
        assertEquals(kr, event.xuid)
        assertEquals(
            Json.parseToJsonElement("""{"action":"mission","missionId":"premiere","rewardLingots":$reward}""").jsonObject,
            event.payload,
        )
        val state = bench.state(kr)
        assertEquals(listOf("premiere"), state.completed)
        assertEquals(Mission("seconde", 0, 1, bench.definitions.economy.prestige.courseStart.level.toLong()), state.current)
    }

    @Test
    @DisplayName("Les missions s'enchaînent dans l'ordre du catalogue ; une fois tout fait, plus de mission en cours et rien ne se rejoue")
    fun chainEndsAndNeverReplays() {
        val bench = bench()
        val secondReward = bench.definitions.economy.prestige.courseStart.level.toLong()
        val firstReward = bench.definitions.economy.lingotsOutsidePrestige.welcomeMission

        bench.record(kr, times = 4)

        val state = bench.state(kr)
        assertNull(state.current)
        assertEquals(listOf("premiere", "seconde"), state.completed)
        assertEquals(firstReward + secondReward, bench.lingots(kr))
        assertEquals(listOf("premiere", "seconde"), bench.store.credits.map { it.refId })

        // Après la fin : autant d'encaissements qu'on veut, plus un Lingot ni un événement.
        bench.record(kr, times = 10)
        assertEquals(2, bench.store.credits.size)
        assertEquals(2, bench.store.published.size)
        assertEquals(firstReward + secondReward, bench.lingots(kr))
        assertEquals(MissionRow(3, completed = true), bench.store.rows.getValue(kr to "premiere"), "la progression ne dépasse pas la cible")
    }

    @Test
    @DisplayName("Seule la mission en cours avance : la seconde n'est pas commencée tant que la première n'est pas faite")
    fun onlyTheCurrentMissionAdvances() {
        val bench = bench()

        bench.record(kr, times = 2)

        assertEquals(setOf(kr to "premiere"), bench.store.rows.keys)
    }

    @Test
    @DisplayName("Chaque joueur a ses missions : l'avancement de l'un ne touche pas l'autre")
    fun missionsArePerPlayer() {
        val bench = bench()
        bench.player(other)

        bench.record(kr, times = 3)
        bench.record(other, times = 1)

        assertEquals(listOf("premiere"), bench.state(kr).completed)
        assertEquals(Mission("premiere", 1, 3, bench.state(other).current!!.rewardLingots), bench.state(other).current)
        assertEquals(emptyList<String>(), bench.state(other).completed)
        assertEquals(0L, bench.lingots(other))
    }

    @Test
    @DisplayName("Une récompense à zéro Lingot termine la mission et publie l'événement, sans écrire de ligne de journal")
    fun zeroRewardWritesNoCredit() {
        val economy = TestDefinitions.text(DefinitionsLoader.ECONOMY_FILE)
        val root = Json.parseToJsonElement(economy).jsonObject
        val outside = root.getValue("lingotsHorsPrestige").jsonObject
        val free = JsonObject(root + ("lingotsHorsPrestige" to JsonObject(outside + ("missionBienvenue" to JsonPrimitive(0))))).toString()
        val bench = bench(economy = free)

        bench.record(kr, times = 3)

        assertTrue(bench.store.credits.isEmpty(), "zéro Lingot : le journal ne reçoit rien")
        assertEquals("0", bench.store.published.single().payload.getValue("rewardLingots").jsonPrimitive.content)
        assertEquals(listOf("premiere"), bench.state(kr).completed)
    }

    @Test
    @DisplayName("Les lignes d'une mission qui n'est plus au catalogue sont ignorées ; un catalogue réordonné reprend à la première mission pas faite")
    fun oldRowsAreIgnoredAndCatalogueOrderRules() {
        val bench = bench()
        bench.store.rows[kr to "retiree"] = MissionRow(9, completed = true)
        bench.store.rows[kr to "seconde"] = MissionRow(1, completed = true)

        val state = bench.state(kr)

        assertEquals(listOf("seconde"), state.completed, "« retiree » n'est pas au catalogue")
        assertEquals("premiere", state.current!!.id, "la première du catalogue est encore à faire")
    }
}
