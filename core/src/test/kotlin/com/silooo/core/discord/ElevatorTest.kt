package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le monte-charge : le message unique posté puis modifié sur place, l'appui d'un membre (un étage de plus, l'attente
 * calculée sur les définitions), le staff qui fait redescendre, le record et le Panthéon, l'image de l'étage, l'état qui
 * survit au redémarrage. La base est un faux en mémoire ; le SQL se teste sur MariaDB (`GameSqlTest`).
 */
class ElevatorTest {
    private val zone = ZoneId.of("Europe/Paris")

    /** 17 h 10 UTC : 18 h 10 à Paris, le 5 novembre 2026. */
    private val at = Instant.parse("2026-11-05T17:10:00Z")

    private val old = Instant.parse("2026-10-01T10:00:00Z")

    private val ground = "Le monte-charge est au rez-de-chaussée. Appuie pour monter d'un étage."

    /** Un serveur, un temps et des bases à part pour chaque test. */
    private inner class Setup(
        withChannel: Boolean = true,
        val rules: ElevatorRules = ElevatorRules(5),
        images: ((Long) -> ImageFile?)? = { null },
        wrap: (FakeDiscordServer) -> DiscordServer = { it },
    ) {
        val fake = FakeDiscordServer()
        val timer = FakeTimer(at)
        val store = InMemoryGameStore()
        val messages = InMemoryBotMessageStore()
        val staff: String = runBlocking { fake.createRole(RoleSpec("staff", null, false, false)) }
        val moderation: String = runBlocking { fake.createRole(RoleSpec("moderation", null, false, false)) }
        private val category: String = runBlocking { fake.createCategory(CategorySpec("Animation", null)) }

        private fun channel(name: String): String =
            runBlocking { fake.createChannel(category, ChannelSpec(name, ChannelKind.TEXTE, null, emptySet(), null, false)) }

        val elevator: String = channel("monte-charge")
        val pantheon: String = channel("pantheon")
        val journalChannel: String = channel("journal-du-bot")
        private val ids = ServerIds(
            mapOf("staff" to staff, "moderation" to moderation),
            buildMap {
                if (withChannel) put("monte-charge", elevator)
                put("pantheon", pantheon)
                put("journal-du-bot", journalChannel)
            },
        )
        val context = BotContext(wrap(fake), ids, timer, ServerPhase.ATTENTE)
        val board = RecordsBoard(context, store, messages, zone)
        val feature = build(images)

        fun build(images: ((Long) -> ImageFile?)?): ElevatorFeature =
            if (images == null) {
                ElevatorFeature(context, rules, zone, store, messages, board, BotJournal(context))
            } else {
                ElevatorFeature(context, rules, zone, store, messages, board, BotJournal(context), images)
            }

        fun start(on: ElevatorFeature = feature) = runBlocking { on.start() }

        fun advance(duration: Duration) = runBlocking { timer.advance(duration) }

        fun seed(floor: Long, last: String?, best: GameRecord? = null) {
            store.states["monte-charge"] = GameState(floor, last)
            best?.let { store.bests["monte-charge"] = it }
        }

        val state: GameState get() = store.states["monte-charge"] ?: GameState.START
        val record: GameRecord? get() = store.bests["monte-charge"]
        val ref: BotMessageRef get() = checkNotNull(messages.refs["monte-charge"]) { "le message n'a pas été posté" }

        /** Le message du salon tel qu'il est maintenant : la dernière modification, ou le message posté. */
        val shown: BotMessage
            get() = fake.edits.lastOrNull { it.channelId == elevator }?.message
                ?: fake.posted.last { it.channelId == elevator }.message
        val elevatorPosts: List<FakeDiscordServer.Posted> get() = fake.posted.filter { it.channelId == elevator }
        val elevatorEdits: List<FakeDiscordServer.Posted> get() = fake.edits.filter { it.channelId == elevator }
        val boardPosts: List<FakeDiscordServer.Posted> get() = fake.posted.filter { it.channelId == pantheon }
        val boardEdits: List<FakeDiscordServer.Posted> get() = fake.edits.filter { it.channelId == pantheon }
        val journal: List<String> get() = fake.posted.filter { it.channelId == journalChannel }.map { it.message.text }

        fun member(id: String, vararg roles: String) = DiscordUser(id, "Membre " + id, roleIds = roles.toSet())

        /** [user] appuie sur le bouton du message [messageId] (le message posté par défaut) ; rend la réponse. */
        fun press(user: DiscordUser, buttonId: String = "monte-charge-plus", messageId: String = ref.messageId): FakeReply {
            val reply = FakeReply()
            runBlocking { feature.handle(DiscordEvent.ButtonPressed(buttonId, user, elevator, messageId, reply)) }
            return reply
        }
    }

