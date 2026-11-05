package com.silooo.core.discord

import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * « Crée ton vocal » : un vocal par membre qui entre dans le vocal d'accueil, le même au second passage, supprimé
 * quand il se vide, et le nettoyage au démarrage.
 */
class VoiceRoomTest {
    /** Un serveur et un temps à part pour chaque test ; [withHub] faux : le plan n'a pas le vocal d'accueil. */
    private inner class Setup(withHub: Boolean = true) {
        val fake = FakeDiscordServer()
        val timer = FakeTimer()
        val category: String = runBlocking { fake.createCategory(CategorySpec("Vocaux", null)) }
        val other: String = runBlocking { fake.createCategory(CategorySpec("Autre", null)) }
        val hub: String = runBlocking { fake.createChannel(category, ChannelSpec("Crée ton vocal", ChannelKind.VOCAL, null, null, null, false)) }
        val context = BotContext(fake, ServerIds(emptyMap(), if (withHub) mapOf("cree-ton-vocal" to hub) else emptyMap(), mapOf("vocaux" to category)), timer, ServerPhase.ATTENTE)
        val feature = VoiceRoomFeature(context)

        /** Les vocaux créés par le bot, dans l'ordre. */
        val rooms: List<ExistingChannel> get() = fake.channels.filter { it.id != hub && it.categoryId == category && it.kind == ChannelKind.VOCAL }

        fun start() = runBlocking { feature.start() }

        fun move(user: String, from: String?, to: String?) = runBlocking { feature.handle(DiscordEvent.VoiceMoved(user, from, to)) }

        /** Le membre entre dans le vocal d'accueil ; Discord le déplace ensuite dans son vocal, ce que l'événement suivant dit. */
        fun enterHub(user: String) {
            val before = fake.moves.size
            move(user, null, hub)
            val moved = fake.moves.drop(before).lastOrNull() ?: return
            move(user, hub, moved.second)
        }
    }

    @Test
    @DisplayName("Un membre qui entre dans « Crée ton vocal » reçoit « Vocal de Nom » dans la catégorie Vocaux et y est déplacé")
    fun createsAndMoves() {
        val setup = Setup()
        setup.fake.names["42"] = "Ana"

        setup.move("42", null, setup.hub)

        val room = setup.rooms.single()
        assertEquals("Vocal de Ana", room.name)
        assertEquals(setup.category, room.categoryId)
        assertEquals(listOf("42" to room.id), setup.fake.moves)
        val spec = setup.fake.channelSpecs.getValue(room.id)
        assertEquals(ChannelKind.VOCAL, spec.kind)
        assertTrue(spec.memberViewers.isEmpty() && spec.viewers == null && !spec.locked)
        assertTrue(setup.fake.deleted.isEmpty())
    }

    @Test
    @DisplayName("Sans nom connu, le vocal s'appelle « Vocal » suivi du numéro d'ordre")
    fun numbersTheRoomsWithoutAName() {
        val setup = Setup()
        setup.fake.names["43"] = "Bob"
        setup.enterHub("42")
        setup.enterHub("43")
        setup.enterHub("44")
        assertEquals(listOf("Vocal 1", "Vocal de Bob", "Vocal 3"), setup.rooms.map { it.name })
    }

    @Test
    @DisplayName("Un nom trop long est coupé à 100 caractères")
    fun longNamesAreCut() {
        val setup = Setup()
        setup.fake.names["42"] = "z".repeat(150)
        setup.move("42", null, setup.hub)
        assertEquals(100, setup.rooms.single().name.length)
        assertTrue(setup.rooms.single().name.startsWith("Vocal de zzz"))
    }

    @Test
    @DisplayName("Au second passage, le membre est déplacé dans son vocal existant, sans en créer un autre")
    fun secondVisitGoesToTheSameRoom() {
        val setup = Setup()
        setup.fake.names["42"] = "Ana"
        setup.enterHub("42")
        val room = setup.rooms.single().id
        setup.move("43", "9001", room)
        // Ana part ailleurs ; Bob reste dans le vocal, qui ne se vide pas.
        setup.move("42", room, "9001")
        assertTrue(setup.fake.deleted.isEmpty())

        setup.move("42", "9001", setup.hub)

        assertEquals(1, setup.rooms.size, "pas de second vocal")
        assertEquals("42" to room, setup.fake.moves.last())
    }

    @Test
    @DisplayName("Un vocal créé est supprimé quand son dernier occupant part ; pas avant")
    fun deletedWhenEmpty() {
        val setup = Setup()
        setup.enterHub("42")
        val room = setup.rooms.single().id
        setup.move("43", "9001", room)

        setup.move("42", room, null)
        assertTrue(setup.fake.deleted.isEmpty(), "il reste un membre")

        setup.move("43", room, "9001")
        assertEquals(listOf(room), setup.fake.deleted)
        assertTrue(setup.rooms.isEmpty())
    }

    @Test
    @DisplayName("Le vocal d'accueil et les vocaux du plan ne sont jamais supprimés")
    fun theHubStays() {
        val setup = Setup()
        setup.move("42", setup.hub, null)
        setup.move("42", null, "9001")
        setup.move("42", "9001", null)
        assertTrue(setup.fake.deleted.isEmpty())
    }

