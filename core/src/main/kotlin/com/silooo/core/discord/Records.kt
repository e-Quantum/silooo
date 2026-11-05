package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Les mots du bot pour les records des petits jeux (`docs/discord.md`, « Les cartes et les tableaux d'Oooscar »), **à
 * valider par Kr**. Sans emoji, sans mot-réclame (`design/README.md`, « Écrire sans slop »). Les membres sont cités
 * par leur identifiant : le tableau n'en prévient aucun.
 */
object RecordTexts {
    /** La clé du tableau dans `discord_bot_messages`. */
    const val KEY = "pantheon-records"

    const val TITLE = "Les records des petits jeux"
    const val NONE = "Aucun record pour l'instant."

    /** « Comptage : 120, par <@7>, le 5 novembre 2026. » */
    fun counting(record: GameRecord, zone: ZoneId): String = "Comptage : " + record.best + holder(record) + ", " + day(record, zone) + "."

    /** « Monte-charge : étage 37, par <@7>, le 5 novembre 2026. » */
    fun elevator(record: GameRecord, zone: ZoneId): String =
        "Monte-charge : étage " + record.best + holder(record) + ", " + day(record, zone) + "."

    /** Le titre puis une ligne par jeu qui a un record ; [NONE] sans aucun. */
    fun board(counting: GameRecord?, elevator: GameRecord?, zone: ZoneId): String {
        val lines = listOfNotNull(counting?.let { counting(it, zone) }, elevator?.let { elevator(it, zone) })
        return TITLE + "\n" + if (lines.isEmpty()) NONE else lines.joinToString("\n")
    }

    private fun holder(record: GameRecord): String = record.holderId?.let { ", par <@" + it + ">" } ?: ""

    /** Le jour seul, sans l'heure : « le 5 novembre 2026 ». */
    private fun day(record: GameRecord, zone: ZoneId): String = AdmissionTexts.moment(record.reachedAt, zone).substringBefore(" à ")
}

/**
 * Le tableau des records des petits jeux, dans `#panthéon` : un seul message, retrouvé par sa clé ([BotMessageStore]) et
 * modifié sur place, reposté seulement s'il a disparu. Les deux jeux le partagent : chacun l'appelle à son démarrage et
 * quand un record tombe. Un salon absent de la phase, ou un refus de Discord, ne casse jamais le jeu qui appelle : le
 * refus va au journal du service.
 *
 * Discord limite les modifications d'un message : le tableau est écrit tout de suite s'il ne l'a pas été depuis [REFRESH]
 * (le premier appel, au démarrage, l'est donc toujours). Sinon une seule mise à jour est programmée pour la fin de
 * l'attente et les appels suivants ne font rien de plus : elle lit l'état final au moment d'écrire, et ne modifie rien
 * si le texte est déjà celui du tableau.
 */
class RecordsBoard(
    private val context: BotContext,
    private val store: GameStore,
    private val messages: BotMessageStore,
    private val zone: ZoneId,
) {
    /** Le moment de la dernière écriture tentée, ou `null` avant la première. */
    private var lastWrite: Instant? = null

    /** Le texte du tableau tel que Discord l'a reçu en dernier. */
    private var shown: String? = null

    /** La mise à jour différée qui attend, ou `null` : une seule à la fois. */
    private var pending: Scheduled? = null

    suspend fun refresh() {
        if (context.ids.channel(PANTHEON) == null || pending != null) return
        val last = lastWrite
        val rest = if (last == null) Duration.ZERO else REFRESH.minus(Duration.between(last, context.timer.now()))
        if (rest.isZero || rest.isNegative) {
            write(onlyIfChanged = false)
        } else {
            pending = context.timer.after(rest) {
                pending = null
                write(onlyIfChanged = true)
            }
        }
    }

    private suspend fun write(onlyIfChanged: Boolean) {
        val channel = context.ids.channel(PANTHEON) ?: return
        try {
            val text = RecordTexts.board(store.record(Games.COUNTING), store.record(Games.ELEVATOR), zone)
            if (onlyIfChanged && text == shown) return
            lastWrite = context.timer.now()
            showLiving(context.server, messages, RecordTexts.KEY, channel, BotMessage(text, card = true))
            shown = text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Bot Discord, records : le tableau n'a pas pu être mis à jour : {}", e.toString())
        }
    }

    companion object {
        /** Le temps minimum entre deux écritures du tableau. */
        val REFRESH: Duration = Duration.ofSeconds(60)

        private const val PANTHEON = "pantheon"
        private val log = LoggerFactory.getLogger(RecordsBoard::class.java)
    }
}

private val livingLog = LoggerFactory.getLogger("com.silooo.core.discord.LivingMessage")

/**
 * Un message du bot qui vit : modifié sur place s'il est connu sous [key] dans [channel] et encore là, posté (et noté)
 * sinon. Rend où il se trouve maintenant.
 */
internal suspend fun showLiving(server: DiscordServer, messages: BotMessageStore, key: String, channel: String, message: BotMessage): BotMessageRef {
    val known = messages.find(key)
    if (known != null && known.channelId == channel) {
        try {
            server.edit(known.channelId, known.messageId, message)
            return known
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            livingLog.info("Bot Discord, message {} : il a disparu, il est reposté ({})", key, e.toString())
        }
    }
    val posted = BotMessageRef(channel, server.post(channel, message))
    messages.save(key, posted.channelId, posted.messageId)
    return posted
}
