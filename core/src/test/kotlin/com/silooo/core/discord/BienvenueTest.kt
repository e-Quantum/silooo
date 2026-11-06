package com.silooo.core.discord

import com.silooo.core.TestDefinitions
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
 * Le message de `#bienvenue` : posté puis retrouvé par sa clé, l'image du jour et celle de la nuit changées aux heures
 * de Paris (y compris au changement d'heure), le bouton « Relier mon compte », l'icône du serveur. Les images sont des
 * octets donnés par le test : les vraies arrivent avec D1.
 */
class BienvenueTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val day = byteArrayOf(1, 2, 3)
    private val night = byteArrayOf(4, 5, 6)
    private val icon = byteArrayOf(7, 8, 9)

    /** Midi à Paris (11 h UTC) en novembre. */
    private val noon = Instant.parse("2026-11-05T11:00:00Z")

    private val allImages: Map<String, ByteArray> = mapOf(
        "discord/bienvenue-jour.png" to day,
        "discord/bienvenue-nuit.png" to night,
        "discord/icone-serveur.png" to icon,
    )

    /** Un serveur, un temps et un stockage à part pour chaque test. */
    private inner class Setup(
        start: Instant = noon,
        images: Map<String, ByteArray> = allImages,
        withChannel: Boolean = true,
        rules: BannerRules = BannerRules(dayHour = 7, nightHour = 22),
        val messages: InMemoryBotMessageStore = InMemoryBotMessageStore(),
        val fake: FakeDiscordServer = FakeDiscordServer(),
    ) {
        val timer = FakeTimer(start)
        val category: String = runBlocking { fake.createCategory(CategorySpec("Infos", null)) }
        val channels: Map<String, String> = runBlocking {
            buildMap {
                if (withChannel) put("bienvenue", fake.createChannel(category, ChannelSpec("bienvenue", ChannelKind.TEXTE, null, emptySet(), null, false)))
                put("discussion", fake.createChannel(category, ChannelSpec("discussion", ChannelKind.TEXTE, null, null, null, false)))
                put("journal-du-bot", fake.createChannel(category, ChannelSpec("journal-du-bot", ChannelKind.TEXTE, null, emptySet(), null, false)))
            }
        }
        val context = BotContext(fake, ServerIds(emptyMap(), channels), timer, ServerPhase.ATTENTE)
        val feature = BienvenueFeature(context, rules, zone, messages, BotJournal(context)) { images[it] }

        val welcome: List<FakeDiscordServer.Posted> get() = fake.posted.filter { it.channelId == channels["bienvenue"] }
        val journal: List<String> get() = fake.posted.filter { it.channelId == channels["journal-du-bot"] }.map { it.message.text }

        fun start() = runBlocking { feature.start() }

        fun advance(duration: Duration) = runBlocking { timer.advance(duration) }

        /** [user] appuie sur le bouton [id] ; rend la réponse. */
        fun press(id: String, user: DiscordUser = DiscordUser("42", "Ana")): FakeReply {
            val reply = FakeReply()
            runBlocking { feature.handle(DiscordEvent.ButtonPressed(id, user, channels["bienvenue"], "1", reply)) }
            return reply
        }
    }

    @Test
    @DisplayName("Le message est posté dans #bienvenue avec l'image du jour, le mot d'accueil et deux boutons")
    fun welcomeIsPosted() {
        val setup = Setup()
        setup.start()

        val posted = setup.welcome.single()
        val message = posted.message
        assertEquals(
            "Bienvenue au QG de Silooo. Le jeu se construit sous tes yeux : les coulisses, les sondages et les giveaways sont ouverts, " +
                "le jeu arrive avec la bêta sur Minecraft Bedrock. Dis bonjour dans <#" +
                setup.channels.getValue("discussion") + ">.",
            message.text,
        )
        assertTrue(message.card)
        assertEquals("bienvenue-jour.png", message.image?.name)
        assertTrue(day.contentEquals(message.image!!.bytes))
        assertEquals(
            listOf(
                ButtonSpec("relier-mon-compte", "Relier mon compte", ButtonStyle.PRIMARY),
                ButtonSpec(WelcomeTexts.REMINDERS_BUTTON_ID, "Mes rappels"),
            ),
            message.buttons,
        )
        assertEquals(emptySet<String>(), message.mentionUserIds)
        assertEquals(BotMessageRef(posted.channelId, posted.messageId), setup.messages.refs["bienvenue"])
    }

    @Test
    @DisplayName("La nuit, l'image de la nuit")
    fun nightImageAtNight() {
        val setup = Setup(start = Instant.parse("2026-11-05T21:30:00Z"))
        setup.start()

        assertEquals("bienvenue-nuit.png", setup.welcome.single().message.image?.name)
        assertTrue(night.contentEquals(setup.welcome.single().message.image!!.bytes))
    }

    @Test
    @DisplayName("À 6 h 59 le message porte encore la nuit, à 7 h le jour")
    fun dayStartsAtSeven() {
        val before = Setup(start = Instant.parse("2026-11-05T05:59:00Z"))
        before.start()
        assertEquals("bienvenue-nuit.png", before.welcome.single().message.image?.name)

        val at = Setup(start = Instant.parse("2026-11-05T06:00:00Z"))
        at.start()
        assertEquals("bienvenue-jour.png", at.welcome.single().message.image?.name)
    }

    @Test
    @DisplayName("Un deuxième démarrage modifie le message au lieu de le reposter")
    fun restartEditsInPlace() {
        val first = Setup()
        first.start()
        val known = first.welcome.single()

        val again = BienvenueFeature(first.context, BannerRules(dayHour = 7, nightHour = 22), zone, first.messages, BotJournal(first.context)) { allImages[it] }
        runBlocking { again.start() }

        assertEquals(1, first.fake.posted.count { it.channelId == known.channelId })
        val edit = first.fake.edits.single()
        assertEquals(known.messageId, edit.messageId)
        assertEquals(known.channelId, edit.channelId)
        assertEquals(known.message.text, edit.message.text)
    }

    @Test
    @DisplayName("Si le message a disparu, il est reposté et noté de nouveau")
    fun vanishedMessageIsReposted() {
        val messages = InMemoryBotMessageStore()
        val setup = Setup(messages = messages)
        messages.refs["bienvenue"] = BotMessageRef(setup.channels.getValue("bienvenue"), "999")
        setup.start()

        val posted = setup.welcome.single()
        assertEquals(BotMessageRef(posted.channelId, posted.messageId), messages.refs["bienvenue"])
        assertTrue(setup.fake.edits.isEmpty())
    }

    @Test
    @DisplayName("L'image passe à la nuit à 22 h, heure de Paris, puis au jour à 7 h")
    fun bannerSwitchesAtTheHours() {
        val setup = Setup()
        setup.start()
        val known = setup.welcome.single()

        // midi à Paris : 22 h est dix heures plus tard (21 h UTC)
        setup.advance(Duration.ofHours(9).plusMinutes(59))
        assertTrue(setup.fake.edits.isEmpty())
        setup.advance(Duration.ofMinutes(1))
        assertEquals(1, setup.fake.edits.size)
        assertEquals(known.messageId, setup.fake.edits[0].messageId)
        assertEquals("bienvenue-nuit.png", setup.fake.edits[0].message.image?.name)
        assertEquals(known.message.buttons, setup.fake.edits[0].message.buttons)

        // 22 h à 7 h : neuf heures
        setup.advance(Duration.ofHours(8).plusMinutes(59))
        assertEquals(1, setup.fake.edits.size)
        setup.advance(Duration.ofMinutes(1))
        assertEquals(2, setup.fake.edits.size)
        assertEquals("bienvenue-jour.png", setup.fake.edits[1].message.image?.name)

        assertEquals(1, setup.timer.pending)
        assertEquals(1, setup.welcome.size)
    }

    @Test
    @DisplayName("Le changement d'heure d'octobre ne décale pas l'image : 7 h reste 7 h à Paris")
    fun summerTimeEndsWithoutShift() {
        // 22 h 30 à Paris le 24 octobre (heure d'été, UTC+2) ; 7 h le 25 est en heure d'hiver (UTC+1) : 9 h 30 plus tard
        val setup = Setup(start = Instant.parse("2026-10-24T20:30:00Z"))
        setup.start()
        assertEquals("bienvenue-nuit.png", setup.welcome.single().message.image?.name)

        setup.advance(Duration.ofHours(9).plusMinutes(29))
        assertTrue(setup.fake.edits.isEmpty())
        setup.advance(Duration.ofMinutes(1))
        assertEquals("bienvenue-jour.png", setup.fake.edits.single().message.image?.name)
    }

    @Test
    @DisplayName("Un jour qui passe par minuit : de 20 h à 6 h")
    fun dayAcrossMidnight() {
        val setup = Setup(start = Instant.parse("2026-11-05T23:30:00Z"), rules = BannerRules(dayHour = 20, nightHour = 6))
        setup.start()
        assertEquals("bienvenue-jour.png", setup.welcome.single().message.image?.name)

        // 0 h 30 à Paris : le jour se termine à 6 h
        setup.advance(Duration.ofHours(5).plusMinutes(29))
        assertTrue(setup.fake.edits.isEmpty())
        setup.advance(Duration.ofMinutes(1))
        assertEquals("bienvenue-nuit.png", setup.fake.edits.single().message.image?.name)
    }

    @Test
    @DisplayName("Un changement d'image refusé par Discord ne coupe pas la minuterie")
    fun failedSwitchKeepsTheTimer() {
        val setup = Setup()
        setup.start()
        // le message disparaît à la main : le changement d'image le reposte, la minuterie reste
        setup.fake.posted.clear()
        setup.advance(Duration.ofHours(10).plusMinutes(1))

        assertEquals(1, setup.welcome.size)
        assertEquals("bienvenue-nuit.png", setup.welcome.single().message.image?.name)
        assertEquals(1, setup.timer.pending)
    }

    @Test
    @DisplayName("Sans les images, le message part sans image et le journal le dit une seule fois")
    fun missingImagesAreReportedOnce() {
        val setup = Setup(images = emptyMap())
        setup.start()

        assertNull(setup.welcome.single().message.image)
        assertEquals(listOf("Image d'accueil absente : elle arrive avec D1."), setup.journal)

        setup.advance(Duration.ofHours(10).plusMinutes(1))
        assertNull(setup.fake.edits.single().message.image)
        assertEquals(listOf("Image d'accueil absente : elle arrive avec D1."), setup.journal)
        assertTrue(setup.fake.icons.isEmpty())
    }

    @Test
    @DisplayName("Une image vide compte comme une image absente")
    fun emptyImageIsMissing() {
        val setup = Setup(images = mapOf("discord/bienvenue-jour.png" to ByteArray(0)))
        setup.start()

        assertNull(setup.welcome.single().message.image)
        assertEquals(1, setup.journal.size)
    }

    @Test
    @DisplayName("Sans #bienvenue sur le serveur, rien n'est posé et rien n'est programmé")
    fun noWelcomeChannel() {
        val setup = Setup(withChannel = false)
        setup.start()

        assertTrue(setup.fake.posted.none { it.channelId != setup.channels["journal-du-bot"] })
        assertEquals(0, setup.timer.pending)
        assertNull(setup.messages.refs["bienvenue"])
    }

    @Test
    @DisplayName("« Relier mon compte » est laissé à la fonction de liaison, sans deuxième réponse")
    fun linkButtonIsLeftToLinking() {
        val setup = Setup()
        setup.start()
        val reply = setup.press("relier-mon-compte")

        assertTrue(reply.sent.isEmpty())
        assertTrue(reply.forms.isEmpty())
        assertEquals(0, reply.acknowledged)
    }

    @Test
    @DisplayName("« Mes rappels » reste à la fonction d'accueil : celle-ci ne répond pas deux fois")
    fun remindersButtonIsLeftToWelcome() {
        val setup = Setup()
        setup.start()

        for (id in listOf(WelcomeTexts.REMINDERS_BUTTON_ID, "autre-bouton", "ticket-fermer:3")) {
            val reply = setup.press(id)
            assertTrue(reply.sent.isEmpty(), id)
            assertEquals(0, reply.acknowledged, id)
        }
    }

    @Test
    @DisplayName("L'icône du serveur est posée depuis la ressource, avec une ligne au journal")
    fun iconIsSet() {
        val setup = Setup()
        setup.start()

        val set = setup.fake.icons.single()
        assertEquals("icone-serveur.png", set.name)
        assertTrue(icon.contentEquals(set.bytes))
        assertEquals(listOf("Icône du serveur posée."), setup.journal)
    }

    @Test
    @DisplayName("Icône déjà là : rien ne change et le journal ne dit rien")
    fun existingIconIsKept() {
        val fake = FakeDiscordServer().apply { iconPresent = true }
        val setup = Setup(fake = fake)
        setup.start()

        assertTrue(fake.icons.isEmpty())
        assertFalse(setup.journal.any { it.contains("Icône") })
        assertEquals(1, setup.welcome.size)
    }

    @Test
    @DisplayName("Un deuxième démarrage ne repose pas l'icône que le premier a posée")
    fun iconIsSetOnlyOnce() {
        val setup = Setup()
        setup.start()
        val again = BienvenueFeature(setup.context, BannerRules(dayHour = 7, nightHour = 22), zone, setup.messages, BotJournal(setup.context)) { allImages[it] }
        runBlocking { again.start() }

        assertEquals(1, setup.fake.icons.size)
        assertEquals(listOf("Icône du serveur posée."), setup.journal)
    }

    @Test
    @DisplayName("Sans la ressource, l'icône n'est pas touchée et le journal ne dit rien")
    fun noIconResource() {
        val setup = Setup(images = allImages - "discord/icone-serveur.png")
        setup.start()

        assertTrue(setup.fake.icons.isEmpty())
        assertFalse(setup.journal.any { it.contains("Icône") })
    }

    @Test
    @DisplayName("Un refus de Discord sur l'icône va au journal, le message est posé quand même")
    fun iconRefusalDoesNotStopTheMessage() {
        val fake = FakeDiscordServer().apply { refusedIcon = "Discord refuse l'image" }
        val setup = Setup(fake = fake)
        setup.start()

        assertEquals(listOf("L'icône du serveur n'a pas pu être posée : Discord refuse l'image."), setup.journal)
        assertEquals(1, setup.welcome.size)
        assertEquals(1, setup.timer.pending)
    }

    @Test
    @DisplayName("Sans #discussion sur le serveur, le texte porte son nom")
    fun textWithoutDiscussionChannel() {
        assertTrue(BienvenueTexts.text("#discussion").endsWith("Dis bonjour dans #discussion."))
        assertFalse(BienvenueTexts.text("#discussion").contains("!"))
    }

    @Test
    @DisplayName("Les heures du jour et de la nuit viennent de definitions/discord.json")
    fun hoursComeFromTheDefinitions() {
        assertEquals(BannerRules(dayHour = 7, nightHour = 22), DiscordDefinitions.load(TestDefinitions.directory).banner)
    }
}
