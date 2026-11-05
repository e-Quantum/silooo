package com.silooo.core.discord.jda

import com.silooo.core.discord.BotConfig
import com.silooo.core.discord.BotMessage
import com.silooo.core.discord.CategorySpec
import com.silooo.core.discord.ChannelKind
import com.silooo.core.discord.ChannelSpec
import com.silooo.core.discord.CommandSpec
import com.silooo.core.discord.DiscordConnection
import com.silooo.core.discord.DiscordEvent
import com.silooo.core.discord.DiscordRefusal
import com.silooo.core.discord.DiscordServer
import com.silooo.core.discord.ExistingCategory
import com.silooo.core.discord.ExistingChannel
import com.silooo.core.discord.ExistingRole
import com.silooo.core.discord.OnboardingState
import com.silooo.core.discord.RoleSpec
import com.silooo.core.discord.ServerSnapshot
import com.silooo.core.discord.rest.DiscordRest
import java.time.Duration
import java.util.EnumSet
import kotlinx.coroutines.future.await
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.requests.restaction.ChannelAction
import net.dv8tion.jda.api.utils.ChunkingFilter
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag

/**
 * La connexion JDA (ADR 0007) : avec [JdaServer], [JdaEvents] et [JdaMessages], le seul endroit qui importe JDA.
 * Les intentions : membres, messages et leur contenu, réactions, vocaux ; les membres et le contenu sont
 * privilégiés, Kr les accorde dans le portail des développeurs. Le cache des emojis du serveur sert aux coulisses
 * (l'emoji qui compte). Le cache des membres se limite aux derniers vus
 * ([MEMBER_CACHE]) et à ceux en vocal : l'accueil guidé et les vocaux en ont besoin, rien d'autre. Ce que JDA ne
 * couvre pas (l'accueil guidé) passe par [DiscordRest], ouvert et fermé avec la connexion.
 */
class JdaConnection private constructor(
    private val jda: JDA,
    private val guild: Guild,
    override val server: DiscordServer,
    private val rest: DiscordRest,
) : DiscordConnection {
    override fun onEvent(handler: (DiscordEvent) -> Unit) {
        jda.addEventListener(JdaEvents(guild.idLong, handler))
    }

    override fun close() {
        jda.shutdown()
        if (!jda.awaitShutdown(SHUTDOWN_WAIT)) jda.shutdownNow()
        rest.close()
    }

    companion object {
        private val SHUTDOWN_WAIT: Duration = Duration.ofSeconds(5)

        /** Combien de membres restent en cache, les derniers vus d'abord : assez pour les arrivées d'une soirée. */
        private const val MEMBER_CACHE = 500

        private val INTENTS: EnumSet<GatewayIntent> = EnumSet.of(
            GatewayIntent.GUILD_MEMBERS,
            GatewayIntent.GUILD_MESSAGES,
            GatewayIntent.MESSAGE_CONTENT,
            GatewayIntent.GUILD_MESSAGE_REACTIONS,
            GatewayIntent.GUILD_VOICE_STATES,
        )

        /** Se connecte et trouve le serveur de [config] ; lève si le jeton est refusé ou si le bot n'y est pas. */
        fun open(config: BotConfig): JdaConnection {
            val jda = JDABuilder.createLight(config.token.reveal(), INTENTS)
                .enableCache(CacheFlag.VOICE_STATE, CacheFlag.EMOJI)
                .setMemberCachePolicy(MemberCachePolicy.lru(MEMBER_CACHE).or(MemberCachePolicy.VOICE))
                .setChunkingFilter(ChunkingFilter.NONE)
                .setLargeThreshold(50)
                .build()
            val rest = DiscordRest(config.token)
            try {
                jda.awaitReady()
                val guild = jda.getGuildById(config.guildId)
                    ?: throw DiscordRefusal("le bot n'est pas sur le serveur ${config.guildId} (invite-le, puis vérifie SILOOO_DISCORD_SERVEUR)")
                return JdaConnection(jda, guild, JdaServer(guild, rest), rest)
            } catch (e: Exception) {
                rest.close()
                jda.shutdownNow()
                throw e
            }
        }
    }
}

