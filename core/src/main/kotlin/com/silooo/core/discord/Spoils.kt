package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Les mots du bot pour les coulisses (`docs/discord.md`, « Le serveur en direct »), **à valider par Kr**. Sans emoji,
 * sans mot-réclame (`design/README.md`, « Écrire sans slop »). Les réponses vont au staff seul ; le spoil et sa ligne
 * d'état sont lus par les membres.
 */
object SpoilTexts {
    const val COMMAND = "spoil"
    const val COMMAND_DESCRIPTION = "Dépose un spoil des coulisses"
    const val TEXT_OPTION = "texte"
    const val TEXT_DESCRIPTION = "Le texte du spoil"
    const val IMAGE_OPTION = "image"
    const val IMAGE_DESCRIPTION = "Une image png ou gif, 16 Mo au plus"
    const val THRESHOLD_OPTION = "seuil"
    const val THRESHOLD_DESCRIPTION = "Les réactions qui publient le suivant (un entier positif)"

    /** Le texte d'un spoil, en caractères : le reste du message de 2 000 est pris par l'en-tête et la ligne d'état. */
    const val TEXT_MAX = 1800

    const val NOT_STAFF = "Cette commande est pour le staff."
    const val NO_CHANNEL = "Le salon des coulisses n'existe pas sur le serveur : rien n'est déposé."
    const val EMPTY_TEXT = "Le texte du spoil est vide : rien n'est déposé."
    const val BAD_THRESHOLD = "Le seuil doit être un entier positif : le nombre de réactions qui publie le suivant. Rien n'est déposé."
    const val PUBLISH_FAILED = "Le spoil est déposé, mais Discord a refusé la publication. Elle sera retentée dans la minute."

    fun tooLong(length: Int): String =
        "Le texte fait " + WelcomeTexts.thousands(length) + " caractères : " + WelcomeTexts.thousands(TEXT_MAX) + " au plus. Rien n'est déposé."

    fun refusedFile(name: String): String = "Le fichier « " + name + " » est refusé : png ou gif, 16 Mo au plus. Rien n'est déposé."

    /** Le premier spoil part tout de suite ; [channel] : la mention du salon. */
    fun deposited(number: Int, channel: String, threshold: Int): String =
        "Spoil n°" + number + " publié dans " + channel + ". Seuil du n°" + (number + 1) + " : " + reactions(threshold) + "."

    /** Un spoil qui attend que [current], le dernier publié, atteigne son seuil. */
    fun queued(number: Int, current: Spoil): String =
        "Spoil n°" + number + " déposé : il partira quand le n°" + current.number + " aura " + reactions(current.nextThreshold) +
            " (" + WelcomeTexts.thousands(current.reactions) + " pour l'instant)."

    /** Un spoil qui attend d'autres spoils déposés avant lui. */
    fun queuedFar(number: Int): String = "Spoil n°" + number + " déposé : il partira après le n°" + (number - 1) + "."

    /** « Les derniers : n°3 87 réactions, n°2 95, n°1 120. » : du plus récent au plus ancien. */
    fun lastOnes(spoils: List<Spoil>): String {
        val parts = spoils.mapIndexed { index, spoil ->
            "n°" + spoil.number + " " + (if (index == 0) reactions(spoil.reactions) else WelcomeTexts.thousands(spoil.reactions))
        }
        return "Les derniers : " + parts.joinToString(", ") + "."
    }

    /** Le message du spoil : l'en-tête (avec le rôle, qui seul est prévenu), le texte, puis la ligne d'état s'il y en a une. */
    fun post(number: Int, text: String, roleId: String?, line: String?): String =
        (if (roleId == null) "" else "<@&" + roleId + "> ") + "Spoil n°" + number + "\n\n" + text + (if (line == null) "" else "\n\n" + line)

    /** La ligne d'état : le suivant [next], le seuil à atteindre et le compte des membres qui ont réagi. */
    fun waiting(next: Int, threshold: Int, count: Int): String =
        "Spoil n°" + next + " à " + reactions(threshold) + " : " + WelcomeTexts.thousands(count) + "."

    /** Le seuil est atteint la nuit : le suivant part à [hour] h. */
    fun waitingForMorning(next: Int, threshold: Int, count: Int, hour: Int): String =
        waiting(next, threshold, count) + " Il part à " + hour + " h."

    fun missing(next: Int): String = "Spoil n°" + next + " : pas encore déposé."

    private fun reactions(count: Int): String = WelcomeTexts.thousands(count) + (if (count > 1) " réactions" else " réaction")
}

