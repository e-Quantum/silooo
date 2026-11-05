package com.silooo.core.discord

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Les mots du bot pour les meilleurs moments (`docs/discord.md`, « Les cartes et les tableaux d'Oooscar »), **à valider
 * par Kr**. Sans emoji, sans mot-réclame (`design/README.md`, « Écrire sans slop »). La copie ne prévient personne : le
 * message est posté sans aucune mention, l'auteur est cité par son identifiant.
 */
object HighlightTexts {
    /** Ce qui reste d'un message trop long pour tenir dans les 2 000 caractères d'un message de Discord. */
    const val CUT = " (suite)"

    /** « <@7> dans <#9> : » puis le texte cité ligne par ligne, puis le lien ; [text] est coupé pour que le tout tienne. */
    fun repost(authorId: String, channelId: String, text: String, link: String): String {
        val head = "<@" + authorId + "> dans <#" + channelId + "> :"
        val quote = quoted(text, BotMessage.MAX_TEXT - head.length - link.length - 2)
        return if (quote.isEmpty()) head + "\n" + link else head + "\n" + quote + "\n" + link
    }

    /** La ligne du journal du bot quand un message est recopié ; [link] : le message d'origine. */
    fun journalReposted(authorId: String, channelId: String, link: String): String =
        "Meilleur moment : le message de <@" + authorId + "> dans <#" + channelId + "> est recopié. " + link

    /** Le texte cité par « > » sur chaque ligne (une ligne vide devient « > »), de [room] caractères au plus. */
    private fun quoted(text: String, room: Int): String {
        if (text.isBlank()) return ""
        val full = text.trim().replace("\r\n", "\n").split('\n').joinToString("\n") { if (it.isEmpty()) ">" else "> " + it }
        if (full.length <= room) return full
        var kept = full.take(room - CUT.length).trimEnd()
        if (kept.isNotEmpty() && kept.last().isHighSurrogate()) kept = kept.dropLast(1)
        return kept + CUT
    }
}

/**
 * Les meilleurs moments (`docs/discord.md`, « Les cartes et les tableaux d'Oooscar »). Quand un message reçoit
 * [HighlightRules.threshold] réactions de l'emoji [HighlightRules.emoji] de membres autres que son auteur (les bots ne
 * comptent pas, le serveur ne les rend pas), il est recopié une seule fois dans `#meilleurs-moments` : l'auteur, le
 * salon, le texte cité, le lien vers l'original. La copie ne mentionne personne.
 *
 * Seuls les salons que le plan montre à tous les membres comptent (et les sujets de leurs forums, pour ceux que le bot a
 * vus passer depuis son démarrage) : un salon du staff ou un ticket ne se recopie jamais en public. Les messages du bot
 * ne sont pas recopiés, ni ceux déjà posés dans `#meilleurs-moments`. Sans ce salon sur le serveur, la fonction ne fait
 * rien. Un refus de Discord à la lecture est dit au journal du service, le message pourra compter à la réaction suivante.
 */
class HighlightFeature(
    private val context: BotContext,
    private val rules: HighlightRules,
    private val store: HighlightStore,
    private val journal: BotJournal,
    plan: ServerPlan = ServerPlan.fromClasspath(),
) : Feature {
    override val name = "meilleurs-moments"

    private val target = context.ids.channel(TARGET)

    /** Les salons du plan que tous les membres voient, présents sur le serveur, sauf `#meilleurs-moments` lui-même. */
    private val everyone: Set<String> = plan.categories
        .flatMap { category -> category.salons.filter { (it.visible ?: category.visible) == null }.mapNotNull { context.ids.channel(it.id) } }
        .toSet() - setOfNotNull(target)

    private val threads = ThreadParents()

    override suspend fun handle(event: DiscordEvent) {
        when {
            event is DiscordEvent.MessagePosted -> threads.note(event)
            event is DiscordEvent.ReactionAdded -> react(event)
        }
    }

    private suspend fun react(event: DiscordEvent.ReactionAdded) {
        if (target == null || event.isBot || event.emoji != rules.emoji || !shared(event.channelId)) return
        if (store.find(event.messageId) != null) return
        val server = context.server
        val reactors = readable("les réactions") { server.reactors(event.channelId, event.messageId, rules.emoji) } ?: return
        if (reactors.size < rules.threshold) return
        val message = readable("le message") { server.message(event.channelId, event.messageId) } ?: return
        if (message.bot || (reactors - message.authorId).size < rules.threshold) return

        val link = server.messageLink(event.channelId, event.messageId)
        val copy = server.post(target, BotMessage(HighlightTexts.repost(message.authorId, event.channelId, message.text, link)))
        store.save(Highlight(event.messageId, event.channelId, message.authorId, copy))
        journal.line(HighlightTexts.journalReposted(message.authorId, event.channelId, link))
    }

    private fun shared(channelId: String): Boolean {
        val parent = threads.parentOf(channelId)
        return channelId in everyone || (parent != null && parent in everyone)
    }

    /** Lit chez Discord ; un refus (message ou salon disparu, droit manquant) va au journal du service et rend `null`. */
    private suspend fun <T> readable(what: String, read: suspend () -> T?): T? =
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DiscordRefusal) {
            log.warn("Bot Discord, meilleurs moments, {} : {}", what, e.message)
            null
        }

    private companion object {
        const val TARGET = "meilleurs-moments"
        val log = LoggerFactory.getLogger(HighlightFeature::class.java)
    }
}
