package com.silooo.core.discord

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les meilleurs moments : le seuil, l'auteur qui ne compte pas, une seule copie, le texte cité et coupé, aucun ping, les
 * salons que le plan cache, les refus de Discord. Les identifiants de salon sont ceux du vrai plan du serveur.
 */
class HighlightTest {
    private val rules = HighlightRules(emoji = "prestige", threshold = 3)

    private inner class Setup(withTarget: Boolean = true) {
        val fake = FakeDiscordServer()
        val store = InMemoryHighlightStore()
        val timer = FakeTimer()
        private val category = runBlocking { fake.createCategory(CategorySpec("Communauté", null)) }

        private fun channel(name: String, kind: ChannelKind = ChannelKind.TEXTE): String =
            runBlocking { fake.createChannel(category, ChannelSpec(name, kind, null, null, null, false)) }

        val target: String? = if (withTarget) channel("meilleurs-moments") else null
        val discussion = channel("discussion")
        val forum = channel("entreprises", ChannelKind.FORUM)
        val moderation = channel("signalements")
        val journalChannel = channel("journal-du-bot")
        val context = BotContext(
            fake,
            ServerIds(
                emptyMap(),
                listOfNotNull(
                    target?.let { "meilleurs-moments" to it },
                    "discussion" to discussion,
                    "entreprises" to forum,
                    "signalements" to moderation,
                    "journal-du-bot" to journalChannel,
                ).toMap(),
            ),
            timer,
            ServerPhase.ATTENTE,
        )
        val feature = HighlightFeature(context, rules, store, BotJournal(context))

        /** Un message de [author] dans [channel], lisible par le faux serveur. */
        fun message(channel: String, id: String, author: String = "a", text: String = "Bravo à toute l'équipe", bot: Boolean = false) {
            fake.messagesById[channel to id] = ChannelMessage(author, "Membre " + author, text, timer.now(), bot)
        }

        fun reactors(channel: String, id: String, vararg users: String, emoji: String = "prestige") {
            fake.reactors[Triple(channel, id, emoji)] = users.toMutableSet()
        }

        fun react(channel: String, id: String, user: String = "x", emoji: String = "prestige", bot: Boolean = false) =
            runBlocking { feature.handle(DiscordEvent.ReactionAdded(channel, id, user, emoji, bot)) }

        fun write(thread: String, parent: String) = runBlocking {
            feature.handle(DiscordEvent.MessagePosted(thread, thread, DiscordUser("w", "Membre w"), "salut", timer.now(), parent))
        }

        fun copies() = fake.posted.filter { it.channelId == target }

        fun journal() = fake.posted.filter { it.channelId == journalChannel }.map { it.message.text }

        fun link(channel: String, id: String) = fake.messageLink(channel, id)
    }

    @Test
    @DisplayName("Sous le seuil rien n'est recopié ; au seuil le message l'est, avec son auteur, son salon, le texte cité et le lien")
    fun copiesAtTheThreshold() {
        val setup = Setup()
        setup.message(setup.discussion, "m1", author = "7")
        setup.reactors(setup.discussion, "m1", "1", "2")
        setup.react(setup.discussion, "m1", "2")
        assertTrue(setup.copies().isEmpty())

        setup.reactors(setup.discussion, "m1", "1", "2", "3")
        setup.react(setup.discussion, "m1", "3")

        val copy = setup.copies().single()
        assertEquals(
            "<@7> dans <#" + setup.discussion + "> :\n> Bravo à toute l'équipe\n" + setup.link(setup.discussion, "m1"),
            copy.message.text,
        )
        assertEquals(Highlight("m1", setup.discussion, "7", copy.messageId), setup.store.highlights["m1"])
        assertEquals(
            listOf("Meilleur moment : le message de <@7> dans <#" + setup.discussion + "> est recopié. " + setup.link(setup.discussion, "m1")),
            setup.journal(),
        )
    }

    @Test
    @DisplayName("La copie ne mentionne personne, ni membre ni rôle, même si le message cite tout le monde")
    fun pingsNobody() {
        val setup = Setup()
        setup.message(setup.discussion, "m1", author = "7", text = "@everyone <@8> <@&9> venez")
        setup.reactors(setup.discussion, "m1", "1", "2", "3")
        setup.react(setup.discussion, "m1")

        val copy = setup.copies().single().message
        assertTrue(copy.mentionUserIds.isEmpty())
        assertTrue(copy.mentionRoleIds.isEmpty())
        assertTrue(copy.text.contains("> @everyone <@8> <@&9> venez"))
    }

