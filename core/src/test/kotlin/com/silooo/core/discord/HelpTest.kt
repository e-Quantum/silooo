package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * L'Aide du mois : le message et le bouton dans chaque sujet neuf, qui peut appuyer, le menu des membres qui ont écrit,
 * le marquage d'un sujet une seule fois, le tableau du panthéon, le journal du premier du mois.
 */
class HelpTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val november = LocalDate.of(2026, 11, 1)
    private val december = LocalDate.of(2026, 12, 1)
    private val emptyBoard = "Aide du mois :\nPersonne n'a encore résolu de sujet ce mois-ci."

    private inner class Setup(
        withForum: Boolean = true,
        withPantheon: Boolean = true,
        val store: InMemoryHelpStore = InMemoryHelpStore(),
        val messages: InMemoryBotMessageStore = InMemoryBotMessageStore(),
    ) {
        val fake = FakeDiscordServer()
        val timer = FakeTimer(Instant.parse("2026-11-05T10:00:00Z"))
        private val category = runBlocking { fake.createCategory(CategorySpec("Aide", null)) }

        private fun channel(name: String, kind: ChannelKind = ChannelKind.TEXTE): String =
            runBlocking { fake.createChannel(category, ChannelSpec(name, kind, null, null, null, false)) }

        val forum: String? = if (withForum) channel("aide", ChannelKind.FORUM) else null
        val otherForum = channel("entreprises", ChannelKind.FORUM)
        val pantheon: String? = if (withPantheon) channel("pantheon") else null
        val journalChannel = channel("journal-du-bot")
        val staffRole = runBlocking { fake.createRole(RoleSpec("staff", null, false, false)) }
        val moderationRole = runBlocking { fake.createRole(RoleSpec("moderation", null, false, false)) }
        val context = BotContext(
            fake,
            ServerIds(
                mapOf("staff" to staffRole, "moderation" to moderationRole),
                listOfNotNull(
                    forum?.let { "aide" to it },
                    "entreprises" to otherForum,
                    pantheon?.let { "pantheon" to it },
                    "journal-du-bot" to journalChannel,
                ).toMap(),
            ),
            timer,
            ServerPhase.ATTENTE,
        )
        val feature = build()

        fun build() = HelpFeature(context, zone, store, messages, BotJournal(context))

        fun start(feature: HelpFeature = this.feature) = runBlocking { feature.start() }

        fun advance(duration: Duration) = runBlocking { timer.advance(duration) }

        /** Un sujet neuf du forum par [author] : le salon du sujet existe, son premier message est celui de l'auteur. */
        fun topic(thread: String, author: String, parent: String? = forum, feature: HelpFeature = this.feature) {
            fake.channels += ExistingChannel(thread, "sujet-" + thread, ChannelKind.TEXTE, null, null)
            fake.messagesById[thread to thread] = ChannelMessage(author, "Membre " + author, "Ma question", timer.now())
            runBlocking {
                feature.handle(DiscordEvent.MessagePosted(thread, thread, DiscordUser(author, "Membre " + author), "Ma question", timer.now(), parent))
            }
        }

        /** [author] écrit dans le sujet ; l'historique de Discord le garde. */
        fun say(thread: String, author: String, name: String = "Membre " + author, bot: Boolean = false) {
            fake.histories.getOrPut(thread) { mutableListOf() } += ChannelMessage(author, name, "Une réponse", timer.now(), bot)
        }

        fun press(thread: String, user: String, roles: Set<String> = emptySet(), feature: HelpFeature = this.feature): FakeReply {
            val reply = FakeReply()
            runBlocking {
                feature.handle(DiscordEvent.ButtonPressed("aide-resolu:" + thread, DiscordUser(user, "Membre " + user, false, roles), thread, "p1", reply))
            }
            return reply
        }

        fun choose(thread: String, resolver: String, user: String, roles: Set<String> = emptySet()): FakeReply {
            val reply = FakeReply()
            runBlocking {
                feature.handle(
                    DiscordEvent.MenuChosen("aide-resolu-par:" + thread, listOf(resolver), DiscordUser(user, "Membre " + user, false, roles), thread, "e1", reply),
                )
            }
            return reply
        }

        fun posts(channel: String?) = fake.posted.filter { it.channelId == channel }

        fun board(): String =
            (fake.edits.lastOrNull { it.channelId == pantheon } ?: fake.posted.last { it.channelId == pantheon }).message.text

        fun journal() = posts(journalChannel).map { it.message.text }
    }

    @Test
    @DisplayName("Un sujet neuf du forum reçoit le message du bot avec le bouton Résolu par, dont l'identifiant porte le sujet")
    fun promptsEveryNewTopic() {
        val setup = Setup()
        setup.topic("t1", "7")

        val prompt = setup.posts("t1").single().message
        assertEquals("Quand ta question est réglée, appuie sur le bouton pour dire qui t'a aidé.", prompt.text)
        assertEquals(listOf(ButtonSpec("aide-resolu:t1", "Résolu par", ButtonStyle.PRIMARY)), prompt.buttons)
        assertTrue(prompt.mentionUserIds.isEmpty())
    }

    @Test
    @DisplayName("Ni une réponse, ni un sujet d'un autre forum, ni un message hors fil, ni un sujet ouvert par un bot ne reçoivent le bouton")
    fun promptsOnlyNewTopicsOfTheHelpForum() {
        val setup = Setup()
        setup.topic("t1", "7", parent = setup.otherForum)
        setup.topic("t2", "7", parent = null)
        runBlocking {
            setup.feature.handle(DiscordEvent.MessagePosted("t3", "m2", DiscordUser("8", "Membre 8"), "Une réponse", setup.timer.now(), setup.forum))
            setup.feature.handle(DiscordEvent.MessagePosted("t4", "t4", DiscordUser("9", "Oooscar", isBot = true), "Salut", setup.timer.now(), setup.forum))
        }
        setup.fake.channels += ExistingChannel("t3", "sujet-t3", ChannelKind.TEXTE, null, null)
        setup.fake.channels += ExistingChannel("t4", "sujet-t4", ChannelKind.TEXTE, null, null)

        assertTrue(setup.fake.posted.none { it.message.buttons.isNotEmpty() })
    }

    @Test
    @DisplayName("Un membre qui n'est ni l'auteur du sujet ni du staff reçoit la phrase, à lui seul, et aucun menu")
    fun refusesAStranger() {
        val setup = Setup()
        setup.topic("t1", "7")
        setup.say("t1", "8")

        val reply = setup.press("t1", "9")
        val (message, onlyToUser) = reply.sent.single()
        assertEquals("Seul l'auteur du sujet ou le staff peut dire qui l'a résolu.", message.text)
        assertTrue(onlyToUser)
        assertEquals(null, message.menu)
    }

    @Test
    @DisplayName("L'auteur du sujet reçoit le menu des membres qui ont écrit, sans lui, sans les bots, sans doublon, par ordre d'arrivée")
    fun offersTheWriters() {
        val setup = Setup()
        setup.topic("t1", "7")
        setup.say("t1", "7")
        setup.say("t1", "8", name = "Alice")
        setup.say("t1", "99", name = "Oooscar", bot = true)
        setup.say("t1", "9", name = "Bob")
        setup.say("t1", "8", name = "Alice")
        setup.say("t1", "7")

        val reply = setup.press("t1", "7")
        val (message, onlyToUser) = reply.sent.single()
        assertTrue(onlyToUser)
        assertEquals("Choisis le membre qui a résolu ce sujet.", message.text)
        val menu = checkNotNull(message.menu)
        assertEquals("aide-resolu-par:t1", menu.id)
        assertEquals("Choisis un membre", menu.placeholder)
        assertEquals(listOf(MenuOption("8", "Alice"), MenuOption("9", "Bob")), menu.options)
        assertEquals(1, menu.maxValues)
    }

    @Test
    @DisplayName("Le staff et la modération peuvent appuyer sur un sujet qui n'est pas à eux")
    fun letsStaffPress() {
        val setup = Setup()
        setup.topic("t1", "7")
        setup.say("t1", "8")

        val staff = setup.press("t1", "1", roles = setOf(setup.staffRole))
        assertNotNull(staff.sent.single().first.menu)
        val moderation = setup.press("t1", "2", roles = setOf(setup.moderationRole))
        assertNotNull(moderation.sent.single().first.menu)
        val other = setup.press("t1", "3", roles = setOf("un-autre-role"))
        assertEquals("Seul l'auteur du sujet ou le staff peut dire qui l'a résolu.", other.sent.single().first.text)
    }

    @Test
    @DisplayName("Sans autre auteur dans le sujet, le membre lit que personne d'autre n'a écrit")
    fun saysWhenNobodyElseWrote() {
        val setup = Setup()
        setup.topic("t1", "7")
        setup.say("t1", "7")
        setup.say("t1", "99", bot = true)

        val reply = setup.press("t1", "7")
        assertEquals("Personne d'autre n'a encore écrit dans ce sujet.", reply.sent.single().first.text)
        assertEquals(null, reply.sent.single().first.menu)
    }

    @Test
    @DisplayName("Un sujet né avant le démarrage du bot : l'auteur est celui de son premier message, relu chez Discord ; s'il est illisible, seul le staff appuie")
    fun readsTheOwnerOfAnOldTopic() {
        val setup = Setup()
        setup.fake.channels += ExistingChannel("old", "sujet-old", ChannelKind.TEXTE, null, null)
        setup.fake.messagesById["old" to "old"] = ChannelMessage("7", "Membre 7", "Ma question", setup.timer.now())
        setup.say("old", "8")

        assertEquals("Seul l'auteur du sujet ou le staff peut dire qui l'a résolu.", setup.press("old", "8").sent.single().first.text)
        assertNotNull(setup.press("old", "7").sent.single().first.menu)
        setup.fake.messagesById.remove("old" to "old")
        val restarted = setup.build()
        assertEquals("Seul l'auteur du sujet ou le staff peut dire qui l'a résolu.", setup.press("old", "7", feature = restarted).sent.single().first.text)
    }

    @Test
    @DisplayName("Le menu garde vingt-cinq membres au plus, et un nom trop long est coupé à cent caractères")
    fun boundsTheMenu() {
        val setup = Setup()
        setup.topic("t1", "7")
        for (n in 1..30) setup.say("t1", "w" + n, name = if (n == 1) "N".repeat(150) else "Membre " + n)

        val menu = checkNotNull(setup.press("t1", "7").sent.single().first.menu)
        assertEquals(25, menu.options.size)
        assertEquals("w1", menu.options.first().value)
        assertEquals(100, menu.options.first().label.length)
    }

    @Test
    @DisplayName("Un sujet que Discord ne laisse pas relire donne une phrase au membre, sans rien casser")
    fun saysWhenTheTopicCannotBeRead() {
        val setup = Setup()
        val reply = setup.press("inconnu", "1", roles = setOf(setup.staffRole))

        assertEquals("Ce sujet ne peut pas être relu pour l'instant.", reply.sent.single().first.text)
    }

    @Test
    @DisplayName("Le choix marque le sujet, compte une réponse au membre, le nomme dans le sujet avec un ping, remplace le menu et met le tableau à jour")
    fun marksTheTopic() {
        val setup = Setup()
        setup.start()
        assertEquals(emptyBoard, setup.board())
        setup.topic("t1", "7")
        setup.say("t1", "8")

        val reply = setup.choose("t1", "8", "7")

        assertEquals(InMemoryHelpStore.Mark("8", "7", setup.timer.now()), setup.store.marks["t1"])
        assertEquals(mapOf("8" to 1), setup.store.months.getValue(november))
        assertEquals("C'est noté.", reply.edits.single().text)
        val resolved = setup.posts("t1").last().message
        assertEquals("Résolu par <@8>.", resolved.text)
        assertEquals(setOf("8"), resolved.mentionUserIds)
        assertEquals("Aide du mois :\n1. <@8>, 1 réponse retenue", setup.board())
        assertTrue(setup.fake.edits.last { it.channelId == setup.pantheon }.message.mentionUserIds.isEmpty())
        assertTrue(setup.fake.edits.last { it.channelId == setup.pantheon }.message.card)
    }

    @Test
    @DisplayName("Le staff peut désigner qui a résolu un sujet qui n'est pas à lui ; il est inscrit comme celui qui a appuyé")
    fun staffMarksATopic() {
        val setup = Setup()
        setup.topic("t1", "7")

        setup.choose("t1", "8", "1", roles = setOf(setup.staffRole))

        assertEquals(InMemoryHelpStore.Mark("8", "1", setup.timer.now()), setup.store.marks["t1"])
    }

    @Test
    @DisplayName("Un sujet déjà résolu le dit au bouton comme au menu, et ne compte rien de plus")
    fun resolvesOnlyOnce() {
        val setup = Setup()
        setup.topic("t1", "7")
        setup.say("t1", "8")
        setup.choose("t1", "8", "7")

        val again = setup.choose("t1", "9", "7")
        assertEquals("Ce sujet est déjà résolu.", again.sent.single().first.text)
        assertEquals("Ce sujet est déjà résolu.", setup.press("t1", "7").sent.single().first.text)
        assertEquals(mapOf("8" to 1), setup.store.months.getValue(november))
        assertEquals("8", setup.store.marks.getValue("t1").resolverId)
        assertEquals(1, setup.posts("t1").count { it.message.text.startsWith("Résolu par") && it.message.buttons.isEmpty() })
    }

    @Test
    @DisplayName("Un membre qui n'est ni l'auteur ni du staff ne peut pas valider un menu non plus")
    fun refusesAStrangerAtTheMenu() {
        val setup = Setup()
        setup.topic("t1", "7")

        val reply = setup.choose("t1", "8", "9")
        assertEquals("Seul l'auteur du sujet ou le staff peut dire qui l'a résolu.", reply.sent.single().first.text)
        assertTrue(setup.store.marks.isEmpty())
        assertTrue(setup.store.months.isEmpty())
    }

    @Test
    @DisplayName("Le tableau classe les membres par réponses retenues, égalité par identifiant, avec le pluriel")
    fun ranksTheBoard() {
        val setup = Setup()
        setup.start()
        for ((thread, resolver) in listOf("t1" to "9", "t2" to "8", "t3" to "9", "t4" to "5")) {
            setup.topic(thread, "7")
            setup.choose(thread, resolver, "7")
        }

        assertEquals("Aide du mois :\n1. <@9>, 2 réponses retenues\n2. <@5>, 1 réponse retenue\n3. <@8>, 1 réponse retenue", setup.board())
        assertEquals(1, setup.posts(setup.pantheon).size, "le tableau est modifié, jamais reposté")
    }

    @Test
    @DisplayName("Après un redémarrage le tableau est modifié sur place")
    fun editsTheBoardAfterARestart() {
        val setup = Setup()
        setup.start()
        setup.start(setup.build())

        assertEquals(1, setup.posts(setup.pantheon).size)
        assertEquals(BotMessageRef(setup.pantheon!!, setup.posts(setup.pantheon).single().messageId), setup.messages.refs["pantheon-aide"])
        assertEquals(1, setup.fake.edits.count { it.channelId == setup.pantheon })
    }

    @Test
    @DisplayName("Le premier du mois à minuit, le journal reçoit le classement du mois et le tableau repart vide ; le mois neuf compte à part")
    fun closesTheMonth() {
        val setup = Setup()
        setup.start()
        setup.topic("t1", "7")
        setup.choose("t1", "8", "7")
        setup.topic("t2", "7")
        setup.choose("t2", "9", "7")
        setup.topic("t3", "7")
        setup.choose("t3", "9", "7")

        setup.advance(Duration.ofHours(612))
        assertTrue(setup.journal().isEmpty(), "le 30 novembre au soir, le mois court encore")
        setup.advance(Duration.ofHours(1))

        assertEquals(listOf("Aide du mois passé :\n1. <@9>, 2 réponses retenues\n2. <@8>, 1 réponse retenue"), setup.journal())
        assertEquals(emptyBoard, setup.board())

        setup.topic("t4", "7")
        setup.choose("t4", "8", "7")
        assertEquals(mapOf("8" to 1), setup.store.months.getValue(december))
        assertEquals(mapOf("9" to 2, "8" to 1), setup.store.months.getValue(november), "le mois passé reste lisible")
        assertEquals("Aide du mois :\n1. <@8>, 1 réponse retenue", setup.board())
        assertEquals(1, setup.timer.pending, "le mois suivant est programmé")
    }

    @Test
    @DisplayName("Un mois sans sujet résolu laisse tout de même sa ligne au journal")
    fun journalsAnEmptyMonth() {
        val setup = Setup()
        setup.start()
        setup.advance(Duration.ofHours(613))

        assertEquals(listOf("Aide du mois passé :\nPersonne n'a résolu de sujet."), setup.journal())
    }

    @Test
    @DisplayName("Sans le forum d'aide sur le serveur, la fonction ne fait rien")
    fun doesNothingWithoutTheForum() {
        val setup = Setup(withForum = false)
        setup.start()
        setup.topic("t1", "7", parent = "autre")
        setup.press("t1", "7")
        setup.choose("t1", "8", "7")

        assertTrue(setup.fake.posted.isEmpty())
        assertTrue(setup.store.marks.isEmpty())
        assertEquals(0, setup.timer.pending)
    }

    @Test
    @DisplayName("Sans le panthéon, les sujets se résolvent et se comptent, sans tableau")
    fun worksWithoutThePantheon() {
        val setup = Setup(withPantheon = false)
        setup.start()
        setup.topic("t1", "7")
        setup.choose("t1", "8", "7")

        assertEquals(mapOf("8" to 1), setup.store.months.getValue(november))
        assertEquals("Résolu par <@8>.", setup.posts("t1").last().message.text)
        assertTrue(setup.messages.refs.isEmpty())
    }
}
