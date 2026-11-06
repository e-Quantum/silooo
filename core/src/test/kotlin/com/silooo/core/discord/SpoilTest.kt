package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les coulisses : le dépôt avec `/spoil`, la publication du premier, la ligne d'état, le seuil le jour et la nuit, les
 * refus. Le faux Discord ne compte que les humains : l'exclusion des bots est celle de `JdaServer.reactors`.
 */
class SpoilTest {
    private val rules = SpoilRules(memberShare = 0.4, refreshSeconds = 60, emoji = "silo", nightStartHour = 22, nightEndHour = 7)
    private val zone = ZoneId.of("Europe/Paris")

    /** Un serveur, un temps et des spoils à part pour chaque test ; [start] : l'heure de départ du temps du test. */
    private inner class Setup(start: Instant = Instant.parse("2026-11-05T10:00:00Z"), withChannel: Boolean = true) {
        val fake = FakeDiscordServer().also { it.members = 375 }
        var missingOnRead = false
        var missingOnEdit = false
        var editFailures = 0
        var readCalls = 0
        var editCalls = 0
        val server = object : DiscordServer by fake {
            override suspend fun reactors(channelId: String, messageId: String, emojiName: String): Set<String> {
                readCalls++
                if (missingOnRead) throw DiscordMessageMissing(channelId, messageId)
                return fake.reactors(channelId, messageId, emojiName)
            }
            override suspend fun edit(channelId: String, messageId: String, message: BotMessage) {
                editCalls++
                if (missingOnEdit) throw DiscordMessageMissing(channelId, messageId)
                if (editFailures > 0) {
                    editFailures--
                    throw DiscordRefusal("Discord temporairement indisponible")
                }
                fake.edit(channelId, messageId, message)
            }
        }
        val timer = FakeTimer(start)
        val store = InMemorySpoilStore()
        val staff: String = runBlocking { fake.createRole(RoleSpec("staff", null, false, false)) }
        val role: String = runBlocking { fake.createRole(RoleSpec("coulisses", null, false, true)) }
        val channel: String? = if (withChannel) {
            runBlocking {
                val category = fake.createCategory(CategorySpec("Le serveur en direct", null))
                fake.createChannel(category, ChannelSpec("coulisses", ChannelKind.TEXTE, null, emptySet(), null, false))
            }
        } else {
            null
        }
        val ids = ServerIds(mapOf("staff" to staff, "coulisses" to role), if (channel == null) emptyMap() else mapOf("coulisses" to channel))
        val feature = SpoilFeature(BotContext(server, ids, timer, ServerPhase.ATTENTE), rules, zone, store)

        fun start() = runBlocking { feature.start() }

        fun advance(duration: Duration) = runBlocking { timer.advance(duration) }

        /** Un `/spoil` du staff (ou de [roles]) ; rend la réponse. */
        fun spoil(
            text: String,
            threshold: String? = null,
            image: ImageFile? = null,
            refusedFile: String? = null,
            roles: Set<String> = setOf(staff),
        ): FakeReply {
            val options = listOfNotNull(
                "texte" to text,
                threshold?.let { "seuil" to it },
                refusedFile?.let { "fichier-refuse" to it },
            ).toMap()
            val files = if (image == null) emptyMap() else mapOf("image" to image)
            val reply = FakeReply()
            runBlocking { feature.handle(DiscordEvent.CommandUsed("spoil", options, DiscordUser("42", "Kr", roleIds = roles), null, reply, files)) }
            return reply
        }

        /** Les membres [users] ont réagi avec `:silo:` au spoil [number]. */
        fun react(number: Int, vararg users: String) {
            val messageId = checkNotNull(store.spoils.first { it.number == number }.messageId)
            fake.reactors[Triple(checkNotNull(channel), messageId, "silo")] = users.toMutableSet()
        }

        fun spoilWithId(number: Int): Spoil = store.spoils.first { it.number == number }
    }

    private fun answer(reply: FakeReply): String = reply.sent.single().first.text

    private fun header(setup: Setup, number: Int): String = "<@&" + setup.role + "> Spoil n°" + number