    @Test
    @DisplayName("Les réactions de l'auteur du message ne comptent pas")
    fun ignoresTheAuthor() {
        val setup = Setup()
        setup.message(setup.discussion, "m1", author = "7")
        setup.reactors(setup.discussion, "m1", "7", "1", "2")
        setup.react(setup.discussion, "m1", "2")
        assertTrue(setup.copies().isEmpty(), "trois réactions dont celle de l'auteur : deux comptent")

        setup.reactors(setup.discussion, "m1", "7", "1", "2", "3")
        setup.react(setup.discussion, "m1", "3")
        assertEquals(1, setup.copies().size)
    }

    @Test
    @DisplayName("Un message n'est recopié qu'une fois, même avec d'autres réactions ensuite ou après un redémarrage")
    fun copiesOnce() {
        val setup = Setup()
        setup.message(setup.discussion, "m1")
        setup.reactors(setup.discussion, "m1", "1", "2", "3")
        setup.react(setup.discussion, "m1")
        setup.reactors(setup.discussion, "m1", "1", "2", "3", "4")
        setup.react(setup.discussion, "m1", "4")
        assertEquals(1, setup.copies().size)

        val restarted = HighlightFeature(setup.context, rules, setup.store, BotJournal(setup.context))
        runBlocking { restarted.handle(DiscordEvent.ReactionAdded(setup.discussion, "m1", "5", "prestige")) }
        assertEquals(1, setup.copies().size)
        assertEquals(1, setup.store.highlights.size)
    }

    @Test
    @DisplayName("Un autre emoji, un bot qui réagit, le salon des meilleurs moments lui-même, un salon caché par le plan ou inconnu : rien")
    fun ignoresWhatIsNotAHighlight() {
        val setup = Setup()
        for (channel in listOf(setup.discussion, checkNotNull(setup.target), setup.moderation, "inconnu")) {
            setup.message(channel, "m1")
            setup.reactors(channel, "m1", "1", "2", "3")
        }
        setup.reactors(setup.discussion, "m2", "1", "2", "3", emoji = "silo")
        setup.message(setup.discussion, "m2")

        setup.react(setup.discussion, "m2", emoji = "silo")
        setup.react(setup.discussion, "m1", bot = true)
        setup.react(checkNotNull(setup.target), "m1")
        setup.react(setup.moderation, "m1")
        setup.react("inconnu", "m1")

        assertTrue(setup.copies().isEmpty())
        assertTrue(setup.store.highlights.isEmpty())
    }

    @Test
    @DisplayName("Un sujet d'un forum ouvert à tous compte une fois que le bot l'a vu passer ; un sujet inconnu ou d'un forum caché non")
    fun countsForumThreads() {
        val setup = Setup()
        setup.message("sujet-1", "sujet-1", author = "7", text = "Ma première usine")
        setup.reactors("sujet-1", "sujet-1", "1", "2", "3")

        setup.react("sujet-1", "sujet-1")
        assertTrue(setup.copies().isEmpty(), "un sujet que le bot n'a pas vu")

        setup.write("sujet-1", setup.forum)
        setup.react("sujet-1", "sujet-1")
        assertEquals("<@7> dans <#sujet-1> :\n> Ma première usine\n" + setup.link("sujet-1", "sujet-1"), setup.copies().single().message.text)

        setup.message("sujet-2", "sujet-2")
        setup.reactors("sujet-2", "sujet-2", "1", "2", "3")
        setup.write("sujet-2", setup.moderation)
        setup.react("sujet-2", "sujet-2")
        assertEquals(1, setup.copies().size)
    }

