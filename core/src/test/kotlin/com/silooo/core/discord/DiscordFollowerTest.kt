package com.silooo.core.discord

import com.silooo.core.Dependencies
import com.silooo.core.TestDefinitions
import com.silooo.core.api.GameEvent
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.db.Xuid
import com.silooo.core.events.InMemoryEventStore
import com.silooo.core.siloooModule
import io.ktor.server.testing.testApplication
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le suiveur d'événements sans base ni réseau : un faux magasin d'événements, un faux curseur, un faux envoyeur.
 * Ce que le curseur fait sur MariaDB est dans `DiscordCursorSqlTest`.
 */
class DiscordFollowerTest {
    private val kr = Xuid.parse("2535428000000001")!!

    private fun prestige(id: Long, name: String = "Les Forges") = GameEvent(
        id, null, "PRESTIGE_ENTREPRISE",
        buildJsonObject { put("companyId", id); put("companyName", name); put("lingots", 10 + id); put("prestigeCount", 1) },
    )

    private fun boost(id: Long) = GameEvent(id, null, "BOOST_COLLECTIF", buildJsonObject { put("percent", 25); put("minutes", 60); put("buyerName", "Zoé") })

    private fun voteParty(id: Long) = GameEvent(id, null, "ANNONCE", buildJsonObject { put("action", "voteParty"); put("votes", 500); put("lingots", 5) })

    private fun noPower(id: Long, xuid: Xuid? = kr) = GameEvent(
        id, xuid?.text, "ANNONCE",
        buildJsonObject { put("action", "sansCourant"); put("playerName", "Zoé"); put("factoryName", "Verrerie") },
    )

    private fun other(id: Long, kind: String = "GEMMES_RECUES") = GameEvent(id, kr.text, kind, buildJsonObject { put("n", id) })

    private fun memberPrestige(id: Long) = GameEvent(id, kr.text, "ANNONCE", buildJsonObject { put("action", "prestige"); put("lingots", 5); put("lingotsTotal", 10) })

    private class Rig(
        events: List<GameEvent>,
        cursor: Long?,
        latest: Long = 0,
        val sender: FakeSender = FakeSender(),
        online: () -> Set<Xuid> = { emptySet() },
    ) {
        val database = PassThroughDatabase()
        val store = InMemoryEventStore(events)
        val cursors = FakeCursorStore(cursor, latest)
        val follower = DiscordFollower(database, store, cursors, sender, online)

        fun poll(): Int = runBlocking { follower.poll() }
    }

    // --- éteint sans adresse

    @Test
    @DisplayName("Sans adresse de webhook, le suiveur est éteint : il ne lit rien, n'écrit rien, n'ouvre aucune transaction")
    fun offWithoutAnAddress() {
        for (sender in listOf<DiscordSender>(FakeSender(enabled = false), DiscordOff)) {
            val database = PassThroughDatabase()
            val store = InMemoryEventStore(listOf(prestige(1)))
            val cursors = FakeCursorStore(cursor = 0)
            val follower = DiscordFollower(database, store, cursors, sender)

            assertFalse(follower.enabled)
            assertEquals(0, runBlocking { follower.poll() })

            assertEquals(0, database.transactions)
            assertNull(store.lastAfter, "aucune lecture de game_events")
            assertEquals(emptyList<Long>(), cursors.writes)
        }
    }

    // --- le premier passage

    @Test
    @DisplayName("Premier passage (aucune ligne de curseur) : le curseur part du dernier événement et rien d'ancien n'est envoyé")
    fun firstPollDoesNotReplay() {
        val rig = Rig(events = listOf(prestige(1), boost(2), voteParty(3), prestige(4), prestige(5), prestige(6), voteParty(7)), cursor = null, latest = 5)

        assertEquals(0, rig.poll())

        assertEquals(emptyList<String>(), rig.sender.sent)
        assertEquals(listOf(5L), rig.cursors.writes)
        assertNull(rig.store.lastAfter, "le premier passage ne lit pas les événements : il pose seulement le curseur")

        assertEquals(2, rig.poll(), "au passage suivant, ce qui est arrivé depuis part")
        assertEquals(5L, rig.store.lastAfter, "la lecture a repris après le curseur posé au premier passage")
        assertEquals(7L, rig.cursors.cursor)
    }