    @Test
    fun missingMessagePausesTheQueueAfterRestart() {
        val setup = Setup()
        setup.spoil("Un.", threshold = "1")
        setup.spoil("Deux.")
        val timer = FakeTimer(setup.timer.now())
        val restarted = SpoilFeature(BotContext(setup.server, setup.ids, timer, ServerPhase.ATTENTE), rules, zone, setup.store)
        setup.missingOnRead = true
        runBlocking { restarted.start(); timer.advance(Duration.ofMinutes(5)) }
        assertEquals(1, setup.readCalls)
        assertEquals(1, setup.fake.posted.size)
        assertNull(setup.spoilWithId(2).messageId)
        assertEquals(0, setup.spoilWithId(1).reactions)
    }

    @Test
    fun missingOnEditPausesWithoutRetryingOrRepublishing() {
        val setup = Setup()
        setup.start()
        setup.spoil("Un.", threshold = "1")
        setup.missingOnEdit = true
        setup.spoil("Deux.")
        val edits = setup.editCalls
        setup.react(1, "1")
        setup.advance(Duration.ofMinutes(5))
        setup.spoil("Trois.")
        assertEquals(edits, setup.editCalls)
        assertEquals(0, setup.readCalls)
        assertEquals(1, setup.fake.posted.size)
        assertNull(setup.spoilWithId(2).messageId)
        assertNull(setup.spoilWithId(3).messageId)
    }

    @Test
    fun removalCancelsMorningAndKeepsPendingSpoil() {
        val setup = Setup(start = Instant.parse("2026-11-05T21:30:30Z"))
        setup.start()
        setup.spoil("Un.", threshold = "1")
        setup.spoil("Deux.")
        setup.react(1, "1")
        setup.advance(Duration.ofMinutes(1))
        assertEquals(2, setup.timer.pending)
        val event = DiscordEvent.MessageRemoved(checkNotNull(setup.channel), checkNotNull(setup.spoilWithId(1).messageId))
        runBlocking { setup.feature.handle(event); setup.feature.handle(event) }
        assertEquals(1, setup.timer.pending)
        setup.advance(Duration.ofHours(12))
        assertEquals(1, setup.fake.posted.size)
        assertNull(setup.spoilWithId(2).messageId)
    }

    @Test
    fun missingAtThresholdStopsPublicationAndAlertsStaffOnce() {
        val setup = Setup()
        setup.spoil("Un.", threshold = "1")
        setup.spoil("Deux.")
        val journal = runBlocking {
            val category = setup.fake.createCategory(CategorySpec("staff", emptySet()))
            setup.fake.createChannel(category, ChannelSpec("journal", ChannelKind.TEXTE, null, emptySet(), emptySet(), false))
        }
        val ids = ServerIds(emptyMap(), mapOf("coulisses" to checkNotNull(setup.channel), ServerPlan.JOURNAL_ID to journal))
        val timer = FakeTimer(setup.timer.now())
        val feature = SpoilFeature(BotContext(setup.server, ids, timer, ServerPhase.ATTENTE), rules, zone, setup.store)
        setup.react(1, "1")
        setup.missingOnEdit = true
        runBlocking { feature.start(); timer.advance(Duration.ofMinutes(5)) }
        val alerts = setup.fake.posted.filter { it.channelId == journal }
        assertEquals(1, alerts.size)
        assertTrue(alerts.single().message.text.contains(checkNotNull(setup.spoilWithId(1).messageId)))
        assertTrue(alerts.single().message.text.contains(checkNotNull(setup.channel)))
        assertEquals(1, setup.fake.posted.count { it.channelId == setup.channel })
        assertNull(setup.spoilWithId(2).messageId)
        assertEquals(1, setup.readCalls)
        assertEquals(0, setup.spoilWithId(1).reactions)
    }

    @Test
    fun temporaryEditFailureCanBeRetried() {
        val setup = Setup()
        setup.start()
        setup.spoil("Un.")
        setup.spoil("Deux.")
        setup.react(1, "1")
        setup.editFailures = 1
        org.junit.jupiter.api.Assertions.assertThrows(DiscordRefusal::class.java) { setup.advance(Duration.ofMinutes(1)) }
        val attempts = setup.editCalls
        setup.advance(Duration.ofMinutes(1))
        assertEquals(attempts + 1, setup.editCalls)
        assertTrue(setup.fake.edits.last().message.text.endsWith(": 1."))
    }

