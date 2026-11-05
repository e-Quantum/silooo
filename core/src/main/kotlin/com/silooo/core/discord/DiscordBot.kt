package com.silooo.core.discord

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Le bot Oooscar : son démarrage et son arrêt, à part de tout le reste (ADR 0007). [start] lance un fil à lui et
 * rend la main tout de suite ; tout ce qui s'y passe mal (plan illisible, jeton refusé, serveur inconnu, Discord
 * injoignable) s'écrit dans les journaux, sans le jeton, et n'arrête ni le jeu ni le site.
 *
 * [connect] ouvre la connexion : JDA en production, un faux dans les tests. [loadPlan] lit le plan du serveur.
 */
class DiscordBot(
    private val config: BotConfig,
    private val connect: (BotConfig) -> DiscordConnection,
    private val loadPlan: () -> ServerPlan = ServerPlan::fromClasspath,
) : AutoCloseable {
    @Volatile
    private var connection: DiscordConnection? = null

    /** Démarre dans un fil « discord-bot », démon : il ne retient pas la JVM à l'arrêt. */
    fun start() {
        Thread({ runGuarded() }, "discord-bot").apply { isDaemon = true }.start()
    }

    /**
     * Un passage complet, dans le fil de l'appelant : lit le plan, se connecte, applique le plan, écrit le compte
     * rendu dans le journal. Rend le compte rendu, ou `null` si le bot n'a pas pu aller jusque-là ; ne lève jamais.
     */
    fun runGuarded(): PlanReport? =
        try {
            val plan = loadPlan()
            val opened = connect(config)
            connection = opened
            val report = runBlocking { PlanApplier(plan, opened.server, config.phase, config.dryRun).run() }
            log.info("Bot Discord : {}.", report.summary())
            report.failures.forEach { log.warn("Bot Discord, échec : {}.", config.token.mask(it)) }
            report
        } catch (e: Throwable) {
            log.error("Le bot Discord s'arrête, le jeu et le site continuent : {}", config.token.mask(e.toString()))
            null
        }

    override fun close() {
        val opened = connection ?: return
        connection = null
        try {
            opened.close()
        } catch (e: Exception) {
            log.warn("Le bot Discord ne s'est pas fermé proprement : {}", config.token.mask(e.toString()))
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(DiscordBot::class.java)
    }
}
