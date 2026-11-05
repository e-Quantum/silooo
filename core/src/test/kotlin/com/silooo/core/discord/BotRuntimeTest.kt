package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** La boucle du bot : un fil, une file bornée, des fonctions isolées, des minuteries qui s'arrêtent avec elle. */
class BotRuntimeTest {
    private class Recording : Feature {
        override val name = "enregistreur"
        val seen = CopyOnWriteArrayList<DiscordEvent>()

        @Volatile
        var started = false

        override suspend fun start() {
            started = true
        }

        override suspend fun handle(event: DiscordEvent) {
            seen += event
        }
    }

    private class Faulty : Feature {
        override val name = "fautive"

        override suspend fun start() {
            throw IllegalStateException("démarrage raté")
        }

        override suspend fun handle(event: DiscordEvent) {
            throw IllegalStateException("traitement raté")
        }
    }

    private fun joined(id: String) = DiscordEvent.MemberJoined(DiscordUser(id, "membre $id"), Instant.EPOCH)

    private fun runtime(fake: FakeDiscordServer, capacity: Int = BotRuntime.DEFAULT_CAPACITY, build: (BotContext) -> List<Feature>) =
        BotRuntime(fake, ServerIds.NONE, ServerPhase.ATTENTE, build, capacity = capacity)

    private fun BotRuntime.inThread(): Thread = Thread({ runBlocking { run() } }, "test-bot").apply { start() }

    @Test
    @DisplayName("Les événements arrivent dans l'ordre, une fonction qui lève n'arrête ni la boucle ni les autres")
    fun servesEventsInOrderAndIsolatesFailures() {
        val recording = Recording()
        val runtime = runtime(FakeDiscordServer()) { listOf(Faulty(), recording) }
        val thread = runtime.inThread()
        runtime.offer(joined("1"))
        runtime.offer(joined("2"))
        awaitUntil("deux événements") { recording.seen.size == 2 }
        assertEquals(listOf("1", "2"), recording.seen.map { (it as DiscordEvent.MemberJoined).user.id })
        assertTrue(recording.started)
        runtime.stop()
        thread.join(2000)
        assertFalse(thread.isAlive)
        assertEquals(0, runtime.dropped)
    }

    @Test
    @DisplayName("Une file pleine abandonne les plus vieux et les compte")
    fun dropsOldestWhenFull() {
        val recording = Recording()
        val runtime = runtime(FakeDiscordServer(), capacity = 2) { listOf(recording) }
        runtime.offer(joined("1"))
        runtime.offer(joined("2"))
        runtime.offer(joined("3"))
        val thread = runtime.inThread()
        awaitUntil("deux événements") { recording.seen.size == 2 }
        assertEquals(listOf("2", "3"), recording.seen.map { (it as DiscordEvent.MemberJoined).user.id })
        assertEquals(1, runtime.dropped)
        runtime.stop()
        thread.join(2000)
        assertFalse(thread.isAlive)
    }

    @Test
    @DisplayName("Les minuteries courent sur la boucle et s'arrêtent avec elle")
    fun timersRunOnTheLoopAndStopWithIt() {
        val fake = FakeDiscordServer()
        val channel = runBlocking {
            val category = fake.createCategory(CategorySpec("Staff", null))
            fake.createChannel(category, ChannelSpec("journal", ChannelKind.TEXTE, null, emptySet(), null, false))
        }
        val ticks = AtomicInteger()
        val feature = object : Feature {
            override val name = "minuteries"

            @Volatile
            lateinit var context: BotContext

            override suspend fun start() {
                context.timer.after(Duration.ofMillis(10)) { context.server.send(channel, "tic") }
                context.timer.every(Duration.ofMillis(15)) { ticks.incrementAndGet() }
            }
        }
        val runtime = runtime(fake) { context ->
            feature.context = context
            listOf(feature)
        }
        val thread = runtime.inThread()
        awaitUntil("le message et deux tics") { fake.messages.size == 1 && ticks.get() >= 2 }
        assertEquals("tic", fake.messages.single().second)
        runtime.stop()
        thread.join(2000)
        assertFalse(thread.isAlive)
        val after = ticks.get()
        Thread.sleep(60)
        assertEquals(after, ticks.get())
    }

    @Test
    @DisplayName("Un arrêt avant tout événement rend la main tout de suite")
    fun stopsWhenIdle() {
        val runtime = runtime(FakeDiscordServer()) { emptyList() }
        val thread = runtime.inThread()
        Thread.sleep(20)
        runtime.stop()
        thread.join(2000)
        assertFalse(thread.isAlive)
    }

    private class WithCommands(override val name: String, names: List<String>) : Feature {
        override val commands = names.map { CommandSpec(it, "Une commande de test") }

        @Volatile
        var startedWith: Int = -1

        @Volatile
        var installedAtStart: Int = -1
        lateinit var fake: FakeDiscordServer

        override suspend fun start() {
            installedAtStart = fake.commandInstalls
            startedWith = commands.size
        }
    }

    @Test
    @DisplayName("Les commandes de toutes les fonctions sont posées une seule fois, avant leur démarrage, sans doublon")
    fun installsTheUnionOfCommandsOnce() {
        val fake = FakeDiscordServer()
        val first = WithCommands("une", listOf("spoil", "sondage")).also { it.fake = fake }
        val second = WithCommands("deux", listOf("sondage", "rappels")).also { it.fake = fake }
        val runtime = runtime(fake) { listOf(first, second, Recording()) }
        val thread = runtime.inThread()
        awaitUntil("les commandes") { fake.commandInstalls == 1 && second.startedWith >= 0 }
        assertEquals(listOf("spoil", "sondage", "rappels"), fake.commands.map { it.name })
        assertEquals(1, first.installedAtStart, "posées avant le démarrage des fonctions")
        runtime.offer(joined("1"))
        Thread.sleep(30)
        assertEquals(1, fake.commandInstalls)
        runtime.stop()
        thread.join(2000)
        assertFalse(thread.isAlive)
    }

    @Test
    @DisplayName("Sans aucune commande déclarée, le bot n'en pose pas et n'efface rien")
    fun installsNothingWithoutCommands() {
        val fake = FakeDiscordServer()
        val recording = Recording()
        val runtime = runtime(fake) { listOf(recording) }
        val thread = runtime.inThread()
        awaitUntil("le démarrage") { recording.started }
        assertEquals(0, fake.commandInstalls)
        runtime.stop()
        thread.join(2000)
        assertFalse(thread.isAlive)
    }
}