    @Test
    @DisplayName("Le premier dépôt publie tout de suite, avec le rôle Coulisses, l'image et la ligne « pas encore déposé »")
    fun firstDepositPublishesRightAway() {
        val setup = Setup()
        setup.start()
        val image = ImageFile("chantier.png", byteArrayOf(1, 2, 3))
        val reply = setup.spoil("Le silo prend forme.", image = image)

        val post = setup.fake.posted.single()
        assertEquals(setup.channel, post.channelId)
        assertEquals(header(setup, 1) + "\n\nLe silo prend forme.\n\nSpoil n°2 : pas encore déposé.", post.message.text)
        assertEquals(setOf(setup.role), post.message.mentionRoleIds)
        assertSame(image, post.message.image)
        assertEquals("Spoil n°1 publié dans <#" + setup.channel + ">. Seuil du n°2 : 150 réactions.", answer(reply))
        assertTrue(reply.sent.single().second, "la réponse est pour le staff seul")
        val stored = setup.spoilWithId(1)
        assertEquals(post.messageId, stored.messageId)
        assertEquals(150, stored.nextThreshold)
        assertEquals("42", stored.createdBy)
        assertTrue(setup.fake.edits.isEmpty())
    }

    @Test
    @DisplayName("Le premier dépôt part même la nuit")
    fun firstDepositPublishesAtNight() {
        val setup = Setup(start = Instant.parse("2026-11-05T22:30:00Z"))
        setup.start()
        setup.spoil("Une nuit de chantier.")
        assertEquals(1, setup.fake.posted.size)
    }

    @Test
    @DisplayName("Le second dépôt attend : la réponse donne le seuil, le compte et les derniers, et la ligne d'état le dit")
    fun secondDepositWaits() {
        val setup = Setup()
        setup.start()
        setup.spoil("Le premier.")
        setup.react(1, "1", "2", "3")
        setup.advance(Duration.ofSeconds(60))
        assertEquals(3, setup.spoilWithId(1).reactions)

        val reply = setup.spoil("Le second.", threshold = "200")
        assertEquals(
            "Spoil n°2 déposé : il partira quand le n°1 aura 150 réactions (3 pour l'instant). Les derniers : n°1 3 réactions.",
            answer(reply),
        )
        assertEquals(1, setup.fake.posted.size)
        assertEquals(200, setup.spoilWithId(2).nextThreshold)
        assertNull(setup.spoilWithId(2).messageId)
        assertEquals(header(setup, 1) + "\n\nLe premier.\n\nSpoil n°2 à 150 réactions : 3.", setup.fake.edits.last().message.text)
    }

    @Test
    @DisplayName("La réponse nomme les trois derniers publiés, du plus récent au plus ancien, et le singulier de « réaction »")
    fun answerListsTheLastThree() {
        val setup = Setup()
        setup.start()
        setup.spoil("Un.", threshold = "2")
        setup.spoil("Deux.", threshold = "2")
        setup.spoil("Trois.", threshold = "2")
        setup.react(1, "1", "2")
        setup.advance(Duration.ofSeconds(60))
        setup.react(2, "1", "2", "3")
        setup.advance(Duration.ofSeconds(60))
        assertEquals(3, setup.fake.posted.size)

        val reply = setup.spoil("Quatre.", threshold = "1")
        assertEquals(
            "Spoil n°4 déposé : il partira quand le n°3 aura 2 réactions (0 pour l'instant). Les derniers : n°3 0 réaction, n°2 3, n°1 2.",
            answer(reply),
        )
    }

    @Test
    @DisplayName("Un spoil déposé derrière deux autres dit qu'il part après le précédent")
    fun farDepositNamesItsPredecessor() {
        val setup = Setup()
        setup.start()
        setup.spoil("Un.")
        setup.spoil("Deux.")
        val reply = setup.spoil("Trois.")
        assertEquals("Spoil n°3 déposé : il partira après le n°2. Les derniers : n°1 0 réaction.", answer(reply))
    }