    @Test
    @DisplayName("Le jeu s'appelle monte-charge")
    fun hasItsName() {
        assertEquals("monte-charge", Setup().feature.name)
    }

    @Test
    @DisplayName("Au départ, le message dit le rez-de-chaussée et porte le bouton « +1 étage » ; sa clé est notée")
    fun startsAtTheGroundFloor() {
        val setup = Setup()
        setup.start()

        val post = setup.elevatorPosts.single()
        assertEquals(ground, post.message.text)
        val button = post.message.buttons.single()
        assertEquals("monte-charge-plus", button.id)
        assertEquals("+1 étage", button.label)
        assertEquals(ButtonStyle.PRIMARY, button.style)
        assertTrue(post.message.card)
        assertNull(post.message.image)
        assertTrue(post.message.mentionUserIds.isEmpty() && post.message.mentionRoleIds.isEmpty(), "aucune mention ne part")
        assertEquals(BotMessageRef(setup.elevator, post.messageId), setup.messages.refs["monte-charge"])
    }

    @Test
    @DisplayName("Au démarrage, le tableau des records est posé aussi")
    fun startShowsTheBoard() {
        val setup = Setup()
        setup.start()
        assertEquals("Les records des petits jeux\nAucun record pour l'instant.", setup.boardPosts.single().message.text)
    }

    @Test
    @DisplayName("Au démarrage, l'étage, le dernier à avoir appuyé et le record viennent de la base")
    fun startsFromTheDatabase() {
        val setup = Setup()
        setup.seed(37, "7", GameRecord(120, "9", old))
        setup.start()

        assertEquals("Le monte-charge est à l'étage 37. Dernier à appuyer : <@7>. Record : étage 120, par <@9>.", setup.shown.text)
    }

    @Test
    @DisplayName("Un étage sans record connu n'en parle pas ; un rez-de-chaussée avec record le rappelle ; sans auteur connu, pas de « par »")
    fun textsAroundTheRecord() {
        val noRecord = Setup()
        noRecord.seed(3, "7")
        noRecord.start()
        assertEquals("Le monte-charge est à l'étage 3. Dernier à appuyer : <@7>.", noRecord.shown.text)

        val atGround = Setup()
        atGround.seed(0, null, GameRecord(120, "9", old))
        atGround.start()
        assertEquals(ground + " Record : étage 120, par <@9>.", atGround.shown.text)

        val anonymous = Setup()
        anonymous.seed(2, null, GameRecord(120, null, old))
        anonymous.start()
        assertEquals("Le monte-charge est à l'étage 2. Record : étage 120.", anonymous.shown.text)
    }

    @Test
    @DisplayName("Un second démarrage retrouve le message par sa clé et le modifie, sans le reposter")
    fun editsInPlaceOnRestart() {
        val setup = Setup()
        setup.start()
        setup.seed(5, "7")
        setup.start()

        assertEquals(1, setup.elevatorPosts.size)
        assertEquals(setup.elevatorPosts.single().messageId, setup.elevatorEdits.single().messageId)
        assertEquals("Le monte-charge est à l'étage 5. Dernier à appuyer : <@7>.", setup.shown.text)
    }

    @Test
    @DisplayName("Un message disparu est reposté au démarrage, et la nouvelle clé est notée")
    fun repostsAVanishedMessage() {
        val setup = Setup()
        setup.messages.refs["monte-charge"] = BotMessageRef(setup.elevator, "disparu")
        setup.start()

        val post = setup.elevatorPosts.single()
        assertEquals(BotMessageRef(setup.elevator, post.messageId), setup.messages.refs["monte-charge"])
        assertTrue(setup.elevatorEdits.isEmpty())
    }

    @Test
    @DisplayName("L'image de l'étage est jointe au message, au démarrage comme après un appui et après le staff")
    fun attachesTheFloorImage() {
        val setup = Setup(images = { floor -> ImageFile(floor.toString() + ".png", byteArrayOf(1, 2, 3)) })
        setup.seed(4, "7")
        setup.start()
        assertEquals("4.png", setup.shown.image?.name)

        setup.press(setup.member("8"))
        assertEquals("5.png", setup.shown.image?.name)

        setup.press(setup.member("3", setup.staff))
        assertEquals("0.png", setup.shown.image?.name)
    }

