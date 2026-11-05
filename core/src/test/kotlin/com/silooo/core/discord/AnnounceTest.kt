package com.silooo.core.discord

import com.silooo.core.wording.AntiIa
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `/annonce` : le garde anti-IA avant l'aperçu, l'aperçu pour le staff seul, Publier et Annuler, la mention du rôle
 * Actus, le journal, l'expiration du brouillon, les refus.
 */
class AnnounceTest {
    /** Un serveur, un temps et un journal à part pour chaque test. */
    private inner class Setup(withChannel: Boolean = true, withActus: Boolean = true, missingChannelId: String? = null) {
        val fake = FakeDiscordServer()
        val timer = FakeTimer()
        val staff: String = runBlocking { fake.createRole(RoleSpec("staff", null, false, false)) }
        val actus: String = runBlocking { fake.createRole(RoleSpec("actus", null, false, true)) }
        val channels: Map<String, String> = runBlocking {
            val category = fake.createCategory(CategorySpec("Le QG", null))
            buildMap {
                if (withChannel) put("annonces", fake.createChannel(category, ChannelSpec("annonces", ChannelKind.ANNONCES, null, emptySet(), null, false)))
                put("journal-du-bot", fake.createChannel(category, ChannelSpec("journal-du-bot", ChannelKind.TEXTE, null, emptySet(), null, false)))
                if (missingChannelId != null) put("annonces", missingChannelId)
            }
        }
        val ids = ServerIds(if (withActus) mapOf("staff" to staff, "actus" to actus) else mapOf("staff" to staff), channels)
        val context = BotContext(fake, ids, timer, ServerPhase.ATTENTE)
        val feature = AnnounceFeature(context, BotJournal(context))

        val published: List<FakeDiscordServer.Posted> get() = fake.posted.filter { it.channelId == channels["annonces"] }
        val journal: List<FakeDiscordServer.Posted> get() = fake.posted.filter { it.channelId == channels["journal-du-bot"] }

        /** Un `/annonce` du staff (ou de [roles]) ; rend la réponse. */
        fun announce(
            title: String,
            text: String,
            mention: String? = null,
            image: ImageFile? = null,
            refusedFile: String? = null,
            roles: Set<String> = setOf(staff),
            user: String = "42",
        ): FakeReply {
            val options = listOfNotNull(
                "titre" to title,
                "texte" to text,
                mention?.let { "mention" to it },
                refusedFile?.let { "fichier-refuse" to it },
            ).toMap()
            val files = if (image == null) emptyMap() else mapOf("image" to image)
            val reply = FakeReply()
            runBlocking { feature.handle(DiscordEvent.CommandUsed("annonce", options, DiscordUser(user, "Kr", roleIds = roles), null, reply, files)) }
            return reply
        }

        /** Un bouton de l'aperçu pressé par [user] ; rend la réponse. */
        fun press(id: String, user: String = "42", roles: Set<String> = setOf(staff)): FakeReply {
            val reply = FakeReply()
            runBlocking { feature.handle(DiscordEvent.ButtonPressed(id, DiscordUser(user, "Kr", roleIds = roles), null, "7", reply)) }
            return reply
        }

        fun advance(duration: Duration) = runBlocking { timer.advance(duration) }
    }

    private val noDraft = "Aucune annonce en attente : relance /annonce."

    @Test
    @DisplayName("La commande /annonce est pour le staff, avec un titre, un texte, une image et le choix de la mention")
    fun commandIsDeclared() {
        val context = BotContext(FakeDiscordServer(), ServerIds.NONE, FakeTimer(), ServerPhase.ATTENTE)
        val command = AnnounceFeature(context, BotJournal(context)).commands.single()
        assertEquals("annonce", command.name)
        assertEquals("Publie une annonce dans le salon des annonces", command.description)
        assertTrue(command.staffOnly)
        assertEquals(listOf("titre", "texte", "image", "mention"), command.options.map { it.name })
        assertEquals(listOf(true, true, false, false), command.options.map { it.required })
        assertEquals(OptionKind.FICHIER, command.options.single { it.name == "image" }.kind)
        assertEquals(listOf("oui", "non"), command.options.single { it.name == "mention" }.choices)
        assertTrue(command.options.all { it.description.length <= 100 })
    }

