package com.silooo.core.discord

import java.time.Duration

/**
 * Le statut d'Oooscar, sous son nom dans la liste des membres : deux lignes en alternance, toutes les
 * [StatusRules.alternateSeconds] secondes (`docs/discord.md`, « La direction artistique »). Textes **à valider par Kr**.
 */
class StatusFeature(private val context: BotContext, private val rules: StatusRules) : Feature {
    override val name = "statut"

    private var playing = false

    override suspend fun start() {
        show()
        context.timer.every(Duration.ofSeconds(rules.alternateSeconds.toLong())) { show() }
    }

    private suspend fun show() {
        playing = !playing
        context.server.status(if (playing) PLAYING else watching(context.server.memberCount()))
    }

    companion object {
        const val PLAYING = "Joue à play.silooo.com"

        fun watching(members: Int): String = "Regarde " + members + (if (members == 1) " entrepreneur" else " entrepreneurs")
    }
}
