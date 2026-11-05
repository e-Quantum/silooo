package com.silooo.core.discord

import java.time.Clock
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Le bot Oooscar : son démarrage et son arrêt, à part de tout le reste (ADR 0007). [start] lance un fil à lui et
 * rend la main tout de suite ; tout ce qui s'y passe mal (plan illisible, jeton refusé, serveur inconnu, Discord
 * injoignable) s'écrit dans les journaux, sans le jeton, et n'arrête ni le jeu ni le site.
 *
 * [connect] ouvre la connexion : JDA en production, un faux dans les tests. [loadPlan] lit le plan du serveur.
 * [features] construit les fonctions du bot ; `null` (ou le mode à blanc) : le bot applique le plan et s'arrête là.
 */
class DiscordBot(
    private val config: BotConfig,
    private val connect: (BotConfig) -> DiscordConnection,
    private val loadPlan: () -> ServerPlan = ServerPlan::fromClasspath,
    private val features: ((BotContext) -> List<Feature>)? = null,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    @Volatile
    private var connection: DiscordConnection? = null

    private var runtime: BotRuntime? = null
    private var closed = false

    /** Démarre dans un fil « discord-bot », démon : il ne retient pas la JVM à l'arrêt. */
    fun start() {
        Thread({ runGuarded() }, "discord-bot").apply { isDaemon = true }.start()
    }

    /**
     * Un passage complet, dans le fil de l'appelant : lit le plan, se connecte, applique le plan, écrit le compte
     * rendu dans le journal, puis sert les événements jusqu'à [close] s'il y a des fonctions et que le mode n'est
     * pas à blanc. Rend le compte rendu, ou `null` si le bot n'a pas pu aller jusque-là ; ne lève jamais.
     */
    fun runGuarded(): PlanReport? =
        try {
            val plan = loadPlan()
            val opened = connect(config)
            connection = opened
            val report = runBlocking { PlanApplier(plan, opened.server, config.phase, config.dryRun).run() }
            log.info("Bot Discord : {}.", report.summary())
            report.failures.forEach { log.warn("Bot Discord, échec : {}.", config.token.mask(it)) }
            val build = features
            if (build != null && !config.dryRun) {
                val loop = synchronized(this) {
                    if (closed) null else BotRuntime(opened.server, report.ids, config.phase, build, clock, mask = config.token::mask).also { runtime = it }
                }
                if (loop != null) {
                    opened.onEvent(loop::offer)
                    runBlocking { loop.run() }
                    log.info("Bot Discord : boucle arrêtée, {} événement(s) abandonné(s).", loop.dropped)
                }
            }
            report
        } catch (e: Throwable) {
            log.error("Le bot Discord s'arrête, le jeu et le site continuent : {}", config.token.mask(e.toString()))
            null
        }

    override fun close() {
        synchronized(this) {
            closed = true
            runtime.also { runtime = null }
        }?.stop()
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
