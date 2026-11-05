package com.silooo.core.discord

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/** Où un tableau vit après [LiveMessage.show] : son salon, son message, et s'il vient d'être posté plutôt que modifié. */
internal class Showing(val channelId: String, val messageId: String, val created: Boolean)

/**
 * Un message du bot qui vit (un tableau du QG) : retrouvé par sa [key] dans [BotMessageStore], modifié sur place, reposté
 * (et noté) seulement s'il a disparu. [channelId] : le salon où il vit, `null` quand la phase du serveur ne l'a pas encore.
 */
internal class LiveMessage(
    private val context: BotContext,
    private val store: BotMessageStore,
    private val key: String,
    private val channelId: String?,
) {
    /** Montre [message] ; `null` sans salon, rien n'est alors envoyé. */
    suspend fun show(message: BotMessage): Showing? {
        val channel = channelId ?: return null
        val known = store.find(key)
        if (known != null && known.channelId == channel) {
            try {
                context.server.edit(known.channelId, known.messageId, message)
                return Showing(channel, known.messageId, created = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.info("Bot Discord, tableau {} : le message a disparu, il est reposté ({})", key, e.toString())
            }
        }
        val posted = context.server.post(channel, message)
        store.save(key, channel, posted)
        return Showing(channel, posted, created = true)
    }

    private companion object {
        val log = LoggerFactory.getLogger(LiveMessage::class.java)
    }
}

/**
 * Une table de souvenirs bornée, du bot en cours : à [capacity] entrées, la plus ancienne part. Pour ce que Discord ne
 * redit pas dans un événement (le forum d'un sujet, l'auteur d'un sujet) et que le bot relit sinon. Le bot n'a qu'un
 * fil : aucune synchronisation.
 */
internal class Remembered<V : Any>(private val capacity: Int = DEFAULT_CAPACITY) {
    private val entries = object : LinkedHashMap<String, V>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>): Boolean = size > capacity
    }

    operator fun set(key: String, value: V) {
        entries[key] = value
    }

    operator fun get(key: String): V? = entries[key]

    companion object {
        const val DEFAULT_CAPACITY = 5000
    }
}

/**
 * Les forums qui portent les sujets où l'on écrit. Un événement de réaction ne dit que le salon de la réaction, qui est
 * le sujet pour un message de forum ; le forum se retient quand le bot voit un message du sujet passer.
 */
internal class ThreadParents {
    private val parents = Remembered<String>()

    fun note(event: DiscordEvent.MessagePosted) {
        event.parentChannelId?.let { parents[event.channelId] = it }
    }

    /** Le forum du sujet [channelId], ou `null` : pas un sujet, ou un sujet que le bot n'a pas vu depuis son démarrage. */
    fun parentOf(channelId: String): String? = parents[channelId]
}

/** Un titre, puis une ligne par rang tant que le message de Discord tient ; sans ligne, le titre puis [empty]. */
internal fun rankedText(title: String, lines: List<String>, empty: String): String {
    if (lines.isEmpty()) return title + "\n" + empty
    val text = StringBuilder(title)
    for (line in lines) {
        if (text.length + 1 + line.length > BotMessage.MAX_TEXT) break
        text.append('\n').append(line)
    }
    return text.toString()
}