    @Test
    @DisplayName("Une image qui manque au classpath : le message part sans image")
    fun noImageOnTheClasspath() {
        val setup = Setup(images = null)
        setup.seed(987_654_321, "7")
        setup.start()

        assertNull(setup.shown.image)
        setup.press(setup.member("8"))
        assertNull(setup.shown.image)
        assertEquals("Le monte-charge est à l'étage 987654322. Dernier à appuyer : <@8>. Record : étage 987654322, par <@8>.", setup.shown.text)
    }

    @Test
    @DisplayName("Un appui monte d'un étage : l'état et le record sont écrits, le message est modifié, le Panthéon suit")
    fun aPressClimbs() {
        val setup = Setup()
        setup.start()
        val reply = setup.press(setup.member("7"))

        assertEquals(1, reply.acknowledged)
        assertTrue(reply.sent.isEmpty(), "le membre voit le message changer, rien d'autre")
        assertEquals(GameState(1, "7"), setup.state)
        assertEquals(at, setup.store.updatedAt["monte-charge"])
        assertEquals(GameRecord(1, "7", at), setup.record)
        val edit = setup.elevatorEdits.single()
        assertEquals(setup.ref.messageId, edit.messageId)
        assertEquals("Le monte-charge est à l'étage 1. Dernier à appuyer : <@7>. Record : étage 1, par <@7>.", edit.message.text)
        assertEquals("monte-charge-plus", edit.message.buttons.single().id)
        assertTrue(edit.message.mentionUserIds.isEmpty(), "personne n'est prévenu")
        assertTrue(setup.boardEdits.isEmpty(), "le tableau vient d'être écrit au démarrage : la mise à jour attend")
        setup.advance(RecordsBoard.REFRESH)
        assertEquals("Les records des petits jeux\nMonte-charge : étage 1, par <@7>, le 5 novembre 2026.", setup.boardEdits.single().message.text)
    }

    @Test
    @DisplayName("Plusieurs membres montent l'étage chacun leur tour")
    fun severalMembersClimb() {
        val setup = Setup()
        setup.start()
        setup.press(setup.member("7"))
        setup.press(setup.member("8"))
        setup.press(setup.member("9"))

        assertEquals(GameState(3, "9"), setup.state)
        assertEquals("Le monte-charge est à l'étage 3. Dernier à appuyer : <@9>. Record : étage 3, par <@9>.", setup.shown.text)
    }

    @Test
    @DisplayName("Un membre qui rappuie trop tôt reçoit, à lui seul, le temps écoulé et l'attente ; rien ne change")
    fun waitsBetweenPresses() {
        val setup = Setup()
        setup.start()
        setup.press(setup.member("7"))
        setup.advance(Duration.ofMinutes(2).plusSeconds(30))
        val editsBefore = setup.fake.edits.size
        val reply = setup.press(setup.member("7"))

        val (message, onlyToUser) = reply.sent.single()
        assertEquals("Tu as appuyé il y a 2 minutes. Le monte-charge accepte un étage toutes les 5 minutes par membre.", message.text)
        assertTrue(onlyToUser)
        assertEquals(0, reply.acknowledged)
        assertEquals(GameState(1, "7"), setup.state)
        assertEquals(editsBefore, setup.fake.edits.size)
    }

    @Test
    @DisplayName("L'attente suit les définitions : les deux nombres du message sont calculés, singulier et pluriel compris")
    fun theWaitComesFromTheRules() {
        val ten = Setup(rules = ElevatorRules(10))
        ten.start()
        ten.press(ten.member("7"))
        ten.advance(Duration.ofMinutes(1))
        assertEquals(
            "Tu as appuyé il y a 1 minute. Le monte-charge accepte un étage toutes les 10 minutes par membre.",
            ten.press(ten.member("7")).sent.single().first.text,
        )
        ten.advance(Duration.ofMinutes(7))
        assertEquals(
            "Tu as appuyé il y a 8 minutes. Le monte-charge accepte un étage toutes les 10 minutes par membre.",
            ten.press(ten.member("7")).sent.single().first.text,
        )

        val quick = Setup()
        quick.start()
        quick.press(quick.member("7"))
        quick.advance(Duration.ofSeconds(20))
        assertEquals(
            "Tu as appuyé il y a moins d'une minute. Le monte-charge accepte un étage toutes les 5 minutes par membre.",
            quick.press(quick.member("7")).sent.single().first.text,
        )

        val every = Setup(rules = ElevatorRules(1))
        every.start()
        every.press(every.member("7"))
        every.advance(Duration.ofSeconds(10))
        assertEquals(
            "Tu as appuyé il y a moins d'une minute. Le monte-charge accepte un étage chaque minute par membre.",
            every.press(every.member("7")).sent.single().first.text,
        )
    }

