package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Les mots du bot pour le message de `#bienvenue` (`docs/discord.md`, « La direction artistique »), **à valider par
 * Kr**. Sans emoji, sans mot-réclame, sans point d'exclamation (`design/README.md`, « Écrire sans slop »). Le
 * message est lu par tous les membres, la réponse du bouton par celui qui l'a pressé seul, les lignes du journal
 * par le staff.
 */
object BienvenueTexts {
    /** La clé du message dans `discord_bot_messages`. */
    const val KEY = "bienvenue"

    const val LINK_BUTTON_ID = "relier-mon-compte"
    const val LINK_BUTTON_LABEL = "Relier mon compte"
    const val LINK_LATER = "La liaison arrive avec la bêta : tu relieras ton compte ici, ou avec la commande lier."

    const val IMAGE_MISSING = "Image d'accueil absente : elle arrive avec D1."
    const val ICON_SET = "Icône du serveur posée."

    /** [discussion] : la mention du salon de discussion, ou son nom. */
    fun text(discussion: String): String =
        "Bienvenue au QG de Silooo. Le jeu se construit sous tes yeux : les coulisses, les sondages et les giveaways sont ouverts, " +
            "le jeu arrive avec la bêta sur Minecraft Bedrock. Dis bonjour dans " + discussion + "."

    fun iconRefused(reason: String?): String = "L'icône du serveur n'a pas pu être posée : " + (reason ?: "Discord a refusé") + "."
}

/**
 * Le message de `#bienvenue` (`docs/discord.md`, « La direction artistique ») : une carte avec une image, le mot
 * d'accueil et deux boutons, « Relier mon compte » et « Mes rappels ». Le message est retrouvé par sa clé
 * ([BotMessageStore]), modifié sur place à chaque démarrage et reposté seulement s'il a disparu. L'image est celle du
 * jour entre [BannerRules.dayHour] et [BannerRules.nightHour] (heures de [zone]), celle de la nuit le reste du temps :
 * le bot la change à ces heures-là. Les images vivent dans les ressources (`discord/bienvenue-jour.png` et
 * `discord/bienvenue-nuit.png`) ; sans elles, le message part sans image et le journal le dit, une fois.
 *
 * Le bouton « Mes rappels » est celui de [WelcomeFeature], qui répond déjà à son identifiant quel que soit le message :
 * cette fonction l'ignore, sinon le membre recevrait deux réponses.
 *
 * Au démarrage, l'icône du serveur est posée depuis `discord/icone-serveur.png` si la ressource existe et si le
 * serveur n'a pas encore d'icône.
 */
