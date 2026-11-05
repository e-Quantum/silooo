package com.silooo.core.discord

/**
 * `SILOOO_DISCORD_ACTIF` : le bot ne démarre que sur demande (ADR 0007). `non` par défaut ; `a-blanc` se connecte,
 * lit le serveur et écrit l'écart avec le plan sans rien changer ; `oui` applique le plan.
 */
enum class BotMode(val word: String) {
    NON("non"),
    A_BLANC("a-blanc"),
    OUI("oui");

    companion object {
        fun parse(raw: String): BotMode? = entries.firstOrNull { it.word == raw.trim().lowercase() }
    }
}

/**
 * `SILOOO_DISCORD_PHASE` : quels salons et rôles du plan existent. Chaque entrée du plan porte la phase où elle
 * apparaît ; le bot crée tout ce qui est de la phase courante ou d'une phase passée.
 */
enum class ServerPhase(val word: String) {
    ATTENTE("attente"),
    BETA("beta"),
    OUVERT("ouvert");

    companion object {
        fun parse(raw: String): ServerPhase? = entries.firstOrNull { it.word == raw.trim().lowercase() }
    }
}

/**
 * Le jeton du bot. Il ne s'affiche jamais : ni `toString`, ni un journal. [reveal] ne sert qu'à ouvrir la
 * connexion ; [mask] efface le jeton d'un message d'erreur avant qu'il aille dans les journaux.
 */
class BotToken(private val value: String) {
    init {
        require(value.isNotBlank()) { "Le jeton du bot est vide" }
    }

    fun reveal(): String = value

    fun mask(text: String?): String = text?.replace(value, "***") ?: ""

    override fun toString() = "***"

    override fun equals(other: Any?) = other is BotToken && other.value == value

    override fun hashCode() = value.hashCode()
}

/**
 * Ce que le bot sait de lui-même au démarrage : son mode, son jeton, le serveur Discord à régler
 * (`SILOOO_DISCORD_SERVEUR`, l'identifiant numérique du serveur) et la phase.
 */
data class BotConfig(
    val mode: BotMode,
    val token: BotToken,
    val guildId: String,
    val phase: ServerPhase = ServerPhase.ATTENTE,
) {
    /** En mode `a-blanc`, le bot n'applique rien : il écrit l'écart. */
    val dryRun: Boolean get() = mode == BotMode.A_BLANC

    override fun toString() = "BotConfig(mode=${mode.word}, token=***, guildId=$guildId, phase=${phase.word})"
}