    @Test
    @DisplayName("Une annonce propre donne un aperçu au staff seul, tel qu'elle partira, avec Publier et Annuler, et rien n'est posté")
    fun cleanAnnouncementGivesAPreview() {
        val setup = Setup()
        val image = ImageFile("etage.png", byteArrayOf(1, 2, 3))
        val reply = setup.announce("Un étage de plus", "Le silo a monté un étage.\nLa suite arrive.", mention = "oui", image = image)

        val (preview, onlyToUser) = reply.sent.single()
        assertTrue(onlyToUser, "l'aperçu est pour le staff seul")
        assertEquals("<@&" + setup.actus + "> **Un étage de plus**\n\nLe silo a monté un étage.\nLa suite arrive.", preview.text)
        assertSame(image, preview.image)
        assertEquals(listOf("annonce-publier", "annonce-annuler"), preview.buttons.map { it.id })
        assertEquals(listOf("Publier", "Annuler"), preview.buttons.map { it.label })
        assertTrue(preview.mentionRoleIds.isEmpty() && preview.mentionUserIds.isEmpty(), "l'aperçu ne prévient personne")
        assertTrue(setup.fake.posted.isEmpty(), "rien n'est posté avant Publier")
    }

    @Test
    @DisplayName("Un tic d'IA est refusé au staff seul avec le résumé du garde, et rien n'est gardé")
    fun aiTicsAreRefused() {
        val setup = Setup()
        val text = "Découvre une expérience épique !"
        val reply = setup.announce("Le chantier", text)

        val (answer, onlyToUser) = reply.sent.single()
        assertTrue(onlyToUser)
        val summary = AntiIa.summary(AntiIa.check("Le chantier\n" + text))
        assertTrue(summary.startsWith("Tics d'IA : ") && summary.contains("épique"), summary)
        assertEquals("L'annonce n'est pas gardée. " + summary + " Corrige et relance /annonce.", answer.text)
        assertTrue(answer.buttons.isEmpty())

        val inTitle = setup.announce("Une aventure incroyable", "Le silo a un étage de plus.")
        assertTrue(inTitle.sent.single().first.text.startsWith("L'annonce n'est pas gardée. Tics d'IA : "))

        assertEquals(noDraft, setup.press("annonce-publier").sent.single().first.text)
        assertTrue(setup.fake.posted.isEmpty())
    }

    @Test
    @DisplayName("Publier poste l'annonce dans #annonces avec le rôle Actus et l'image, écrit au journal et change l'aperçu sans bouton")
    fun publishPostsAndJournals() {
        val setup = Setup()
        val image = ImageFile("etage.png", byteArrayOf(1, 2, 3))
        setup.announce("Un étage de plus", "Le silo a monté un étage.", mention = "oui", image = image)
        val reply = setup.press("annonce-publier")

        val post = setup.published.single()
        assertEquals("<@&" + setup.actus + "> **Un étage de plus**\n\nLe silo a monté un étage.", post.message.text)
        assertEquals(setOf(setup.actus), post.message.mentionRoleIds)
        assertTrue(post.message.mentionUserIds.isEmpty())
        assertSame(image, post.message.image)
        assertTrue(post.message.buttons.isEmpty())

        val line = setup.journal.single().message
        assertEquals("Annonce publiée par <@42> : Un étage de plus.", line.text)
        assertTrue(line.mentionRoleIds.isEmpty() && line.mentionUserIds.isEmpty(), "la ligne du journal ne prévient personne")

        val edited = reply.edits.single()
        assertEquals("Publié dans <#" + setup.channels["annonces"] + ">.", edited.text)
        assertTrue(edited.buttons.isEmpty())
        assertTrue(reply.sent.isEmpty())
        assertEquals(0, setup.timer.pending, "la minuterie du brouillon est annulée")

        assertEquals(noDraft, setup.press("annonce-publier").sent.single().first.text)
        assertEquals(1, setup.published.size, "le brouillon est effacé : pas de seconde publication")
    }

    @Test
    @DisplayName("Sans mention demandée (par défaut ou « non »), le rôle n'est ni écrit ni prévenu")
    fun noMentionByDefault() {
        val setup = Setup()
        setup.announce("Un étage de plus", "Le silo a monté un étage.")
        val reply = setup.press("annonce-publier")
        val first = setup.published.single().message
        assertEquals("**Un étage de plus**\n\nLe silo a monté un étage.", first.text)
        assertTrue(first.mentionRoleIds.isEmpty())
        assertEquals("Publié dans <#" + setup.channels["annonces"] + ">.", reply.edits.single().text)

        val withNo = setup.announce("Deux étages", "Le silo a monté un étage de plus.", mention = "non")
        assertFalse(withNo.sent.single().first.text.contains("<@&"))
        setup.press("annonce-publier")
        assertTrue(setup.published.last().message.mentionRoleIds.isEmpty())
    }

