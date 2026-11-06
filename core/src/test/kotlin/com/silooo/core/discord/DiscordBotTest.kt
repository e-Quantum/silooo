package com.silooo.core.discord

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Le bot : ce qui tombe tombe seul, sans lever, et le jeton ne s'affiche jamais. */
class DiscordBotTest {
    private val config = BotConfig(BotMode.OUI, BotToken("jeton-secret-de-test"), "123456789", ServerPhase.ATTENTE)

    private class Recording : Feature {
        override val name = "enregistreur"
        val seen = CopyOnWriteArrayList<DiscordEvent>()

        override suspend fun handle(event: DiscordEvent) {
            seen += event
        }
    }

    @Test
    @DisplayName("Une connexion refusée rend null sans lever")
    fun refusedConnection() {
        val bot = DiscordBot(config, { throw IllegalStateException("401 pour jeton-secret-de-test") })
        assertNull(bot.runGuarded())
        bot.close()
    }

    @Test
    @DisplayName("Un plan illisible arrête le bot avant la connexion")
    fun unreadablePlan() {
        val bot = DiscordBot(config, { fail("le bot ne doit pas se connecter") }, loadPlan = { throw PlanException("plan cassé") })
        assertNull(bot.runGuarded())
    }

    @Test
    @DisplayName("Sans fonction, le plan s'applique, le journal reçoit le compte rendu, et close coupe la connexion")
    fun appliesPlanAndCloses() {
        val connection = FakeConnection()
        val bot = DiscordBot(config, { connection })
        val report = checkNotNull(bot.runGuarded())
        assertEquals(emptyList<String>(), report.failures)
        assertEquals(1, connection.server.messages.size)
        assertFalse(connection.closed)
        bot.close()
        assertTrue(connection.closed)
        bot.close()
    }

    @Test
    @DisplayName("Avec des fonctions, le bot sert les événements jusqu'à close, qui coupe aussi la connexion")
    fun servesEventsUntilClosed() {
        val connection = FakeConnection()
        val recording = Recording()
        val bot = DiscordBot(config, { connection }, features = { listOf(recording) })
        val result = AtomicReference<PlanReport?>()
        val thread = Thread({ result.set(bot.runGuarded()) }, "test-bot").apply { start() }
        awaitUntil("le bot écoute") { connection.listening }
        connection.emit(DiscordEvent.MemberLeft("42", Instant.EPOCH))
        awaitUntil("un événement") { recording.seen.size == 1 }
        assertTrue(thread.isAlive)
        bot.close()
        thread.join(2000)
        assertFalse(thread.isAlive)
        assertTrue(connection.closed)
        assertTrue(checkNotNull(result.get()).channelIds.containsKey(ServerPlan.JOURNAL_ID))
    }

    @Test
    fun `Un journal expose empeche de demarrer les fonctions`() = runBlocking {
        val connection = FakeConnection()
        val plan = ServerPlan.fromClasspath()
        val initial = PlanApplier(plan, connection.server, ServerPhase.ATTENTE, false).run()
        connection.server.publicAccessOverrides[initial.channelIds.getValue(ServerPlan.JOURNAL_ID)] = PublicChannelAccess(true, true, false)
        var built = false
        val bot = DiscordBot(config, { connection }, features = { built = true; listOf(Recording()) })
        val thread = Thread { bot.runGuarded() }
        try {
            thread.start()
            thread.join(2000)
            assertFalse(thread.isAlive)
            assertFalse(built)
            assertFalse(connection.listening)
        } finally {
            bot.close()
            thread.join(2000)
        }
    }

    /** Le serveur que les fonctions reçoivent, une fois la boucle lancée ; le bot est ensuite fermé. */
    private fun serverSeenByFeatures(config: BotConfig, connection: FakeConnection): DiscordServer {
        val seen = AtomicReference<DiscordServer?>()
        val bot = DiscordBot(config, { connection }, features = { context ->
            seen.set(context.server)
            listOf(Recording())
        })
        val thread = Thread({ bot.runGuarded() }, "test-bot").apply { start() }
        awaitUntil("les fonctions") { seen.get() != null }
        bot.close()
        thread.join(2000)
        return checkNotNull(seen.get())
    }

    @Test
    @DisplayName("QG fermé par défaut : les fonctions reçoivent le serveur fermé, qui retient les messages privés hors staff")
    fun closedByDefault() {
        assertFalse(config.public)
        val connection = FakeConnection()
        val server = serverSeenByFeatures(config, connection)
        assertTrue(server is ClosedServer)
        assertFalse(runBlocking { server.direct("42", BotMessage("Bonjour")) })
        assertTrue(connection.server.directs.isEmpty())
    }

    @Test
    @DisplayName("QG public : les fonctions reçoivent le vrai serveur")
    fun publicKeepsRealServer() {
        val connection = FakeConnection()
        val server = serverSeenByFeatures(config.copy(public = true), connection)
        assertSame(connection.server, server)
        assertTrue(runBlocking { server.direct("42", BotMessage("Bonjour")) })
        assertEquals(1, connection.server.directs.size)
    }

    @Test
    @DisplayName("À blanc, le bot ne sert aucun événement même avec des fonctions")
    fun dryRunDoesNotLoop() {
        val connection = FakeConnection()
        val bot = DiscordBot(config.copy(mode = BotMode.A_BLANC), { connection }, features = { listOf(Recording()) })
        val report = checkNotNull(bot.runGuarded())
        assertTrue(report.dryRun)
        assertFalse(connection.listening)
        bot.close()
    }

    @Test
    @DisplayName("Le jeton ne s'affiche ni dans la configuration ni dans un message d'erreur masqué")
    fun tokenNeverShows() {
        val token = BotToken("jeton-secret-de-test")
        assertEquals("***", token.toString())
        assertEquals("erreur 401 avec *** dedans", token.mask("erreur 401 avec jeton-secret-de-test dedans"))
        assertEquals("", token.mask(null))
        assertFalse(config.toString().contains("jeton-secret"))
        assertTrue(config.toString().contains("mode=oui"))
        assertFalse(config.dryRun)
        assertTrue(config.copy(mode = BotMode.A_BLANC).dryRun)
    }

    @Test
    @DisplayName("Les mots des variables se lisent sans tenir compte de la casse ni des espaces")
    fun words() {
        assertEquals(BotMode.A_BLANC, BotMode.parse(" A-Blanc "))
        assertNull(BotMode.parse("peut-être"))
        assertEquals(ServerPhase.OUVERT, ServerPhase.parse("Ouvert"))
        assertNull(ServerPhase.parse(""))
    }
}