    @Test
    @DisplayName("La ligne d'état n'est modifiée que quand le compte change, et l'image reste jointe")
    fun lineChangesOnlyWhenTheCountChanges() {
        val setup = Setup()
        setup.start()
        val image = ImageFile("chantier.gif", byteArrayOf(9, 9))
        setup.spoil("Le premier.", image = image)
        setup.spoil("Le second.")
        val afterDeposit = setup.fake.edits.size
        assertEquals(header(setup, 1) + "\n\nLe premier.\n\nSpoil n°2 à 150 réactions : 0.", setup.fake.edits.last().message.text)

        setup.advance(Duration.ofMinutes(5))
        assertEquals(afterDeposit, setup.fake.edits.size, "le compte n'a pas bougé : aucune modification")

        setup.react(1, "1", "2", "3")
        setup.advance(Duration.ofSeconds(60))
        assertEquals(afterDeposit + 1, setup.fake.edits.size)
        assertEquals(header(setup, 1) + "\n\nLe premier.\n\nSpoil n°2 à 150 réactions : 3.", setup.fake.edits.last().message.text)
        assertSame(image, setup.fake.edits.last().message.image)
        assertTrue(setup.fake.edits.last().message.mentionRoleIds.isEmpty(), "une modification ne prévient personne")
        assertEquals(setup.fake.posted[0].messageId, setup.fake.edits.last().messageId)

        setup.advance(Duration.ofMinutes(3))
        assertEquals(afterDeposit + 1, setup.fake.edits.size)

        setup.react(1, "1", "2", "3", "4")
        setup.advance(Duration.ofSeconds(60))
        assertEquals(afterDeposit + 2, setup.fake.edits.size)
        assertEquals(4, setup.spoilWithId(1).reactions)
    }

    @Test
    @DisplayName("Au seuil, le jour, le suivant part, l'ancien perd sa ligne et la ligne suit le nouveau")
    fun thresholdByDayPublishesTheNext() {
        val setup = Setup()
        setup.start()
        setup.spoil("Le premier.")
        setup.spoil("Le second.", threshold = "10")
        setup.spoil("Le troisième.")
        setup.react(1, *Array(149) { it.toString() })
        setup.advance(Duration.ofSeconds(60))
        assertEquals(1, setup.fake.posted.size)

        setup.react(1, *Array(150) { it.toString() })
        setup.advance(Duration.ofSeconds(60))
        assertEquals(2, setup.fake.posted.size)
        val second = setup.fake.posted[1]
        assertEquals(header(setup, 2) + "\n\nLe second.\n\nSpoil n°3 à 10 réactions : 0.", setup.fake.edits.last { it.messageId == second.messageId }.message.text)
        assertEquals(setOf(setup.role), second.message.mentionRoleIds)
        assertEquals(header(setup, 1) + "\n\nLe premier.", setup.fake.edits.last { it.messageId == setup.fake.posted[0].messageId }.message.text)
        assertEquals(150, setup.spoilWithId(1).reactions)
        assertEquals(second.messageId, setup.spoilWithId(2).messageId)

        setup.react(2, "1")
        setup.advance(Duration.ofSeconds(60))
        assertEquals(header(setup, 2) + "\n\nLe second.\n\nSpoil n°3 à 10 réactions : 1.", setup.fake.edits.last().message.text)
    }

