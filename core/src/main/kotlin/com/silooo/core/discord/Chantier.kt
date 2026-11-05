package com.silooo.core.discord

import com.silooo.core.Config
import com.silooo.core.site.ChantierState
import com.silooo.core.site.ChantierStep
import com.silooo.core.site.NewsArticles
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Les mots du bot pour le chantier en direct (`docs/discord.md`, « Les cartes et les tableaux d'Oooscar »), **à valider
 * par Kr**. Sans emoji, sans mot-réclame (`design/README.md`, « Écrire sans slop »). Les titres d'étapes viennent des
 * actus du site (`site/contenu/actus/`), que le site affiche de la même façon.
 */
object ChantierTexts {
    /** La clé du message dans `discord_bot_messages`. */
    const val KEY = "chantier"

    const val TITLE = "Le chantier en direct"
    const val DONE = "Faites"
    const val CURRENT = "En cours"
    const val NEXT = "À venir"

    /** Le journal du bot, quand le message vient d'être posté mais n'a pas pu être épinglé. */
    const val NOT_PINNED = "Le chantier en direct n'a pas pu être épinglé dans les coulisses."

    /** Une ligne d'étape : « titre », ou « titre : adresse » quand l'étape a un article ; [siteUrl] complète une adresse du site. */
    fun line(step: ChantierStep, siteUrl: String): String {
        val path = step.url ?: return step.title
        return step.title + " : " + (if (path.startsWith("/")) siteUrl.trimEnd('/') + path else path)
    }

    /** Le titre, puis chaque groupe qui a des étapes, séparés par une ligne vide ; coupé avant de dépasser un message de Discord. */
    fun board(steps: List<ChantierStep>, siteUrl: String): String {
        val text = StringBuilder(TITLE)
        val groups = listOf(
            DONE to ChantierState.FAITE,
            CURRENT to ChantierState.EN_COURS,
            NEXT to ChantierState.A_VENIR,
        )
        for ((heading, state) in groups) {
            val lines = steps.filter { it.state == state }.map { line(it, siteUrl) }
            if (lines.isEmpty() || text.length + 2 + heading.length > BotMessage.MAX_TEXT) continue
            text.append("\n\n").append(heading)
            for (line in lines) {
                if (text.length + 1 + line.length > BotMessage.MAX_TEXT) break
                text.append('\n').append(line)
            }
        }
        return text.toString()
    }
}

/**
 * Le chantier en direct (`docs/discord.md`, « Les cartes et les tableaux d'Oooscar »). Au démarrage, le bot lit les actus
 * du site dans ses ressources et poste dans `#coulisses` les étapes du chantier en trois groupes : faites, en cours, à
 * venir. Le message est retrouvé par sa clé ([BotMessageStore]) et modifié sur place ; il est épinglé une seule fois, quand
 * il est posté (la première fois, ou s'il avait disparu). Les actus sont des fichiers du jar : elles ne changent qu'avec un
 * déploiement, donc le bot n'a rien à faire d'autre pendant qu'il tourne. Sans `#coulisses` sur le serveur, il ne fait rien.
 *
 * [siteUrl] : l'adresse publique du site, qui complète les adresses relatives des articles. [steps] : où lire les étapes
 * (les actus du jar par défaut).
 */
class ChantierFeature(
    private val context: BotContext,
    messages: BotMessageStore,
    private val journal: BotJournal,
    private val siteUrl: String = Config.DEFAULT_SITE_URL,
    private val steps: () -> List<ChantierStep> = { NewsArticles.chantier(NewsArticles.fromClasspath()) },
) : Feature {
    override val name = "chantier"

    private val board = LiveMessage(context, messages, ChantierTexts.KEY, context.ids.channel(COULISSES))

    override suspend fun start() {
        if (context.ids.channel(COULISSES) == null) return
        val shown = board.show(BotMessage(ChantierTexts.board(steps(), siteUrl), card = true)) ?: return
        if (!shown.created) return
        try {
            context.server.pin(shown.channelId, shown.messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Bot Discord, chantier : le message n'a pas pu être épinglé : {}", e.toString())
            journal.line(ChantierTexts.NOT_PINNED)
        }
    }

    private companion object {
        const val COULISSES = "coulisses"
        val log = LoggerFactory.getLogger(ChantierFeature::class.java)
    }
}
