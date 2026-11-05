package com.silooo.core.discord

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Les mots du bot pour le comptage (`docs/discord.md`, « Les cartes et les tableaux d'Oooscar »), **à valider par Kr**.
 * Sans emoji, sans mot-réclame (`design/README.md`, « Écrire sans slop »). Les membres sont cités par leur identifiant :
 * aucun message ne prévient personne.
 */
object CountingTexts {
    /** L'emoji du serveur dont Oooscar valide un nombre. */
    const val EMOJI = "donar"

    /** « <@7> a cassé la chaîne à 57 : il fallait 58. Le record est 120, par <@9>. On repart de 1. » */
    fun wrongNumber(userId: String, reached: Long, record: GameRecord?): String =
        "<@" + userId + "> a cassé la chaîne à " + reached + " : il fallait " + (reached + 1) + ". " + recordSentence(record) + RESTART

    /** « <@7> a joué deux fois de suite : la chaîne s'arrête à 57. Le record est 120, par <@9>. On repart de 1. » */
    fun twiceInARow(userId: String, reached: Long, record: GameRecord?): String =
        "<@" + userId + "> a joué deux fois de suite : la chaîne s'arrête à " + reached + ". " + recordSentence(record) + RESTART

    /** La chaîne n'a pas commencé : un autre nombre que 1 ne casse rien. */
    fun notStarted(userId: String, written: Long): String = "<@" + userId + "> a écrit " + written + " : la chaîne commence à 1."

    /** La ligne du journal du bot quand une chaîne casse. */
    fun journalBroken(userId: String, reached: Long): String = "Comptage : la chaîne s'arrête à " + reached + ", cassée par <@" + userId + ">."

    private const val RESTART = " On repart de 1."

    private fun recordSentence(record: GameRecord?): String =
        when {
            record == null -> "Pas encore de record."
            record.holderId == null -> "Le record est " + record.best + "."
            else -> "Le record est " + record.best + ", par <@" + record.holderId + ">."
        }
}

/**
 * Le comptage (`docs/discord.md`, « Les cartes et les tableaux d'Oooscar »). `#comptage` est réservé aux nombres :
 * chacun écrit le suivant, jamais deux fois de suite. Un message qui n'est pas un entier est supprimé. Le bon nombre
 * reçoit l'emoji `:donar:` et un record battu est écrit puis porté au Panthéon ([RecordsBoard]). Un mauvais nombre, ou
 * le même membre deux fois, casse la chaîne : Oooscar le dit, rappelle le record et on repart de 1. L'état vit en base
 * (`discord_games`) : il survit au redémarrage. Un serveur sans `#comptage` : la fonction ne fait rien.
 */
class CountingFeature(
    private val context: BotContext,
    private val store: GameStore,
    private val records: RecordsBoard,
    private val journal: BotJournal,
) : Feature {
    override val name = "comptage"

    override suspend fun start() {
        if (context.ids.channel(CHANNEL) == null) return
        records.refresh()
    }

    override suspend fun handle(event: DiscordEvent) {
        if (event !is DiscordEvent.MessagePosted || event.author.isBot) return
        val channel = context.ids.channel(CHANNEL) ?: return
        if (event.channelId != channel) return
        val number = NUMBER.matchEntire(event.text.trim())?.value?.toLongOrNull()
        if (number == null) {
            attempt("suppression") { context.server.deleteMessage(channel, event.messageId) }
            return
        }
        val state = store.state(Games.COUNTING)
        val record = store.record(Games.COUNTING)
        val user = event.author.id
        when {
            number == state.value + 1 && user != state.lastUserId -> accept(channel, event.messageId, number, user, record)
            number != state.value + 1 && state.value == 0L -> context.server.send(channel, CountingTexts.notStarted(user, number))
            number != state.value + 1 -> breakChain(channel, user, state.value, CountingTexts.wrongNumber(user, state.value, record))
            else -> breakChain(channel, user, state.value, CountingTexts.twiceInARow(user, state.value, record))
        }
    }

    /** Le bon nombre : l'état s'écrit d'abord, puis l'emoji, puis le record s'il tombe. */
    private suspend fun accept(channel: String, messageId: String, number: Long, user: String, record: GameRecord?) {
        val now = context.timer.now()
        store.saveState(Games.COUNTING, GameState(number, user), now)
        attempt("réaction") { context.server.react(channel, messageId, CountingTexts.EMOJI) }
        if (number > (record?.best ?: 0L)) {
            store.saveRecord(Games.COUNTING, GameRecord(number, user, now))
            records.refresh()
        }
    }

    /** La chaîne casse à [reached] : l'état repart de zéro, Oooscar le dit dans le salon et le journal en garde la trace. */
    private suspend fun breakChain(channel: String, user: String, reached: Long, text: String) {
        store.saveState(Games.COUNTING, GameState.START, context.timer.now())
        context.server.send(channel, text)
        journal.line(CountingTexts.journalBroken(user, reached))
    }

    /** Un geste vers Discord dont le refus ne défait rien : il va au journal du service. */
    private suspend fun attempt(what: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Bot Discord, comptage, {} : {}", what, e.toString())
        }
    }

    private companion object {
        const val CHANNEL = "comptage"

        /** Chiffres seulement, dix-huit au plus : un entier de cette taille tient dans un `Long`. */
        val NUMBER = Regex("[0-9]{1,18}")

        val log = LoggerFactory.getLogger(CountingFeature::class.java)
    }
}
