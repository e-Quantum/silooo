package com.silooo.core.discord

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les tickets : le menu de `#tickets` (posté, retrouvé, édité), la question de jeu renvoyée vers l'aide, l'ouverture
 * d'un salon privé, la fermeture avec l'archive au journal, le ticket dont le salon a disparu. La base est un faux en
 * mémoire ; le SQL se teste sur MariaDB (`TicketSqlTest`, `BotMessageSqlTest`).
 */
class TicketTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val archiveRules = TicketArchiveRules(100, 8, 3, 30, 300, 30, 10, 4_000_000)

    /** 17 h 10 UTC : 18 h 10 à Paris en novembre. */
    private val at = Instant.parse("2026-11-05T17:10:00Z")

    /** Un serveur, un temps et des bases à part pour chaque test. */
    private inner class Setup(
        withTickets: Boolean = true,
        withCategory: Boolean = true,
        withStaffRoles: Boolean = true,
        refused: Set<String> = emptySet(),
    ) {
        val fake = FakeDiscordServer(refused = refused)
        val timer = FakeTimer()
        val store = InMemoryTicketStore()
        val archives = InMemoryTicketArchiveStore()
        val messages = InMemoryBotMessageStore()
        val staff: String = runBlocking { fake.createRole(RoleSpec("staff", null, false, false)) }
        val moderation: String = runBlocking { fake.createRole(RoleSpec("moderation", null, false, false)) }
        val category: String = runBlocking { fake.createCategory(CategorySpec("Aide", null)) }
        val channels: Map<String, String> = runBlocking {
            buildMap {
                if (withTickets) put("tickets", fake.createChannel(category, ChannelSpec("tickets", ChannelKind.TEXTE, null, emptySet(), null, false)))
                put("aide", fake.createChannel(category, ChannelSpec("aide", ChannelKind.FORUM, null, null, null, false)))
                put("journal-du-bot", fake.createChannel(category, ChannelSpec("journal-du-bot", ChannelKind.TEXTE, null, emptySet(), setOf(staff, moderation), false)))
            }
        }
        val ids = ServerIds(
            if (withStaffRoles) mapOf("staff" to staff, "moderation" to moderation) else emptyMap(),
            channels,
            if (withCategory) mapOf("aide" to category) else emptyMap(),
        )
        val context = BotContext(fake, ids, timer, ServerPhase.ATTENTE)
        val feature = TicketFeature(context, store, messages, BotJournal(context), zone, archives, archiveRules)

        val help: String get() = "<#" + channels.getValue("aide") + ">"
        val ticketsPosts: List<FakeDiscordServer.Posted> get() = fake.posted.filter { it.channelId == channels["tickets"] }
        val journal: List<String> get() = fake.posted.filter { it.channelId == channels["journal-du-bot"] }.map { it.message.text }
        val archiveFiles: List<String> get() = fake.posted.filter { it.channelId == channels["journal-du-bot"] }.mapNotNull { it.message.document?.bytes?.toString(Charsets.UTF_8) }

        fun start() = runBlocking { feature.start() }

        /** [user] choisit [value] dans le menu ; rend la réponse. */
        fun choose(value: String, user: DiscordUser = member, messageId: String = "1"): FakeReply {
            val reply = FakeReply()
            runBlocking {
                feature.handle(DiscordEvent.MenuChosen(TicketTexts.MENU_ID, listOf(value), user, channels["tickets"], messageId, reply))
            }
            return reply
        }

        /** [user] appuie sur « Fermer le ticket » du ticket [number] ; rend la réponse. */
        fun close(number: Int, user: DiscordUser = member): FakeReply {
            val reply = FakeReply()
            runBlocking {
                feature.handle(DiscordEvent.ButtonPressed(TicketTexts.CLOSE_PREFIX + number, user, ticket(number)?.channelId, "1", reply))
            }
            return reply
        }

        /** Ouvre un ticket « achat » pour le membre et rend l'identifiant de son salon. */
        fun openPurchase(user: DiscordUser = member): String {
            choose("achat", user)
            return checkNotNull(store.tickets.last { it.userId == user.id }.channelId.takeIf { it.isNotEmpty() })
        }

        fun say(channelId: String, authorId: String, authorName: String, text: String, time: Instant = at) {
            fake.histories.getOrPut(channelId) { mutableListOf() } += ChannelMessage(authorId, authorName, text, time)
        }

        /** La ligne du ticket [id], ouverte ou fermée. */
        fun ticket(id: Int): Ticket? = store.tickets.firstOrNull { it.id == id }

        /** Un membre du staff, avec le rôle Staff. */
        fun staffMember(): DiscordUser {
            fake.memberRoles["77"] = mutableSetOf(staff)
            return DiscordUser("77", "Kr", roleIds = setOf(staff))
        }
    }

    private val member = DiscordUser("42", "Ana")

    @Test
    @DisplayName("Au démarrage, le menu est posté dans #tickets avec ses cinq choix et noté sous sa clé")
    fun menuIsPosted() {
        val setup = Setup()
        setup.start()

        val post = setup.ticketsPosts.single()
        assertEquals(
            "Pour ce qui ne se dit pas en public. Choisis, et un salon privé s'ouvre entre toi et le staff. Une question de jeu se pose dans " + setup.help + ".",
            post.message.text,
        )
        val menu = checkNotNull(post.message.menu)
        assertEquals("tickets-menu", menu.id)
        assertEquals("Choisis ta demande", menu.placeholder)
        assertEquals(listOf("jeu", "achat", "compte", "sanction", "createurs"), menu.options.map { it.value })
        assertEquals(
            listOf("Une question de jeu", "Un achat ou des Gemmes", "Mon compte ou ma liaison", "Contester une sanction", "Créateurs"),
            menu.options.map { it.label },
        )
        assertEquals("Elle va dans le salon d'aide", menu.options.first().description)
        assertTrue(post.message.mentionUserIds.isEmpty() && post.message.mentionRoleIds.isEmpty())
        assertEquals(BotMessageRef(setup.channels.getValue("tickets"), post.messageId), setup.messages.refs["tickets-menu"])
        assertTrue(setup.fake.edits.isEmpty())
    }

    @Test
    @DisplayName("Après un redémarrage, le menu est retrouvé par sa clé et modifié sur place, pas reposté")
    fun menuIsEditedAfterARestart() {
        val setup = Setup()
        setup.start()
        val first = setup.ticketsPosts.single()

        val restarted = TicketFeature(setup.context, setup.store, setup.messages, BotJournal(setup.context), zone, setup.archives, archiveRules)
        runBlocking { restarted.start() }

        assertEquals(1, setup.ticketsPosts.size, "aucun nouveau message")
        val edit = setup.fake.edits.single()
        assertEquals(first.messageId, edit.messageId)
        assertEquals(first.channelId, edit.channelId)
        assertEquals(first.message, edit.message, "le texte suit le code")
        assertEquals(BotMessageRef(first.channelId, first.messageId), setup.messages.refs["tickets-menu"])
    }

    @Test
    @DisplayName("Si le message du menu a disparu ou est dans un autre salon, le menu est reposté et la clé suit")
    fun menuIsPostedAgainWhenTheMessageIsGone() {
        val gone = Setup()
        gone.messages.refs["tickets-menu"] = BotMessageRef(gone.channels.getValue("tickets"), "424242")
        gone.start()
        val post = gone.ticketsPosts.single()
        assertTrue(gone.fake.edits.isEmpty(), "l'édition a été refusée : le message n'est pas dans le faux Discord")
        assertEquals(BotMessageRef(post.channelId, post.messageId), gone.messages.refs["tickets-menu"])

        val moved = Setup()
        moved.messages.refs["tickets-menu"] = BotMessageRef(moved.channels.getValue("aide"), "424242")
        moved.start()
        assertEquals(1, moved.ticketsPosts.size)
        assertEquals(moved.ticketsPosts.single().messageId, moved.messages.refs.getValue("tickets-menu").messageId)
    }

    @Test
    @DisplayName("Sans salon #tickets, le bot ne poste rien ; les tickets ouverts sont quand même relus")
    fun noTicketsChannel() {
        val setup = Setup(withTickets = false)
        runBlocking { setup.store.open("5", "achat", "", at) }
        setup.start()
        assertTrue(setup.fake.posted.none { it.message.menu != null })
        assertNull(setup.messages.refs["tickets-menu"])
        assertEquals("bot", setup.ticket(1)?.closedBy)
        assertEquals(listOf("Ticket n°1 : salon supprimé à la main, fermé."), setup.journal)
    }

    @Test
    @DisplayName("Après chaque choix, le menu est remis à neuf sur place pour que le membre puisse choisir deux fois la même demande")
    fun menuIsResetAfterAChoice() {
        val setup = Setup()
        setup.start()
        val menu = setup.ticketsPosts.single()

        setup.choose("jeu", messageId = menu.messageId)
        setup.choose("achat", messageId = menu.messageId)

        assertEquals(2, setup.fake.edits.size)
        assertTrue(setup.fake.edits.all { it.channelId == menu.channelId && it.messageId == menu.messageId && it.message == menu.message })
        assertEquals(1, setup.ticketsPosts.size, "le menu n'est pas reposté")
    }

    @Test
    @DisplayName("Une question de jeu est renvoyée vers #aide, au membre seul : pas de ticket, pas de salon")
    fun gameQuestionGoesToHelp() {
        val setup = Setup()
        val reply = setup.choose("jeu")

        val (message, onlyToUser) = reply.sent.single()
        assertEquals(
            "Une question de jeu se pose dans " + setup.help + " : la réponse sert à tous. Si c'est privé, choisis un autre sujet.",
            message.text,
        )
        assertTrue(onlyToUser)
        assertTrue(setup.store.tickets.isEmpty())
        assertTrue(setup.fake.channels.none { it.name.startsWith("ticket-") })
        assertTrue(setup.journal.isEmpty())
    }

    @Test
    @DisplayName("Un achat ouvre un salon privé nommé ticket-achat-1 : sujet, droits du membre, du staff et de la modération, message avec le bouton, journal")
    fun openingATicket() {
        val setup = Setup()
        val reply = setup.choose("achat")

        val ticket = setup.store.tickets.single()
        assertEquals(Ticket(1, "42", "achat", ticket.channelId, setup.timer.now()), ticket)
        val channel = setup.fake.channels.single { it.id == ticket.channelId }
        assertEquals("ticket-achat-1", channel.name)
        assertEquals(ChannelKind.TEXTE, channel.kind)
        assertEquals(setup.category, channel.categoryId)
        assertEquals("Ticket n°1 de Ana : achat", channel.topic)
        val spec = setup.fake.channelSpecs.getValue(channel.id)
        assertEquals(setOf("42"), spec.memberViewers)
        assertEquals(setOf(setup.staff, setup.moderation), spec.viewers)
        assertNull(spec.writers)

        val welcome = setup.fake.posted.single { it.channelId == channel.id }.message
        assertEquals("<@42> Ton ticket est ouvert. Dis-nous tout ici : le staff lit.", welcome.text)
        assertEquals(setOf("42"), welcome.mentionUserIds)
        assertTrue(welcome.mentionRoleIds.isEmpty())
        assertEquals(listOf("ticket-prendre:1", "ticket-liberer:1", "ticket-fermer:1"), welcome.buttons.map { it.id })
        assertEquals(listOf("Prendre en charge", "Libérer le ticket", "Fermer le ticket"), welcome.buttons.map { it.label })

        val (answer, onlyToUser) = reply.sent.single()
        assertEquals("Ton ticket est ouvert : <#" + channel.id + ">.", answer.text)
        assertTrue(onlyToUser)
        assertEquals(listOf("Ticket n°1 ouvert par <@42> : achat."), setup.journal)
    }

    @Test
    @DisplayName("Chaque choix a son nom de salon ; le numéro monte d'un ticket à l'autre")
    fun channelNamesFollowTheChoice() {
        val setup = Setup()
        setup.choose("compte", DiscordUser("1", "Un"))
        setup.choose("sanction", DiscordUser("2", "Deux"))
        setup.choose("createurs", DiscordUser("3", "Trois"))
        assertEquals(
            listOf("ticket-compte-1", "ticket-sanction-2", "ticket-createurs-3"),
            setup.fake.channels.map { it.name }.filter { it.startsWith("ticket-") },
        )
    }

    @Test
    @DisplayName("Un membre qui a déjà un ticket ouvert reçoit le lien du salon, sans second salon ; fermé, il peut en rouvrir un")
    fun oneOpenTicketPerMember() {
        val setup = Setup()
        val channel = setup.openPurchase()
        val again = setup.choose("compte")

        assertEquals("Tu as déjà un ticket ouvert : <#" + channel + ">.", again.sent.single().first.text)
        assertTrue(again.sent.single().second)
        assertEquals(1, setup.store.tickets.size)
        assertEquals(1, setup.fake.channels.count { it.name.startsWith("ticket-") })

        setup.close(1)
        setup.choose("compte")
        assertEquals(listOf(1, 2), setup.store.tickets.map { it.id })
        assertEquals("ticket-compte-2", setup.fake.channels.last().name)
    }

    @Test
    @DisplayName("Sans catégorie Aide, ou pour un choix inconnu, ou si Discord refuse le salon : les tickets ne sont pas prêts, rien n'est gardé ouvert")
    fun notReady() {
        val ready = "Les tickets ne sont pas prêts sur ce serveur."

        val noCategory = Setup(withCategory = false)
        assertEquals(ready, noCategory.choose("achat").sent.single().first.text)
        assertTrue(noCategory.store.tickets.isEmpty())

        val unknown = Setup()
        assertEquals(ready, unknown.choose("autre").sent.single().first.text)
        assertTrue(unknown.store.tickets.isEmpty())

        val refused = Setup(refused = setOf("ticket-achat-1"))
        assertEquals(ready, refused.choose("achat").sent.single().first.text)
        assertNull(runBlocking { refused.store.openOf("42") }, "la ligne est refermée : le membre peut réessayer")
        assertEquals("bot", refused.store.tickets.single().closedBy)
    }

    @Test
    @DisplayName("Un bot qui choisit dans le menu est ignoré")
    fun botsAreIgnored() {
        val setup = Setup()
        val reply = setup.choose("achat", DiscordUser("9", "Un bot", isBot = true))
        assertTrue(reply.sent.isEmpty())
        assertTrue(setup.store.tickets.isEmpty())
    }

    @Test
    @DisplayName("L'auteur ferme son ticket : fermé en base, archive au journal (ligne de fermeture, puis les messages du salon, le bot compris), salon supprimé")
    fun closeByTheAuthor() {
        val setup = Setup()
        val channel = setup.openPurchase()
        setup.say(channel, "bot", "Oooscar", "<@42> Ton ticket est ouvert. Dis-nous tout ici : le staff lit.")
        setup.say(channel, "42", "Ana", "Mon achat n'est pas arrivé.", Instant.parse("2026-11-05T17:12:00Z"))
        setup.say(channel, "77", "Kr", "Je regarde.", Instant.parse("2026-11-05T17:20:00Z"))
        setup.say(channel, "42", "Ana", "", Instant.parse("2026-11-05T17:21:00Z"))
        val before = setup.journal.size

        val reply = setup.close(1)

        assertEquals(1, reply.acknowledged)
        assertTrue(reply.sent.isEmpty())
        val closed = setup.ticket(1)
        assertEquals(setup.timer.now(), closed?.closedAt)
        assertEquals("42", closed?.closedBy)
        assertEquals(1, setup.journal.drop(before).size)
        val archive = setup.archiveFiles.single()
        assertTrue(archive.contains("2026-11-05T17:10:00Z | Oooscar"))
        assertTrue(archive.contains("Ana (42) | message 2"))
        assertTrue(archive.contains("Mon achat n'est pas arrivé."))
        assertTrue(archive.contains("Je regarde."))
        assertTrue(archive.indexOf("Mon achat n'est pas arrivé.") < archive.indexOf("Je regarde."))
        assertEquals(listOf(channel), setup.fake.deleted)
        assertTrue(setup.fake.channels.none { it.id == channel })
    }

    @Test
    @DisplayName("Le staff et la modération ferment le ticket d'un autre ; un tiers est refusé et rien ne bouge")
    fun closeByStaffOrRefused() {
        val setup = Setup()
        val channel = setup.openPurchase()
        val before = setup.journal.size

        val stranger = setup.close(1, DiscordUser("55", "Bob", roleIds = setOf("1234")))
        assertEquals("Ce bouton est pour le staff et l'auteur du ticket.", stranger.sent.single().first.text)
        assertTrue(stranger.sent.single().second)
        assertEquals(0, stranger.acknowledged)
        assertNull(setup.ticket(1)?.closedAt)
        assertTrue(setup.fake.deleted.isEmpty())
        assertEquals(before, setup.journal.size)

        setup.fake.memberRoles["66"] = mutableSetOf(setup.moderation)
        val byModeration = setup.close(1, DiscordUser("66", "Mod", roleIds = setOf(setup.moderation)))
        assertEquals(1, byModeration.acknowledged)
        assertEquals("66", setup.ticket(1)?.closedBy)
        assertEquals(listOf(channel), setup.fake.deleted)
        assertTrue(setup.journal[before].startsWith("Archive du ticket n°1 (achat) de <@42>, fermeture demandée par <@66>."))

        val second = Setup()
        second.openPurchase()
        second.close(1, second.staffMember())
        assertEquals("77", second.ticket(1)?.closedBy)
        assertTrue(second.journal.last().startsWith("Archive du ticket n°1 (achat) de <@42>, fermeture demandée par <@77>."))
    }

    @Test
    @DisplayName("Sans les rôles Staff et Modération sur le serveur, seul l'auteur ferme")
    fun onlyTheAuthorWithoutStaffRoles() {
        val setup = Setup(withStaffRoles = false)
        setup.openPurchase()
        val refused = setup.close(1, DiscordUser("77", "Kr", roleIds = setOf(setup.staff)))
        assertEquals("Ce bouton est pour le staff et l'auteur du ticket.", refused.sent.single().first.text)
        assertNull(setup.ticket(1)?.closedAt)
        assertEquals(1, setup.close(1).acknowledged)
    }

    @Test
    @DisplayName("Un ticket déjà fermé ou inconnu répond qu'il est déjà fermé")
    fun alreadyClosed() {
        val setup = Setup()
        setup.openPurchase()
        setup.close(1)
        val deletedBefore = setup.fake.deleted.size

        val again = setup.close(1)
        assertEquals("Ce ticket est déjà fermé.", again.sent.single().first.text)
        assertEquals(deletedBefore, setup.fake.deleted.size)

        val unknown = setup.close(99)
        assertEquals("Ce ticket est déjà fermé.", unknown.sent.single().first.text)
    }

    @Test
    @DisplayName("Les lignes du ticket sont conservées dans un fichier texte sans limite de 2 000 caractères")
    fun archiveKeepsEveryLineInAFile() {
        val setup = Setup()
        val channel = setup.openPurchase()
        repeat(40) { setup.say(channel, "42", "Ana", "Message " + (it + 1) + " " + "x".repeat(80)) }
        setup.close(1)
        val archive = setup.archiveFiles.single()
        assertTrue(archive.length > 2000)
        assertEquals(40, Regex("Ana \\(42\\) \\| message").findAll(archive).count())
        for (i in 1..40) assertTrue(archive.contains("Message " + i + " " + "x".repeat(80)))
        assertEquals(listOf(channel), setup.fake.deleted)
    }

    @Test
    @DisplayName("Un grand message Unicode est conservé sans découpe ni perte")
    fun aHugeMessageIsKept() {
        val setup = Setup()
        val channel = setup.openPurchase()
        val content = "é𝄞".repeat(1500)
        setup.say(channel, "42", "Ana", content)
        setup.close(1)
        assertTrue(setup.archiveFiles.single().contains(content))
        assertEquals(listOf(channel), setup.fake.deleted)
    }

    @Test
    @DisplayName("Un salon illisible reste ouvert pour permettre une nouvelle tentative de fermeture")
    fun unreadableChannelStays() {
        val setup = Setup()
        val channel = setup.openPurchase()
        setup.fake.channels.removeAll { it.id == channel }
        val before = setup.journal.size

        val reply = setup.close(1)

        assertEquals(1, reply.acknowledged)
        assertNull(setup.ticket(1)?.closedAt)
        assertEquals(TicketTexts.ARCHIVE_FAILED, reply.sent.single().first.text)
        assertTrue(setup.journal.drop(before).isEmpty())
        assertNotNull(runBlocking { setup.archives.find(1) }?.lastError)
        assertTrue(setup.fake.deleted.isEmpty())
    }

    @Test
    @DisplayName("Au démarrage, un ticket ouvert dont le salon a disparu est fermé par le bot ; les autres restent ouverts")
    fun vanishedChannelsAreClosedAtStart() {
        val setup = Setup()
        val kept = setup.openPurchase(DiscordUser("1", "Un"))
        val lost = setup.openPurchase(DiscordUser("2", "Deux"))
        runBlocking { setup.store.open("3", "compte", "", at) }
        setup.fake.channels.removeAll { it.id == lost }
        val before = setup.journal.size

        setup.start()

        assertEquals(listOf(1), setup.store.tickets.filter { it.closedAt == null }.map { it.id })
        assertEquals(kept, setup.store.tickets.first().channelId)
        assertEquals("bot", setup.ticket(2)?.closedBy)
        assertEquals("bot", setup.ticket(3)?.closedBy)
        assertEquals(
            listOf("Ticket n°2 : salon supprimé à la main, fermé.", "Ticket n°3 : salon supprimé à la main, fermé."),
            setup.journal.drop(before),
        )
        assertEquals(setup.timer.now(), setup.ticket(2)?.closedAt)
    }
    private fun Setup.action(action: String, user: DiscordUser = member, channel: String? = ticket(1)?.channelId): FakeReply {
        val reply = FakeReply()
        runBlocking { feature.handle(DiscordEvent.CommandUsed("ticket", mapOf("action" to action), user, channel, reply)) }
        return reply
    }

    @Test
    fun assignmentSurvivesRestartAndCannotBeStolen() {
        val setup = Setup()
        setup.openPurchase()
        val staff = setup.staffMember()
        assertEquals(TicketTexts.CLAIMED, setup.action("prendre", staff).sent.single().first.text)
        assertEquals(staff.id, setup.ticket(1)?.assignedTo)
        assertEquals(setup.timer.now(), setup.ticket(1)?.claimedAt)
        val other = DiscordUser("88", "Autre")
        setup.fake.memberRoles[other.id] = mutableSetOf(setup.staff)
        assertEquals(TicketTexts.UNAVAILABLE, setup.action("prendre", other).sent.single().first.text)
        assertEquals(TicketTexts.NOT_ASSIGNED, setup.action("liberer", other).sent.single().first.text)
        val restarted = TicketFeature(setup.context, setup.store, setup.messages, BotJournal(setup.context), zone, setup.archives, archiveRules)
        val state = FakeReply()
        runBlocking { restarted.handle(DiscordEvent.CommandUsed("ticket", mapOf("action" to "etat"), member, setup.ticket(1)?.channelId, state)) }
        assertTrue(state.sent.single().first.text.contains("<@77>"))
        assertTrue(state.sent.single().second)
        assertEquals(TicketTexts.RELEASED, setup.action("liberer", staff).sent.single().first.text)
        assertNull(setup.ticket(1)?.assignedTo)
        assertEquals(TicketTexts.CLAIMED, setup.action("prendre", other).sent.single().first.text)
    }

    @Test
    fun assignmentButtonsCheckLiveRolesAndSourceChannel() {
        val setup = Setup()
        val channel = setup.openPurchase()
        val staff = setup.staffMember()
        fun press(prefix: String, user: DiscordUser, source: String = channel): FakeReply {
            val reply = FakeReply()
            runBlocking { setup.feature.handle(DiscordEvent.ButtonPressed(prefix + "1", user, source, "1", reply)) }
            return reply
        }
        assertEquals(TicketTexts.STAFF_ONLY, press(TicketTexts.CLAIM_PREFIX, member).sent.single().first.text)
        assertEquals(TicketTexts.WRONG_CHANNEL, press(TicketTexts.CLAIM_PREFIX, staff, "elsewhere").sent.single().first.text)
        setup.fake.memberRoles[staff.id]?.clear()
        assertEquals(TicketTexts.STAFF_ONLY, press(TicketTexts.CLAIM_PREFIX, staff).sent.single().first.text)
        assertEquals(TicketTexts.NOT_ALLOWED, setup.close(1, staff).sent.single().first.text)
        assertNull(setup.ticket(1)?.assignedTo)
        assertNull(setup.ticket(1)?.closedAt)
        setup.fake.memberRoles[staff.id] = mutableSetOf(setup.staff)
        assertEquals(TicketTexts.CLAIMED, press(TicketTexts.CLAIM_PREFIX, staff).sent.single().first.text)
        assertEquals(TicketTexts.RELEASED, press(TicketTexts.RELEASE_PREFIX, staff).sent.single().first.text)
    }

    @Test
    fun missingJournalKeepsTicketOpenAndRetryCanCloseIt() {
        val setup = Setup()
        val channel = setup.openPurchase()
        val noJournal = BotContext(setup.fake, ServerIds(mapOf("staff" to setup.staff), emptyMap(), emptyMap()), setup.timer, ServerPhase.ATTENTE)
        val feature = TicketFeature(noJournal, setup.store, setup.messages, BotJournal(noJournal), zone, setup.archives, archiveRules)
        val reply = FakeReply()
        runBlocking { feature.handle(DiscordEvent.ButtonPressed(TicketTexts.CLOSE_PREFIX + "1", member, channel, "1", reply)) }
        assertEquals(TicketTexts.ARCHIVE_FAILED, reply.sent.single().first.text)
        assertNull(setup.ticket(1)?.closedAt)
        assertTrue(setup.fake.deleted.isEmpty())
        setup.close(1)
        assertNotNull(setup.ticket(1)?.closedAt)
        assertEquals(listOf(channel), setup.fake.deleted)
    }

    @Test
    fun refusedArchiveKeepsSourceAndTicketOpen() {
        val setup = Setup()
        val channel = setup.openPurchase()
        val rejecting = object : DiscordServer by setup.fake {
            override suspend fun post(channelId: String, message: BotMessage): String { throw DiscordRefusal("envoi refusé") }
        }
        val context = BotContext(rejecting, setup.ids, setup.timer, ServerPhase.ATTENTE)
        val feature = TicketFeature(context, setup.store, setup.messages, BotJournal(context), zone, setup.archives, archiveRules)
        val reply = FakeReply()
        runBlocking { feature.handle(DiscordEvent.ButtonPressed(TicketTexts.CLOSE_PREFIX + "1", member, channel, "1", reply)) }
        assertEquals(TicketTexts.ARCHIVE_FAILED, reply.sent.single().first.text)
        assertNull(setup.ticket(1)?.closedAt)
        assertTrue(setup.fake.deleted.isEmpty())
    }

    @Test
    fun partialArchiveFailurePreservesTicketAndCanBeRetried() {
        val setup = Setup()
        val channel = setup.openPurchase()
        repeat(101) { setup.say(channel, member.id, member.name, "Mon achat manque " + it) }
        var sends = 0
        val partial = object : DiscordServer by setup.fake {
            override suspend fun post(channelId: String, message: BotMessage): String {
                sends++
                if (sends == 2) throw DiscordRefusal("archive interrompue")
                return setup.fake.post(channelId, message)
            }
        }
        val context = BotContext(partial, setup.ids, setup.timer, ServerPhase.ATTENTE)
        val feature = TicketFeature(context, setup.store, setup.messages, BotJournal(context), zone, setup.archives, archiveRules)
        val reply = FakeReply()
        runBlocking { feature.handle(DiscordEvent.ButtonPressed(TicketTexts.CLOSE_PREFIX + "1", member, channel, "1", reply)) }
        assertEquals(2, sends)
        assertEquals(TicketTexts.ARCHIVE_FAILED, reply.sent.single().first.text)
        assertNull(setup.ticket(1)?.closedAt)
        assertTrue(setup.fake.deleted.isEmpty())
        setup.close(1)
        assertNotNull(setup.ticket(1)?.closedAt)
        assertTrue(setup.archiveFiles.any { it.contains("Mon achat manque 100") })
        assertEquals(listOf(channel), setup.fake.deleted)
    }

    @Test
    fun slashClosureCompletesItsPrivateAcknowledgement() {
        val setup = Setup()
        val channel = setup.openPurchase()
        val response = setup.action("fermer")
        assertEquals(TicketTexts.CLOSED, response.sent.single().first.text)
        assertTrue(response.sent.single().second)
        assertEquals(1, response.acknowledged)
        assertEquals(listOf(channel), setup.fake.deleted)
    }

}
