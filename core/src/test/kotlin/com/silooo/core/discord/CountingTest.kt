package com.silooo.core.discord

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
 * Le comptage : le bon nombre validé et enregistré, la chaîne cassée par un mauvais nombre ou par le même membre, le
 * record qui tombe et le Panthéon qui le suit, les messages qui ne sont pas des nombres, l'état qui survit au
 * redémarrage. La base est un faux en mémoire ; le SQL se teste sur MariaDB (`GameSqlTest`).
 */
class CountingTest {
    private val zone = ZoneId.of("Europe/Paris")

    /** 17 h 10 UTC : 18 h 10 à Paris, le 5 novembre 2026. */
    private val at = Instant.parse("2026-11-05T17:10:00Z")

    private val old = Instant.parse("2026-10-01T10:00:00Z")

    /** Un serveur, un temps et des bases à part pour chaque test. */
    private inner class Setup(
        withCounting: Boolean = true,
        withEmoji: Boolean = true,
        wrap: (FakeDiscordServer) -> DiscordServer = { it },
    ) {
        val fake = FakeDiscordServer()
        val timer = FakeTimer(at)
        val store = InMemoryGameStore()
        val messages = InMemoryBotMessageStore()
        private val category: String = runBlocking { fake.createCategory(CategorySpec("Animation", null)) }

        private fun channel(name: String): String =
            runBlocking { fake.createChannel(category, ChannelSpec(name, ChannelKind.TEXTE, null, emptySet(), null, false)) }

        val counting: String = channel("comptage")
        val pantheon: String = channel("pantheon")
        val journalChannel: String = channel("journal-du-bot")
        private val ids = ServerIds(
            emptyMap(),
            buildMap {
                if (withCounting) put("comptage", counting)
                put("pantheon", pantheon)
                put("journal-du-bot", journalChannel)
            },
        )
        val context = BotContext(wrap(fake), ids, timer, ServerPhase.ATTENTE)
        val board = RecordsBoard(context, store, messages, zone)
        val feature = CountingFeature(context, store, board, BotJournal(context))
        private var nextMessage = 1

        init {
            if (withEmoji) fake.emojis += "donar"
        }

        fun start() = runBlocking { feature.start() }

        fun advance(duration: java.time.Duration) = runBlocking { timer.advance(duration) }

        /** [user] écrit [text] dans [channelId] (le comptage par défaut) ; rend l'identifiant du message. */
        fun say(user: String, text: String, channelId: String = counting, bot: Boolean = false): String {
            val id = "m" + nextMessage++
            runBlocking {
                feature.handle(DiscordEvent.MessagePosted(channelId, id, DiscordUser(user, "Membre " + user, bot), text, timer.now()))
            }
            return id
        }

        fun seed(value: Long, last: String?, best: GameRecord? = null) {
            store.states["comptage"] = GameState(value, last)
            best?.let { store.bests["comptage"] = it }
        }

        /** Ce que le bot a écrit dans le comptage. */
        val said: List<String> get() = fake.posted.filter { it.channelId == counting }.map { it.message.text }
        val journal: List<String> get() = fake.posted.filter { it.channelId == journalChannel }.map { it.message.text }
        val boardPosts: List<FakeDiscordServer.Posted> get() = fake.posted.filter { it.channelId == pantheon }
        val boardEdits: List<FakeDiscordServer.Posted> get() = fake.edits.filter { it.channelId == pantheon }
        val state: GameState get() = store.states["comptage"] ?: GameState.START

        fun reactionsOn(messageId: String): List<String> = fake.reactions[counting to messageId].orEmpty()
    }

    @Test
    @DisplayName("Le jeu s'appelle comptage")
    fun hasItsName() {
        assertEquals("comptage", Setup().feature.name)
    }