/**
 * Les coulisses (`docs/discord.md`, « Le serveur en direct »). Le staff dépose les spoils à l'avance avec `/spoil` ;
 * le bot publie le premier dans `#coulisses` avec le rôle Coulisses, compte chaque minute les membres qui ont réagi
 * avec l'emoji du serveur (une fois chacun, les bots exclus), tient à jour la ligne d'état sous le spoil courant, et
 * publie le suivant quand le seuil est atteint. Entre [SpoilRules.nightStartHour] h et [SpoilRules.nightEndHour] h,
 * il attend le matin. Sans spoil déposé, il attend le dépôt. Le compte est en base et Discord est relu à chaque tic :
 * un redémarrage ne perd rien.
 */
class SpoilFeature(
    private val context: BotContext,
    private val rules: SpoilRules,
    private val zone: ZoneId,
    private val store: SpoilStore,
) : Feature {
    override val name = "coulisses"

    override val commands = listOf(
        CommandSpec(
            SpoilTexts.COMMAND,
            SpoilTexts.COMMAND_DESCRIPTION,
            options = listOf(
                CommandOption(SpoilTexts.TEXT_OPTION, SpoilTexts.TEXT_DESCRIPTION, required = true),
                CommandOption(SpoilTexts.IMAGE_OPTION, SpoilTexts.IMAGE_DESCRIPTION, kind = OptionKind.FICHIER),
                CommandOption(SpoilTexts.THRESHOLD_OPTION, SpoilTexts.THRESHOLD_DESCRIPTION),
            ),
            staffOnly = true,
        ),
    )

    /** Le spoil dont la ligne d'état est affichée, et cette ligne : on ne modifie le message que si elle change. */
    private var shownNumber: Int? = null
    private var shownLine: String? = null

    /** La publication de nuit, programmée une seule fois pour le matin. */
    private var morning: Scheduled? = null

    private val gate = Mutex()

    override suspend fun start() {
        if (channel() == null) return
        context.timer.every(Duration.ofSeconds(rules.refreshSeconds.toLong())) { tick() }
    }

    override suspend fun handle(event: DiscordEvent) {
        if (event is DiscordEvent.CommandUsed && event.name == SpoilTexts.COMMAND) deposit(event)
    }

    private suspend fun deposit(event: DiscordEvent.CommandUsed) {
        val staff = context.ids.role(STAFF) ?: return refuse(event, STAFF_ROLE_UNAVAILABLE)
        if (staff !in event.user.roleIds) return refuse(event, SpoilTexts.NOT_STAFF)
        val channel = channel() ?: return refuse(event, SpoilTexts.NO_CHANNEL)
        val text = event.options[SpoilTexts.TEXT_OPTION].orEmpty().trim()
        if (text.isEmpty()) return refuse(event, SpoilTexts.EMPTY_TEXT)
        if (text.length > SpoilTexts.TEXT_MAX) return refuse(event, SpoilTexts.tooLong(text.length))
        event.options[DiscordEvent.CommandUsed.REFUSED_FILE]?.let { return refuse(event, SpoilTexts.refusedFile(it)) }
        val threshold = threshold(event.options[SpoilTexts.THRESHOLD_OPTION]) ?: return refuse(event, SpoilTexts.BAD_THRESHOLD)

        val current = store.current()
        val lastOnes = store.lastPublished(LAST_ONES)
        val number = store.add(text, event.files[SpoilTexts.IMAGE_OPTION], threshold, event.user.id, context.timer.now())
        if (current == null) {
            val first = store.nextPending() ?: return
            try {
                publish(first)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                event.reply.send(BotMessage(SpoilTexts.PUBLISH_FAILED), onlyToUser = true)
                throw e
            }
            event.reply.send(BotMessage(SpoilTexts.deposited(first.number, "<#" + channel + ">", first.nextThreshold)), onlyToUser = true)
            return
        }
        val answer = if (number == current.number + 1) SpoilTexts.queued(number, current) else SpoilTexts.queuedFar(number)
        event.reply.send(BotMessage(answer + " " + SpoilTexts.lastOnes(lastOnes)), onlyToUser = true)
        showLine(current, current.reactions, store.nextPending(), waiting = morning != null)
    }

    private suspend fun refuse(event: DiscordEvent.CommandUsed, text: String) {
        event.reply.send(BotMessage(text), onlyToUser = true)
    }

    /** `null` : le seuil donné n'est pas un entier positif ; absent, 40 % des membres (au moins un). */
    private suspend fun threshold(given: String?): Int? {
        val value = given?.trim().orEmpty()
        if (value.isEmpty()) return max(1, (context.server.memberCount() * rules.memberShare).roundToInt())
        return value.toIntOrNull()?.takeIf { it > 0 }
    }

    /** Un tic par minute : le compte, la ligne d'état, la publication du suivant. */
    private suspend fun tick() {
        val channel = channel() ?: return
        val current = store.current()
        if (current == null) {
            store.nextPending()?.let { publish(it) }
            return
        }
        val messageId = current.messageId ?: return
        val count = try {
            context.server.reactors(channel, messageId, rules.emoji).size
        } catch (e: DiscordRefusal) {
            current.reactions
        }
        if (count != current.reactions) store.reactions(current.number, count)
        val pending = store.nextPending()
        if (pending != null && count >= current.nextThreshold) {
            if (!isNight(context.timer.now())) {
                publish(pending)
                return
            }
            waitForMorning(pending)
        }
        showLine(current, count, pending, waiting = morning != null)
    }

    /**
     * Publie [spoil] sous le courant : celui-ci perd sa ligne d'état, la ligne suit le nouveau. Le tic et un dépôt
     * peuvent se croiser pendant l'envoi : sous le verrou, un spoil déjà publié n'est pas publié deux fois.
     */
    private suspend fun publish(spoil: Spoil): Unit = gate.withLock {
        val channel = channel() ?: return@withLock
        if (store.nextPending()?.number != spoil.number) return@withLock
        val planned = morning
        morning = null
        planned?.cancel()
        val previous = store.current()
        val role = context.ids.role(COULISSES)
        val line = SpoilTexts.missing(spoil.number + 1)
        val messageId = context.server.post(
            channel,
            BotMessage(SpoilTexts.post(spoil.number, spoil.text, role, line), spoil.image, mentionRoleIds = setOfNotNull(role)),
        )
        val at = context.timer.now()
        store.published(spoil.number, messageId, at)
        shownNumber = spoil.number
        shownLine = line
        // Un suivant est peut-être déjà déposé : la ligne le dit tout de suite.
        showLine(spoil.copy(messageId = messageId, publishedAt = at), 0, store.nextPending(), waiting = false)
        val before = previous?.messageId
        if (previous != null && before != null) {
            context.server.edit(channel, before, BotMessage(SpoilTexts.post(previous.number, previous.text, role, null), previous.image))
        }
    }

    /** La ligne d'état sous [current], modifiée sur place seulement si elle a changé. */
    private suspend fun showLine(current: Spoil, count: Int, pending: Spoil?, waiting: Boolean) {
        val channel = channel() ?: return
        val messageId = current.messageId ?: return
        val line = when {
            pending == null -> SpoilTexts.missing(current.number + 1)
            waiting -> SpoilTexts.waitingForMorning(pending.number, current.nextThreshold, count, rules.nightEndHour)
            else -> SpoilTexts.waiting(pending.number, current.nextThreshold, count)
        }
        if (current.number == shownNumber && line == shownLine) return
        val role = context.ids.role(COULISSES)
        context.server.edit(channel, messageId, BotMessage(SpoilTexts.post(current.number, current.text, role, line), current.image))
        shownNumber = current.number
        shownLine = line
    }

    /** Programme la publication de [pending] à l'heure du matin, une seule fois. */
    private fun waitForMorning(pending: Spoil) {
        if (morning != null) return
        val now = context.timer.now()
        morning = context.timer.after(Duration.between(now, nextMorning(now))) {
            morning = null
            val next = store.nextPending()
            if (next != null && next.number == pending.number) publish(next)
        }
    }

    private fun isNight(at: Instant): Boolean {
        val hour = at.atZone(zone).hour
        return hour >= rules.nightStartHour || hour < rules.nightEndHour
    }

    /** La prochaine [SpoilRules.nightEndHour] h pile du fuseau, strictement après [at]. */
    private fun nextMorning(at: Instant): Instant {
        val day = at.atZone(zone).toLocalDate()
        val today = day.atTime(rules.nightEndHour, 0).atZone(zone).toInstant()
        return if (today.isAfter(at)) today else day.plusDays(1).atTime(rules.nightEndHour, 0).atZone(zone).toInstant()
    }

    private fun channel(): String? = context.ids.channel(COULISSES)

    private companion object {
        const val COULISSES = "coulisses"
        const val STAFF = "staff"
        const val LAST_ONES = 3
    }
}
