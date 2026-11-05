package com.silooo.core.discord

/**
 * Tout ce que le bot peut faire sur le serveur Discord, et rien d'autre (ADR 0007). Le paquet `discord/jda/`
 * l'implémente avec JDA ; les tests posent un faux en mémoire. Les identifiants sont ceux de Discord, en texte.
 *
 * Aucune méthode ne supprime quoi que ce soit : le bot crée et modifie, l'interface ne sait pas effacer.
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

    /** Un message du bot, sans aucune mention. */
    suspend fun send(channelId: String, text: String)
}

/** Une connexion ouverte à Discord ; [close] la coupe proprement. */
interface DiscordConnection : AutoCloseable {
    val server: DiscordServer
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