    @Test
    @DisplayName("Mention demandée mais rôle Actus absent du serveur : l'annonce part sans mention")
    fun mentionWithoutRole() {
        val setup = Setup(withActus = false)
        val reply = setup.announce("Un étage de plus", "Le silo a monté un étage.", mention = "oui")
        assertEquals("**Un étage de plus**\n\nLe silo a monté un étage.", reply.sent.single().first.text)
        setup.press("annonce-publier")
        val post = setup.published.single().message
        assertEquals("**Un étage de plus**\n\nLe silo a monté un étage.", post.text)
        assertTrue(post.mentionRoleIds.isEmpty())
    }

    @Test
    @DisplayName("Annuler efface le brouillon et change l'aperçu en « Annulé. »")
    fun cancelClearsTheDraft() {
        val setup = Setup()
        setup.announce("Un étage de plus", "Le silo a monté un étage.")
        val reply = setup.press("annonce-annuler")

        assertEquals("Annulé.", reply.edits.single().text)
        assertTrue(reply.edits.single().buttons.isEmpty())
        assertEquals(0, setup.timer.pending)
        assertEquals(noDraft, setup.press("annonce-publier").sent.single().first.text)
        assertTrue(setup.fake.posted.isEmpty())
    }

    @Test
    @DisplayName("Publier sans brouillon dit qu'aucune annonce n'attend, au staff seul, et ne poste rien")
    fun publishWithoutDraft() {
        val setup = Setup()
        val reply = setup.press("annonce-publier")
        val (answer, onlyToUser) = reply.sent.single()
        assertEquals(noDraft, answer.text)
        assertTrue(onlyToUser)
        assertTrue(reply.edits.isEmpty())
        assertTrue(setup.fake.posted.isEmpty())
    }

    @Test
    @DisplayName("Le brouillon d'un membre n'est pas celui d'un autre")
    fun draftsBelongToTheirAuthor() {
        val setup = Setup()
        setup.announce("Un étage de plus", "Le silo a monté un étage.", user = "42")
        assertEquals(noDraft, setup.press("annonce-publier", user = "43").sent.single().first.text)
        assertTrue(setup.fake.posted.isEmpty())
        setup.press("annonce-publier", user = "42")
        assertEquals(1, setup.published.size)
        assertEquals("Annonce publiée par <@42> : Un étage de plus.", setup.journal.single().message.text)
    }

    @Test
    @DisplayName("Un brouillon vit quinze minutes : à quatorze minutes cinquante-neuf il publie, à quinze il n'y a plus rien")
    fun draftExpiresAfterFifteenMinutes() {
        val kept = Setup()
        kept.announce("Un étage de plus", "Le silo a monté un étage.")
        kept.advance(Duration.ofMinutes(14).plusSeconds(59))
        kept.press("annonce-publier")
        assertEquals(1, kept.published.size)

        val expired = Setup()
        expired.announce("Un étage de plus", "Le silo a monté un étage.")
        expired.advance(Duration.ofMinutes(15))
        assertEquals(noDraft, expired.press("annonce-publier").sent.single().first.text)
        assertTrue(expired.fake.posted.isEmpty())
    }

    @Test
    @DisplayName("Un second /annonce remplace le premier brouillon : Publier poste le second, une seule fois")
    fun secondAnnouncementReplacesTheFirst() {
        val setup = Setup()
        setup.announce("Premier titre", "Le premier texte.")
        setup.advance(Duration.ofMinutes(10))
        setup.announce("Second titre", "Le second texte.")
        assertEquals(1, setup.timer.pending, "la minuterie de l'ancien brouillon est annulée")

        setup.advance(Duration.ofMinutes(10))
        setup.press("annonce-publier")
        assertEquals("**Second titre**\n\nLe second texte.", setup.published.single().message.text)
        assertEquals(noDraft, setup.press("annonce-publier").sent.single().first.text)
        assertEquals(1, setup.published.size)
    }

    @Test
    @DisplayName("Un membre sans le rôle Staff reçoit un refus et ne laisse aucun brouillon")
    fun nonStaffIsRefused() {
        val setup = Setup()
        val reply = setup.announce("Un étage de plus", "Le silo a monté un étage.", roles = emptySet())
        val (answer, onlyToUser) = reply.sent.single()
        assertEquals("Cette commande est pour le staff.", answer.text)
        assertTrue(onlyToUser)
        assertEquals("Cette commande est pour le staff.", setup.press("annonce-publier", roles = emptySet()).sent.single().first.text)
        assertEquals(0, setup.timer.pending)
        assertTrue(setup.fake.posted.isEmpty())
    }

