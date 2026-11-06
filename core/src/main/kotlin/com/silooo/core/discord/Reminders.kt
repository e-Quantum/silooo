package com.silooo.core.discord

import com.silooo.core.discord.linking.GameDiscordLinks
import java.time.Duration
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.CancellationException

/** Player-facing wording for opt-in reminders; pending validation by Kr. */
object ReminderTexts {
    const val COMMAND = "rappels"
    const val STOP_PREFIX = "rappels-arreter:"
    const val STOP_LABEL = "Arrêter ces rappels"
    const val UNLINKED = "Relie d'abord ton compte Minecraft avec /lier pour régler les rappels."
    const val HELP = "Avec /rappels, choisis une catégorie, active-la ou coupe-la. Tu peux aussi régler ton fuseau IANA."
    const val BAD_ZONE = "Ce fuseau IANA n'est pas reconnu."
    const val STOPPED = "Tes rappels sont arrêtés."
    const val UNKNOWN_CATEGORY = "Cette catégorie de rappel n'existe pas."
    const val SILO_UNAVAILABLE = "Le rappel Silo plein n'est pas disponible : le jeu ne publie pas encore un état canonique de capacité du Silo."
    const val CONFIRM = "Préférences enregistrées."
    const val MENU_ID = "rappels-choix"

    fun message(category: ReminderCategory, payload: String): String = when (category) {
        ReminderCategory.SILO -> "Ton Silo est plein."
        ReminderCategory.VOTE -> "Un vote vient d'être confirmé pour ton compte."
        ReminderCategory.VOTE_STREAK -> "Ta série de votes a atteint un palier."
        ReminderCategory.ORDER_DONE -> "La commande du jour est terminée."
        ReminderCategory.COMPANY_INVITE -> "Tu as reçu une invitation d'entreprise dans Silooo."
        ReminderCategory.PRESTIGE -> "Ton entreprise vient de gagner un prestige."
        ReminderCategory.GEMS -> {
            val gems = runCatching { Json.parseToJsonElement(payload).jsonObject["gemmes"]?.jsonPrimitive?.longOrNull }.getOrNull()
            "Tu as reçu" + (gems?.let { " $it" } ?: "") + " Gemmes."
        }
    }
}

