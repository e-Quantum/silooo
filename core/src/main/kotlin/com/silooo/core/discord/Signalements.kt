package com.silooo.core.discord

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class SignalementRules(
    val cooldown: Duration = Duration.ofSeconds(30),
    val formLifetime: Duration = Duration.ofMinutes(10),
    val retention: Duration = Duration.ofDays(30),
    val purgeInterval: Duration = Duration.ofHours(6),
    val reasonMaxLength: Int = 500,
    val contentMaxLength: Int = 1000,
) {
    init {
        require(!cooldown.isNegative && !cooldown.isZero)
        require(!formLifetime.isNegative && !formLifetime.isZero)
        require(retention == Duration.ofDays(30)) { "les dossiers sont conservés trente jours" }
        require(!purgeInterval.isNegative && !purgeInterval.isZero)
        require(reasonMaxLength in 1..1000)
        require(contentMaxLength in 1..1000)
    }
}

/** Signalement privé, expirable et à usage unique ; aucune lecture d'historique n'est effectuée. */
class SignalementFeature(
    private val context: BotContext,
    private val store: SignalementStore,
    private val rules: SignalementRules,
    private val canView: suspend (channelId: String, reporterId: String) -> Boolean,
) : Feature {
    override val name = "signalements"
    override val commands = listOf(CommandSpec("Signaler", "", kind = CommandKind.MESSAGE))
    private val forms = ConcurrentHashMap<String, Pending>()
    private val random = SecureRandom()

    private data class Pending(val reporterId: String, val channelId: String, val messageId: String, val expiresAt: Instant)

    override suspend fun start() {
        context.ids.channel(SIGNALMENTS_CHANNEL)
            ?: throw DiscordRefusal("le salon des signalements n'est pas configuré")
        context.timer.every(rules.purgeInterval) {
            val now = context.timer.now()
            forms.entries.removeIf { it.value.expiresAt <= now }
            purgeExpired(now.minus(rules.retention))
        }
        purgeExpired(context.timer.now().minus(rules.retention))
    }

    override suspend fun handle(event: DiscordEvent) {
        when (event) {
            is DiscordEvent.MessageReported -> begin(event)
            is DiscordEvent.FormSubmitted -> if (event.id.startsWith(FORM_PREFIX)) submit(event)
            is DiscordEvent.ButtonPressed -> if (event.id.startsWith(ACTION_PREFIX)) action(event)
            else -> Unit
        }
    }

    private suspend fun begin(event: DiscordEvent.MessageReported) {
        val now = context.timer.now()
        forms.entries.removeIf { it.value.expiresAt <= now }
        if (event.user.isBot) return event.reply.send(BotMessage("Un message de bot ne peut pas être signalé."))
        if (event.channelId.isBlank() || event.messageId.isBlank() || !canView(event.channelId, event.user.id)) {
            return event.reply.send(BotMessage("Tu ne peux pas signaler ce message depuis ce compte."))
        }
        val target = context.server.message(event.channelId, event.messageId)
            ?: return event.reply.send(BotMessage("Ce message n'est plus disponible."))
        if (target.authorId == event.user.id || target.bot) {
            return event.reply.send(BotMessage("Ce message ne peut pas être signalé."))
        }
        if (forms.size >= MAX_PENDING_FORMS) {
            return event.reply.send(BotMessage("Les formulaires de signalement sont temporairement occupés. Réessaie dans quelques minutes."))
        }
        val nonce = token()
        forms[nonce] = Pending(event.user.id, event.channelId, event.messageId, context.timer.now().plus(rules.formLifetime))
        event.reply.form(FormSpec(FORM_PREFIX + nonce, "Signaler un message", listOf(
            FormField("reason", "Pourquoi le signales-tu ?", long = true, maxLength = rules.reasonMaxLength,
                placeholder = "Décris brièvement le problème"),
        )))
    }

    private suspend fun submit(event: DiscordEvent.FormSubmitted) {
        val nonce = event.id.removePrefix(FORM_PREFIX)
        val pending = forms[nonce]
        val now = context.timer.now()
        if (pending == null || pending.reporterId != event.user.id) {
            return event.reply.send(BotMessage("Ce formulaire a expiré. Relance le signalement depuis le message."))
        }
        if (pending.expiresAt <= now) {
            forms.remove(nonce, pending)
            return event.reply.send(BotMessage("Ce formulaire a expiré. Relance le signalement depuis le message."))
        }
        if (!forms.remove(nonce, pending)) return event.reply.send(BotMessage("Ce formulaire a déjà été utilisé."))
        val reason = event.fields["reason"]?.trim().orEmpty()
        if (reason.isBlank() || reason.length > rules.reasonMaxLength || !canView(pending.channelId, event.user.id)) {
            return event.reply.send(BotMessage("Le motif est vide ou le message n'est plus accessible."))
        }
        val target = context.server.message(pending.channelId, pending.messageId)
        if (target == null || target.authorId == event.user.id || target.bot) {
            return event.reply.send(BotMessage("Ce message n'est plus disponible."))
        }
        event.reply.acknowledge()
        val report = store.create(event.user.id, pending.channelId, pending.messageId, reason, now, rules.cooldown)
            ?: return event.reply.send(BotMessage("Tu as déjà signalé ce message ou tu dois attendre avant d'en envoyer un autre."))
        val channel = context.ids.channel(SIGNALMENTS_CHANNEL)
            ?: return event.reply.send(BotMessage("Le salon des signalements n'est pas configuré."))
        val text = "Signalement #${report.id}\n" +
            "Auteur : ${target.authorName} (${target.authorId})\n" +
            "Publié : ${target.at}\n" +
            "Message : ${context.server.messageLink(pending.channelId, pending.messageId)}\n" +
            "Contenu signalé : ${target.text.take(rules.contentMaxLength)}\n" +
            "Motif : ${reason.take(rules.reasonMaxLength)}\n" +
            "État : nouveau"
        val posted = try {
            context.server.post(channel, BotMessage("Signalement #${report.id} : préparation du dossier."))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            try { store.publicationFailed(report.id) } catch (_: Exception) {}
            org.slf4j.LoggerFactory.getLogger(SignalementFeature::class.java).warn("Signalement #{} réservé, publication à reprendre manuellement", report.id)
            return event.reply.send(BotMessage("Ton signalement est enregistré, mais la publication du dossier n'a pas abouti."))
        }
        try { store.posted(report.id, channel, posted) }
        catch (e: kotlinx.coroutines.CancellationException) {
            try { context.server.deleteMessage(channel, posted) } catch (_: Exception) {}
            throw e
        }
        catch (e: Exception) {
            try { context.server.deleteMessage(channel, posted) } catch (_: Exception) {}
            try { store.publicationFailed(report.id) } catch (_: Exception) {}
            org.slf4j.LoggerFactory.getLogger(SignalementFeature::class.java).warn("Signalement #{} : pointeur de publication non conservé, placeholder supprimé", report.id)
            return event.reply.send(BotMessage("Ton signalement est enregistré, mais le dossier privé n'a pas pu être préparé."))
        }
        try {
            context.server.edit(channel, posted, BotMessage(text, buttons = listOf(
                ButtonSpec(actionId(report.id, "claim"), "Prendre en charge", ButtonStyle.PRIMARY),
                ButtonSpec(actionId(report.id, "resolved"), "Résolu", ButtonStyle.NEUTRAL),
                ButtonSpec(actionId(report.id, "dismissed"), "Sans suite", ButtonStyle.DANGER),
            ), card = true))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            try { context.server.deleteMessage(channel, posted) } catch (_: Exception) {}
            try { store.publicationFailed(report.id) } catch (_: Exception) {}
            org.slf4j.LoggerFactory.getLogger(SignalementFeature::class.java).warn("Signalement #{} : édition du dossier refusée, placeholder nettoyé", report.id)
            return event.reply.send(BotMessage("Ton signalement est enregistré, mais sa publication n'est pas terminée. Le dossier privé sera nettoyé."))
        }
        try { store.published(report.id) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            org.slf4j.LoggerFactory.getLogger(SignalementFeature::class.java).warn("Signalement #{} publié, état de publication non actualisé", report.id)
        }
        event.reply.send(BotMessage("Merci, ton signalement est enregistré."))
    }

    private suspend fun action(event: DiscordEvent.ButtonPressed) {
        val parts = event.id.split(':')
        if (parts.size != 3) return event.reply.send(BotMessage("Cette action n'est pas valide."))
        val roleIds = context.server.freshRolesOf(event.user.id)
        val staff = context.ids.role(STAFF_ROLE_ID)
        val moderation = context.ids.role(MODERATION_ROLE_ID)
        if ((staff == null || staff !in roleIds) && (moderation == null || moderation !in roleIds)) {
            return event.reply.send(BotMessage("Cette action est réservée au staff et à la modération."))
        }
        val reportsChannel = context.ids.channel(SIGNALMENTS_CHANNEL)
        if (reportsChannel == null || event.channelId != reportsChannel || !canView(reportsChannel, event.user.id)) {
            return event.reply.send(BotMessage("Tu ne peux pas accéder aux dossiers de modération."))
        }
        event.reply.acknowledge()
        val id = parts[1].toLongOrNull() ?: return event.reply.send(BotMessage("Ce dossier n'existe pas."))
        val state = when (parts[2]) { "claim" -> "pris"; "resolved" -> "resolu"; "dismissed" -> "sans-suite"; else -> return event.reply.send(BotMessage("Cette action n'est pas valide.")) }
        val current = context.server.message(event.channelId!!, event.messageId)
        if (current == null || current.bot.not() || !current.text.startsWith("Signalement #$id\n")) {
            return event.reply.send(BotMessage("Cette carte de dossier n'est plus disponible."))
        }
        val updated = store.setState(id, state, event.user.id, context.timer.now())
        if (!updated) return event.reply.send(BotMessage("Ce dossier est déjà pris en charge ou fermé."))
        val preserved = current.text.lineSequence().filterNot { it.startsWith("État :") || it.startsWith("Clôturé par :") }.joinToString("\n")
        val closed = state == "resolu" || state == "sans-suite"
        val labels = mapOf("pris" to "Pris en charge", "resolu" to "Résolu", "sans-suite" to "Sans suite")
        val assignment = if (state == "pris") "Pris en charge par : ${event.user.name}"
            else current.text.lineSequence().firstOrNull { it.startsWith("Pris en charge par :") } ?: "Pris en charge par : aucun membre"
        val closing = if (closed) "\nClôturé par : ${event.user.name}" else ""
        context.server.edit(event.channelId, event.messageId, BotMessage(
            "$preserved\nÉtat : ${labels.getValue(state)}\n$assignment$closing",
            buttons = if (closed) listOf(
                ButtonSpec(actionId(id, "claim"), "Prendre en charge", disabled = true),
                ButtonSpec(actionId(id, "resolved"), "Résolu", disabled = true),
                ButtonSpec(actionId(id, "dismissed"), "Sans suite", disabled = true),
            ) else listOf(
                ButtonSpec(actionId(id, "claim"), "Prendre en charge", ButtonStyle.PRIMARY),
                ButtonSpec(actionId(id, "resolved"), "Résolu", ButtonStyle.NEUTRAL),
                ButtonSpec(actionId(id, "dismissed"), "Sans suite", ButtonStyle.DANGER),
            ), card = true,
        ))
        event.reply.send(BotMessage("Dossier #$id : action enregistrée (état $state)."))
    }

    private fun token(): String = ByteArray(24).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun actionId(id: Long, state: String) = "$ACTION_PREFIX$id:$state"

    private suspend fun purgeExpired(before: Instant) {
        for (report in store.expired(before)) {
            try {
                if (report.postChannelId != null && report.postMessageId != null) {
                    context.server.deleteMessage(report.postChannelId, report.postMessageId)
                }
                store.purge(report.id, before)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: DiscordMessageMissing) { store.purge(report.id, before) }
            catch (e: Exception) {
                org.slf4j.LoggerFactory.getLogger(SignalementFeature::class.java)
                    .warn("Signalement #{} expiré : suppression du contexte impossible, reprise requise", report.id)
            }
        }
    }

    companion object {
        const val MAX_PENDING_FORMS = 1000
        const val SIGNALMENTS_CHANNEL = "signalements"
        const val STAFF_ROLE_ID = "staff"
        const val MODERATION_ROLE_ID = "moderation"
        const val FORM_PREFIX = "report-submit:"
        const val ACTION_PREFIX = "report-action:"
    }
}
