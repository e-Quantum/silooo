package com.silooo.core.discord.jda

import com.silooo.core.discord.BotMessage
import com.silooo.core.discord.DiscordEvent
import com.silooo.core.discord.DiscordUser
import com.silooo.core.discord.FormSpec
import com.silooo.core.discord.Reply
import java.time.Clock
import kotlinx.coroutines.future.await
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateFlagsEvent
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
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
        offer(DiscordEvent.MessagePosted(event.channel.id, event.messageId, author, event.message.contentRaw, event.message.timeCreated.toInstant()))
    }

    override fun onMessageDelete(event: MessageDeleteEvent) {
        if (!event.isFromGuild || event.guild.idLong != guildId) return
        offer(DiscordEvent.MessageRemoved(event.channel.id, event.messageId))
    }

    override fun onMessageReactionAdd(event: MessageReactionAddEvent) {
        if (!event.isFromGuild || event.guild.idLong != guildId) return
        offer(DiscordEvent.ReactionAdded(event.channel.id, event.messageId, event.userId, event.emoji.name))
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
        if (event.guild?.idLong != guildId) return
        val options = event.options.associate { it.name to it.asString }
        offer(DiscordEvent.CommandUsed(event.name, options, actor(event), event.channel.id, JdaReply(event, event, null)))
    }

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        if (event.guild?.idLong != guildId) return
        offer(DiscordEvent.ButtonPressed(event.componentId, actor(event), event.channel.id, event.messageId, JdaReply(event, event, event)))
    }

    override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
        if (event.guild?.idLong != guildId) return
        offer(DiscordEvent.MenuChosen(event.componentId, event.values, actor(event), event.channel.id, event.messageId, JdaReply(event, event, event)))
    }

    override fun onModalInteraction(event: ModalInteractionEvent) {
        if (event.guild?.idLong != guildId) return
        val fields = event.values.associate { it.customId to it.asString }
        offer(DiscordEvent.FormSubmitted(event.modalId, fields, actor(event), event.channel.id, JdaReply(event, null, event)))
    }

    private fun actor(event: Interaction): DiscordUser =
        event.member?.let(::user) ?: DiscordUser(event.user.id, event.user.effectiveName, event.user.isBot)

    private fun user(member: Member): DiscordUser =
        DiscordUser(member.id, member.effectiveName, member.user.isBot, member.roles.map { it.id }.toSet())
}

/** La [Reply] d'un geste JDA ; ce que le geste ne permet pas (un formulaire depuis un formulaire) est refusé. */
internal class JdaReply(
    private val replies: IReplyCallback,
    private val modals: IModalCallback?,
    private val edits: IMessageEditCallback?,
) : Reply {
    override suspend fun send(message: BotMessage, onlyToUser: Boolean) {
        replies.reply(JdaMessages.create(message)).setEphemeral(onlyToUser).submit().await()
    }

    override suspend fun form(form: FormSpec) {
        val target = modals ?: throw DiscordRefusal("ce geste ne peut pas ouvrir de formulaire")
        target.replyModal(JdaMessages.modal(form)).submit().await()
    }

    override suspend fun edit(message: BotMessage) {
        val target = edits ?: throw DiscordRefusal("ce geste n'a pas de message à modifier")
        target.editMessage(JdaMessages.edit(message)).submit().await()
    }

    override suspend fun acknowledge() {
        val target = edits
        if (target != null) target.deferEdit().submit().await() else replies.deferReply(true).submit().await()
    }
}