/** Opt-in settings and private delivery of canonical game events. */
class ReminderFeature(
    private val context: BotContext,
    private val rules: ReminderRules,
    private val zone: ZoneId,
    private val store: ReminderStore,
    private val game: GameDiscordLinks,
) : Feature {
    override val name = "rappels"
    override val commands = listOf(
        CommandSpec(
            ReminderTexts.COMMAND,
            "Régler tes rappels Silooo",
            listOf(
                CommandOption("categorie", "Catégorie à régler", choices = ReminderCategory.entries.filter { it != ReminderCategory.SILO }.map { it.column }),
                CommandOption("actif", "Activer ou couper", choices = listOf("oui", "non")),
                CommandOption("fuseau", "Fuseau IANA, par exemple Europe/Paris"),
            ),
        ),
    )

    override suspend fun start() {
        context.timer.every(Duration.ofSeconds(30)) {
            pollSources()
            deliverDue()
        }
    }

    override suspend fun handle(event: DiscordEvent) {
        when {
            event is DiscordEvent.CommandUsed && event.name == ReminderTexts.COMMAND -> configure(event)
            event is DiscordEvent.ButtonPressed && event.id == WelcomeTexts.REMINDERS_BUTTON_ID -> showSettings(event)
            event is DiscordEvent.ButtonPressed && event.id.startsWith(ReminderTexts.STOP_PREFIX) -> stop(event)
            event is DiscordEvent.MenuChosen && event.id == ReminderTexts.MENU_ID -> choose(event)
        }
    }

    private suspend fun configure(event: DiscordEvent.CommandUsed) {
        if (event.user.isBot) return
        // This proof is checked before preferences or any private delivery are read.
        if (game.findByDiscord(event.user.id) == null) return answer(event.reply, ReminderTexts.UNLINKED)
        val zoneText = event.options["fuseau"]?.trim()?.takeIf { it.isNotEmpty() }
        if (zoneText != null && runCatching { ZoneId.of(zoneText) }.isFailure) return answer(event.reply, ReminderTexts.BAD_ZONE)
        val categoryText = event.options["categorie"]
        val category = categoryText?.let(ReminderCategory::from)
        if (categoryText != null && category == null) return answer(event.reply, ReminderTexts.UNKNOWN_CATEGORY)
        if (category == ReminderCategory.SILO) return answer(event.reply, ReminderTexts.SILO_UNAVAILABLE)
        val enable = event.options["actif"]?.let { it == "oui" }
        if (category == null && zoneText == null) {
            val preferences = store.preferences(event.user.id)
            val enabled = preferences?.enabled.orEmpty().joinToString(", ") { it.column }.ifEmpty { "aucun" }
            return event.reply.send(settings("Rappels actifs : $enabled. Fuseau : ${preferences?.timeZone ?: zone.id}."), onlyToUser = true)
        }
        store.set(event.user.id, category, enable ?: false, zoneText, context.timer.now())
        answer(event.reply, ReminderTexts.CONFIRM)
    }

    private suspend fun showSettings(event: DiscordEvent.ButtonPressed) {
        if (event.user.isBot) return
        if (game.findByDiscord(event.user.id) == null) return answer(event.reply, ReminderTexts.UNLINKED)
        val preferences = store.preferences(event.user.id)
        val enabled = preferences?.enabled.orEmpty().joinToString(", ") { it.column }.ifEmpty { "aucun" }
        event.reply.send(settings("Rappels actifs : $enabled. Fuseau : ${preferences?.timeZone ?: zone.id}."), onlyToUser = true)
    }

    private suspend fun choose(event: DiscordEvent.MenuChosen) {
        if (event.user.isBot) return
        if (game.findByDiscord(event.user.id) == null) return answer(event.reply, ReminderTexts.UNLINKED)
        val selected = event.values.mapNotNull(ReminderCategory::from).toSet()
        if (selected.size != event.values.size) return answer(event.reply, ReminderTexts.UNKNOWN_CATEGORY)
        if (ReminderCategory.SILO in selected) return answer(event.reply, ReminderTexts.SILO_UNAVAILABLE)
        for (category in selected) store.set(event.user.id, category, true, null, context.timer.now())
        event.reply.edit(BotMessage(ReminderTexts.CONFIRM))
    }

    private suspend fun stop(event: DiscordEvent.ButtonPressed) {
        if (event.user.isBot) return
        if (game.findByDiscord(event.user.id) == null) return answer(event.reply, ReminderTexts.UNLINKED)
        val category = ReminderCategory.from(event.id.removePrefix(ReminderTexts.STOP_PREFIX))
            ?: return answer(event.reply, ReminderTexts.UNKNOWN_CATEGORY)
        store.set(event.user.id, category, false, null, context.timer.now())
        answer(event.reply, "Le rappel ${category.column} est arrêté.")
    }

    /** The game event cursor and delivery ids make polling/restarts safe. Unknown events are skipped unchanged. */
    private suspend fun pollSources() {
        var cursor = store.cursor()
        while (true) {
            val events = store.eventsAfter(cursor)
            if (events.isEmpty()) return
            for (source in events) {
                for ((category, xuid, text) in relevant(source)) {
                    for ((discordId, linkedXuid) in store.linkedRecipients(xuid, category)) {
                        if (linkedXuid != xuid) continue
                        val proof = game.findByDiscord(discordId) ?: continue
                        if (proof.xuid != linkedXuid) continue
                        val prefs = store.preferences(discordId) ?: continue
                        if (category !in prefs.enabled) continue
                        store.enqueue(ReminderDelivery("game:${source.id}:${category.column}:$discordId", discordId, category, text, source.occurredAt))
                    }
                }
                cursor = source.id
                store.advanceCursor(cursor)
            }
            if (events.size < 250) return
        }
    }

    private suspend fun deliverDue() {
        val now = context.timer.now()
        for (delivery in store.due(now, zone, rules)) {
            val identity = game.findByDiscord(delivery.userId)
            if (identity == null) {
                store.finish(delivery.id, sent = false, failure = "no-active-game-link", now = now)
                continue
            }
            // The proof precedes the preference lookup and every DM attempt.
            val prefs = store.preferences(delivery.userId)
            if (prefs == null || delivery.category !in prefs.enabled) {
                store.finish(delivery.id, sent = false, failure = "opt-out", now = now)
                continue
            }
            if (!store.claim(delivery.id, now, zone, rules)) continue
            var failure: String? = null
            val delivered = try {
                context.server.direct(delivery.userId, BotMessage(delivery.message, buttons = listOf(ButtonSpec(ReminderTexts.STOP_PREFIX + delivery.category.column, ReminderTexts.STOP_LABEL, ButtonStyle.DANGER))))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = if (e is DiscordRefusal) "discord-refused" else "discord-${e.javaClass.simpleName}".take(255)
                false
            }
            if (!delivered && failure == null) failure = "discord-refused"
            store.finish(delivery.id, delivered, failure, context.timer.now())
        }
    }

    private fun relevant(event: ReminderSourceEvent): List<Triple<ReminderCategory, String?, String>> {
        val fields = runCatching { Json.parseToJsonElement(event.payload).jsonObject }.getOrNull().orEmpty()
        fun number(name: String) = fields[name]?.jsonPrimitive?.longOrNull
        val action = fields["action"]?.jsonPrimitive?.content
        val payload = event.payload
        val user = event.xuid
        return when (event.kind) {
            "GEMMES_RECUES" -> if (user == null) emptyList() else listOf(Triple(ReminderCategory.GEMS, user, ReminderTexts.message(ReminderCategory.GEMS, payload)))
            "INVITATION" -> if (user == null) emptyList() else listOf(Triple(ReminderCategory.COMPANY_INVITE, user, ReminderTexts.message(ReminderCategory.COMPANY_INVITE, payload)))
            "COMMANDE_TERMINEE" -> if (user == null) emptyList() else listOf(Triple(ReminderCategory.ORDER_DONE, user, ReminderTexts.message(ReminderCategory.ORDER_DONE, payload)))
            "PRESTIGE_ENTREPRISE" -> if (user == null) emptyList() else listOf(Triple(ReminderCategory.PRESTIGE, user, ReminderTexts.message(ReminderCategory.PRESTIGE, payload)))
            "ANNONCE" -> when (action) {
                "vote" -> if (user == null) emptyList() else buildList {
                    add(Triple(ReminderCategory.VOTE, user, ReminderTexts.message(ReminderCategory.VOTE, payload)))
                    if ((number("streakBonusLingots") ?: 0L) > 0L) add(Triple(ReminderCategory.VOTE_STREAK, user, ReminderTexts.message(ReminderCategory.VOTE_STREAK, payload)))
                }
                "voteParty" -> listOf(Triple(ReminderCategory.VOTE, null, "La Vote party vient d'être déclenchée."))
                "prestige" -> if (user == null) emptyList() else listOf(Triple(ReminderCategory.PRESTIGE, user, ReminderTexts.message(ReminderCategory.PRESTIGE, payload)))
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private suspend fun answer(reply: Reply, text: String) = reply.send(BotMessage(text), onlyToUser = true)

    private fun settings(text: String): BotMessage = BotMessage(
        text + " Active des catégories dans le menu. Pour en couper une, utilise /rappels avec catégorie et actif=non. " + ReminderTexts.SILO_UNAVAILABLE,
        menu = MenuSpec(
            ReminderTexts.MENU_ID,
            "Catégories de rappels",
            ReminderCategory.entries.filter { it != ReminderCategory.SILO }.map { MenuOption(it.column, it.column) },
            maxValues = ReminderCategory.entries.size - 1,
        ),
        buttons = listOf(ButtonSpec(WelcomeTexts.REMINDERS_BUTTON_ID, WelcomeTexts.REMINDERS_BUTTON_LABEL)),
    )
}