    @Test
    @DisplayName("À l'attente pile, le membre monte de nouveau ; juste avant, non")
    fun waitEndsExactly() {
        val setup = Setup()
        setup.start()
        setup.press(setup.member("7"))
        setup.advance(Duration.ofMinutes(5).minusSeconds(1))
        assertEquals(1, setup.press(setup.member("7")).sent.size)
        assertEquals(1L, setup.state.value)

        setup.advance(Duration.ofSeconds(1))
        val reply = setup.press(setup.member("7"))
        assertEquals(1, reply.acknowledged)
        assertEquals(2L, setup.state.value)
    }

    @Test
    @DisplayName("Un refus ne repousse pas l'attente, et l'attente d'un membre ne gêne pas les autres")
    fun waitsAreIndividual() {
        val setup = Setup()
        setup.start()
        setup.press(setup.member("7"))
        setup.advance(Duration.ofMinutes(4))
        setup.press(setup.member("7"))
        setup.press(setup.member("8"))
        setup.advance(Duration.ofMinutes(1))

        assertEquals(1, setup.press(setup.member("7")).acknowledged, "cinq minutes après le premier appui accepté, pas après le refus")
        assertEquals(3L, setup.state.value)
    }

    @Test
    @DisplayName("Un étage qui égale le record ne le change pas ; celui qui le dépasse le reprend pour son auteur et le Panthéon suit")
    fun onlyBeatingTheRecordCounts() {
        val setup = Setup()
        val record = GameRecord(120, "9", old)
        setup.seed(118, "7", record)
        setup.start()
        val boardEditsBefore = setup.boardEdits.size

        setup.press(setup.member("8"))
        setup.press(setup.member("7"))
        assertEquals(120L, setup.state.value)
        assertEquals(record, setup.record)
        assertEquals(boardEditsBefore, setup.boardEdits.size, "le tableau ne bouge pas tant que le record tient")
        assertEquals(0, setup.timer.pending, "aucune mise à jour n'est programmée tant que le record tient")

        setup.press(setup.member("6"))
        assertEquals(GameRecord(121, "6", at), setup.record)
        setup.advance(RecordsBoard.REFRESH)
        assertEquals("Les records des petits jeux\nMonte-charge : étage 121, par <@6>, le 5 novembre 2026.", setup.boardEdits.last().message.text)
        assertEquals("Le monte-charge est à l'étage 121. Dernier à appuyer : <@6>. Record : étage 121, par <@6>.", setup.shown.text)
    }

    @Test
    @DisplayName("Le staff qui appuie fait redescendre : étage à zéro, record gardé, réponse au seul membre, journal")
    fun staffLowersTheElevator() {
        val setup = Setup()
        setup.seed(37, "7", GameRecord(120, "9", old))
        setup.start()
        val reply = setup.press(setup.member("3", setup.staff))

        val (message, onlyToUser) = reply.sent.single()
        assertEquals("Le monte-charge est redescendu.", message.text)
        assertTrue(onlyToUser)
        assertEquals(0, reply.acknowledged)
        assertEquals(GameState.START, setup.state)
        assertEquals(GameRecord(120, "9", old), setup.record)
        val edit = setup.elevatorEdits.single()
        assertEquals(setup.ref.messageId, edit.messageId)
        assertEquals("Le staff fait redescendre le monte-charge. Record : étage 120, par <@9>.", edit.message.text)
        assertEquals("monte-charge-plus", edit.message.buttons.single().id)
        assertEquals(
            listOf("<@3> a fait redescendre le monte-charge de l'étage 37, le 5 novembre 2026 à 18 h 10."),
            setup.journal,
        )
    }

    @Test
    @DisplayName("La modération aussi fait redescendre ; le staff n'est jamais soumis à l'attente ; à zéro, rien à journaliser")
    fun moderationAndNoWaitForStaff() {
        val setup = Setup()
        setup.seed(5, "7", GameRecord(5, "7", old))
        setup.start()
        setup.press(setup.member("3", setup.moderation))
        assertEquals(GameState.START, setup.state)
        assertEquals(1, setup.journal.size)

        val again = setup.press(setup.member("3", setup.moderation))
        assertEquals("Le monte-charge est redescendu.", again.sent.single().first.text)
        assertEquals(1, setup.journal.size, "déjà au rez-de-chaussée : pas de seconde ligne")
        assertEquals(GameState.START, setup.state)
    }

