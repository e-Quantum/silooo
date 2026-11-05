package com.silooo.core.discord

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.sql.Connection
import org.slf4j.LoggerFactory

/** Un envoyeur qui note ce qu'on lui donne, sans réseau. [outcome] dit si Discord « prend » chaque message. */
class FakeSender(
    override val enabled: Boolean = true,
    private val outcome: (String) -> Boolean = { true },
) : DiscordSender {
    val sent = mutableListOf<String>()

    override suspend fun send(message: String): Boolean {
        sent += message
        return outcome(message)
    }
}

/** Le curseur du suiveur en mémoire : aucune connexion n'est ouverte. */
class FakeCursorStore(var cursor: Long? = null, var latest: Long = 0) : DiscordCursorStore {
    val writes = mutableListOf<Long>()

    override fun read(connection: Connection): Long? = cursor

    override fun write(connection: Connection, lastEventId: Long) {
        cursor = lastEventId
        writes += lastEventId
    }

    override fun latestEventId(connection: Connection): Long = latest
}

/** Un transport qui rejoue [steps] dans l'ordre (la dernière étape se répète) : une [DiscordReply] est rendue, une exception est levée. */
class FakeTransport(vararg steps: Any) : DiscordTransport {
    private val queue = ArrayDeque(steps.toList())
    val bodies = mutableListOf<String>()

    override suspend fun post(body: String): DiscordReply {
        bodies += body
        val step = if (queue.size > 1) queue.removeFirst() else queue.first()
        return when (step) {
            is DiscordReply -> step
            is Exception -> throw step
            else -> error("Étape de transport inconnue : $step")
        }
    }
}

/** Capte les lignes de journal du paquet `discord` le temps d'un test (même au niveau INFO, que les tests coupent d'ordinaire). */
class LogCapture(name: String = "com.silooo.core.discord") : AutoCloseable {
    private val logger = LoggerFactory.getLogger(name) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private val before: Level? = logger.level

    init {
        appender.start()
        logger.level = Level.ALL
        logger.addAppender(appender)
    }

    /** Les lignes telles qu'elles seraient écrites, arguments remplacés. */
    val lines: List<String> get() = appender.list.map { it.formattedMessage }

    override fun close() {
        logger.detachAppender(appender)
        logger.level = before
    }
}
