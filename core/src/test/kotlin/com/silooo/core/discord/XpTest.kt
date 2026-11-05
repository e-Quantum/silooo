package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * L'XP de tchat : les salons qui comptent, l'intervalle, les niveaux et leurs rôles, la suppression, `/niveau`, le
 * classement du lundi et le lundi manqué. Le tirage des points est celui de la graine du test ; les tests de niveau
 * fixent un seul tirage (50 points) pour des comptes exacts.
 */
class XpTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val report = ReportRules(hour = 9, linkedWithinDays = 7, returnedAfterDays = 7)
    private val wide = XpRules(
        pointsMin = 15,
        pointsMax = 25,
        intervalSeconds = 60,
        levelBase = 100,
        levelGrowth = 50,
        channels = listOf("discussion", "captures", "entreprises"),
        roles = listOf(LevelRole(5, "apprenti"), LevelRole(10, "ouvrier")),
        leaderboardSize = 10,
    )
    private val fixed = wide.copy(pointsMin = 50, pointsMax = 50)

    /** Le lundi qui ouvre la semaine du jeudi 5 novembre 2026, puis les deux suivants. */
    private val monday = LocalDate.of(2026, 11, 2)
    private val nextMonday = LocalDate.of(2026, 11, 9)
    private val afterNextMonday = LocalDate.of(2026, 11, 16)

    /** Un serveur, un temps et un registre à part pour chaque test ; [start] : l'heure de départ du temps du test. */
    private inner class Setup(
        start: Instant = Instant.parse("2026-11-05T10:00:00Z"),
        rules: XpRules = fixed,
        withDiscussion: Boolean = true,
        brokenRole: Boolean = false,
        random: Random = Random(1),
    ) {
        val fake = FakeDiscordServer()
        val timer = FakeTimer(start)
        val store = InMemoryXpStore()
        val apprenti: String = runBlocking { fake.createRole(RoleSpec("apprenti", null, false, false)) }
        val ouvrier: String = runBlocking { fake.createRole(RoleSpec("ouvrier", null, false, false)) }
        private val category = runBlocking { fake.createCategory(CategorySpec("Discussion", null)) }

        private fun channel(name: String, kind: ChannelKind = ChannelKind.TEXTE): String =
            runBlocking { fake.createChannel(category, ChannelSpec(name, kind, null, null, null, false)) }

        val discussion: String? = if (withDiscussion) channel("discussion") else null
        val captures = channel("captures")
        val gameChat = channel("tchat-du-jeu")
        val forum = channel("entreprises", ChannelKind.FORUM)
        private val ids = ServerIds(
            mapOf("apprenti" to if (brokenRole) "999" else apprenti, "ouvrier" to ouvrier),
            listOfNotNull(
                discussion?.let { "discussion" to it },
                "captures" to captures,
                "tchat-du-jeu" to gameChat,
                "entreprises" to forum,
            ).toMap(),
        )
        val feature = XpFeature(BotContext(fake, ids, timer, ServerPhase.ATTENTE), rules, report, zone, store, random)
        private var nextMessage = 1

        fun start() = runBlocking { feature.start() }

        fun advance(duration: Duration) = runBlocking { timer.advance(duration) }

        /** [user] écrit dans [channelId] (le salon de discussion par défaut) ; rend l'identifiant du message. */
        fun say(user: String, channelId: String? = discussion, bot: Boolean = false, parent: String? = null): String {
            val id = "m" + nextMessage++
            val author = DiscordUser(user, "Membre " + user, bot)
            runBlocking { feature.handle(DiscordEvent.MessagePosted(checkNotNull(channelId), id, author, "salut", timer.now(), parent)) }
            return id
        }

        fun remove(messageId: String) = runBlocking { feature.handle(DiscordEvent.MessageRemoved(discussion ?: captures, messageId)) }

        /** Un `/niveau` de [user] ; rend la réponse. */
        fun niveau(user: String): FakeReply {
            val reply = FakeReply()
            runBlocking { feature.handle(DiscordEvent.CommandUsed("niveau", emptyMap(), DiscordUser(user, "Membre " + user), null, reply)) }
            return reply
        }

        fun seed(user: String, points: Int, level: Int, week: Int = 0, weekStart: LocalDate = monday) {
            store.records[user] = XpRecord(user, points, level, week, weekStart, null)
        }

        fun record(user: String): XpRecord = checkNotNull(store.records[user]) { "pas de ligne pour " + user }

        fun roles(user: String): Set<String> = fake.memberRoles[user].orEmpty()
    }

    @Test
    @DisplayName("La commande niveau n'a pas d'option et n'est pas réservée au staff")
    fun exposesTheCommand() {
        val command = Setup().feature.commands.single()
        assertEquals("niveau", command.name)
        assertEquals("Montre ton niveau d'XP de tchat", command.description)
        assertTrue(command.options.isEmpty())
        assertFalse(command.staffOnly)
    }

    @Test
    @DisplayName("Un message compte entre le minimum et le maximum, avec le lundi de la semaine, et s'écrit pour être repris")
    fun countsBetweenMinAndMax() {
        val setup = Setup(rules = wide, random = Random(42))
        val ids = (1..30).map { "u" + it }
        val messages = ids.map { setup.say(it) }

        val points = ids.map { setup.record(it).points }
        assertTrue(points.all { it in 15..25 }, "tous les tirages sont entre 15 et 25 : " + points)
        assertTrue(points.toSet().size > 1, "le tirage varie")
        val first = setup.record("u1")
        assertEquals(first.points, first.weekPoints)
        assertEquals(0, first.level)
        assertEquals(monday, first.weekStart)
        assertEquals(setup.timer.now(), first.lastCountedAt)
        assertEquals(InMemoryXpStore.Counted("u1", first.points, setup.timer.now()), setup.store.messages[messages[0]])
    }

    @Test
    @DisplayName("Un second message dans la minute ne compte pas, un message à la minute pile compte")
    fun countsOncePerMinute() {
        val setup = Setup()
        setup.say("7")
        assertEquals(50, setup.record("7").points)

        setup.advance(Duration.ofSeconds(30))
        val tooSoon = setup.say("7")
        assertEquals(50, setup.record("7").points)
        assertFalse(tooSoon in setup.store.messages)

        setup.advance(Duration.ofSeconds(30))
        setup.say("7")
        assertEquals(100, setup.record("7").points)
        assertEquals(100, setup.record("7").weekPoints)

        setup.say("8")
        assertEquals(50, setup.record("8").points, "l'intervalle est par membre")
    }

    @Test
    @DisplayName("Les salons de la liste comptent, un fil de forum de la liste aussi ; le tchat du jeu et un salon inconnu ne comptent pas")
    fun countsOnlyListedChannels() {
        val setup = Setup()
        setup.say("1", setup.captures)
        setup.say("2", "5000", parent = setup.forum)
        assertEquals(50, setup.record("1").points)
        assertEquals(50, setup.record("2").points)

        setup.say("3", setup.gameChat)
        setup.say("4", "5001")
        setup.say("5", "5002", parent = setup.gameChat)
        assertNull(setup.store.records["3"], "le tchat du jeu ne compte pas")
        assertNull(setup.store.records["4"], "un salon hors liste ne compte pas")
        assertNull(setup.store.records["5"], "un fil d'un salon hors liste ne compte pas")
        assertTrue(setup.fake.posted.isEmpty())
    }

    @Test
    @DisplayName("Un bot ne compte pas, et un salon de la liste que le serveur n'a pas non plus")
    fun ignoresBots() {
        val setup = Setup(withDiscussion = false)
        setup.say("1", setup.captures, bot = true)
        assertNull(setup.store.records["1"])
        setup.say("2", "123")
        assertNull(setup.store.records["2"], "un salon que le serveur n'a pas ne compte pas")
        setup.say("3", setup.captures)
        assertEquals(50, setup.record("3").points)
    }

    @Test
    @DisplayName("Le niveau 5 donne le rôle d'apprenti et poste la ligne dans le salon, sans prévenir personne")
    fun levelFiveGivesTheRole() {
        val setup = Setup()
        setup.seed("7", points = 950, level = 4)
        setup.say("7")

        assertEquals(1000, setup.record("7").points)
        assertEquals(5, setup.record("7").level)
        assertEquals(setOf(setup.apprenti), setup.roles("7"))
        val post = setup.fake.posted.single()
        assertEquals(setup.discussion, post.channelId)
        assertEquals("<@7> passe au niveau 5.", post.message.text)
        assertTrue(post.message.mentionUserIds.isEmpty())
        assertTrue(post.message.mentionRoleIds.isEmpty())
    }

    @Test
    @DisplayName("Le niveau 10 donne le rôle d'ouvrier et retire celui d'apprenti : un seul rôle de niveau")
    fun levelTenSwapsTheRole() {
        val setup = Setup()
        runBlocking { setup.fake.giveRole("7", setup.apprenti) }
        setup.seed("7", points = 3200, level = 9)
        setup.say("7")

        assertEquals(10, setup.record("7").level)
        assertEquals(setOf(setup.ouvrier), setup.roles("7"))
        assertEquals("<@7> passe au niveau 10.", setup.fake.posted.single().message.text)
    }

    @Test
    @DisplayName("Un niveau sans rôle s'écrit sans rien poster ; le niveau suivant sans rôle ne touche pas au rôle porté")
    fun levelsWithoutRoleAreSilent() {
        val setup = Setup()
        setup.seed("7", points = 60, level = 0)
        setup.say("7")
        assertEquals(110, setup.record("7").points)
        assertEquals(1, setup.record("7").level)
        assertTrue(setup.fake.posted.isEmpty())
        assertTrue(setup.roles("7").isEmpty())

        runBlocking { setup.fake.giveRole("8", setup.apprenti) }
        setup.seed("8", points = 1300, level = 5)
        setup.say("8")
        assertEquals(6, setup.record("8").level, "1 350 points : le niveau 6")
        assertEquals(setOf(setup.apprenti), setup.roles("8"))
        assertTrue(setup.fake.posted.isEmpty())
    }

    @Test
    @DisplayName("Un refus de Discord sur le rôle n'annule ni les points ni le niveau, et la ligne part quand même")
    fun aRefusedRoleKeepsThePoints() {
        val setup = Setup(brokenRole = true)
        setup.seed("7", points = 950, level = 4)
        setup.say("7")

        assertEquals(1000, setup.record("7").points)
        assertEquals(5, setup.record("7").level)
        assertTrue(setup.roles("7").isEmpty())
        assertEquals("<@7> passe au niveau 5.", setup.fake.posted.single().message.text)
    }

    @Test
    @DisplayName("Un message supprimé perd ses points, une seule fois ; le niveau et le rôle restent")
    fun deletionTakesThePointsBack() {
        val setup = Setup()
        setup.seed("7", points = 950, level = 4, week = 200)
        val message = setup.say("7")
        assertEquals(1000, setup.record("7").points)
        assertEquals(setOf(setup.apprenti), setup.roles("7"))

        setup.remove(message)
        val after = setup.record("7")
        assertEquals(950, after.points)
        assertEquals(200, after.weekPoints)
        assertEquals(5, after.level, "le niveau franchi reste")
        assertEquals(setOf(setup.apprenti), setup.roles("7"), "le rôle reste")

        setup.remove(message)
        assertEquals(950, setup.record("7").points, "la seconde suppression ne reprend rien")
        setup.remove("inconnu")
        assertEquals(950, setup.record("7").points)
    }

    @Test
    @DisplayName("Les points repris ne passent jamais sous zéro")
    fun neverBelowZero() {
        val setup = Setup()
        val message = setup.say("7")
        setup.store.records["7"] = setup.record("7").copy(points = 10, weekPoints = 5)
        setup.remove(message)
        assertEquals(0, setup.record("7").points)
        assertEquals(0, setup.record("7").weekPoints)
    }

    @Test
    @DisplayName("/niveau répond au membre seul : son niveau, ses points, sa semaine et le prochain palier")
    fun niveauShowsTheLine() {
        val setup = Setup()
        setup.seed("7", points = 1800, level = 7, week = 180)
        val reply = setup.niveau("7")

        val (message, onlyToUser) = reply.sent.single()
        assertEquals("Niveau 7, 1 800 points, 180 cette semaine. Prochain niveau à 2 200 points.", message.text)
        assertTrue(onlyToUser)
    }

    @Test
    @DisplayName("/niveau sans ligne renvoie vers #discussion, sans toucher au registre")
    fun niveauWithoutPoints() {
        val setup = Setup()
        val reply = setup.niveau("7")

        assertEquals("Pas encore de points : écris dans <#" + setup.discussion + ">.", reply.sent.single().first.text)
        assertTrue(reply.sent.single().second)
        assertTrue(setup.store.records.isEmpty())
    }

    @Test
    @DisplayName("Au singulier : un point ne prend pas de s")
    fun singularPoint() {
        assertEquals("Niveau 0, 1 point, 1 cette semaine. Prochain niveau à 100 points.", XpTexts.yourLevel(0, 1, 1, 100))
        assertEquals("1. <@7>, 1 point", XpTexts.rankLine(1, "7", 1))
    }

    @Test
    @DisplayName("Le lundi à 9 h, les dix premiers sont publiés dans #discussion sans prévenir personne, puis la semaine repart de zéro")
    fun mondayPublishesTheTop() {
        val setup = Setup()
        val weeks = listOf(540, 480, 480, 400, 390, 300, 250, 200, 150, 100, 50, 0)
        weeks.forEachIndexed { index, week -> setup.seed((101 + index).toString(), points = 1000 + week, level = 4, week = week) }
        setup.start()
        setup.advance(Duration.ofDays(3).plusHours(21).plusMinutes(59))
        assertTrue(setup.fake.posted.isEmpty(), "rien avant 9 h le lundi")

        setup.advance(Duration.ofMinutes(1))
        val post = setup.fake.posted.single()
        assertEquals(setup.discussion, post.channelId)
        assertEquals(
            listOf(
                "Les dix de la semaine :",
                "1. <@101>, 540 points",
                "2. <@102>, 480 points",
                "3. <@103>, 480 points",
                "4. <@104>, 400 points",
                "5. <@105>, 390 points",
                "6. <@106>, 300 points",
                "7. <@107>, 250 points",
                "8. <@108>, 200 points",
                "9. <@109>, 150 points",
                "10. <@110>, 100 points",
            ).joinToString("\n"),
            post.message.text,
        )
        assertTrue(post.message.mentionUserIds.isEmpty())
        assertTrue(setup.store.records.values.all { it.weekPoints == 0 && it.weekStart == nextMonday })
        assertEquals(1540, setup.record("101").points, "le total ne bouge pas")
        assertEquals(1, setup.timer.pending, "le lundi suivant est déjà programmé")

        setup.advance(Duration.ofDays(7))
        assertEquals("Personne n'a écrit cette semaine.", setup.fake.posted.last().message.text)
        assertEquals(2, setup.fake.posted.size)
        assertTrue(setup.store.records.values.all { it.weekStart == afterNextMonday })
    }

    @Test
    @DisplayName("La semaine change à l'heure du rapport : le message du lundi matin entre dans le classement qui sort le même matin")
    fun theWeekChangesAtReportTime() {
        val setup = Setup(start = Instant.parse("2026-11-09T06:30:00Z"))
        setup.start()
        setup.say("1")
        assertEquals(monday, setup.record("1").weekStart, "7 h 30 le lundi : encore la semaine qui finit")

        setup.advance(Duration.ofHours(2))
        assertEquals("Les dix de la semaine :\n1. <@1>, 50 points", setup.fake.posted.single().message.text)
        assertEquals(nextMonday, setup.record("1").weekStart)
        setup.say("2")
        assertEquals(nextMonday, setup.record("2").weekStart, "9 h 30 le lundi : la nouvelle semaine")
        assertEquals(0, setup.record("1").weekPoints)
        assertEquals(50, setup.record("2").weekPoints)
    }

    @Test
    @DisplayName("Un bot éteint le lundi publie et remet à zéro dès son démarrage, puis garde le lundi suivant")
    fun startupAfterAMissedMonday() {
        val setup = Setup(start = Instant.parse("2026-11-09T09:00:00Z"))
        setup.seed("1", points = 700, level = 3, week = 300)
        setup.seed("2", points = 900, level = 4, week = 120)
        setup.start()

        assertEquals("Les dix de la semaine :\n1. <@1>, 300 points\n2. <@2>, 120 points", setup.fake.posted.single().message.text)
        assertTrue(setup.store.records.values.all { it.weekPoints == 0 && it.weekStart == nextMonday })
        assertEquals(1, setup.timer.pending)

        setup.start()
        assertEquals(1, setup.fake.posted.size, "un second démarrage ne rejoue pas le classement vidé")
    }

    @Test
    @DisplayName("Au démarrage, rien ne part quand la semaine est à jour, avant 9 h le lundi, ou sans aucune ligne")
    fun startupWithNothingToPublish() {
        val current = Setup()
        current.seed("1", points = 700, level = 3, week = 300)
        current.start()
        assertTrue(current.fake.posted.isEmpty())
        assertEquals(300, current.record("1").weekPoints)

        val early = Setup(start = Instant.parse("2026-11-09T07:00:00Z"))
        early.seed("1", points = 700, level = 3, week = 300)
        early.start()
        assertTrue(early.fake.posted.isEmpty(), "8 h le lundi : le rapport n'est pas passé")
        assertEquals(300, early.record("1").weekPoints)

        val empty = Setup(start = Instant.parse("2026-11-09T09:00:00Z"))
        empty.start()
        assertTrue(empty.fake.posted.isEmpty())
        assertEquals(1, empty.timer.pending)
    }

    @Test
    @DisplayName("Sans salon de discussion sur le serveur, rien n'est posté mais la semaine repart quand même de zéro")
    fun mondayWithoutDiscussion() {
        val setup = Setup(withDiscussion = false)
        setup.seed("1", points = 700, level = 3, week = 300)
        setup.start()
        setup.advance(Duration.ofDays(4))

        assertTrue(setup.fake.posted.isEmpty())
        assertEquals(0, setup.record("1").weekPoints)
        assertEquals(nextMonday, setup.record("1").weekStart)
    }

    @Test
    @DisplayName("Le classement tient dans un message de Discord, même pour une très longue liste")
    fun leaderboardFitsInOneMessage() {
        val setup = Setup(rules = fixed.copy(leaderboardSize = 400))
        (1..400).forEach { setup.seed("1234567890123456" + it.toString().padStart(3, '0'), points = 500 + it, level = 3, week = it) }
        setup.start()
        setup.advance(Duration.ofDays(4))

        val text = setup.fake.posted.single().message.text
        assertTrue(text.length <= BotMessage.MAX_TEXT)
        assertTrue(text.startsWith("Le classement de la semaine :\n1. <@1234567890123456400>, 400 points"))
        assertNotEquals("Personne n'a écrit cette semaine.", text)
    }
}