    @Test
    @DisplayName("Au seuil, la nuit, le suivant attend 7 h, la ligne le dit, et la publication n'est programmée qu'une fois")
    fun thresholdByNightWaitsForTheMorning() {
        // 22 h 30 min 30 s à Paris : les tics tombent à 30 s, la publication de 7 h pile passe avant le tic suivant.
        val setup = Setup(start = Instant.parse("2026-11-05T21:30:30Z"))
        setup.start()
        setup.spoil("Le premier.", threshold = "2")
        setup.spoil("Le second.")
        assertEquals(1, setup.timer.pending)

        setup.react(1, "1", "2", "3")
        setup.advance(Duration.ofSeconds(60))
        assertEquals(1, setup.fake.posted.size)
        assertEquals(header(setup, 1) + "\n\nLe premier.\n\nSpoil n°2 à 2 réactions : 3. Il part à 7 h.", setup.fake.edits.last().message.text)
        assertEquals(2, setup.timer.pending)

        setup.advance(Duration.ofMinutes(10))
        val edits = setup.fake.edits.size
        assertEquals(2, setup.timer.pending, "une seule publication programmée")

        setup.advance(Duration.ofHours(8) + Duration.ofMinutes(18))
        assertEquals(1, setup.fake.posted.size, "il est 6 h 59 : il attend encore")
        setup.advance(Duration.ofSeconds(30))
        assertEquals(2, setup.fake.posted.size, "7 h pile : il part")
        assertEquals(header(setup, 2) + "\n\nLe second.\n\nSpoil n°3 : pas encore déposé.", setup.fake.posted[1].message.text)
        assertTrue(setup.fake.edits.size > edits)
        assertEquals(header(setup, 1) + "\n\nLe premier.", setup.fake.edits.last().message.text)

        setup.advance(Duration.ofHours(1))
        assertEquals(2, setup.fake.posted.size)
        assertEquals(1, setup.timer.pending)
    }

    @Test
    @DisplayName("Au petit matin, avant 7 h, le suivant part le jour même à 7 h")
    fun earlyMorningWaitsForTheSameDay() {
        val setup = Setup(start = Instant.parse("2026-11-05T03:00:30Z")) // 4 h 00 min 30 s à Paris
        setup.start()
        setup.spoil("Le premier.", threshold = "1")
        setup.spoil("Le second.")
        setup.react(1, "1")
        setup.advance(Duration.ofSeconds(60))
        assertEquals(1, setup.fake.posted.size)
        setup.advance(Duration.ofHours(2) + Duration.ofMinutes(58))
        assertEquals(1, setup.fake.posted.size, "6 h 59")
        setup.advance(Duration.ofSeconds(60))
        assertEquals(2, setup.fake.posted.size)
    }

    @Test
    @DisplayName("Rien de déposé : le bot attend le dépôt, sans rien écrire")
    fun waitsForTheDeposit() {
        val setup = Setup()
        setup.start()
        setup.advance(Duration.ofMinutes(10))
        assertTrue(setup.fake.posted.isEmpty() && setup.fake.edits.isEmpty())
        assertNull(runBlocking { setup.store.current() })
    }

    @Test
    @DisplayName("Le seuil proposé est 40 % des membres, au moins un ; un seuil donné passe tel quel")
    fun defaultThreshold() {
        val big = Setup()
        big.spoil("Un.")
        assertEquals(150, big.spoilWithId(1).nextThreshold)
        big.spoil("Deux.", threshold = " 12 ")
        assertEquals(12, big.spoilWithId(2).nextThreshold)
        for ((members, expected) in listOf(0 to 1, 1 to 1, 2 to 1, 3 to 1, 4 to 2, 10 to 4, 1204 to 482)) {
            val setup = Setup()
            setup.fake.members = members
            setup.spoil("Texte.")
            assertEquals(expected, setup.spoilWithId(1).nextThreshold, members.toString() + " membres")
        }
    }

    @Test
    @DisplayName("Un seuil qui n'est pas un entier positif, un texte trop long, un fichier refusé ou un membre hors du staff : rien d'écrit")
    fun refusals() {
        val setup = Setup()
        setup.start()
        for (threshold in listOf("abc", "0", "-3", "1,5", "99999999999")) {
            val reply = setup.spoil("Texte.", threshold = threshold)
            assertEquals(SpoilTexts.BAD_THRESHOLD, answer(reply), threshold)
            assertTrue(reply.sent.single().second)
        }
        val tooLong = setup.spoil("a".repeat(1801))
        assertEquals("Le texte fait 1 801 caractères : 1 800 au plus. Rien n'est déposé.", answer(tooLong))
        assertEquals("Le texte du spoil est vide : rien n'est déposé.", answer(setup.spoil("   ")))
        val file = setup.spoil("Texte.", refusedFile = "plan.pdf")
        assertEquals("Le fichier « plan.pdf » est refusé : png ou gif, 16 Mo au plus. Rien n'est déposé.", answer(file))
        val member = setup.spoil("Texte.", roles = emptySet())
        assertEquals("Cette commande est pour le staff.", answer(member))
        assertTrue(member.sent.single().second)

        assertTrue(setup.store.spoils.isEmpty(), "rien d'écrit")
        assertTrue(setup.fake.posted.isEmpty())

        val exactly = setup.spoil("a".repeat(1800))
        assertTrue(answer(exactly).startsWith("Spoil n°1 publié"), "1 800 caractères passent")
        assertTrue(setup.fake.posted.single().message.text.length <= BotMessage.MAX_TEXT)
    }

