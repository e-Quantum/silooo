package com.silooo.core.discord

import org.slf4j.LoggerFactory

/**
 * Le journal du bot (`#journal-du-bot`, `ServerPlan.JOURNAL_ID`) : chaque geste du staff et du bot y laisse une ligne
 * (`docs/discord.md`, « Le staff »). Le salon est en écriture pour le bot seul ; un serveur qui ne l'a pas, ou un refus
 * de Discord, ne casse jamais le geste qu'on journalise : la ligne est perdue pour le salon et dite au journal du service.
 */
class BotJournal(private val context: BotContext) {
    /** Écrit [text], sans aucune mention, dans le journal du bot ; rien si le serveur n'a pas ce salon. */
    suspend fun line(text: String) {
        val channel = context.ids.channel(ServerPlan.JOURNAL_ID) ?: return
        try {
            context.server.send(channel, text)
        } catch (e: DiscordRefusal) {
            log.warn("Bot Discord, journal : {}", e.message)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(BotJournal::class.java)
    }
}
