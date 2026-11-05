package com.silooo.core.discord

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Une fonction du bot (l'accueil, les coulisses, l'XP, le rapport du lundi) : elle reçoit les événements et agit
 * sur le serveur par le [BotContext] reçu à sa construction. Une exception dans une fonction s'écrit dans les
 * journaux et n'arrête ni la boucle ni les autres fonctions.
 */
interface Feature {
    val name: String

    /** Les commandes de la fonction : [BotRuntime] pose l'union de celles de toutes les fonctions, une fois, avant [start]. */
    val commands: List<CommandSpec> get() = emptyList()

    /** Au démarrage, le plan appliqué et les commandes posées : les tableaux, les minuteries. */
    suspend fun start() {}

    suspend fun handle(event: DiscordEvent) {}
}

/** Ce qu'une fonction a sous la main : le serveur, les identifiants du plan, le temps, la phase. */
class BotContext(
    val server: DiscordServer,
    val ids: ServerIds,
    val timer: Timer,
    val phase: ServerPhase,
)

/** Les identifiants Discord des rôles et salons du plan, par identifiant du plan ; `null` : absent du serveur. */
class ServerIds(val roles: Map<String, String>, val channels: Map<String, String>) {
    fun role(planId: String): String? = roles[planId]

    fun channel(planId: String): String? = channels[planId]

    companion object {
        val NONE = ServerIds(emptyMap(), emptyMap())
    }
}

/** Une action programmée ; [cancel] l'annule tant qu'elle n'a pas couru (ou arrête une répétition). */
fun interface Scheduled {
    fun cancel()
}

/** L'horloge et les minuteries du bot, remplaçables dans les tests : tout le temps passe par ici. */
interface Timer {
    fun now(): Instant

    /** [action] une fois, après [delay]. */
    fun after(delay: Duration, action: suspend () -> Unit): Scheduled

    /** [action] toutes les [period], la première fois après [period]. */
    fun every(period: Duration, action: suspend () -> Unit): Scheduled
}

/** Les minuteries sur la boucle du bot : chaque action court sur le fil des événements, jamais en même temps qu'eux. */
class CoroutineTimer(
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val mask: (String) -> String = { it },
) : Timer {
    override fun now(): Instant = clock.instant()

    override fun after(delay: Duration, action: suspend () -> Unit): Scheduled {
        val job = scope.launch {
            delay(delay.toMillis())
            guarded(action)
        }
        return Scheduled { job.cancel() }
    }

    override fun every(period: Duration, action: suspend () -> Unit): Scheduled {
        val job = scope.launch {
            while (isActive) {
                delay(period.toMillis())
                guarded(action)
            }
        }
        return Scheduled { job.cancel() }
    }

    private suspend fun guarded(action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Bot Discord, minuterie : {}", mask(e.toString()))
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(CoroutineTimer::class.java)
    }
}

/**
 * La boucle du bot (ADR 0007) : un seul fil, une file bornée d'événements (si Discord va plus vite que le bot, les
 * plus vieux partent et sont comptés), chaque fonction isolée. [offer] et [stop] s'appellent de n'importe quel fil ;
 * [run] occupe le fil de l'appelant jusqu'à [stop].
 */
class BotRuntime(
    private val server: DiscordServer,
    private val ids: ServerIds,
    private val phase: ServerPhase,
    private val build: (BotContext) -> List<Feature>,
    private val clock: Clock = Clock.systemUTC(),
    capacity: Int = DEFAULT_CAPACITY,
    private val mask: (String) -> String = { it },
) {
    private val queue = BoundedQueue<DiscordEvent>(capacity)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var running = true

    /** Les événements abandonnés parce que la file était pleine. */
    val dropped: Long get() = queue.dropped

    fun offer(event: DiscordEvent) {
        queue.offer(event)
        wake.trySend(Unit)
    }

    fun stop() {
        running = false
        wake.trySend(Unit)
    }

    /** Construit et démarre les fonctions, puis sert les événements un à un jusqu'à [stop] ; les minuteries s'arrêtent avec elle. */
    suspend fun run() {
        coroutineScope {
            val timer = CoroutineTimer(this, clock, mask)
            val features = build(BotContext(server, ids, timer, phase))
            val commands = features.flatMap { it.commands }.distinctBy { it.name }
            if (commands.isNotEmpty()) guarded("commandes", "démarrage") { server.installCommands(commands) }
            for (feature in features) guarded(feature.name, "démarrage") { feature.start() }
            while (running) {
                val event = queue.poll()
                if (event == null) {
                    wake.receive()
                    continue
                }
                val what = event::class.simpleName.orEmpty()
                for (feature in features) guarded(feature.name, what) { feature.handle(event) }
            }
            coroutineContext.job.children.forEach { it.cancel() }
        }
    }

    private suspend fun guarded(feature: String, what: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Bot Discord, {} ({}) : {}", feature, what, mask(e.toString()))
        }
    }

    companion object {
        /** Un millier d'événements en attente : au-delà, le bot a un problème, et les perdre vaut mieux que la mémoire. */
        const val DEFAULT_CAPACITY = 1000
        private val log = LoggerFactory.getLogger(BotRuntime::class.java)
    }
}
