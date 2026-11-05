package com.silooo.core.discord

/**
 * Tout ce que le bot peut faire sur le serveur Discord, et rien d'autre (ADR 0007). Le paquet `discord/jda/`
 * l'implémente avec JDA ; les tests posent un faux en mémoire. Les identifiants sont ceux de Discord, en texte.
 *
 * Aucune méthode ne supprime un rôle, une catégorie ni un salon du plan : le bot crée et modifie, l'interface
 * ne sait pas effacer ce que Kr voit.
 */
interface DiscordServer {
    /** Les rôles, catégories et salons tels qu'ils sont maintenant. */
    suspend fun snapshot(): ServerSnapshot

    suspend fun createRole(role: RoleSpec): String

    suspend fun updateRole(id: String, role: RoleSpec)

    suspend fun createCategory(category: CategorySpec): String

    suspend fun createChannel(categoryId: String, channel: ChannelSpec): String

    /** Change le sujet d'un salon existant ; jamais son nom, son type ni ses droits (posés à la création, et à Kr ensuite). */
    suspend fun updateChannel(id: String, channel: ChannelSpec)

    /** Un message du bot ; rend l'identifiant du message. */
    suspend fun post(channelId: String, message: BotMessage): String

    /** Un message du bot, texte seul, sans aucune mention. */
    suspend fun send(channelId: String, text: String) {
        post(channelId, BotMessage(text))
    }

    /** Modifie un message du bot sur place : les tableaux qui vivent ne sont jamais repostés. */
    suspend fun edit(channelId: String, messageId: String, message: BotMessage)

    /** Un message privé ; faux si le membre les refuse (le bot n'insiste pas). */
    suspend fun direct(userId: String, message: BotMessage): Boolean

    suspend fun giveRole(userId: String, roleId: String)

    suspend fun takeRole(userId: String, roleId: String)

    /** Renomme un salon ; Discord n'en accepte que deux par dix minutes par salon. */
    suspend fun rename(channelId: String, name: String)

    /** Le statut du bot, sous son nom dans la liste des membres. */
    suspend fun status(text: String)

    suspend fun memberCount(): Int

    /**
     * Les identifiants des membres qui ont réagi au message [messageId] de [channelId] avec l'emoji du serveur nommé
     * [emojiName], les bots exclus. Un emoji que le serveur n'a pas : un ensemble vide. Discord refuse (message ou
     * salon disparu, droit manquant) : [DiscordRefusal].
     */
    suspend fun reactors(channelId: String, messageId: String, emojiName: String): Set<String>

    /** Pose les commandes du bot sur le serveur, à la place des précédentes. */
    suspend fun installCommands(commands: List<CommandSpec>)

    /** L'accueil guidé tel qu'il est ; un serveur qui n'en a jamais eu rend [OnboardingState.NONE]. */
    suspend fun onboarding(): OnboardingState

    /** Remplace l'accueil guidé par [state] et rend ce que Discord a gardé, ses identifiants compris. */
    suspend fun setOnboarding(state: OnboardingState): OnboardingState
}

/** Discord a refusé quelque chose que le bot sait expliquer ; le message ne contient jamais le jeton. */
class DiscordRefusal(message: String) : Exception(message)

/** Une connexion ouverte à Discord ; [close] la coupe proprement. */
interface DiscordConnection : AutoCloseable {
    val server: DiscordServer

    /** Donne les événements du serveur à [handler], depuis les fils de la connexion ; un seul destinataire. */
    fun onEvent(handler: (DiscordEvent) -> Unit)
}

data class ServerSnapshot(
    val roles: List<ExistingRole>,
    val categories: List<ExistingCategory>,
    val channels: List<ExistingChannel>,
)

/** [color] : `#rrggbb` en minuscules, ou `null` sans couleur. */
data class ExistingRole(val id: String, val name: String, val color: String?)

data class ExistingCategory(val id: String, val name: String)

data class ExistingChannel(
    val id: String,
    val name: String,
    val kind: ChannelKind,
    val categoryId: String?,
    val topic: String?,
)

data class RoleSpec(val name: String, val color: String?, val hoisted: Boolean, val mentionable: Boolean)

/** [viewers] : les identifiants Discord des rôles qui voient la catégorie ; `null`, tout le monde. */
data class CategorySpec(val name: String, val viewers: Set<String>?)

/**
 * [writers] : les rôles qui écrivent ; vide, le bot seul ; `null`, tout le monde. [viewers] : comme [CategorySpec].
 * [locked] : un vocal où personne ne se connecte.
 */
data class ChannelSpec(
    val name: String,
    val kind: ChannelKind,
    val topic: String?,
    val writers: Set<String>?,
    val viewers: Set<String>?,
    val locked: Boolean,
)
