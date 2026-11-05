package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Le bonjour d'Oooscar et le tuto : le délai par plateforme, le message privé et son secours, les arrivées groupées, les départs. */
class WelcomeTest {
    private val rules = WelcomeRules(HelloSeconds(console = 10, telephone = 7, pc = 5), groupSeconds = 30, groupMentionsMax = 10)
    private val fake = FakeDiscordServer()
    private val timer = FakeTimer()
    private val store = InMemoryMemberStore()

    private class Setup(val ids: ServerIds, val discussion: String, val rejoindre: String)

    private val setup = runBlocking {
        val roles = WelcomeFeature.PLATFORMS.associateWith { fake.createRole(RoleSpec(it, null, false, false)) }
        val category = fake.createCategory(CategorySpec("Accueil", null))
        val discussion = fake.createChannel(category, ChannelSpec("discussion", ChannelKind.TEXTE, null, null, null, false))
        val rejoindre = fake.createChannel(category, ChannelSpec("rejoindre", ChannelKind.TEXTE, null, emptySet(), null, false))
        Setup(ServerIds(roles, mapOf("discussion" to discussion, "rejoindre" to rejoindre)), discussion, rejoindre)
    }
    private val ids = setup.ids
    private val discussion = setup.discussion
    private val rejoindre = setup.rejoindre

    init {
        fake.members = 1204
    }

    private val feature = WelcomeFeature(BotContext(fake, ids, timer, ServerPhase.ATTENTE), rules, store)

    private fun user(id: String, platform: String?) = DiscordUser(id, "membre $id", roleIds = setOfNotNull(platform?.let(ids::role)))

    private fun arrive(id: String, platform: String?) = runBlocking {
        feature.handle(DiscordEvent.MemberJoined(user(id, null), timer.now()))
        feature.handle(DiscordEvent.OnboardingCompleted(user(id, platform), timer.now()))
    }

    private fun advance(seconds: Long) = runBlocking { timer.advance(Duration.ofSeconds(seconds)) }

    @Test
    @DisplayName("Sur console, le bonjour part 10 s après l'accueil, mentionne le membre, et le tuto arrive en privé")
    fun greetsAfterTheDelay() {
        val start = timer.now()
        arrive("7", "console")
        advance(9)
        assertTrue(fake.posted.isEmpty() && fake.directs.isEmpty())
        advance(1)
        val hello = fake.posted.single()
        assertEquals(discussion, hello.channelId)
        assertEquals("Bienvenue <@7>, tu es le 1 204e entrepreneur. Sur console, tout est dans <#" + rejoindre + ">.", hello.message.text)
        assertEquals(setOf("7"), hello.message.mentionUserIds)
        assertTrue(hello.message.buttons.isEmpty())
        val (to, tutorial) = fake.directs.single()
        assertEquals("7", to)
        assertTrue(tutorial.text.startsWith("Ton tuto Silooo, en trois étapes.\n1. Sur ta console, ajoute HubyOnMC en ami"), tutorial.text)
        assertTrue(tutorial.text.contains("3. Dis bonjour dans <#" + discussion + ">"))
        assertEquals(listOf(WelcomeTexts.REMINDERS_BUTTON_ID), tutorial.buttons.map { it.id })
        val record = checkNotNull(store.records["7"])
        assertEquals(start, record.firstJoinedAt)
        assertEquals("console", record.platform)
        assertEquals(start + Duration.ofSeconds(10), record.greetedAt)
        assertEquals(start + Duration.ofSeconds(10), record.tutorialAt)
        assertFalse(record.tutorialRefused)
    }

    @Test
    @DisplayName("Sur PC, 5 s ; sans rôle de plateforme, le plus long des délais et un tuto pour toutes les plateformes")
    fun delaysFollowThePlatform() {
        arrive("1", "pc")
        arrive("2", null)
        advance(5)
        assertEquals(1, fake.posted.size)
        assertTrue(fake.posted[0].message.text.contains("Sur PC, tout est dans"))
        advance(30) // prêt à 10 s, 5 s après le premier bonjour : il attend la fenêtre de 30 s
        assertEquals(2, fake.posted.size)
        assertEquals("Bienvenue <@2>, tu es le 1 204e entrepreneur. Tout est dans <#" + rejoindre + ">.", fake.posted[1].message.text)
        assertTrue(fake.directs[1].second.text.contains("Sur console, ajoute HubyOnMC en ami et rejoins sa partie. Sur téléphone et PC"))
    }

