package com.silooo.core.discord.jda

import com.silooo.core.discord.BotConfig
import com.silooo.core.discord.CategorySpec
import com.silooo.core.discord.ChannelKind
import com.silooo.core.discord.ChannelSpec
import com.silooo.core.discord.DiscordConnection
import com.silooo.core.discord.DiscordServer
import com.silooo.core.discord.ExistingCategory
import com.silooo.core.discord.ExistingChannel
import com.silooo.core.discord.ExistingRole
import com.silooo.core.discord.RoleSpec
import com.silooo.core.discord.ServerSnapshot
import java.time.Duration
import java.util.EnumSet
import kotlinx.coroutines.future.await
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.requests.restaction.ChannelAction
import net.dv8tion.jda.api.utils.ChunkingFilter
import net.dv8tion.jda.api.utils.MemberCachePolicy

/** Discord a refusé quelque chose que le bot sait expliquer ; le message ne contient jamais le jeton. */
class DiscordRefusal(message: String) : Exception(message)

/**
 * La connexion JDA (ADR 0007), le seul endroit avec [JdaServer] qui importe JDA. Légère : aucun cache de membres,
 * aucune intention privilégiée pour l'instant (les membres et le contenu des messages viendront avec l'accueil,
 * une fois les intentions accordées par Kr).
 */
class JdaConnection private constructor(private val jda: JDA, override val server: DiscordServer) : DiscordConnection {
    override fun close() {
        jda.shutdown()
        if (!jda.awaitShutdown(SHUTDOWN_WAIT)) jda.shutdownNow()
    }

    companion object {
        private val SHUTDOWN_WAIT: Duration = Duration.ofSeconds(5)

        /** Se connecte et trouve le serveur de [config] ; lève si le jeton est refusé ou si le bot n'y est pas. */
        fun open(config: BotConfig): JdaConnection {
            val jda = JDABuilder.createLight(config.token.reveal(), EnumSet.noneOf(GatewayIntent::class.java))
                .setMemberCachePolicy(MemberCachePolicy.NONE)
                .setChunkingFilter(ChunkingFilter.NONE)
                .setLargeThreshold(50)
                .build()
            try {
                jda.awaitReady()
                val guild = jda.getGuildById(config.guildId)
                    ?: throw DiscordRefusal("le bot n'est pas sur le serveur ${config.guildId} (invite-le, puis vérifie SILOOO_DISCORD_SERVEUR)")
                return JdaConnection(jda, JdaServer(guild))
            } catch (e: Exception) {
                jda.shutdownNow()
                throw e
            }
        }
    }
}

/** [DiscordServer] sur un serveur JDA. Chaque appel attend la réponse de Discord dans une coroutine. */
class JdaServer(private val guild: Guild) : DiscordServer {

    override suspend fun snapshot(): ServerSnapshot = ServerSnapshot(
        roles = guild.roles.filter { !it.isPublicRole && !it.isManaged }.map { ExistingRole(it.id, it.name, colorOf(it)) },
        categories = guild.categories.map { ExistingCategory(it.id, it.name) },
        channels = guild.channels.mapNotNull { existing(it) },
    )

    override suspend fun createRole(role: RoleSpec): String =
        guild.createRole()
            .setName(role.name)
            .setColor(rgb(role.color))
            .setHoisted(role.hoisted)
            .setMentionable(role.mentionable)
            .submit().await().id

    override suspend fun updateRole(id: String, role: RoleSpec) {
        val existing = guild.getRoleById(id) ?: throw DiscordRefusal("le rôle $id n'existe plus")
        existing.manager.setColor(rgb(role.color) ?: Role.DEFAULT_COLOR_RAW).setHoisted(role.hoisted).setMentionable(role.mentionable).submit().await()
    }

    override suspend fun createCategory(category: CategorySpec): String {
        val action = guild.createCategory(category.name)
        viewers(action, category.viewers)
        return action.submit().await().id
    }