    @Test
    @DisplayName("Un refus de Discord à la lecture des réactions garde le dernier compte, sans modifier le message")
    fun refusalKeepsTheLastCount() {
        val setup = Setup()
        setup.start()
        setup.spoil("Le premier.")
        setup.spoil("Le second.")
        setup.react(1, "1", "2")
        setup.advance(Duration.ofSeconds(60))
        assertEquals(2, setup.spoilWithId(1).reactions)
        val edits = setup.fake.edits.size

        setup.fake.refusedReactors = "Discord est injoignable"
        setup.advance(Duration.ofMinutes(3))
        assertEquals(2, setup.spoilWithId(1).reactions)
        assertEquals(edits, setup.fake.edits.size)

        setup.fake.refusedReactors = null
        setup.react(1, "1", "2", "3")
        setup.advance(Duration.ofSeconds(60))
        assertEquals(3, setup.spoilWithId(1).reactions)
    }

    @Test
    @DisplayName("Un redémarrage ne perd rien : le compte est en base, le tic relit Discord et publie au seuil")
    fun restartLosesNothing() {
        val setup = Setup()
        setup.start()
        setup.spoil("Le premier.", threshold = "3")
        setup.spoil("Le second.")
        setup.react(1, "1", "2")
        setup.advance(Duration.ofSeconds(60))
        assertEquals(2, setup.spoilWithId(1).reactions)

        // Le bot redémarre : une autre instance, d'autres minuteries, les mêmes spoils en base et le même Discord.
        val timer = FakeTimer(setup.timer.now())
        val restarted = SpoilFeature(BotContext(setup.fake, setup.ids, timer, ServerPhase.ATTENTE), rules, zone, setup.store)
        runBlocking { restarted.start() }
        setup.react(1, "1", "2", "3")
        runBlocking { timer.advance(Duration.ofSeconds(120)) }
        assertEquals(2, setup.fake.posted.size, "le second part une seule fois, même après deux tics")
        assertEquals(3, setup.spoilWithId(1).reactions)
        assertEquals(header(setup, 2) + "\n\nLe second.\n\nSpoil n°3 : pas encore déposé.", setup.fake.posted[1].message.text)
    }

    @Test
    @DisplayName("Sans salon coulisses sur le serveur, la fonction ne programme rien et refuse le dépôt")
    fun withoutChannelNothingHappens() {
        val setup = Setup(withChannel = false)
        setup.start()
        assertEquals(0, setup.timer.pending)
        val reply = setup.spoil("Texte.")
        assertEquals(SpoilTexts.NO_CHANNEL, answer(reply))
        assertTrue(setup.store.spoils.isEmpty() && setup.fake.posted.isEmpty())
    }

    @Test
    @DisplayName("La commande /spoil : un texte obligatoire, une image en fichier, un seuil facultatif, réservée au staff")
    fun declaresTheCommand() {
        val command = Setup().feature.commands.single()
        assertEquals("spoil", command.name)
        assertTrue(command.staffOnly)
        assertEquals(listOf("texte", "image", "seuil"), command.options.map { it.name })
        assertEquals(listOf(true, false, false), command.options.map { it.required })
        assertEquals(listOf(OptionKind.TEXTE, OptionKind.FICHIER, OptionKind.TEXTE), command.options.map { it.kind })
    }
}