    @Test
    @DisplayName("Le premier nombre est 1 : il reçoit l'emoji donar, l'état et le premier record sont écrits")
    fun startsAtOne() {
        val setup = Setup()
        val first = setup.say("7", "1")

        assertEquals(listOf("donar"), setup.reactionsOn(first))
        assertEquals(GameState(1, "7"), setup.state)
        assertEquals(at, setup.store.updatedAt["comptage"])
        assertEquals(GameRecord(1, "7", at), setup.store.bests["comptage"])
        assertTrue(setup.said.isEmpty(), "Oooscar valide d'un emoji et n'écrit rien")
        assertTrue(setup.fake.deletedMessages.isEmpty())
    }

    @Test
    @DisplayName("Deux membres qui se relaient font monter la chaîne et le record, le Panthéon suit chaque record")
    fun climbsTogether() {
        val setup = Setup()
        val ids = listOf("7" to "1", "8" to "2", "7" to "3", "8" to "4").map { (user, text) -> setup.say(user, text) }

        assertTrue(ids.all { setup.reactionsOn(it) == listOf("donar") })
        assertEquals(GameState(4, "8"), setup.state)
        assertEquals(GameRecord(4, "8", at), setup.store.bests["comptage"])
        assertTrue(setup.said.isEmpty())
        assertEquals(1, setup.boardPosts.size)
        assertTrue(setup.boardEdits.isEmpty(), "le premier record écrit le tableau, les suivants attendent")
        setup.advance(RecordsBoard.REFRESH)
        assertEquals("Les records des petits jeux\nComptage : 4, par <@8>, le 5 novembre 2026.", setup.boardEdits.single().message.text)
    }

    @Test
    @DisplayName("Un nombre qui égale le record ne le change pas ; celui qui le dépasse le reprend pour son auteur")
    fun onlyBeatingTheRecordCounts() {
        val setup = Setup()
        val record = GameRecord(120, "9", old)
        setup.seed(118, "7", record)

        setup.say("8", "119")
        setup.say("7", "120")
        assertEquals(record, setup.store.bests["comptage"])
        assertTrue(setup.fake.posted.none { it.channelId == setup.pantheon }, "le tableau ne bouge pas tant que le record tient")

        setup.say("8", "121")
        assertEquals(GameRecord(121, "8", at), setup.store.bests["comptage"])
        assertEquals("Les records des petits jeux\nComptage : 121, par <@8>, le 5 novembre 2026.", setup.boardPosts.single().message.text)
    }

    @Test
    @DisplayName("Un mauvais nombre casse la chaîne : le salon le dit sans ping, rappelle le record et l'état repart de zéro")
    fun wrongNumberBreaksTheChain() {
        val setup = Setup()
        setup.seed(57, "7", GameRecord(120, "9", old))
        setup.say("8", "60")

        assertEquals(listOf("<@8> a cassé la chaîne à 57 : il fallait 58. Le record est 120, par <@9>. On repart de 1."), setup.said)
        val post = setup.fake.posted.single { it.channelId == setup.counting }
        assertTrue(post.message.mentionUserIds.isEmpty() && post.message.mentionRoleIds.isEmpty(), "aucune mention ne part")
        assertEquals(GameState.START, setup.state)
        assertEquals(GameRecord(120, "9", old), setup.store.bests["comptage"], "le record reste")
        assertEquals(listOf("Comptage : la chaîne s'arrête à 57, cassée par <@8>."), setup.journal)
        assertTrue(setup.fake.deletedMessages.isEmpty(), "le mauvais nombre reste dans le salon")
        assertTrue(setup.fake.reactions.isEmpty())
    }

    @Test
    @DisplayName("Le même membre deux fois de suite casse la chaîne, avec son propre message")
    fun twiceInARowBreaksTheChain() {
        val setup = Setup()
        setup.seed(57, "7", GameRecord(120, "9", old))
        setup.say("7", "58")

        assertEquals(
            listOf("<@7> a joué deux fois de suite : la chaîne s'arrête à 57. Le record est 120, par <@9>. On repart de 1."),
            setup.said,
        )
        assertEquals(GameState.START, setup.state)
        assertEquals(listOf("Comptage : la chaîne s'arrête à 57, cassée par <@7>."), setup.journal)
        assertTrue(setup.fake.reactions.isEmpty())
    }