    override suspend fun createChannel(categoryId: String, channel: ChannelSpec): String {
        val category = guild.getCategoryById(categoryId) ?: throw DiscordRefusal("la catégorie $categoryId n'existe plus")
        val action: ChannelAction<out GuildChannel> = when (channel.kind) {
            ChannelKind.TEXTE -> category.createTextChannel(channel.name).setTopic(channel.topic)
            ChannelKind.ANNONCES -> category.createNewsChannel(channel.name).setTopic(channel.topic)
            ChannelKind.FORUM -> category.createForumChannel(channel.name).setTopic(channel.topic)
            ChannelKind.VOCAL -> category.createVoiceChannel(channel.name)
        }
        viewers(action, channel.viewers)
        channel.writers?.let { writers ->
            action.addPermissionOverride(guild.publicRole, EnumSet.noneOf(Permission::class.java), WRITE)
            writers.forEach { action.addRolePermissionOverride(it.toLong(), WRITE, EnumSet.noneOf(Permission::class.java)) }
        }
        if (channel.locked) action.addPermissionOverride(guild.publicRole, EnumSet.noneOf(Permission::class.java), EnumSet.of(Permission.VOICE_CONNECT))
        return action.submit().await().id
    }

    override suspend fun updateChannel(id: String, channel: ChannelSpec) {
        when (val existing = guild.getGuildChannelById(id) ?: throw DiscordRefusal("le salon $id n'existe plus")) {
            is TextChannel -> existing.manager.setTopic(channel.topic).submit().await()
            is NewsChannel -> existing.manager.setTopic(channel.topic).submit().await()
            is ForumChannel -> existing.manager.setTopic(channel.topic).submit().await()
            else -> Unit
        }
    }

    override suspend fun send(channelId: String, text: String) {
        val channel = guild.getChannelById(GuildMessageChannel::class.java, channelId)
            ?: throw DiscordRefusal("le salon $channelId n'est pas un salon où écrire")
        channel.sendMessage(text).setAllowedMentions(emptyList<Message.MentionType>()).submit().await()
    }

    /** Une catégorie ou un salon réservé : personne ne le voit, sauf les rôles cités et le bot lui-même. */
    private fun viewers(action: ChannelAction<*>, viewers: Set<String>?) {
        if (viewers == null) return
        val none = EnumSet.noneOf(Permission::class.java)
        action.addPermissionOverride(guild.publicRole, none, VIEW)
        action.addMemberPermissionOverride(guild.selfMember.idLong, VIEW, none)
        viewers.forEach { action.addRolePermissionOverride(it.toLong(), VIEW, none) }
    }

    private fun existing(channel: GuildChannel): ExistingChannel? {
        val kind = when (channel.type) {
            ChannelType.TEXT -> ChannelKind.TEXTE
            ChannelType.NEWS -> ChannelKind.ANNONCES
            ChannelType.FORUM -> ChannelKind.FORUM
            ChannelType.VOICE -> ChannelKind.VOCAL
            else -> return null
        }
        val topic = when (channel) {
            is TextChannel -> channel.topic
            is NewsChannel -> channel.topic
            is ForumChannel -> channel.topic
            else -> null
        }
        return ExistingChannel(channel.id, channel.name, kind, (channel as? ICategorizableChannel)?.parentCategoryId, topic)
    }

    private fun colorOf(role: Role): String? =
        if (role.colorRaw == Role.DEFAULT_COLOR_RAW) null else "#%06x".format(role.colorRaw and 0xFFFFFF)

    private fun rgb(color: String?): Int? = color?.removePrefix("#")?.toInt(16)

    private companion object {
        val VIEW: EnumSet<Permission> = EnumSet.of(Permission.VIEW_CHANNEL)
        val WRITE: EnumSet<Permission> = EnumSet.of(Permission.MESSAGE_SEND, Permission.MESSAGE_SEND_IN_THREADS, Permission.CREATE_PUBLIC_THREADS)
    }
}