    @Test
    @DisplayName("Un message du bot n'est pas recopié, ni un message disparu ; un refus de Discord à la lecture ne casse rien")
    fun survivesWhatDiscordRefuses() {
        val setup = Setup()
        setup.message(setup.discussion, "bot", author = "9", bot = true)
        setup.reactors(setup.discussion, "bot", "1", "2", "3")
        setup.react(setup.discussion, "bot")

        setup.reactors(setup.discussion, "gone", "1", "2", "3")
        setup.react(setup.discussion, "gone")
        assertTrue(setup.copies().isEmpty())

        setup.message(setup.discussion, "m1")
        setup.fake.refusedReactors = "droit manquant"
        setup.react(setup.discussion, "m1")
        assertTrue(setup.copies().isEmpty())
        assertTrue(setup.store.highlights.isEmpty(), "rien n'est noté : la réaction suivante pourra recopier")

        setup.fake.refusedReactors = null
        setup.reactors(setup.discussion, "m1", "1", "2", "3")
        setup.react(setup.discussion, "m1")
        assertEquals(1, setup.copies().size)
    }

    @Test
    @DisplayName("Un texte sur plusieurs lignes est cité ligne par ligne, une ligne vide devient un chevron seul")
    fun quotesEveryLine() {
        val setup = Setup()
        setup.message(setup.discussion, "m1", author = "7", text = "Première ligne\n\nTroisième ligne\r\nQuatrième")
        setup.reactors(setup.discussion, "m1", "1", "2", "3")
        setup.react(setup.discussion, "m1")

        assertEquals(
            "<@7> dans <#" + setup.discussion + "> :\n> Première ligne\n>\n> Troisième ligne\n> Quatrième\n" + setup.link(setup.discussion, "m1"),
            setup.copies().single().message.text,
        )
    }

    @Test
    @DisplayName("Un texte trop long est coupé pour tenir sous la limite de Discord, avec la suite dite et le lien intact")
    fun cutsALongText() {
        val setup = Setup()
        setup.message(setup.discussion, "m1", author = "7", text = "mot ".repeat(1500))
        setup.reactors(setup.discussion, "m1", "1", "2", "3")
        setup.react(setup.discussion, "m1")

        val text = setup.copies().single().message.text
        assertTrue(text.length <= BotMessage.MAX_TEXT, "la longueur est " + text.length)
        assertTrue(text.startsWith("<@7> dans <#" + setup.discussion + "> :\n> mot mot"))
        assertTrue(text.endsWith(" (suite)\n" + setup.link(setup.discussion, "m1")))
    }

    @Test
    @DisplayName("Un texte coupé en plein emoji ne laisse pas la moitié d'un caractère")
    fun cutsBetweenSurrogates() {
        val setup = Setup()
        val link = setup.link(setup.discussion, "m1")
        val head = "<@7> dans <#" + setup.discussion + "> :"
        val room = BotMessage.MAX_TEXT - head.length - link.length - 2
        // Le texte cité commence par « > » : la coupe tombe juste après la première moitié du caractère hors du plan de base.
        val filler = "a".repeat(room - HighlightTexts.CUT.length - 3)
        setup.message(setup.discussion, "m1", author = "7", text = filler + "😀" + "b".repeat(50))
        setup.reactors(setup.discussion, "m1", "1", "2", "3")
        setup.react(setup.discussion, "m1")

        val text = setup.copies().single().message.text
        assertTrue(text.length <= BotMessage.MAX_TEXT)
        assertTrue(text.none { it.isSurrogate() }, "ni la moitié d'un caractère, ni le caractère entier")
        assertTrue(text.endsWith(" (suite)\n" + link))
    }

    @Test
    @DisplayName("Un message sans texte est recopié avec son auteur et son lien seulement")
    fun copiesABlankMessage() {
        val setup = Setup()
        setup.message(setup.discussion, "m1", author = "7", text = "")
        setup.reactors(setup.discussion, "m1", "1", "2", "3")
        setup.react(setup.discussion, "m1")

        assertEquals("<@7> dans <#" + setup.discussion + "> :\n" + setup.link(setup.discussion, "m1"), setup.copies().single().message.text)
    }

    @Test
    @DisplayName("Sans le salon des meilleurs moments sur le serveur, la fonction ne lit rien et ne poste rien")
    fun doesNothingWithoutTarget() {
        val setup = Setup(withTarget = false)
        setup.message(setup.discussion, "m1")
        setup.reactors(setup.discussion, "m1", "1", "2", "3")
        setup.react(setup.discussion, "m1")

        assertTrue(setup.fake.posted.isEmpty())
        assertTrue(setup.store.highlights.isEmpty())
    }
}