    @Test
    @DisplayName("Le même membre qui se trompe de nombre reçoit le message du mauvais nombre")
    fun sameMemberWithAWrongNumber() {
        val setup = Setup()
        setup.seed(57, "7", GameRecord(120, "9", old))
        setup.say("7", "70")

        assertEquals(listOf("<@7> a cassé la chaîne à 57 : il fallait 58. Le record est 120, par <@9>. On repart de 1."), setup.said)
    }

    @Test
    @DisplayName("Une chaîne cassée repart de 1, et le même membre peut rejouer le 1")
    fun restartsAtOne() {
        val setup = Setup()
        setup.say("7", "1")
        setup.say("8", "2")
        setup.say("7", "9")
        assertEquals(GameState.START, setup.state)

        val again = setup.say("7", "1")
        assertEquals(listOf("donar"), setup.reactionsOn(again))
        assertEquals(GameState(1, "7"), setup.state)
        assertEquals(2L, setup.store.bests["comptage"]?.best, "le record de la première chaîne reste")
    }

    @Test
    @DisplayName("Sans record connu, la phrase du record dit qu'il n'y en a pas ; sans auteur connu, elle ne cite personne")
    fun recordSentences() {
        val setup = Setup()
        setup.seed(57, "7")
        setup.say("8", "9")
        assertEquals("<@8> a cassé la chaîne à 57 : il fallait 58. Pas encore de record. On repart de 1.", setup.said.single())

        setup.seed(30, "7", GameRecord(120, null, old))
        setup.say("8", "9")
        assertEquals("<@8> a cassé la chaîne à 30 : il fallait 31. Le record est 120. On repart de 1.", setup.said.last())
    }

    @Test
    @DisplayName("Quand la chaîne n'a pas commencé, un autre nombre que 1 ne casse rien : Oooscar dit où elle commence")
    fun notStarted() {
        val setup = Setup()
        setup.say("8", "5")

        assertEquals(listOf("<@8> a écrit 5 : la chaîne commence à 1."), setup.said)
        assertEquals(0, setup.store.stateWrites)
        assertTrue(setup.journal.isEmpty())
        assertTrue(setup.fake.deletedMessages.isEmpty())
    }

    @Test
    @DisplayName("Ce qui n'est pas un entier de chiffres est supprimé, sans réponse ni changement d'état")
    fun deletesWhatIsNotANumber() {
        val setup = Setup()
        setup.seed(3, "7", GameRecord(10, "9", old))
        val texts = listOf(
            "bonjour", "4 et 5", "12abc", "1.5", "-3", "+4", "", "   ", "quatre", "1 2", "٤", "1234567890123456789", "4!", "<@7> 4",
        )
        val ids = texts.map { setup.say("8", it) }

        assertEquals(ids.map { setup.counting to it }, setup.fake.deletedMessages)
        assertTrue(setup.said.isEmpty())
        assertEquals(0, setup.store.stateWrites)
        assertEquals(GameState(3, "7"), setup.state)
    }

    @Test
    @DisplayName("Les blancs autour d'un nombre ne comptent pas, ni les zéros devant : le nombre vaut ce qu'il vaut")
    fun trimsTheNumber() {
        val setup = Setup()
        setup.seed(3, "7")
        val padded = setup.say("8", "  4 \n")
        val zeros = setup.say("7", "0005")

        assertEquals(listOf("donar"), setup.reactionsOn(padded))
        assertEquals(listOf("donar"), setup.reactionsOn(zeros))
        assertEquals(GameState(5, "7"), setup.state)
    }

    @Test
    @DisplayName("Dix-huit chiffres passent, un de plus est supprimé")
    fun eighteenDigitsAtMost() {
        val setup = Setup()
        setup.seed(999_999_999_999_999_998L, "7")
        val last = setup.say("8", "999999999999999999")
        assertEquals(listOf("donar"), setup.reactionsOn(last))
        assertEquals(GameState(999_999_999_999_999_999L, "8"), setup.state)

        val tooLong = setup.say("7", "1000000000000000000")
        assertEquals(listOf(setup.counting to tooLong), setup.fake.deletedMessages)
        assertEquals(GameState(999_999_999_999_999_999L, "8"), setup.state)
    }

