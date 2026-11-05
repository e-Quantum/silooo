package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Les mots du bot pour le monte-charge (`docs/discord.md`, « Les cartes et les tableaux d'Oooscar »), **à valider par
 * Kr**. Sans emoji, sans mot-réclame (`design/README.md`, « Écrire sans slop »). Les membres sont cités par leur
 * identifiant : le message ne prévient personne. Les nombres viennent des définitions et de l'état, jamais d'ici.
 */
object ElevatorTexts {
    /** La clé du message dans `discord_bot_messages`. */
    const val KEY = "monte-charge"

    /** L'identifiant du bouton de Discord. */
    const val PLUS_ID = "monte-charge-plus"
    const val PLUS_LABEL = "+1 étage"

    const val GROUND = "Le monte-charge est au rez-de-chaussée. Appuie pour monter d'un étage."

    /** La réponse au membre du staff qui fait redescendre, à lui seul. */
    const val GONE_DOWN = "Le monte-charge est redescendu."

    /** Le message tel qu'il est à l'étage [floor] ; [last] : le dernier membre à avoir appuyé. */
    fun atFloor(floor: Long, last: String?, record: GameRecord?): String {
        if (floor == 0L) return GROUND + recordPart(record)
        val lastPart = if (last == null) "" else " Dernier à appuyer : <@" + last + ">."
        return "Le monte-charge est à l'étage " + floor + "." + lastPart + recordPart(record)
    }

    /** Le message d'après le geste du staff. */
    fun staffReset(record: GameRecord?): String = "Le staff fait redescendre le monte-charge." + recordPart(record)

    /** La réponse à un membre qui rappuie trop tôt, à lui seul ; [elapsed] et [wait] en minutes. */
    fun tooSoon(elapsed: Long, wait: Int): String =
        "Tu as appuyé il y a " + (if (elapsed < 1) "moins d'une minute" else minutes(elapsed)) + ". " +
            "Le monte-charge accepte un étage " + (if (wait == 1) "chaque minute" else "toutes les " + wait + " minutes") + " par membre."

    /** La ligne du journal du bot quand le staff fait redescendre. */
    fun journalReset(userId: String, floor: Long, at: Instant, zone: ZoneId): String =
        "<@" + userId + "> a fait redescendre le monte-charge de l'étage " + floor + ", " + AdmissionTexts.moment(at, zone) + "."

    private fun recordPart(record: GameRecord?): String =
        if (record == null) "" else " Record : étage " + record.best + (record.holderId?.let { ", par <@" + it + ">" } ?: "") + "."

    private fun minutes(count: Long): String = count.toString() + (if (count > 1) " minutes" else " minute")
}

/**
 * Le monte-charge (`docs/discord.md`, « Les cartes et les tableaux d'Oooscar »). `#monte-charge` porte un seul message,
 * retrouvé par sa clé ([BotMessageStore]) et modifié sur place à chaque démarrage et à chaque appui, avec le bouton
 * « +1 étage » et l'image de l'étage si le service en a une. Un membre fait monter l'étage d'un cran, au plus une fois
 * par [ElevatorRules.waitMinutes] (la carte des derniers appuis vit en mémoire : un redémarrage la vide). Un membre du
 * staff ou de la modération qui appuie fait redescendre à zéro. Un étage au-dessus du record l'écrit et le porte au
 * Panthéon ([RecordsBoard]). L'étage et le dernier membre vivent en base (`discord_games`). Un serveur sans
 * `#monte-charge` : la fonction ne fait rien.
 *
 * [images] rend l'image de l'étage, ou `null` : par défaut `discord/monte-charge/<étage>.png` sur le classpath.
 */
class ElevatorFeature(
    private val context: BotContext,
    private val rules: ElevatorRules,
    private val zone: ZoneId,
    private val store: GameStore,
    private val messages: BotMessageStore,
    private val records: RecordsBoard,
    private val journal: BotJournal,
    private val images: (Long) -> ImageFile? = ::classpathImage,
) : Feature {
    override val name = "monte-charge"

    /** Le dernier appui accepté de chaque membre ordinaire. */
    private val lastPress = mutableMapOf<String, Instant>()

    override suspend fun start() {
        val channel = context.ids.channel(CHANNEL) ?: return
        try {
            val state = store.state(Games.ELEVATOR)
            showLiving(context.server, messages, ElevatorTexts.KEY, channel, message(state, store.record(Games.ELEVATOR)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Bot Discord, monte-charge : le message n'a pas pu être posé : {}", e.toString())
        }
        records.refresh()
    }

    override suspend fun handle(event: DiscordEvent) {
        if (event !is DiscordEvent.ButtonPressed || event.id != ElevatorTexts.PLUS_ID || event.user.isBot) return
        if (context.ids.channel(CHANNEL) == null) return
        if (staffRoles().any { it in event.user.roleIds }) lower(event) else press(event)
    }

    /** Le staff fait redescendre : la réponse part d'abord (Discord l'attend sous trois secondes), le message suit. */
    private suspend fun lower(event: DiscordEvent.ButtonPressed) {
        val now = context.timer.now()
        val before = store.state(Games.ELEVATOR)
        store.saveState(Games.ELEVATOR, GameState.START, now)
        event.reply.send(BotMessage(ElevatorTexts.GONE_DOWN), onlyToUser = true)
        val record = store.record(Games.ELEVATOR)
        redraw(event, BotMessage(ElevatorTexts.staffReset(record), image = images(0L), buttons = listOf(plusButton()), card = true))
        if (before.value > 0L) journal.line(ElevatorTexts.journalReset(event.user.id, before.value, now, zone))
    }

    private suspend fun press(event: DiscordEvent.ButtonPressed) {
        val user = event.user.id
        val now = context.timer.now()
        val last = lastPress[user]
        val wait = Duration.ofMinutes(rules.waitMinutes.toLong())
        if (last != null && Duration.between(last, now) < wait) {
            val text = ElevatorTexts.tooSoon(Duration.between(last, now).toMinutes(), rules.waitMinutes)
            return event.reply.send(BotMessage(text), onlyToUser = true)
        }
        val floor = store.state(Games.ELEVATOR).value + 1
        store.saveState(Games.ELEVATOR, GameState(floor, user), now)
        lastPress.values.removeAll { Duration.between(it, now) >= wait }
        lastPress[user] = now
        val known = store.record(Games.ELEVATOR)
        val record = if (floor > (known?.best ?: 0L)) GameRecord(floor, user, now).also { store.saveRecord(Games.ELEVATOR, it) } else known
        event.reply.acknowledge()
        redraw(event, message(GameState(floor, user), record))
        if (record !== known) records.refresh()
    }

    /** Remet le message qui portait le bouton à neuf ; un refus de Discord va au journal du service, l'état est déjà écrit. */
    private suspend fun redraw(event: DiscordEvent.ButtonPressed, message: BotMessage) {
        val channel = event.channelId ?: return
        try {
            context.server.edit(channel, event.messageId, message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Bot Discord, monte-charge : le message n'a pas pu être modifié : {}", e.toString())
        }
    }

    private fun message(state: GameState, record: GameRecord?): BotMessage =
        BotMessage(
            ElevatorTexts.atFloor(state.value, state.lastUserId, record),
            image = images(state.value),
            buttons = listOf(plusButton()),
            card = true,
        )

    private fun plusButton() = ButtonSpec(ElevatorTexts.PLUS_ID, ElevatorTexts.PLUS_LABEL, ButtonStyle.PRIMARY)

    private fun staffRoles(): Set<String> = setOfNotNull(context.ids.role(STAFF), context.ids.role(MODERATION))

    private companion object {
        const val CHANNEL = "monte-charge"
        const val STAFF = "staff"
        const val MODERATION = "moderation"

        val log = LoggerFactory.getLogger(ElevatorFeature::class.java)
    }
}

/** L'image `discord/monte-charge/<étage>.png` du classpath ; `null` si le service n'en a pas pour cet étage. */
private fun classpathImage(floor: Long): ImageFile? {
    val bytes = ElevatorFeature::class.java.classLoader.getResourceAsStream("discord/monte-charge/" + floor + ".png")?.use { it.readBytes() }
    return if (bytes == null || bytes.isEmpty()) null else ImageFile(floor.toString() + ".png", bytes)
}
