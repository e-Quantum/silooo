package com.silooo.core.discord

import java.time.Duration
import java.time.Instant

/**
 * Les mots d'Oooscar à l'arrivée (`docs/discord.md`, « L'arrivée et les rôles »), **à valider par Kr**. Sans emoji,
 * sans mot-réclame (`design/README.md`, « Écrire sans slop ») ; les mentions sont des identifiants Discord.
 */
object WelcomeTexts {
    const val TUTORIAL_BUTTON_ID = "mon-tuto"
    const val TUTORIAL_BUTTON_LABEL = "Mon tuto"
    const val REMINDERS_BUTTON_ID = "mes-rappels"
    const val REMINDERS_BUTTON_LABEL = "Mes rappels"
    const val REMINDERS_LATER = "Les rappels arrivent avec la bêta. Tu les régleras ici, avec la commande rappels."

    /** Le bonjour d'un seul membre ; [rank] : son rang parmi les membres, [rejoindre] : la mention du salon, ou son nom. */
    fun hello(userId: String, rank: Int, platform: String?, rejoindre: String): String =
        "Bienvenue <@" + userId + ">, tu es le " + ordinal(rank) + " entrepreneur. " + where(platform, rejoindre)

    /** Le bonjour de plusieurs membres à la fois, mentionnés ; [rejoindre] comme pour [hello]. */
    fun helloAll(userIds: List<String>, rejoindre: String): String =
        "Bienvenue " + named(userIds.map { "<@" + it + ">" }) + ". Tout est dans " + rejoindre + "."

    /** Trop d'arrivées pour les mentionner : une ligne. */
    fun helloMany(count: Int, rejoindre: String): String =
        count.toString() + " nouveaux sont arrivés. Bienvenue à tous : tout est dans " + rejoindre + "."

    /** Le tuto en trois étapes, selon la plateforme ; [discussion] : la mention du salon. */
    fun tutorial(platform: String?, discussion: String, phase: ServerPhase = ServerPhase.OUVERT): String {
        if (phase == ServerPhase.ATTENTE) return "Le jeu est encore fermé.\n" +
            "1. Suis le chantier sur silooo.com.\n" +
            "2. Inscris-toi sur silooo.com pour devenir Pionnier.\n" +
            "3. Retrouve les autres dans " + discussion + ". Pour une question, utilise le salon aide ou ouvre un ticket."
        val first = when (platform) {
            "console" -> "Sur ta console, ajoute HubyOnMC en ami, puis rejoins sa partie depuis ta liste d'amis."
            "telephone" -> "Dans Minecraft, onglet Serveurs, ajoute un serveur : adresse play.silooo.com, port 19142."
            "pc" -> "Dans Minecraft (Windows 10 ou 11), onglet Serveurs, ajoute un serveur : adresse play.silooo.com, port 19142."
            else -> "Sur console, ajoute HubyOnMC en ami et rejoins sa partie. Sur téléphone et PC, l'adresse est play.silooo.com, port 19142."
        }
        return "Ton tuto Silooo, en trois étapes.\n" +
            "1. " + first + "\n" +
            "2. Inscris-toi sur silooo.com et relie ton Discord : à la bêta, ton pseudo et tes rôles suivront.\n" +
            "3. Dis bonjour dans " + discussion + ". Les coulisses, les sondages et les giveaways sont déjà ouverts ; le jeu arrive avec la bêta."
    }

    private fun where(platform: String?, rejoindre: String): String = when (platform) {
        "console" -> "Sur console, tout est dans " + rejoindre + "."
        "telephone" -> "Sur téléphone, tout est dans " + rejoindre + "."
        "pc" -> "Sur PC, tout est dans " + rejoindre + "."
        else -> "Tout est dans " + rejoindre + "."
    }

    /** « 1er », « 2e », « 1 204e » : les milliers séparés d'une espace. */
    fun ordinal(rank: Int): String = thousands(rank) + (if (rank == 1) "er" else "e")

    fun thousands(number: Int): String = number.toString().reversed().chunked(3).joinToString(" ").reversed()

    /** « a », « a et b », « a, b et c ». */
    private fun named(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " et " + names.last()
    }
}

/**
 * Le bonjour d'Oooscar et le tuto (`docs/discord.md`, « L'arrivée et les rôles ») : quelques secondes après la fin de
 * l'accueil guidé, selon la plateforme ([WelcomeRules.helloSeconds]), une mention dans `#discussion` et un seul
 * message privé, le tuto. Messages privés fermés : le bouton « Mon tuto » sous la mention l'affiche, visible du
 * membre seul. Quand les arrivées se bousculent, un message toutes les [WelcomeRules.groupSeconds] secondes, qui
 * mentionne jusqu'à [WelcomeRules.groupMentionsMax] nouveaux, et au-delà une seule ligne. Tout passe par
 * [MemberStore], pour le rapport du lundi.
 */