class BienvenueFeature(
    private val context: BotContext,
    private val rules: BannerRules,
    private val zone: ZoneId,
    private val messages: BotMessageStore,
    private val journal: BotJournal,
    private val resources: (String) -> ByteArray? = ::classpathBytes,
) : Feature {
    override val name = "bienvenue"

    private var missingReported = false

    override suspend fun start() {
        applyIcon()
        val channel = context.ids.channel(CHANNEL) ?: return
        attempt("le message") { show(channel) }
        arm()
    }

    override suspend fun handle(event: DiscordEvent) {
        if (event is DiscordEvent.ButtonPressed && event.id == BienvenueTexts.LINK_BUTTON_ID) {
            event.reply.send(BotMessage(BienvenueTexts.LINK_LATER), onlyToUser = true)
        }
    }

    /** Le message, modifié sur place s'il est connu et encore là, posté (et noté) sinon. */
    private suspend fun show(channel: String) {
        val message = card()
        val known = messages.find(BienvenueTexts.KEY)
        if (known != null && known.channelId == channel) {
            try {
                context.server.edit(known.channelId, known.messageId, message)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.info("Bot Discord, bienvenue : le message a disparu, il est reposté ({})", e.toString())
            }
        }
        messages.save(BienvenueTexts.KEY, channel, context.server.post(channel, message))
    }

    private suspend fun card(): BotMessage {
        val file = if (isDay(context.timer.now())) DAY_IMAGE else NIGHT_IMAGE
        val bytes = resources(RESOURCE_DIRECTORY + file)?.takeIf { it.isNotEmpty() }
        if (bytes == null && !missingReported) {
            missingReported = true
            journal.line(BienvenueTexts.IMAGE_MISSING)
        }
        return BotMessage(
            BienvenueTexts.text(context.ids.channel(DISCUSSION)?.let { "<#" + it + ">" } ?: ("#" + DISCUSSION)),
            image = bytes?.let { ImageFile(file, it) },
            buttons = listOf(
                ButtonSpec(BienvenueTexts.LINK_BUTTON_ID, BienvenueTexts.LINK_BUTTON_LABEL, ButtonStyle.PRIMARY),
                ButtonSpec(WelcomeTexts.REMINDERS_BUTTON_ID, WelcomeTexts.REMINDERS_BUTTON_LABEL),
            ),
            card = true,
        )
    }

    /** Programme le prochain changement d'image ; l'action se reprogramme, que le changement ait réussi ou non. */
    private fun arm() {
        val now = context.timer.now()
        context.timer.after(Duration.between(now, nextSwitch(now))) {
            try {
                context.ids.channel(CHANNEL)?.let { channel -> attempt("le changement d'image") { show(channel) } }
            } finally {
                arm()
            }
        }
    }

    /**
     * L'icône du serveur, depuis les ressources, au démarrage seulement et seulement si le serveur n'en a pas : celle que
     * Kr a posée à la main (une icône animée des boosts, par exemple) n'est jamais écrasée. Rien quand le fichier n'est
     * pas encore là.
     */
    private suspend fun applyIcon() {
        val bytes = resources(RESOURCE_DIRECTORY + ICON)?.takeIf { it.isNotEmpty() } ?: return
        if (context.server.hasIcon()) return
        try {
            context.server.setIcon(ImageFile(ICON, bytes))
            journal.line(BienvenueTexts.ICON_SET)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DiscordRefusal) {
            log.warn("Bot Discord, bienvenue : l'icône n'a pas été posée : {}", e.message)
            journal.line(BienvenueTexts.iconRefused(e.message))
        }
    }

    /** Le jour va de [BannerRules.dayHour] (compris) à [BannerRules.nightHour] (exclu), en passant par minuit si besoin. */
    private fun isDay(at: Instant): Boolean {
        val hour = at.atZone(zone).hour
        return if (rules.dayHour < rules.nightHour) {
            hour >= rules.dayHour && hour < rules.nightHour
        } else {
            hour >= rules.dayHour || hour < rules.nightHour
        }
    }

    /** Le prochain passage du jour à la nuit ou de la nuit au jour, strictement après [at]. */
    private fun nextSwitch(at: Instant): Instant {
        val today = at.atZone(zone).toLocalDate()
        return (0L..1L)
            .flatMap { days -> listOf(rules.dayHour, rules.nightHour).map { hour -> today.plusDays(days).atTime(hour, 0).atZone(zone).toInstant() } }
            .filter { it.isAfter(at) }
            .min()
    }

    /** Un geste dont l'échec va au journal du service et ne casse ni le démarrage ni la minuterie. */
    private suspend fun attempt(what: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Bot Discord, bienvenue, {} : {}", what, e.toString())
        }
    }

    private companion object {
        const val CHANNEL = "bienvenue"
        const val DISCUSSION = "discussion"
        const val RESOURCE_DIRECTORY = "discord/"
        const val DAY_IMAGE = "bienvenue-jour.png"
        const val NIGHT_IMAGE = "bienvenue-nuit.png"
        const val ICON = "icone-serveur.png"

        val log = LoggerFactory.getLogger(BienvenueFeature::class.java)
    }
}

/** Une ressource du service, ou `null` quand elle n'y est pas. */
private fun classpathBytes(path: String): ByteArray? =
    BienvenueFeature::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