    @Test
    @DisplayName("Un membre qui quitte son vocal pour « Crée ton vocal » en reçoit un neuf quand l'ancien s'est vidé")
    fun leavingEmptiesThenRecreates() {
        val setup = Setup()
        setup.enterHub("42")
        val first = setup.rooms.single().id

        setup.move("42", first, setup.hub)

        assertEquals(listOf(first), setup.fake.deleted)
        val second = setup.rooms.single()
        assertTrue(second.id != first)
        assertEquals("42" to second.id, setup.fake.moves.last())
    }

    @Test
    @DisplayName("Si Discord refuse le déplacement, le vocal reste, puis il est supprimé s'il est resté vide")
    fun refusedMoveLeavesTheRoomThenDropsIt() {
        val setup = Setup()
        setup.fake.notInVoice += "42"
        setup.move("42", null, setup.hub)

        val room = setup.rooms.single().id
        assertTrue(setup.fake.moves.isEmpty())
        assertTrue(setup.fake.deleted.isEmpty(), "le vocal reste")

        runBlocking { setup.timer.advance(VoiceRoomFeature.EMPTY_GRACE) }
        assertEquals(listOf(room), setup.fake.deleted)
    }

    @Test
    @DisplayName("Un vocal dont le déplacement a été refusé reste si quelqu'un y entre avant le délai")
    fun refusedMoveButSomeoneJoins() {
        val setup = Setup()
        setup.fake.notInVoice += "42"
        setup.move("42", null, setup.hub)
        val room = setup.rooms.single().id

        setup.move("42", null, room)
        runBlocking { setup.timer.advance(VoiceRoomFeature.EMPTY_GRACE.plus(Duration.ofSeconds(1))) }

        assertTrue(setup.fake.deleted.isEmpty())
        setup.move("42", room, null)
        assertEquals(listOf(room), setup.fake.deleted)
    }

    @Test
    @DisplayName("Si le vocal du membre a été supprimé à la main, le membre en reçoit un neuf")
    fun staleRoomIsReplaced() {
        val setup = Setup()
        setup.fake.names["42"] = "Ana"
        setup.enterHub("42")
        setup.enterHub("43")
        val room = setup.rooms.first().id
        setup.fake.channels.removeAll { it.id == room }

        setup.move("42", null, setup.hub)

        assertEquals(2, setup.rooms.size)
        assertEquals("Vocal de Ana", setup.rooms.last().name)
        assertEquals("42" to setup.rooms.last().id, setup.fake.moves.last())
    }

    @Test
    @DisplayName("Au démarrage, les vocaux créés par le bot et vides sont supprimés ; ceux qui ont du monde sont repris avec leur compte")
    fun cleanupAtStart() {
        val setup = Setup()
        val empty = runBlocking { setup.fake.createChannel(setup.category, ChannelSpec("Vocal de Ana", ChannelKind.VOCAL, null, null, null, false)) }
        val busy = runBlocking { setup.fake.createChannel(setup.category, ChannelSpec("Vocal de Bob", ChannelKind.VOCAL, null, null, null, false)) }
        val office = runBlocking { setup.fake.createChannel(setup.category, ChannelSpec("Bureau Acme", ChannelKind.VOCAL, null, null, null, false)) }
        val elsewhere = runBlocking { setup.fake.createChannel(setup.other, ChannelSpec("Vocal de Zoé", ChannelKind.VOCAL, null, null, null, false)) }
        setup.fake.voiceCounts[busy] = 2

        setup.start()

        assertEquals(listOf(empty), setup.fake.deleted)
        assertTrue(setup.fake.channels.any { it.id == setup.hub }, "le vocal d'accueil reste")
        assertTrue(setup.fake.channels.any { it.id == office }, "un vocal qui n'est pas de lui reste")
        assertTrue(setup.fake.channels.any { it.id == elsewhere }, "un vocal d'une autre catégorie reste")

        setup.move("50", busy, null)
        assertTrue(setup.fake.deleted.size == 1, "un occupant sur deux est parti")
        setup.move("51", busy, null)
        assertEquals(listOf(empty, busy), setup.fake.deleted)
    }

    @Test
    @DisplayName("Sans vocal d'accueil ou sans catégorie dans le plan, rien ne se passe")
    fun nothingWithoutThePlan() {
        val noHub = Setup(withHub = false)
        noHub.move("42", null, noHub.hub)
        assertTrue(noHub.rooms.isEmpty())
        assertTrue(noHub.fake.moves.isEmpty())

        val fake = FakeDiscordServer()
        val context = BotContext(fake, ServerIds.NONE, FakeTimer(), ServerPhase.ATTENTE)
        val feature = VoiceRoomFeature(context)
        runBlocking {
            feature.start()
            feature.handle(DiscordEvent.VoiceMoved("42", null, "1"))
        }
        assertTrue(fake.channels.isEmpty() && fake.deleted.isEmpty() && fake.moves.isEmpty())

        val noCategory = FakeDiscordServer()
        val withoutCategory = VoiceRoomFeature(BotContext(noCategory, ServerIds(emptyMap(), mapOf("cree-ton-vocal" to "5")), FakeTimer(), ServerPhase.ATTENTE))
        runBlocking { withoutCategory.handle(DiscordEvent.VoiceMoved("42", null, "5")) }
        assertTrue(noCategory.channels.isEmpty() && noCategory.moves.isEmpty())
    }
}