class WelcomeFeature(
    private val context: BotContext,
    private val rules: WelcomeRules,
    private val members: MemberStore,
    private val reports: ReportStore? = null,
) : Feature {
    override val name = "accueil"

    private class Arrival(val user: DiscordUser, val platform: String?, val tutorialRefused: Boolean)

    private val pendingHellos = mutableMapOf<String, Scheduled>()
    private val waiting = mutableListOf<Arrival>()
    private var lastGreetingAt: Instant? = null
    private var groupFlush: Scheduled? = null

    override suspend fun handle(event: DiscordEvent) {
        when (event) {
            is DiscordEvent.MemberJoined -> if (!event.user.isBot) {
                members.joined(event.user.id, event.at)
                context.ids.role("entrepreneur")?.let { role ->
                    try { context.server.giveRole(event.user.id, role) }
                    catch (e: DiscordRefusal) { org.slf4j.LoggerFactory.getLogger(WelcomeFeature::class.java).warn("Accueil : rôle refusé : {}", e.message) }
                }
            }
            is DiscordEvent.OnboardingCompleted -> onboarded(event)
            is DiscordEvent.MemberLeft -> left(event)
            is DiscordEvent.ButtonPressed -> button(event)
            else -> {}
        }
    }

    private suspend fun onboarded(event: DiscordEvent.OnboardingCompleted) {
        if (event.user.isBot) return
        val platform = platformOf(event.user)
        members.onboarded(event.user.id, event.at, platform)
        pendingHellos.remove(event.user.id)?.cancel()
        val delay = Duration.ofSeconds(rules.helloSeconds.forPlatform(platform).toLong())
        pendingHellos[event.user.id] = context.timer.after(delay) {
            pendingHellos.remove(event.user.id)
            greet(event.user, platform)
        }
    }

    private suspend fun left(event: DiscordEvent.MemberLeft) {
        pendingHellos.remove(event.userId)?.cancel()
        waiting.removeAll { it.user.id == event.userId }
        members.left(event.userId, event.at)
    }

    private suspend fun button(event: DiscordEvent.ButtonPressed) {
        when (event.id) {
            WelcomeTexts.TUTORIAL_BUTTON_ID -> event.reply.send(tutorial(platformOf(event.user)), onlyToUser = true)
            WelcomeTexts.REMINDERS_BUTTON_ID -> event.reply.send(BotMessage(WelcomeTexts.REMINDERS_LATER), onlyToUser = true)
        }
    }

    /** Le tuto part en privé tout de suite ; le bonjour suit, seul ou groupé. */
    private suspend fun greet(user: DiscordUser, platform: String?) {
        val now = context.timer.now()
        val sent = context.server.direct(user.id, tutorial(platform))
        members.tutorialSent(user.id, now, refused = !sent)
        if (!sent) reports?.tutorialDmFailed(user.id, now)
        waiting += Arrival(user, platform, tutorialRefused = !sent)
        val last = lastGreetingAt
        val groupPeriod = Duration.ofSeconds(rules.groupSeconds.toLong())
        if (last == null || !now.isBefore(last + groupPeriod)) {
            flush()
        } else if (groupFlush == null) {
            groupFlush = context.timer.after(Duration.between(now, last + groupPeriod)) {
                groupFlush = null
                flush()
            }
        }
    }

    /** Un seul message pour tous ceux qui attendent. */
    private suspend fun flush() {
        val arrivals = waiting.toList()
        waiting.clear()
        if (arrivals.isEmpty()) return
        val channel = context.ids.channel(DISCUSSION) ?: return
        val rejoindre = mention(REJOINDRE)
        val text = when {
            arrivals.size == 1 -> WelcomeTexts.hello(arrivals[0].user.id, context.server.memberCount(), arrivals[0].platform, rejoindre)
            arrivals.size <= rules.groupMentionsMax -> WelcomeTexts.helloAll(arrivals.map { it.user.id }, rejoindre)
            else -> WelcomeTexts.helloMany(arrivals.size, rejoindre)
        }
        val mentioned = if (arrivals.size <= rules.groupMentionsMax) arrivals.map { it.user.id }.toSet() else emptySet()
        val buttons = if (arrivals.any { it.tutorialRefused }) listOf(ButtonSpec(WelcomeTexts.TUTORIAL_BUTTON_ID, WelcomeTexts.TUTORIAL_BUTTON_LABEL)) else emptyList()
        context.server.post(channel, BotMessage(text, buttons = buttons, mentionUserIds = mentioned))
        val now = context.timer.now()
        lastGreetingAt = now
        for (arrival in arrivals) members.greeted(arrival.user.id, now)
    }

    private fun tutorial(platform: String?): BotMessage = BotMessage(
        WelcomeTexts.tutorial(platform, mention(DISCUSSION), context.phase),
        buttons = listOf(ButtonSpec(WelcomeTexts.REMINDERS_BUTTON_ID, WelcomeTexts.REMINDERS_BUTTON_LABEL)),
    )

    /** Le rôle de plateforme que le membre porte, ou rien. */
    private fun platformOf(user: DiscordUser): String? = PLATFORMS.firstOrNull { planId -> context.ids.role(planId)?.let { it in user.roleIds } == true }

    /** La mention d'un salon du plan, ou son identifiant de plan quand le serveur ne l'a pas. */
    private fun mention(planId: String): String = context.ids.channel(planId)?.let { "<#" + it + ">" } ?: ("#" + planId)

    companion object {
        const val DISCUSSION = "discussion"
        const val REJOINDRE = "rejoindre"
        val PLATFORMS = listOf("console", "telephone", "pc")
    }
}
