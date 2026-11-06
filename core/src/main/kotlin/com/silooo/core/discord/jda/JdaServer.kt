package com.silooo.core.discord.jda

import com.silooo.core.discord.AutomodRule
import com.silooo.core.discord.AutomodTrigger
import com.silooo.core.discord.BotConfig
import com.silooo.core.discord.BotMessage
import com.silooo.core.discord.CategorySpec
import com.silooo.core.discord.ChannelKind
import com.silooo.core.discord.ChannelMessage
import com.silooo.core.discord.ChannelSpec
import com.silooo.core.discord.CommandSpec
import com.silooo.core.discord.DiscordConnection
import com.silooo.core.discord.DiscordEvent
import com.silooo.core.discord.DiscordMessageMissing
import com.silooo.core.discord.DiscordRefusal
import com.silooo.core.discord.DiscordServer
import com.silooo.core.discord.ExistingCategory
import com.silooo.core.discord.ExistingChannel
import com.silooo.core.discord.ExistingRole
import com.silooo.core.discord.ImageFile
import com.silooo.core.discord.PublicChannelAccess
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
import net.dv8tion.jda.api.entities.Icon
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.entities.automod.AutoModResponse
import net.dv8tion.jda.api.entities.automod.build.AutoModRuleData
import net.dv8tion.jda.api.entities.automod.build.TriggerConfig
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import net.dv8tion.jda.api.entities.Message
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
            GatewayIntent.GUILD_EXPRESSIONS,
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

    override suspend fun rulesChannelId(): String? = guild.rulesChannel?.id

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
        val privateViewers = channel.viewers ?: if (channel.memberViewers.isEmpty()) null else emptySet()
        val none = EnumSet.noneOf(Permission::class.java)
        val publicDenied = planPublicDenials(privateViewers != null, channel.writers != null, channel.locked)
        if (publicDenied.isNotEmpty()) action.addPermissionOverride(guild.publicRole, none, publicDenied)
        if (privateViewers != null) action.addMemberPermissionOverride(guild.selfMember.idLong, VIEW, none)
        val roleIds = privateViewers.orEmpty() + channel.writers.orEmpty()
        roleIds.forEach { id ->
            val allowed = EnumSet.noneOf(Permission::class.java)
            if (id in privateViewers.orEmpty()) allowed.addAll(VIEW)
            if (id in channel.writers.orEmpty()) allowed.addAll(WRITE)
            action.addRolePermissionOverride(id.toLong(), allowed, none)
        }
        if (channel.memberViewers.isNotEmpty()) {
            val allowed = if (channel.kind == ChannelKind.VOCAL) VOICE_MEMBER else TEXT_MEMBER
            channel.memberViewers.forEach { action.addMemberPermissionOverride(it.toLong(), allowed, none) }
        }
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

    override suspend fun reconcilePlanPermissions(
        id: String, viewers: Set<String>?, writers: Set<String>?, locked: Boolean,
        managedViewers: Set<String>, dryRun: Boolean,
    ): Boolean {
        val channel = guild.getGuildChannelById(id)?.permissionContainer
            ?: throw DiscordRefusal("le salon ou la catégorie $id n'existe plus")
        val view = Permission.VIEW_CHANNEL.rawValue
        val write = WRITE.fold(0L) { bits, permission -> bits or permission.rawValue }
        val desired = mutableMapOf<Role, Pair<Long, Long>>()
        fun update(role: Role, allowBits: Long, denyBits: Long, clearBits: Long = 0L) {
            val previous = channel.getPermissionOverride(role)
            val oldAllow = previous?.allowedRaw ?: 0L
            val oldDeny = previous?.deniedRaw ?: 0L
            val mask = allowBits or denyBits or clearBits
            val allow = (oldAllow and mask.inv()) or allowBits
            val deny = (oldDeny and mask.inv()) or denyBits
            if (allow != oldAllow || deny != oldDeny) desired[role] = allow to deny
        }
        val everyone = channel.getPermissionOverride(guild.publicRole)
        val oldAllow = everyone?.allowedRaw ?: 0L
        val oldDeny = everyone?.deniedRaw ?: 0L
        val publicMasks = planPublicPermissionMasks(oldAllow, oldDeny, viewers != null, writers != null, locked)
        if (publicMasks != (oldAllow to oldDeny)) desired[guild.publicRole] = publicMasks
        if (viewers != null) {
            for (roleId in managedViewers + viewers) {
                val allowed = roleId in viewers
                val writeAllowed = roleId in writers.orEmpty()
                val allowBits = (if (allowed) view else 0L) or (if (writeAllowed) write else 0L)
                val clearBits = (if (!allowed) view else 0L)
                update(role(roleId), allowBits, 0L, clearBits)
            }
        }
        for (roleId in writers.orEmpty() - managedViewers - viewers.orEmpty()) update(role(roleId), write, 0L)
        if (!dryRun) for ((role, masks) in desired) {
            channel.upsertPermissionOverride(role).setPermissions(masks.first, masks.second).submit().await()
        }
        // Ne pas supprimer les exceptions ajoutées à la main : signaler plutôt qu'elles peuvent exposer le salon.
        if (viewers != null && channel.permissionOverrides.any {
                it.allowedRaw and view != 0L && it.id != guild.selfMember.id &&
                    (it.isMemberOverride || it.id !in viewers)
            }) throw DiscordRefusal("une exception de visibilité supplémentaire doit être vérifiée sur $id")
        return desired.isNotEmpty()
    }

    override suspend fun publicChannelAccess(id: String): PublicChannelAccess {
        val channel = guild.getGuildChannelById(id) ?: throw DiscordRefusal("le salon $id n'existe plus")
        val permissions = guild.publicRole.getPermissions(channel)
        return PublicChannelAccess(
            Permission.VIEW_CHANNEL in permissions,
            Permission.MESSAGE_HISTORY in permissions,
            Permission.MESSAGE_SEND in permissions,
            channel !is ForumChannel || (Permission.CREATE_PUBLIC_THREADS in permissions && Permission.MESSAGE_SEND_IN_THREADS in permissions),
        )
    }

    override suspend fun post(channelId: String, message: BotMessage): String =
        messageChannel(channelId).sendMessage(JdaMessages.create(message)).submit().await().id

    override suspend fun edit(channelId: String, messageId: String, message: BotMessage) {
        try {
            messageChannel(channelId).editMessageById(messageId, JdaMessages.edit(message)).submit().await()
        } catch (e: ErrorResponseException) {
            if (e.errorResponse == ErrorResponse.UNKNOWN_MESSAGE) throw DiscordMessageMissing(channelId, messageId)
            throw e
        }
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
            if (e.errorResponse == ErrorResponse.UNKNOWN_MESSAGE) throw DiscordMessageMissing(channelId, messageId)
            throw DiscordRefusal("Discord refuse de lire les réactions du message " + messageId + " : " + e.meaning)
        } catch (e: InsufficientPermissionException) {
            throw DiscordRefusal("le bot n'a pas le droit de lire les réactions du salon " + channelId)
        }
    }

    override suspend fun deleteChannel(id: String) {
        val channel = guild.getGuildChannelById(id) ?: throw DiscordRefusal("le salon " + id + " n'existe plus")
        if (channel !is TextChannel && channel !is VoiceChannel) throw DiscordRefusal("le salon " + id + " n'est ni un salon de texte ni un vocal : le bot ne le supprime pas")
        try {
            channel.delete().submit().await()
        } catch (e: ErrorResponseException) {
            throw DiscordRefusal("Discord refuse de supprimer le salon " + id + " : " + e.meaning)
        } catch (e: InsufficientPermissionException) {
            throw DiscordRefusal("le bot n'a pas le droit de supprimer le salon " + id)
        }
    }

    override suspend fun history(channelId: String, limit: Int): List<ChannelMessage> {
        try {
            val newestFirst = messageChannel(channelId).iterableHistory.takeAsync(limit).await()
            return newestFirst.reversed().map { channelMessage(it) }
        } catch (e: ErrorResponseException) {
            throw DiscordRefusal("Discord refuse de lire le salon " + channelId + " : " + e.meaning)
        } catch (e: InsufficientPermissionException) {
            throw DiscordRefusal("le bot n'a pas le droit de lire le salon " + channelId)
        }
    }

    override suspend fun pin(channelId: String, messageId: String) {
        try {
            messageChannel(channelId).pinMessageById(messageId).submit().await()
        } catch (e: ErrorResponseException) {
            throw DiscordRefusal("Discord refuse d'épingler le message " + messageId + " : " + e.meaning)
        } catch (e: InsufficientPermissionException) {
            throw DiscordRefusal("le bot n'a pas le droit d'épingler dans le salon " + channelId)
        }
    }

    override suspend fun react(channelId: String, messageId: String, emojiName: String): Boolean {
        val emoji = guild.getEmojisByName(emojiName, true).firstOrNull() ?: return false
        try {
            messageChannel(channelId).addReactionById(messageId, emoji).submit().await()
            return true
        } catch (e: ErrorResponseException) {
            throw DiscordRefusal("Discord refuse la réaction " + emojiName + " sur le message " + messageId + " : " + e.meaning)
        } catch (e: InsufficientPermissionException) {
            throw DiscordRefusal("le bot n'a pas le droit de réagir dans le salon " + channelId)
        }
    }

    override suspend fun deleteMessage(channelId: String, messageId: String) {
        try {
            messageChannel(channelId).deleteMessageById(messageId).submit().await()
        } catch (e: ErrorResponseException) {
            if (e.errorResponse == ErrorResponse.UNKNOWN_MESSAGE) return
            throw DiscordRefusal("Discord refuse de supprimer le message " + messageId + " : " + e.meaning)
        } catch (e: InsufficientPermissionException) {
            throw DiscordRefusal("le bot n'a pas le droit de supprimer dans le salon " + channelId)
        }
    }

    override suspend fun message(channelId: String, messageId: String): ChannelMessage? {
        try {
            return channelMessage(messageChannel(channelId).retrieveMessageById(messageId).submit().await())
        } catch (e: ErrorResponseException) {
            if (e.errorResponse == ErrorResponse.UNKNOWN_MESSAGE) return null
            throw DiscordRefusal("Discord refuse de lire le message " + messageId + " : " + e.meaning)
        } catch (e: InsufficientPermissionException) {
            throw DiscordRefusal("le bot n'a pas le droit de lire le salon " + channelId)
        }
    }

    override fun messageLink(channelId: String, messageId: String): String =
        "https://discord.com/channels/" + guild.id + "/" + channelId + "/" + messageId

    /** Ce que le bot garde d'un message lu : qui, quoi (le texte et les noms des fichiers joints), quand. */
    private fun channelMessage(message: Message): ChannelMessage {
        val names = message.attachments.map { "[" + it.fileName + "]" }
        return ChannelMessage(
            authorId = message.author.id,
            authorName = message.member?.effectiveName ?: message.author.effectiveName,
            text = (listOf(message.contentRaw) + names).filter { it.isNotBlank() }.joinToString(" "),
            at = message.timeCreated.toInstant(),
            bot = message.author.isBot,
        )
    }

    override suspend fun moveToVoice(userId: String, channelId: String) {
        val target = guild.getVoiceChannelById(channelId) ?: throw DiscordRefusal("le vocal " + channelId + " n'existe plus")
        val member = guild.getMemberById(userId) ?: throw DiscordRefusal("le membre " + userId + " n'est pas en vocal")
        if (member.voiceState?.inAudioChannel() != true) throw DiscordRefusal("le membre " + userId + " n'est pas en vocal")
        try {
            guild.moveVoiceMember(member, target).submit().await()
        } catch (e: ErrorResponseException) {
            throw DiscordRefusal("Discord refuse de déplacer le membre " + userId + " : " + e.meaning)
        } catch (e: InsufficientPermissionException) {
            throw DiscordRefusal("le bot n'a pas le droit de déplacer le membre " + userId)
        }
    }

    override suspend fun voiceMembers(channelId: String): Int =
        (guild.getVoiceChannelById(channelId) ?: throw DiscordRefusal("le vocal " + channelId + " n'existe plus")).members.size

    override suspend fun memberName(userId: String): String? {
        guild.getMemberById(userId)?.let { return it.effectiveName }
        return try {
            guild.retrieveMemberById(userId).submit().await().effectiveName
        } catch (e: ErrorResponseException) {
            if (e.errorResponse == ErrorResponse.UNKNOWN_MEMBER || e.errorResponse == ErrorResponse.UNKNOWN_USER) null else throw e
        }
    }

    override suspend fun rolesOf(userId: String): Set<String> {
        val member = guild.getMemberById(userId) ?: try {
            guild.retrieveMemberById(userId).submit().await()
        } catch (e: ErrorResponseException) {
            if (e.errorResponse == ErrorResponse.UNKNOWN_MEMBER || e.errorResponse == ErrorResponse.UNKNOWN_USER) {
                return emptySet()
            }
            throw DiscordRefusal("Discord refuse de lire les rôles du membre " + userId + " : " + e.meaning)
        } catch (e: InsufficientPermissionException) {
            throw DiscordRefusal("le bot n'a pas le droit de lire les rôles du membre " + userId)
        }
        return member.roles.map { it.id }.toSet()
    }

    override suspend fun installCommands(commands: List<CommandSpec>) {
        guild.updateCommands().addCommands(commands.map(JdaMessages::command)).submit().await()
    }

    override suspend fun onboarding(): OnboardingState = rest.onboarding(guild.id)

    override suspend fun setOnboarding(state: OnboardingState): OnboardingState = rest.setOnboarding(guild.id, state)

    override suspend fun automodRules(): List<String> =
        refusing("lire les règles AutoMod") { guild.retrieveAutoModRules().submit().await().map { it.name } }

    override suspend fun setAutomodRule(rule: AutomodRule) {
        val trigger = triggerOf(rule.trigger)
        val responses = buildList {
            add(AutoModResponse.blockMessage())
            rule.alertChannelId?.let { add(AutoModResponse.sendAlert(messageChannel(it))) }
        }
        val exempt = rule.exemptRoleIds.map { role(it) }
        refusing("poser la règle AutoMod « " + rule.name + " »") {
            val known = guild.retrieveAutoModRules().submit().await().firstOrNull { it.name == rule.name }
            if (known == null) {
                guild.createAutoModRule(
                    AutoModRuleData.onMessage(rule.name, trigger).putResponses(responses).setExemptRoles(exempt).setEnabled(true),
                ).submit().await()
            } else {
                guild.modifyAutoModRuleById(known.id)
                    .setTriggerConfig(trigger)
                    .setResponses(responses)
                    .setExemptRoles(exempt)
                    .setEnabled(true)
                    .submit().await()
            }
        }
    }

    override suspend fun setIcon(image: ImageFile) {
        refusing("changer l'icône du serveur") { guild.manager.setIcon(Icon.from(image.bytes)).submit().await() }
    }

    override suspend fun hasIcon(): Boolean = guild.iconId != null

    private fun triggerOf(trigger: AutomodTrigger): TriggerConfig = when (trigger) {
        is AutomodTrigger.Keywords -> TriggerConfig.keywordFilter(trigger.words).addAllowList(trigger.allowed)
        is AutomodTrigger.Patterns -> TriggerConfig.patternFilter(trigger.regexes).addAllowList(trigger.allowed)
        is AutomodTrigger.MentionSpam -> TriggerConfig.mentionSpam(trigger.limit)
    }

    /** Un geste qui demande « Gérer le serveur » : tout refus de Discord ou de JDA devient un [DiscordRefusal]. */
    private suspend fun <T> refusing(what: String, action: suspend () -> T): T =
        try {
            action()
        } catch (e: ErrorResponseException) {
            throw DiscordRefusal("Discord refuse de " + what + " : " + e.meaning)
        } catch (e: InsufficientPermissionException) {
            throw DiscordRefusal("le bot n'a pas le droit de " + what + " : il lui faut « Gérer le serveur »")
        } catch (e: IllegalArgumentException) {
            throw DiscordRefusal("impossible de " + what + " : " + e.message)
        }

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

        /** Ce qu'un membre nommé par `memberViewers` reçoit : voir et écrire dans un salon, se connecter et parler dans un vocal. */
        val TEXT_MEMBER: EnumSet<Permission> = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY)
        val VOICE_MEMBER: EnumSet<Permission> = EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK)
        val WRITE: EnumSet<Permission> = EnumSet.of(Permission.MESSAGE_SEND, Permission.MESSAGE_SEND_IN_THREADS, Permission.CREATE_PUBLIC_THREADS)
    }
}

/** Un seul override @everyone cumule les interdictions du plan, sans qu'une écrase l'autre. */
internal fun planPublicDenials(isPrivate: Boolean, readOnly: Boolean, locked: Boolean): EnumSet<Permission> =
    EnumSet.noneOf(Permission::class.java).apply {
        if (isPrivate) add(Permission.VIEW_CHANNEL)
        if (readOnly) addAll(EnumSet.of(Permission.MESSAGE_SEND, Permission.MESSAGE_SEND_IN_THREADS, Permission.CREATE_PUBLIC_THREADS))
        if (locked) add(Permission.VOICE_CONNECT)
    }

/** Pose les refus du plan sans retirer les verrouillages manuels (par exemple /lock de Dyno). */
internal fun planPublicPermissionMasks(
    oldAllow: Long, oldDeny: Long, isPrivate: Boolean, readOnly: Boolean, locked: Boolean,
): Pair<Long, Long> {
    val denied = planPublicDenials(isPrivate, readOnly, locked).fold(0L) { mask, permission -> mask or permission.rawValue }
    return (oldAllow and denied.inv()) to (oldDeny or denied)
}