    @Test
    @DisplayName("Premier passage sur une table vide : le curseur part de 0 et le premier événement à venir est annoncé")
    fun firstPollOnAnEmptyTable() {
        val rig = Rig(events = listOf(prestige(1)), cursor = null, latest = 0)

        assertEquals(0, rig.poll())
        assertEquals(0L, rig.cursors.cursor, "la ligne existe maintenant")

        assertEquals(1, rig.poll())
        assertEquals(1L, rig.cursors.cursor)
    }

    // --- le filtre et le curseur

    @Test
    @DisplayName("Seuls PRESTIGE_ENTREPRISE, BOOST_COLLECTIF et l'ANNONCE voteParty partent ; les autres sortes sont lues et passées, le curseur va au bout")
    fun onlyTheRightKindsAreSent() {
        val events = listOf(
            other(1),
            other(2, "INCIDENT"),
            memberPrestige(3),
            prestige(4, "Les Forges"),
            other(5, "INVITATION"),
            boost(6),
            other(7, "ANNONCE"),
            voteParty(8),
            other(9),
        )
        val rig = Rig(events, cursor = 0)

        assertEquals(3, rig.poll())

        assertEquals(
            listOf(
                "Les Forges a fait son 1er prestige et gagne 14 Lingots.",
                "Zoé offre un boost collectif : production +25 % pour tout le monde pendant une heure.",
                "Vote party : le serveur a atteint 500 votes, chaque joueur présent reçoit 5 Lingots.",
            ),
            rig.sender.sent,
        )
        assertEquals(9L, rig.cursors.cursor, "le curseur passe aussi les événements qui ne regardent pas Discord")
    }

    @Test
    @DisplayName("Le curseur s'écrit après chaque message envoyé et à la fin de la lecture ; un passage sans rien de neuf n'écrit rien")
    fun theCursorAdvancesAndNothingIsReplayed() {
        val rig = Rig(events = listOf(prestige(1), other(2), boost(3), other(4)), cursor = 0)

        assertEquals(2, rig.poll())
        assertEquals(listOf(1L, 3L, 4L), rig.cursors.writes)

        assertEquals(0, rig.poll())
        assertEquals(2, rig.sender.sent.size, "rien n'est renvoyé")
        assertEquals(listOf(1L, 3L, 4L), rig.cursors.writes, "et le curseur ne bouge plus")
        assertEquals(4L, rig.store.lastAfter)
    }

    @Test
    @DisplayName("Un curseur déjà avancé (redémarrage du service) : seuls les événements après lui sont lus")
    fun aRestartResumesAfterTheCursor() {
        val rig = Rig(events = listOf(prestige(1), prestige(2), prestige(3)), cursor = 2)

        assertEquals(1, rig.poll())

        assertEquals(1, rig.sender.sent.size)
        assertTrue(rig.sender.sent.single().contains("13 Lingots"), "c'est l'événement 3 (10 + 3 Lingots)")
        assertEquals(3L, rig.cursors.cursor)
    }

    // --- les pannes

    @Test
    @DisplayName("Un message que Discord refuse est perdu et noté ; le curseur avance quand même, les suivants partent")
    fun aFailedSendDoesNotBlockTheFollow() {
        val sender = FakeSender(outcome = { !it.contains("Les Forges") })
        val rig = Rig(events = listOf(prestige(1, "Les Forges"), prestige(2, "Les Mines")), cursor = 0, sender = sender)

        LogCapture().use { logs ->
            assertEquals(1, rig.poll(), "un message pris sur deux")
            assertTrue(logs.lines.any { it.contains("n'a pas pu être annoncé") }, logs.lines.toString())
        }

        assertEquals(2, sender.sent.size)
        assertEquals(2L, rig.cursors.cursor)
        assertEquals(0, rig.poll(), "le message perdu n'est pas rejoué")
        assertEquals(2, sender.sent.size)
    }

    @Test
    @DisplayName("Un événement cassé (champ manquant) est noté et sauté : le curseur avance, les autres messages partent")
    fun aBrokenEventIsSkipped() {
        val broken = GameEvent(1, null, "PRESTIGE_ENTREPRISE", buildJsonObject { put("lingots", 10) })
        val rig = Rig(events = listOf(broken, prestige(2)), cursor = 0)

        LogCapture().use { logs ->
            assertEquals(1, rig.poll())
            assertTrue(logs.lines.any { it.contains("n'a pas de message") && it.contains("companyName") }, logs.lines.toString())
        }

        assertEquals(2L, rig.cursors.cursor)
    }

    // --- la mesure