    @Test
    @DisplayName("Le rôle Staff est revérifié à la publication, même après un aperçu autorisé")
    fun staffRoleIsRecheckedBeforePublishing() {
        val setup = Setup()
        setup.announce("Un étage de plus", "Le silo a monté un étage.")

        val denied = setup.press("annonce-publier", roles = emptySet())
        assertEquals("Cette commande est pour le staff.", denied.sent.single().first.text)
        assertTrue(denied.sent.single().second)
        assertTrue(denied.edits.isEmpty())
        assertTrue(setup.fake.posted.isEmpty())
        assertEquals(1, setup.timer.pending, "le refus ne consomme pas le brouillon")

        setup.press("annonce-publier")
        assertEquals(1, setup.published.size)
    }

    @Test
    @DisplayName("Un titre ou un texte vide, trop long, ou un fichier refusé : un refus au staff seul, rien de gardé")
    fun refusals() {
        val setup = Setup()
        val clean = "Le silo a monté un étage."

        fun refusal(reply: FakeReply): String {
            val (answer, onlyToUser) = reply.sent.single()
            assertTrue(onlyToUser)
            assertTrue(answer.buttons.isEmpty())
            return answer.text
        }

        assertEquals("Le titre est vide : rien n'est gardé.", refusal(setup.announce("   ", clean)))
        assertEquals("Le texte est vide : rien n'est gardé.", refusal(setup.announce("Un titre", " \n ")))
        assertEquals(
            "Le titre fait 101 caractères : 100 au plus. Rien n'est gardé.",
            refusal(setup.announce("a".repeat(101), clean)),
        )
        assertEquals(
            "Le texte fait 1 801 caractères : 1 800 au plus. Rien n'est gardé.",
            refusal(setup.announce("Un titre", "a".repeat(1801))),
        )
        assertEquals(
            "Le fichier « notes.pdf » est refusé : png ou gif, 16 Mo au plus. Rien n'est gardé.",
            refusal(setup.announce("Un titre", clean, refusedFile = "notes.pdf")),
        )

        assertEquals(0, setup.timer.pending, "aucun brouillon gardé")
        assertEquals(noDraft, setup.press("annonce-publier").sent.single().first.text)
        assertTrue(setup.fake.posted.isEmpty())
    }

    @Test
    @DisplayName("Un titre de 100 caractères et un texte de 1 800 passent, mention du rôle comprise, sous les 2 000 d'un message")
    fun longestAnnouncementFits() {
        val setup = Setup()
        val reply = setup.announce("a".repeat(100), "b".repeat(1800), mention = "oui")
        assertTrue(reply.sent.single().first.text.length <= BotMessage.MAX_TEXT)
        setup.press("annonce-publier")
        assertEquals(1, setup.published.size)
    }

    @Test
    @DisplayName("Sans salon des annonces, Publier le dit au staff seul et garde le brouillon")
    fun publishWithoutChannel() {
        val setup = Setup(withChannel = false)
        setup.announce("Un étage de plus", "Le silo a monté un étage.")
        val reply = setup.press("annonce-publier")
        val (answer, onlyToUser) = reply.sent.single()
        assertEquals("Le salon des annonces n'existe pas sur le serveur.", answer.text)
        assertTrue(onlyToUser)
        assertTrue(reply.edits.isEmpty())
        assertTrue(setup.fake.posted.isEmpty())
        assertEquals("Le salon des annonces n'existe pas sur le serveur.", setup.press("annonce-publier").sent.single().first.text)
    }

    @Test
    @DisplayName("Si Discord refuse la publication, le brouillon reste : le staff peut recommencer")
    fun refusedPublicationKeepsTheDraft() {
        val setup = Setup(withChannel = false, missingChannelId = "999")
        setup.announce("Un étage de plus", "Le silo a monté un étage.")
        assertThrows(IllegalStateException::class.java) { setup.press("annonce-publier") }
        assertEquals(1, setup.timer.pending, "le brouillon a retrouvé sa minuterie")
        assertThrows(IllegalStateException::class.java) { setup.press("annonce-publier") }
        assertTrue(setup.journal.isEmpty(), "rien n'est journalisé tant que rien n'est publié")
    }
}
