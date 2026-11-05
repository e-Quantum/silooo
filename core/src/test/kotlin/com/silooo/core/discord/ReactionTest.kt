package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le top réactions : une réaction par message et par membre, les salons de la liste, les bots, le tableau modifié sur
 * place au plus une fois par minute, le lundi (journal, purge, tableau vide), le lundi manqué.
 */
class ReactionTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val rules = ReactionRules(topSize = 3, channels = listOf("discussion", "captures", "entreprises"))

    /** Le lundi qui ouvre la semaine du jeudi 5 novembre 2026, puis le suivant. */
    private val monday = LocalDate.of(2026, 11, 2)
    private val nextMonday = LocalDate.of(2026, 11, 9)

    private val emptyBoard = "Top réactions de la semaine :\nPersonne n'a encore réagi cette semaine."

    private inner class Setup(
        start: Instant = Instant.parse("2026-11-05T10:00:00Z"),
        withPantheon: Boolean = true,
        val store: InMemoryReactionStore = InMemoryReactionStore(),
        val messages: InMemoryBotMessageStore = InMemoryBotMessageStore(),
    ) {
        val fake = FakeDiscordServer()
        val timer = FakeTimer(start)
        private val category = runBlocking { fake.createCategory(CategorySpec("Communauté", null)) }

        private fun channel(name: String, kind: ChannelKind = ChannelKind.TEXTE): String =
            runBlocking { fake.createChannel(category, ChannelSpec(name, kind, null, null, null, false)) }

        val pantheon: String? = if (withPantheon) channel("pantheon") else null
        val discussion = channel("discussion")
        val captures = channel("captures")
        val forum = channel("entreprises", ChannelKind.FORUM)
        val gameChat = channel("tchat-du-jeu")
        val journalChannel = channel("journal-du-bot")
        val context = BotContext(
            fake,
            ServerIds(
                emptyMap(),
                listOfNotNull(
                    pantheon?.let { "pantheon" to it },
                    "discussion" to discussion,
                    "captures" to captures,
                    "entreprises" to forum,
                    "tchat-du-jeu" to gameChat,
                    "journal-du-bot" to journalChannel,
                ).toMap(),
            ),
            timer,
            ServerPhase.ATTENTE,
        )

        val feature = build()

        fun build() = ReactionFeature(context, rules, zone, store, messages, BotJournal(context))

        fun start(feature: ReactionFeature = this.feature) = runBlocking { feature.start() }

        fun advance(duration: Duration) = runBlocking { timer.advance(duration) }

        fun react(user: String, message: String, channel: String = discussion, emoji: String = "prestige", bot: Boolean = false) =
            runBlocking { feature.handle(DiscordEvent.ReactionAdded(channel, message, user, emoji, bot)) }

        fun unreact(user: String, message: String, channel: String = discussion) =
            runBlocking { feature.handle(DiscordEvent.ReactionRemoved(channel, message, user, "prestige")) }

        fun write(thread: String, parent: String) = runBlocking {
            val author = DiscordUser("w", "Membre w")
            feature.handle(DiscordEvent.MessagePosted(thread, thread, author, "salut", timer.now(), parent))
        }

        /** Le dernier état du tableau : le dernier message modifié, sinon le dernier posté. */
        fun board(): String =
            (fake.edits.lastOrNull { it.channelId == pantheon } ?: fake.posted.last { it.channelId == pantheon }).message.text

        fun boardPosts() = fake.posted.filter { it.channelId == pantheon }

        fun boardEdits() = fake.edits.filter { it.channelId == pantheon }

        fun journal() = fake.posted.filter { it.channelId == journalChannel }.map { it.message.text }
    }

    @Test
    @DisplayName("Au démarrage le tableau est posté dans le panthéon, vide, en carte, sans mention, et sa clé est notée")
    fun postsAnEmptyBoard() {
        val setup = Setup()
        setup.start()

        val post = setup.boardPosts().single()
        assertEquals(emptyBoard, post.message.text)
        assertTrue(post.message.card)
        assertTrue(post.message.mentionUserIds.isEmpty())
        assertEquals(BotMessageRef(setup.pantheon!!, post.messageId), setup.messages.refs["pantheon-reactions"])
        assertTrue(setup.journal().isEmpty())
    }

    @Test
    @DisplayName("Une réaction compte une fois par message et par membre, quel que soit l'emoji, et chaque message de plus compte")
    fun countsOncePerMessageAndMember() {
        val setup = Setup()
        setup.start()

        setup.react("7", "m1", emoji = "prestige")
        setup.react("7", "m1", emoji = "silo")
        assertEquals(mapOf("7" to 1), setup.store.weeks.getValue(monday))
        setup.react("7", "m2", emoji = "silo")
        setup.react("8", "m1")
        assertEquals(mapOf("7" to 2, "8" to 1), setup.store.weeks.getValue(monday))
        assertEquals(3, setup.store.marks.size)
    }

    @Test
    @DisplayName("Les bots, les salons hors de la liste et les réactions retirées ne comptent pas")
    fun ignoresBotsOtherChannelsAndRemovals() {
        val setup = Setup()
        setup.start()

        setup.react("7", "m1", bot = true)
        setup.react("7", "m2", channel = setup.gameChat)
        setup.react("7", "m3", channel = "inconnu")
        setup.react("7", "m4", channel = setup.captures)
        setup.react("8", "m5", channel = setup.pantheon!!)
        setup.unreact("7", "m4", channel = setup.captures)

        assertEquals(mapOf("7" to 1), setup.store.weeks.getValue(monday))
        assertEquals(1, setup.store.marks.size)
    }

    @Test
    @DisplayName("Le tableau se modifie au plus une fois par minute, seulement si une réaction a compté, et montre les rangs du plus au moins")
    fun refreshesOncePerMinuteWhenChanged() {
        val setup = Setup()
        setup.start()
        val readsAfterStart = setup.store.reads

        setup.react("8", "m1")
        setup.react("7", "m1")
        setup.react("7", "m2")
        setup.advance(Duration.ofSeconds(59))
        assertTrue(setup.boardEdits().isEmpty(), "pas avant la minute")
        setup.advance(Duration.ofSeconds(1))
        assertEquals("Top réactions de la semaine :\n1. <@7>, 2 réactions\n2. <@8>, 1 réaction", setup.board())
        assertEquals(1, setup.boardEdits().size)
        assertTrue(setup.boardEdits().single().message.card)
        assertTrue(setup.boardEdits().single().message.mentionUserIds.isEmpty())

        val reads = setup.store.reads
        setup.advance(Duration.ofMinutes(5))
        assertEquals(1, setup.boardEdits().size, "rien n'a changé : rien n'est modifié")
        assertEquals(reads, setup.store.reads, "rien n'est relu non plus")
        assertTrue(reads > readsAfterStart)
        assertEquals(1, setup.boardPosts().size, "jamais reposté")
    }

    @Test
    @DisplayName("Le tableau montre topTaille lignes, l'égalité se départage par identifiant")
    fun limitsTheBoard() {
        val setup = Setup()
        setup.start()

        for ((user, count) in listOf("d" to 1, "c" to 2, "b" to 2, "a" to 3)) {
            for (n in 1..count) setup.react(user, "m" + n)
        }
        setup.advance(ReactionFeature.REFRESH)

        assertEquals(
            "Top réactions de la semaine :\n1. <@a>, 3 réactions\n2. <@b>, 2 réactions\n3. <@c>, 2 réactions",
            setup.board(),
        )
    }

    @Test
    @DisplayName("Un sujet de forum de la liste compte une fois que le bot l'a vu passer ; un sujet d'un autre forum ou inconnu non")
    fun countsForumThreads() {
        val setup = Setup()
        setup.start()

        setup.react("7", "t1", channel = "sujet-1")
        assertTrue(setup.store.weeks.isEmpty(), "un sujet que le bot n'a pas vu")

        setup.write("sujet-1", setup.forum)
        setup.write("sujet-2", "autre-forum")
        setup.react("7", "t1", channel = "sujet-1")
        setup.react("7", "t2", channel = "sujet-2")
        assertEquals(mapOf("7" to 1), setup.store.weeks.getValue(monday))
    }

    @Test
    @DisplayName("Sans panthéon sur le serveur, rien n'est posté, rien n'est compté, rien n'est programmé")
    fun doesNothingWithoutPantheon() {
        val setup = Setup(withPantheon = false)
        setup.start()
        setup.react("7", "m1")

        assertTrue(setup.fake.posted.isEmpty())
        assertTrue(setup.store.weeks.isEmpty())
        assertEquals(0, setup.timer.pending)
    }

    @Test
    @DisplayName("Le lundi à minuit, le journal reçoit le classement de la semaine, les marques sont purgées et le tableau repart vide")
    fun closesTheWeekOnMonday() {
        val setup = Setup()
        setup.start()
        setup.react("7", "m1")
        setup.react("7", "m2")
        setup.react("8", "m1")

        setup.advance(Duration.ofHours(84))
        assertTrue(setup.journal().isEmpty(), "le dimanche soir, la semaine court encore")
        setup.advance(Duration.ofHours(1))

        assertEquals(listOf("Top réactions de la semaine passée :\n1. <@7>, 2 réactions\n2. <@8>, 1 réaction"), setup.journal())
        assertEquals(emptyBoard, setup.board())
        assertTrue(setup.store.weeks.isEmpty())
        assertTrue(setup.store.marks.isEmpty())

        setup.react("9", "m1")
        assertEquals(mapOf("9" to 1), setup.store.weeks.getValue(nextMonday), "la semaine neuve repart de zéro, même sur un vieux message")
        assertEquals(2, setup.timer.pending, "le lundi suivant et le tableau sont programmés")
    }

    @Test
    @DisplayName("Une semaine sans réaction laisse tout de même sa ligne au journal")
    fun journalsAnEmptyWeek() {
        val setup = Setup()
        setup.start()
        setup.advance(Duration.ofHours(85))

        assertEquals(listOf("Top réactions de la semaine passée :\nPersonne n'a réagi."), setup.journal())
        assertEquals(emptyBoard, setup.board())
    }

    @Test
    @DisplayName("Une marque posée avant le lundi est purgée : le même message compte dans la semaine neuve, mais une seule fois")
    fun purgesMarksBeforeMonday() {
        val setup = Setup(start = Instant.parse("2026-11-08T22:30:00Z"))
        setup.start()
        setup.react("7", "m1")
        setup.advance(Duration.ofMinutes(31))

        setup.react("7", "m1")
        assertEquals(mapOf("7" to 1), setup.store.weeks.getValue(nextMonday), "la marque d'avant minuit a été purgée, la réaction compte dans la semaine neuve")
        assertEquals(1, setup.store.marks.size)
        setup.react("7", "m1")
        assertEquals(mapOf("7" to 1), setup.store.weeks.getValue(nextMonday))
    }

    @Test
    @DisplayName("Un bot éteint le lundi écrit la semaine manquée au journal à son démarrage, purge, et pose le tableau vide")
    fun catchesUpAMissedMonday() {
        val setup = Setup(start = Instant.parse("2026-11-12T10:00:00Z"))
        setup.store.weeks[monday] = mutableMapOf("7" to 4, "8" to 1)
        setup.store.marks["m1" to "7"] = Instant.parse("2026-11-04T10:00:00Z")
        setup.start()

        assertEquals(listOf("Top réactions de la semaine passée :\n1. <@7>, 4 réactions\n2. <@8>, 1 réaction"), setup.journal())
        assertTrue(setup.store.weeks.isEmpty())
        assertTrue(setup.store.marks.isEmpty())
        assertEquals(emptyBoard, setup.boardPosts().single().message.text)
    }

    @Test
    @DisplayName("Le démarrage d'une semaine encore en cours n'écrit rien au journal, et le tableau montre ce qui est déjà compté")
    fun startsMidWeekWithoutJournal() {
        val setup = Setup()
        setup.store.weeks[monday] = mutableMapOf("7" to 4)
        setup.start()

        assertTrue(setup.journal().isEmpty())
        assertEquals("Top réactions de la semaine :\n1. <@7>, 4 réactions", setup.boardPosts().single().message.text)
    }

    @Test
    @DisplayName("Après un redémarrage le tableau est modifié sur place ; s'il a disparu ou s'il est ailleurs, il est reposté et noté")
    fun editsInPlaceOrRepostsWhenGone() {
        val setup = Setup()
        setup.start()
        setup.start(setup.build())
        assertEquals(1, setup.boardPosts().size)
        assertEquals(1, setup.boardEdits().size)

        val gone = Setup()
        gone.messages.refs["pantheon-reactions"] = BotMessageRef(gone.pantheon!!, "999")
        gone.start()
        val reposted = gone.boardPosts().single()
        assertEquals(reposted.messageId, gone.messages.refs.getValue("pantheon-reactions").messageId)

        val elsewhere = Setup()
        elsewhere.messages.refs["pantheon-reactions"] = BotMessageRef(elsewhere.discussion, "999")
        elsewhere.start()
        assertEquals(BotMessageRef(elsewhere.pantheon!!, elsewhere.boardPosts().single().messageId), elsewhere.messages.refs["pantheon-reactions"])
        assertFalse(elsewhere.fake.edits.isNotEmpty())
    }
}
