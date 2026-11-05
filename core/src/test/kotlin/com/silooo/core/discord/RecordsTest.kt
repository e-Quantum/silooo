package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le tableau des records dans `#panthéon` : le texte avec ou sans record, le message posté une fois puis modifié sur
 * place, retrouvé par sa clé, reposté s'il a disparu, et un refus de Discord qui ne casse rien.
 */
class RecordsTest {
    private val zone = ZoneId.of("Europe/Paris")

    /** 17 h 10 UTC : 18 h 10 à Paris, le 5 novembre 2026. */
    private val at = Instant.parse("2026-11-05T17:10:00Z")

    /** Un serveur et des bases à part pour chaque test. */
    private inner class Setup(withPantheon: Boolean = true, wrap: (FakeDiscordServer) -> DiscordServer = { it }) {
        val fake = FakeDiscordServer()
        val store = InMemoryGameStore()
        val messages = InMemoryBotMessageStore()
        private val category: String = runBlocking { fake.createCategory(CategorySpec("Animation", null)) }
        val pantheon: String? =
            if (withPantheon) runBlocking { fake.createChannel(category, ChannelSpec("pantheon", ChannelKind.TEXTE, null, emptySet(), null, false)) } else null
        private val ids = ServerIds(emptyMap(), listOfNotNull(pantheon?.let { "pantheon" to it }).toMap())
        val timer = FakeTimer(at)
        val board = RecordsBoard(BotContext(wrap(fake), ids, timer, ServerPhase.ATTENTE), store, messages, zone)

        val posts: List<FakeDiscordServer.Posted> get() = fake.posted.filter { it.channelId == pantheon }

        fun refresh() = runBlocking { board.refresh() }

        fun advance(duration: Duration) = runBlocking { timer.advance(duration) }
    }

    @Test
    @DisplayName("Sans aucun record, le tableau le dit")
    fun saysNoRecord() {
        val setup = Setup()
        setup.refresh()

        val post = setup.posts.single()
        assertEquals("Les records des petits jeux\nAucun record pour l'instant.", post.message.text)
        assertTrue(post.message.card)
        assertTrue(post.message.mentionUserIds.isEmpty() && post.message.mentionRoleIds.isEmpty(), "aucune mention ne part")
        assertEquals(BotMessageRef(setup.pantheon!!, post.messageId), setup.messages.refs["pantheon-records"])
    }

    @Test
    @DisplayName("Avec les deux records, une ligne par jeu avec l'auteur et le jour")
    fun listsBothRecords() {
        val setup = Setup()
        setup.store.bests["comptage"] = GameRecord(120, "9", at)
        setup.store.bests["monte-charge"] = GameRecord(37, "8", at)
        setup.refresh()

        assertEquals(
            "Les records des petits jeux\nComptage : 120, par <@9>, le 5 novembre 2026.\nMonte-charge : étage 37, par <@8>, le 5 novembre 2026.",
            setup.posts.single().message.text,
        )
    }

    @Test
    @DisplayName("Un seul record : seul son jeu est écrit ; le premier du mois s'écrit « 1er » ; sans auteur connu, pas de « par »")
    fun listsOnlyTheGamesWithARecord() {
        val setup = Setup()
        setup.store.bests["monte-charge"] = GameRecord(12, "8", Instant.parse("2026-11-30T23:30:00Z"))
        setup.refresh()
        assertEquals("Les records des petits jeux\nMonte-charge : étage 12, par <@8>, le 1er décembre 2026.", setup.posts.single().message.text)

        setup.advance(RecordsBoard.REFRESH)
        setup.store.bests.clear()
        setup.store.bests["comptage"] = GameRecord(5, null, at)
        setup.refresh()
        assertEquals("Les records des petits jeux\nComptage : 5, le 5 novembre 2026.", setup.edits().single().message.text)
    }

    @Test
    @DisplayName("Un second passage modifie le message sur place, sans le reposter")
    fun editsInPlace() {
        val setup = Setup()
        setup.refresh()
        setup.advance(RecordsBoard.REFRESH)
        setup.store.bests["comptage"] = GameRecord(3, "9", at)
        setup.refresh()
        setup.advance(RecordsBoard.REFRESH)
        setup.refresh()

        assertEquals(1, setup.posts.size)
        val edits = setup.edits()
        assertEquals(2, edits.size)
        assertEquals(setup.posts.single().messageId, edits.last().messageId)
        assertEquals("Les records des petits jeux\nComptage : 3, par <@9>, le 5 novembre 2026.", edits.last().message.text)
    }