    @Test
    @DisplayName("Après le staff, un membre remonte depuis le rez-de-chaussée ; le geste du staff ne lui coûte aucune attente")
    fun climbsAgainAfterTheStaff() {
        val setup = Setup()
        setup.seed(37, "7", GameRecord(120, "9", old))
        setup.start()
        setup.press(setup.member("3", setup.staff))
        val reply = setup.press(setup.member("3"))

        assertEquals(1, reply.acknowledged)
        assertEquals(GameState(1, "3"), setup.state)
        assertEquals(GameRecord(120, "9", old), setup.record)
    }

    @Test
    @DisplayName("Un autre bouton, un bot et les autres événements sont ignorés")
    fun ignoresWhatIsNotAPress() {
        val setup = Setup()
        setup.start()
        val other = setup.press(setup.member("7"), buttonId = "autre-bouton")
        val bot = FakeReply()
        runBlocking {
            setup.feature.handle(DiscordEvent.ButtonPressed("monte-charge-plus", DiscordUser("99", "Bot", isBot = true), setup.elevator, setup.ref.messageId, bot))
            setup.feature.handle(DiscordEvent.MessagePosted(setup.elevator, "m1", DiscordUser("7", "Membre 7"), "1", at))
        }

        assertTrue(other.sent.isEmpty() && other.acknowledged == 0)
        assertTrue(bot.sent.isEmpty() && bot.acknowledged == 0)
        assertEquals(GameState.START, setup.state)
        assertTrue(setup.elevatorEdits.isEmpty())
    }

    @Test
    @DisplayName("L'état vient de la base : une fonction neuve reprend l'étage, mais pas les attentes, qui vivent en mémoire")
    fun survivesARestart() {
        val setup = Setup()
        setup.start()
        setup.press(setup.member("7"))
        setup.press(setup.member("8"))

        val reborn = setup.build({ null })
        setup.start(reborn)
        assertEquals("Le monte-charge est à l'étage 2. Dernier à appuyer : <@8>. Record : étage 2, par <@8>.", setup.shown.text)

        val reply = FakeReply()
        runBlocking { reborn.handle(DiscordEvent.ButtonPressed("monte-charge-plus", setup.member("7"), setup.elevator, setup.ref.messageId, reply)) }
        assertEquals(1, reply.acknowledged)
        assertEquals(GameState(3, "7"), setup.state)
    }

    @Test
    @DisplayName("Un refus de Discord sur la modification ne défait pas l'étage déjà écrit")
    fun survivesARefusedEdit() {
        val setup = Setup(wrap = { RefusingEdit(it) })
        setup.start()
        val reply = setup.press(setup.member("7"))

        assertEquals(1, reply.acknowledged)
        assertEquals(GameState(1, "7"), setup.state)
        assertEquals(GameRecord(1, "7", at), setup.record)
    }

    @Test
    @DisplayName("Sans salon monte-charge dans la phase, la fonction ne fait rien")
    fun doesNothingWithoutTheChannel() {
        val setup = Setup(withChannel = false)
        setup.start()
        val reply = FakeReply()
        runBlocking { setup.feature.handle(DiscordEvent.ButtonPressed("monte-charge-plus", setup.member("7"), "1", "2", reply)) }
        val staffReply = FakeReply()
        runBlocking { setup.feature.handle(DiscordEvent.ButtonPressed("monte-charge-plus", setup.member("3", setup.staff), "1", "2", staffReply)) }

        assertTrue(setup.fake.posted.isEmpty())
        assertTrue(reply.sent.isEmpty() && reply.acknowledged == 0)
        assertTrue(staffReply.sent.isEmpty() && staffReply.acknowledged == 0)
        assertEquals(0, setup.store.stateWrites)
        assertFalse(setup.messages.refs.containsKey("monte-charge"))
    }

    /** Un Discord qui refuse de modifier un message. */
    private class RefusingEdit(private val inner: FakeDiscordServer) : DiscordServer by inner {
        override suspend fun edit(channelId: String, messageId: String, message: BotMessage) {
            throw DiscordRefusal("droit manquant")
        }
    }
}
