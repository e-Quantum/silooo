package com.silooo.core.discord.jda

import com.silooo.core.discord.BotMessage
import com.silooo.core.discord.DiscordEvent
import com.silooo.core.discord.DiscordRefusal
import com.silooo.core.discord.DiscordUser
import com.silooo.core.discord.FormSpec
import com.silooo.core.discord.ImageFile
import com.silooo.core.discord.Reply
import java.time.Clock
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.future.await
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateFlagsEvent
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.Interaction
import net.dv8tion.jda.api.interactions.callbacks.IMessageEditCallback
import net.dv8tion.jda.api.interactions.callbacks.IModalCallback
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType

/**
 * Traduit les événements JDA du serveur [guildId] en [DiscordEvent] et les donne à [offer], depuis les fils de
 * JDA : rien n'y est traité, la boucle du bot s'en charge. Tout ce qui vient d'un autre serveur est ignoré.
 */
internal class JdaEvents(
    private val guildId: Long,
    private val offer: (DiscordEvent) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) : ListenerAdapter() {
    override fun onGuildMemberJoin(event: GuildMemberJoinEvent) {
        if (event.guild.idLong != guildId) return
        offer(DiscordEvent.MemberJoined(user(event.member), clock.instant()))
    }

    override fun onGuildMemberUpdateFlags(event: GuildMemberUpdateFlagsEvent) {
        if (event.guild.idLong != guildId) return
        val before = event.oldValue.orEmpty()
        val after = event.newValue.orEmpty()
        if (Member.MemberFlag.COMPLETED_ONBOARDING in after && Member.MemberFlag.COMPLETED_ONBOARDING !in before) {
            offer(DiscordEvent.OnboardingCompleted(user(event.member), clock.instant()))
        }
    }

    override fun onGuildMemberRemove(event: GuildMemberRemoveEvent) {
        if (event.guild.idLong != guildId) return
        offer(DiscordEvent.MemberLeft(event.user.id, clock.instant()))
    }

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (!event.isFromGuild || event.guild.idLong != guildId) return
        val author = event.member?.let(::user) ?: DiscordUser(event.author.id, event.author.effectiveName, event.author.isBot)
        val parent = if (event.isFromThread) event.channel.asThreadChannel().parentChannel.id else null
        offer(DiscordEvent.MessagePosted(event.channel.id, event.messageId, author, event.message.contentRaw, event.message.timeCreated.toInstant(), parent))
    }

    override fun onMessageDelete(event: MessageDeleteEvent) {
        if (!event.isFromGuild || event.guild.idLong != guildId) return
        offer(DiscordEvent.MessageRemoved(event.channel.id, event.messageId))
    }

    override fun onMessageReactionAdd(event: MessageReactionAddEvent) {
        if (!event.isFromGuild || event.guild.idLong != guildId) return
        val reactor = event.user ?: event.member?.user
        offer(DiscordEvent.ReactionAdded(event.channel.id, event.messageId, event.userId, event.emoji.name, reactor?.isBot ?: false))
    }

    override fun onMessageReactionRemove(event: MessageReactionRemoveEvent) {
        if (!event.isFromGuild || event.guild.idLong != guildId) return
        offer(DiscordEvent.ReactionRemoved(event.channel.id, event.messageId, event.userId, event.emoji.name))
    }

    override fun onGuildVoiceUpdate(event: GuildVoiceUpdateEvent) {
        if (event.guild.idLong != guildId) return
        offer(DiscordEvent.VoiceMoved(event.member.id, event.channelLeft?.id, event.channelJoined?.id))
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (event.guild?.idLong != guildId && event.name !in ACCOUNT_COMMANDS) return
        val options = event.options.filter { it.type != OptionType.ATTACHMENT }.associate { it.name to if (it.type == OptionType.USER) it.asUser.id else it.asString }
        val attachments = event.options.filter { it.type == OptionType.ATTACHMENT }
        val user = actor(event)
        val channelId = event.channel.id
        if (attachments.isEmpty()) {
            val deferred = event.name in MODERATION_COMMANDS
            if (deferred) {
                event.deferReply(true).queue({ offer(DiscordEvent.CommandUsed(event.name, options, user, channelId, JdaReply(event, event, null, deferred = true))) })
            } else offer(DiscordEvent.CommandUsed(event.name, options, user, channelId, JdaReply(event, event, null)))
            return
        }
        // Recevoir un fichier peut prendre plus de trois secondes : on accuse réception d'abord (visible du membre
        // seul), la réponse partira par le message différé, et le fil de JDA n'attend jamais le téléchargement.
        event.deferReply(true).queue()
        val fetches = attachments.map(::fetch)
        CompletableFuture.allOf(*fetches.toTypedArray()).whenComplete { _, _ ->
            val received = fetches.map { it.join() }
            val files = received.mapNotNull { fetched -> fetched.file?.let { fetched.optionName to it } }.toMap()
            val refused = received.firstNotNullOfOrNull { it.refusedName }
            val allOptions = if (refused == null) options else options + (DiscordEvent.CommandUsed.REFUSED_FILE to refused)
            offer(DiscordEvent.CommandUsed(event.name, allOptions, user, channelId, JdaReply(event, event, null, deferred = true), files))
        }
    }

    /** Un fichier reçu, ou refusé (trop gros, ni png ni gif, introuvable) : jamais d'exception, le fichier refusé a son nom. */
    private class Fetched(val optionName: String, val file: ImageFile?, val refusedName: String?)

    private fun fetch(option: OptionMapping): CompletableFuture<Fetched> {
        val attachment = option.asAttachment
        val refused = Fetched(option.name, null, attachment.fileName)
        val extension = attachment.fileExtension?.lowercase()
        if (extension == null || extension !in EXTENSIONS || attachment.size > MAX_FILE_BYTES) return CompletableFuture.completedFuture(refused)
        return attachment.proxy.download()
            .thenApply { stream -> stream.use { it.readNBytes(MAX_FILE_BYTES + 1) } }
            .thenApply { bytes ->
                if (bytes.isEmpty() || bytes.size > MAX_FILE_BYTES) refused else Fetched(option.name, ImageFile(imageName(attachment, extension), bytes), null)
            }
            .exceptionally { refused }
    }

    /** `nom.png` ou `nom.gif` : le nom du fichier en minuscules, les signes autres que lettres, chiffres et tirets en tirets. */
    private fun imageName(attachment: Message.Attachment, extension: String): String {
        val base = attachment.fileName.substringBeforeLast('.').lowercase().replace(NOT_IN_NAME, "-").trim('-').take(MAX_NAME).trim('-')
        return base.ifEmpty { "fichier" } + "." + extension
    }

    override fun onMessageContextInteraction(event: MessageContextInteractionEvent) {
        if (event.guild?.idLong != guildId || event.name != "Signaler") return
        offer(DiscordEvent.MessageReported(actor(event), event.target.channelId, event.target.id, JdaReply(event, event, null)))
    }

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        if (event.guild?.idLong != guildId && !isAccountButton(event.componentId)) return
        offer(DiscordEvent.ButtonPressed(event.componentId, actor(event), event.channel.id, event.messageId, JdaReply(event, event, event)))
    }

    override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
        if (event.guild?.idLong != guildId) return
        offer(DiscordEvent.MenuChosen(event.componentId, event.values, actor(event), event.channel.id, event.messageId, JdaReply(event, event, event)))
    }

    override fun onModalInteraction(event: ModalInteractionEvent) {
        if (event.guild?.idLong != guildId) return
        val fields = event.values.associate { it.customId to it.asString }
        offer(DiscordEvent.FormSubmitted(event.modalId, fields, actor(event), event.channel.id, JdaReply(event, null, if (event.message == null) null else event)))
    }

    private fun actor(event: Interaction): DiscordUser =
        event.member?.let(::user) ?: DiscordUser(event.user.id, event.user.effectiveName, event.user.isBot)

    private fun user(member: Member): DiscordUser =
        DiscordUser(member.id, member.effectiveName, member.user.isBot, member.roles.map { it.id }.toSet())

    private companion object {
        /** 16 Mo : la taille de la colonne `image` de `discord_spoils`. */
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
        const val MAX_NAME = 40
        val EXTENSIONS = setOf("png", "gif")
        val NOT_IN_NAME = Regex("[^a-z0-9-]+")
    }
}