    @Test
    @DisplayName("Messages privés fermés : le bouton « Mon tuto » sous le bonjour montre le tuto au membre seul")
    fun offersTheTutorialButtonWhenDirectsAreClosed() {
        fake.closedDirects += "9"
        arrive("9", "telephone")
        advance(7)
        assertTrue(fake.directs.isEmpty())
        val hello = fake.posted.single()
        assertEquals(listOf(WelcomeTexts.TUTORIAL_BUTTON_LABEL), hello.message.buttons.map { it.label })
        assertTrue(checkNotNull(store.records["9"]).tutorialRefused)

        val reply = FakeReply()
        runBlocking { feature.handle(DiscordEvent.ButtonPressed(WelcomeTexts.TUTORIAL_BUTTON_ID, user("9", "telephone"), discussion, hello.messageId, reply)) }
        val (message, onlyToUser) = reply.sent.single()
        assertTrue(onlyToUser)
        assertTrue(message.text.contains("1. Dans Minecraft, onglet Serveurs, ajoute un serveur : adresse play.silooo.com, port 19142."), message.text)

        val later = FakeReply()
        runBlocking { feature.handle(DiscordEvent.ButtonPressed(WelcomeTexts.REMINDERS_BUTTON_ID, user("9", "telephone"), null, "m", later)) }
        assertEquals(WelcomeTexts.REMINDERS_LATER, later.sent.single().first.text)
    }

    @Test
    @DisplayName("Trois arrivées en même temps : le premier bonjour part, les deux autres attendent 30 s et partent ensemble, mentionnés")
    fun groupsArrivals() {
        arrive("1", "pc")
        arrive("2", "pc")
        arrive("3", "pc")
        advance(5)
        assertEquals(1, fake.posted.size)
        assertEquals(3, fake.directs.size, "chaque tuto part tout de suite")
        advance(29)
        assertEquals(1, fake.posted.size)
        advance(1)
        assertEquals(2, fake.posted.size)
        val group = fake.posted[1].message
        assertEquals("Bienvenue <@2> et <@3>. Tout est dans <#" + rejoindre + ">.", group.text)
        assertEquals(setOf("2", "3"), group.mentionUserIds)
        assertEquals(timer.now(), store.records["3"]?.greetedAt)

        arrive("4", "pc")
        advance(5)
        assertEquals(2, fake.posted.size, "le dernier bonjour date de 5 s : le quatrième attend")
        advance(25)
        assertEquals("Bienvenue <@4>, tu es le 1 204e entrepreneur. Sur PC, tout est dans <#" + rejoindre + ">.", fake.posted[2].message.text)
    }

    @Test
    @DisplayName("Au-delà de dix en attente, une seule ligne sans mention")
    fun summarizesManyArrivals() {
        for (i in 1..12) arrive(i.toString(), "pc")
        advance(5)
        advance(30)
        assertEquals(2, fake.posted.size)
        val many = fake.posted[1].message
        assertEquals("11 nouveaux sont arrivés. Bienvenue à tous : tout est dans <#" + rejoindre + ">.", many.text)
        assertTrue(many.mentionUserIds.isEmpty())
        assertEquals(12, store.records.values.count { it.greetedAt != null })
    }

    @Test
    @DisplayName("Un membre parti avant son bonjour n'est pas salué ; un retour efface le départ et garde la première arrivée")
    fun forgetsWhoLeft() {
        val start = timer.now()
        arrive("5", "console")
        advance(3)
        runBlocking { feature.handle(DiscordEvent.MemberLeft("5", timer.now())) }
        advance(60)
        assertTrue(fake.posted.isEmpty())
        assertEquals(start + Duration.ofSeconds(3), store.records["5"]?.leftAt)

        runBlocking { feature.handle(DiscordEvent.MemberJoined(user("5", null), timer.now())) }
        val back = checkNotNull(store.records["5"])
        assertNull(back.leftAt)
        assertEquals(start, back.firstJoinedAt)
        assertEquals(start + Duration.ofSeconds(63), back.joinedAt)
    }

    @Test
    @DisplayName("Les bots et les gestes inconnus ne font rien ; les ordinaux et les milliers s'écrivent en français")
    fun ignoresBotsAndSpellsNumbers() {
        runBlocking {
            feature.handle(DiscordEvent.MemberJoined(DiscordUser("b", "robot", isBot = true), timer.now()))
            feature.handle(DiscordEvent.OnboardingCompleted(DiscordUser("b", "robot", isBot = true), timer.now()))
            feature.handle(DiscordEvent.ButtonPressed("autre", user("1", null), null, "m", FakeReply()))
        }
        advance(60)
        assertTrue(fake.posted.isEmpty() && store.records.isEmpty())
        assertEquals("1er", WelcomeTexts.ordinal(1))
        assertEquals("12e", WelcomeTexts.ordinal(12))
        assertEquals("1 204e", WelcomeTexts.ordinal(1204))
        assertEquals("1 000 000", WelcomeTexts.thousands(1_000_000))
    }

    @Test
    @DisplayName("Sans salon de discussion sur le serveur, rien ne part et rien ne lève")
    fun staysQuietWithoutTheChannel() {
        val quiet = WelcomeFeature(BotContext(fake, ServerIds.NONE, timer, ServerPhase.ATTENTE), rules, store)
        runBlocking { quiet.handle(DiscordEvent.OnboardingCompleted(user("1", null), Instant.EPOCH)) }
        advance(60)
        assertTrue(fake.posted.isEmpty())
        assertEquals(1, fake.directs.size, "le tuto part quand même, avec le nom du salon")
        assertTrue(fake.directs[0].second.text.contains("Dis bonjour dans #discussion."))
    }
}