/**
 * [DiscordServer] sur un serveur JDA. Chaque appel attend la réponse de Discord dans une coroutine ; l'accueil
 * guidé, que JDA ne couvre pas, passe par [rest].
 */
class JdaServer(private val guild: Guild, private val rest: DiscordRest) : DiscordServer {

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

    override suspend fun post(channelId: String, message: BotMessage): String =
        messageChannel(channelId).sendMessage(JdaMessages.create(message)).submit().await().id

    override suspend fun edit(channelId: String, messageId: String, message: BotMessage) {
        messageChannel(channelId).editMessageById(messageId, JdaMessages.edit(message)).submit().await()
    }

    override suspend fun direct(userId: String, message: BotMessage): Boolean =
        try {
            val user = guild.jda.retrieveUserById(userId).submit().await()
            val channel = user.openPrivateChannel().submit().await()
            channel.sendMessage(JdaMessages.create(message)).submit().await()
            true
        } catch (e: ErrorResponseException) {
            if (e.errorResponse == ErrorResponse.CANNOT_SEND_TO_USER) false else throw e
        }

    override suspend fun giveRole(userId: String, roleId: String) {
        guild.addRoleToMember(UserSnowflake.fromId(userId), role(roleId)).submit().await()
    }

    override suspend fun takeRole(userId: String, roleId: String) {
        guild.removeRoleFromMember(UserSnowflake.fromId(userId), role(roleId)).submit().await()
    }

    override suspend fun rename(channelId: String, name: String) {
        val channel = guild.getGuildChannelById(channelId) ?: throw DiscordRefusal("le salon $channelId n'existe plus")
        channel.manager.setName(name).submit().await()
    }

    override suspend fun status(text: String) {
        guild.jda.presence.activity = Activity.customStatus(text)
    }

    override suspend fun memberCount(): Int = guild.memberCount

    override suspend fun reactors(channelId: String, messageId: String, emojiName: String): Set<String> {
        val emoji = guild.getEmojisByName(emojiName, true).firstOrNull() ?: return emptySet()
        try {
            val message = messageChannel(channelId).retrieveMessageById(messageId).submit().await()
            val users = message.retrieveReactionUsers(emoji).takeAsync(MAX_REACTORS).await()
            return users.filter { !it.isBot }.map { it.id }.toSet()
        } catch (e: ErrorResponseException) {
            throw DiscordRefusal("Discord refuse de lire les réactions du message " + messageId + " : " + e.meaning)
        } catch (e: InsufficientPermissionException) {
            throw DiscordRefusal("le bot n'a pas le droit de lire les réactions du salon " + channelId)
        }
    }

    override suspend fun installCommands(commands: List<CommandSpec>) {
        guild.updateCommands().addCommands(commands.map(JdaMessages::command)).submit().await()
    }

    override suspend fun onboarding(): OnboardingState = rest.onboarding(guild.id)

    override suspend fun setOnboarding(state: OnboardingState): OnboardingState = rest.setOnboarding(guild.id, state)

    private fun messageChannel(channelId: String): GuildMessageChannel =
        guild.getChannelById(GuildMessageChannel::class.java, channelId)
            ?: throw DiscordRefusal("le salon $channelId n'est pas un salon où écrire")

    private fun role(roleId: String): Role = guild.getRoleById(roleId) ?: throw DiscordRefusal("le rôle $roleId n'existe plus")

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
        /** Combien de membres au plus un message compte : bien au-delà de ce que le serveur aura jamais. */
        const val MAX_REACTORS = 50_000
        val VIEW: EnumSet<Permission> = EnumSet.of(Permission.VIEW_CHANNEL)
        val WRITE: EnumSet<Permission> = EnumSet.of(Permission.MESSAGE_SEND, Permission.MESSAGE_SEND_IN_THREADS, Permission.CREATE_PUBLIC_THREADS)
    }
}