/**
 * La [Reply] d'un geste JDA ; ce que le geste ne permet pas (un formulaire depuis un formulaire) est refusé.
 * [deferred] : le geste a déjà reçu son accusé de réception (visible du membre seul) ; la réponse passe alors par
 * le message différé, toujours visible du membre seul, et ni formulaire ni modification n'est possible.
 */
internal class JdaReply(
    private val replies: IReplyCallback,
    private val modals: IModalCallback?,
    private val edits: IMessageEditCallback?,
    private val deferred: Boolean = false,
) : Reply {
    override suspend fun send(message: BotMessage, onlyToUser: Boolean) {
        if (deferred || replies.isAcknowledged) {
            replies.hook.sendMessage(JdaMessages.create(message)).setEphemeral(onlyToUser).submit().await()
        } else {
            replies.reply(JdaMessages.create(message)).setEphemeral(onlyToUser).submit().await()
        }
    }

    override suspend fun form(form: FormSpec) {
        val target = modals?.takeUnless { deferred } ?: throw DiscordRefusal("ce geste ne peut pas ouvrir de formulaire")
        target.replyModal(JdaMessages.modal(form)).submit().await()
    }

    override suspend fun edit(message: BotMessage) {
        val target = edits ?: throw DiscordRefusal("ce geste n'a pas de message à modifier")
        if (replies.isAcknowledged) replies.hook.editOriginal(JdaMessages.edit(message)).submit().await()
        else target.editMessage(JdaMessages.edit(message)).submit().await()
    }

    override suspend fun acknowledge() {
        if (deferred) return
        val target = edits
        if (target != null) target.deferEdit().submit().await() else replies.deferReply(true).submit().await()
    }
}

/** Hors du QG, seules les cartes du joueur et ses boutons privés sont routés. Aucun geste staff. */
internal val ACCOUNT_COMMANDS = setOf("entreprise", "usine")
internal fun isAccountButton(id: String): Boolean = id.startsWith("jeu-montrer:") || id.startsWith("rappels-arreter:")

internal val MODERATION_COMMANDS = setOf("avertir", "timeout", "retablir", "dossier", "gestion")