    @Test
    @DisplayName("Les bots et les autres salons ne comptent pas et ne sont jamais supprimés")
    fun ignoresBotsAndOtherChannels() {
        val setup = Setup()
        setup.say("99", "bonjour", bot = true)
        setup.say("99", "1", bot = true)
        setup.say("8", "bonjour", channelId = setup.pantheon)
        setup.say("8", "1", channelId = setup.pantheon)

        assertTrue(setup.fake.deletedMessages.isEmpty())
        assertEquals(0, setup.store.stateWrites)
        assertTrue(setup.said.isEmpty())
    }

    @Test
    @DisplayName("Les autres événements sont ignorés")
    fun ignoresOtherEvents() {
        val setup = Setup()
        runBlocking {
            setup.feature.handle(DiscordEvent.MessageRemoved(setup.counting, "1"))
            setup.feature.handle(DiscordEvent.MemberLeft("7", at))
        }
        assertEquals(0, setup.store.stateWrites)
        assertTrue(setup.fake.posted.isEmpty())
    }

    @Test
    @DisplayName("Sans l'emoji donar sur le serveur, le nombre est accepté quand même")
    fun worksWithoutTheEmoji() {
        val setup = Setup(withEmoji = false)
        setup.say("7", "1")
        setup.say("8", "2")

        assertEquals(GameState(2, "8"), setup.state)
        assertTrue(setup.fake.reactions.isEmpty())
    }

    @Test
    @DisplayName("Un refus de Discord sur la suppression ne casse rien")
    fun survivesARefusedDeletion() {
        val setup = Setup(wrap = { RefusingDelete(it) })
        setup.seed(3, "7")
        setup.say("8", "bonjour")
        setup.say("8", "4")

        assertEquals(GameState(4, "8"), setup.state)
    }

    @Test
    @DisplayName("L'état vient de la base : une fonction neuve reprend la chaîne là où elle était")
    fun survivesARestart() {
        val setup = Setup()
        setup.say("7", "1")
        setup.say("8", "2")

        val reborn = CountingFeature(setup.context, setup.store, setup.board, BotJournal(setup.context))
        runBlocking {
            reborn.start()
            reborn.handle(DiscordEvent.MessagePosted(setup.counting, "m100", DiscordUser("7", "Membre 7"), "3", at))
            reborn.handle(DiscordEvent.MessagePosted(setup.counting, "m101", DiscordUser("7", "Membre 7"), "4", at))
        }

        assertEquals(listOf("<@7> a joué deux fois de suite : la chaîne s'arrête à 3. Le record est 3, par <@7>. On repart de 1."), setup.said)
    }

    @Test
    @DisplayName("Au démarrage, le tableau des records est posé tout de suite ; un second appel trop proche ne le modifie pas s'il n'a pas changé")
    fun startShowsTheBoard() {
        val setup = Setup()
        setup.start()
        assertEquals("Les records des petits jeux\nAucun record pour l'instant.", setup.boardPosts.single().message.text)

        setup.start()
        setup.advance(RecordsBoard.REFRESH)
        assertEquals(1, setup.boardPosts.size)
        assertTrue(setup.boardEdits.isEmpty())
    }

    @Test
    @DisplayName("Sans salon comptage dans la phase, la fonction ne fait rien")
    fun doesNothingWithoutTheChannel() {
        val setup = Setup(withCounting = false)
        setup.start()
        setup.say("8", "bonjour")
        setup.say("8", "1")

        assertTrue(setup.fake.posted.isEmpty())
        assertTrue(setup.fake.deletedMessages.isEmpty())
        assertEquals(0, setup.store.stateWrites)
        assertNull(setup.store.bests["comptage"])
        assertFalse(setup.messages.refs.containsKey("pantheon-records"))
    }

    /** Un Discord qui refuse de supprimer un message. */
    private class RefusingDelete(private val inner: FakeDiscordServer) : DiscordServer by inner {
        override suspend fun deleteMessage(channelId: String, messageId: String) {
            throw DiscordRefusal("droit manquant")
        }
    }
}
