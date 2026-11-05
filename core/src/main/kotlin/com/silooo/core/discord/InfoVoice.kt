package com.silooo.core.discord

import java.time.Duration
import java.time.Instant

/**
 * Le vocal d'info « N en ligne » (`docs/discord.md`, « Les salons ») : le nombre de joueurs en jeu, lu dans
 * [onlineCount], remplace le nombre dans le nom que le plan a donné au salon. Discord n'accepte que
 * [InfoVoiceRules.renamesMax] renommages par [InfoVoiceRules.windowMinutes] minutes et par salon : le bot regarde
 * le compte à ce rythme et ne renomme que si le nombre a changé et que le quota le permet. Les deux autres vocaux
 * d'info (l'adresse, l'ami console) gardent le nom du plan.
 */
class InfoVoiceFeature(
    private val context: BotContext,
    private val rules: InfoVoiceRules,
    private val onlineCount: () -> Int,
) : Feature {
    override val name = "vocaux d'info"

    private val budget = RenameBudget(rules.renamesMax, Duration.ofMinutes(rules.windowMinutes.toLong()))
    private var currentName: String? = null

    override suspend fun start() {
        val channel = context.ids.channel(ONLINE) ?: return
        currentName = context.server.snapshot().channels.firstOrNull { it.id == channel }?.name
        refresh(channel)
        context.timer.every(Duration.ofMinutes(rules.windowMinutes.toLong()).dividedBy(rules.renamesMax.toLong())) { refresh(channel) }
    }

    private suspend fun refresh(channel: String) {
        val name = currentName ?: return
        val wanted = NUMBER.replaceFirst(name, onlineCount().toString())
        if (wanted == name) return
        val now = context.timer.now()
        if (!budget.allows(now)) return
        context.server.rename(channel, wanted)
        budget.spent(now)
        currentName = wanted
    }

    companion object {
        const val ONLINE = "en-ligne"
        private val NUMBER = Regex("[0-9]+")
    }
}

/**
 * Le quota de renommages de Discord : [max] par [window] glissante. La fenêtre est fermée : un renommage vieux d'une
 * fenêtre pile compte encore, pour ne jamais en poser trois sur un intervalle de dix minutes.
 */
class RenameBudget(private val max: Int, private val window: Duration) {
    private val spent = mutableListOf<Instant>()

    fun allows(now: Instant): Boolean {
        spent.removeAll { it.isBefore(now - window) }
        return spent.size < max
    }

    fun spent(now: Instant) {
        spent += now
    }
}