    @Test
    @DisplayName("Pas de rafale : au plus 20 messages par passage, le reste attend le passage suivant, curseur gardé")
    fun noMoreThanTwentyMessagesPerPoll() {
        val rig = Rig(events = (1L..25L).map { prestige(it) }, cursor = 0)

        assertEquals(DiscordFollower.MAX_MESSAGES, rig.poll())
        assertEquals(20, rig.sender.sent.size)
        assertEquals(20L, rig.cursors.cursor, "le curseur s'arrête sur le dernier événement annoncé")

        assertEquals(5, rig.poll())
        assertEquals(25, rig.sender.sent.size)
        assertEquals(25L, rig.cursors.cursor)
        assertEquals(25, rig.sender.sent.toSet().size, "chaque événement a été annoncé une fois")
    }

    @Test
    @DisplayName("Un long retard d'événements sans intérêt est lu page après page dans un seul passage")
    fun aLongBacklogIsReadPageByPage() {
        val filler = (1L..1200L).map { other(it) }
        val rig = Rig(events = filler + prestige(1201), cursor = 0)

        assertEquals(1, rig.poll())

        assertEquals(1201L, rig.cursors.cursor)
        assertEquals(1, rig.sender.sent.size)
    }

    // --- le courant qui manque

    @Test
    @DisplayName("sansCourant : le message part si le joueur visé est connecté quand le suiveur le lit, sinon l'alerte est passée")
    fun noPowerOnlyForAPresentPlayer() {
        val away = Rig(events = listOf(noPower(1)), cursor = 0, online = { emptySet() })
        assertEquals(0, away.poll())
        assertEquals(1L, away.cursors.cursor, "l'alerte d'un joueur absent est passée, pas gardée")

        val present = Rig(events = listOf(noPower(1)), cursor = 0, online = { setOf(kr) })
        assertEquals(1, present.poll())
        assertEquals(listOf("L'usine Verrerie de Zoé est à l'arrêt faute de courant."), present.sender.sent)

        val everyone = Rig(events = listOf(noPower(1, xuid = null)), cursor = 0, online = { setOf(kr) })
        assertEquals(0, everyone.poll(), "sans destinataire, rien")
    }

    // --- la tâche de fond

    private suspend fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (!condition() && System.nanoTime() < deadline) delay(20)
        assertTrue(condition(), "le suiveur n'a pas tourné dans les temps")
    }

    private fun dependencies(rig: Rig, poll: Duration?) = Dependencies(
        database = rig.database,
        definitions = TestDefinitions.loaded,
        apiKey = "cle-de-test",
        discord = rig.follower,
        discordPoll = poll,
    )

    @Test
    @DisplayName("Le module lance le suiveur quand Dependencies.discord et discordPoll sont posés : un événement déjà là est annoncé, une seule fois")
    fun theModuleRunsTheFollower() {
        val rig = Rig(events = listOf(prestige(1)), cursor = 0)
        testApplication {
            application { siloooModule(dependencies(rig, poll = Duration.ofMillis(20))) }
            startApplication()
            waitUntil { rig.sender.sent.size == 1 }
        }
        assertEquals(1L, rig.cursors.cursor)
        assertEquals(1, rig.sender.sent.size, "malgré les passages qui se répètent, un événement part une fois")
    }

    @Test
    @DisplayName("Sans adresse (suiveur éteint), le module ne lance rien et le dit en une ligne de journal")
    fun theModuleStaysQuietWhenOff() {
        val rig = Rig(events = listOf(prestige(1)), cursor = 0, sender = FakeSender(enabled = false))
        LogCapture("io.ktor.test").use { logs ->
            testApplication {
                application { siloooModule(dependencies(rig, poll = Duration.ofMillis(20))) }
                startApplication()
                delay(200)
            }
            assertEquals(1, logs.lines.count { it.contains("SILOOO_DISCORD_WEBHOOK") }, logs.lines.toString())
        }
        assertEquals(0, rig.database.transactions)
        assertEquals(emptyList<String>(), rig.sender.sent)
    }

    @Test
    @DisplayName("Sans Dependencies.discordPoll, le suiveur n'est pas lancé même s'il existe")
    fun theModuleNeedsThePollInterval() {
        val rig = Rig(events = listOf(prestige(1)), cursor = 0)
        testApplication {
            application { siloooModule(dependencies(rig, poll = null)) }
            startApplication()
            delay(200)
        }
        assertEquals(0, rig.database.transactions)
    }
}