    @Test
    @DisplayName("Une rafale de records produit une seule écriture, puis, la minuterie avancée, le tableau montre l'état final")
    fun smoothsABurstOfRecords() {
        val setup = Setup()
        setup.refresh()
        assertEquals(1, setup.posts.size, "le premier appel écrit tout de suite")

        for (best in 1L..10L) {
            setup.store.bests["comptage"] = GameRecord(best, "9", at)
            setup.refresh()
        }
        assertEquals(1, setup.posts.size)
        assertTrue(setup.edits().isEmpty(), "la rafale n'écrit rien tant que l'attente dure")
        assertEquals(1, setup.timer.pending, "une seule mise à jour attend")

        setup.advance(RecordsBoard.REFRESH.minusSeconds(1))
        assertTrue(setup.edits().isEmpty())

        setup.advance(Duration.ofSeconds(1))
        assertEquals("Les records des petits jeux\nComptage : 10, par <@9>, le 5 novembre 2026.", setup.edits().single().message.text)
        assertEquals(1, setup.posts.size)
        assertEquals(0, setup.timer.pending)
    }

    @Test
    @DisplayName("La mise à jour différée attend le reste du délai ; une fois passée, le tableau s'écrit de nouveau tout de suite")
    fun waitsForTheRestOfTheDelay() {
        val setup = Setup()
        setup.refresh()
        setup.advance(Duration.ofSeconds(20))
        setup.store.bests["monte-charge"] = GameRecord(4, "8", at)
        setup.refresh()

        setup.advance(Duration.ofSeconds(39))
        assertTrue(setup.edits().isEmpty())
        setup.advance(Duration.ofSeconds(1))
        assertEquals(1, setup.edits().size)

        setup.store.bests["monte-charge"] = GameRecord(5, "8", at)
        setup.refresh()
        assertEquals(1, setup.edits().size, "l'écriture différée compte : le délai repart de là")
        assertEquals(1, setup.timer.pending)

        setup.advance(RecordsBoard.REFRESH)
        assertEquals(2, setup.edits().size, "la mise à jour différée a écrit à la fin du délai")
        setup.advance(RecordsBoard.REFRESH)
        setup.store.bests["monte-charge"] = GameRecord(6, "8", at)
        setup.refresh()
        assertEquals(3, setup.edits().size)
        assertEquals("Les records des petits jeux\nMonte-charge : étage 6, par <@8>, le 5 novembre 2026.", setup.edits().last().message.text)
    }

    @Test
    @DisplayName("Une mise à jour différée ne modifie rien si le tableau montre déjà le même texte")
    fun skipsAnUnchangedBoard() {
        val setup = Setup()
        setup.store.bests["comptage"] = GameRecord(3, "9", at)
        setup.refresh()
        setup.refresh()
        setup.advance(RecordsBoard.REFRESH)

        assertEquals(1, setup.posts.size)
        assertTrue(setup.edits().isEmpty())
        assertEquals(0, setup.timer.pending)
    }

    @Test
    @DisplayName("Sans salon panthéon, rien n'est programmé")
    fun schedulesNothingWithoutThePantheon() {
        val setup = Setup(withPantheon = false)
        setup.refresh()
        setup.refresh()

        assertEquals(0, setup.timer.pending)
    }

    @Test
    @DisplayName("Après un redémarrage, le message est retrouvé par sa clé et modifié, pas reposté")
    fun findsTheMessageAgain() {
        val setup = Setup()
        setup.refresh()
        val again = RecordsBoard(BotContext(setup.fake, ServerIds(emptyMap(), mapOf("pantheon" to setup.pantheon!!)), FakeTimer(), ServerPhase.ATTENTE), setup.store, setup.messages, zone)
        runBlocking { again.refresh() }

        assertEquals(1, setup.posts.size)
        assertEquals(1, setup.edits().size)
    }

    @Test
    @DisplayName("Un message qui a disparu est reposté, et la nouvelle clé est notée")
    fun repostsAVanishedMessage() {
        val setup = Setup()
        setup.messages.refs["pantheon-records"] = BotMessageRef(setup.pantheon!!, "disparu")
        setup.refresh()

        val post = setup.posts.single()
        assertEquals(BotMessageRef(setup.pantheon, post.messageId), setup.messages.refs["pantheon-records"])
        assertTrue(setup.edits().isEmpty())
    }

    @Test
    @DisplayName("Sans salon panthéon dans la phase, rien n'est écrit")
    fun doesNothingWithoutThePantheon() {
        val setup = Setup(withPantheon = false)
        setup.refresh()

        assertTrue(setup.fake.posted.isEmpty())
        assertTrue(setup.messages.refs.isEmpty())
    }

    @Test
    @DisplayName("Un refus de Discord ne casse pas l'appelant et ne note rien")
    fun swallowsARefusal() {
        val setup = Setup(wrap = { RefusingServer(it) })
        setup.store.bests["comptage"] = GameRecord(3, "9", at)
        setup.refresh()

        assertFalse(setup.messages.refs.containsKey("pantheon-records"))
        assertTrue(setup.fake.posted.isEmpty())
    }

    private fun Setup.edits(): List<FakeDiscordServer.Posted> = fake.edits.filter { it.channelId == pantheon }

    /** Un Discord qui refuse tout message du bot. */
    private class RefusingServer(private val inner: FakeDiscordServer) : DiscordServer by inner {
        override suspend fun post(channelId: String, message: BotMessage): String = throw DiscordRefusal("droit manquant")

        override suspend fun send(channelId: String, text: String) {
            throw DiscordRefusal("droit manquant")
        }
    }
}
