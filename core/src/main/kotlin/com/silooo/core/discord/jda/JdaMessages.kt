package com.silooo.core.discord.jda

import com.silooo.core.discord.BotMessage
import com.silooo.core.discord.ButtonSpec
import com.silooo.core.discord.ButtonStyle
import com.silooo.core.discord.CommandSpec
import com.silooo.core.discord.CommandKind
import com.silooo.core.discord.CommandPermission
import com.silooo.core.discord.FormSpec
import com.silooo.core.discord.MenuSpec
import com.silooo.core.discord.OptionKind
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.container.ContainerChildComponent
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.mediagallery.MediaGallery
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import net.dv8tion.jda.api.modals.Modal
import net.dv8tion.jda.api.utils.FileUpload
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder
import net.dv8tion.jda.api.utils.messages.MessageEditData
import net.dv8tion.jda.api.utils.messages.MessageRequest

/**
 * Traduit les messages, formulaires et commandes du bot en objets JDA : le seul endroit qui connaît les composants
 * de Discord. Une carte est un conteneur (composants récents) avec la barre ciel ; le reste, un message classique.
 */
internal object JdaMessages {
    fun create(message: BotMessage): MessageCreateData = MessageCreateBuilder().also { fill(it, message) }.build()

    /** Tout est remplacé : un bouton retiré du message disparaît. */
    fun edit(message: BotMessage): MessageEditData = MessageEditBuilder().setReplace(true).also { fill(it, message) }.build()

    private fun fill(request: MessageRequest<*>, message: BotMessage) {
        request.setAllowedMentions(emptyList<Message.MentionType>())
        if (message.mentionUserIds.isNotEmpty()) request.mentionUsers(*message.mentionUserIds.toTypedArray())
        if (message.mentionRoleIds.isNotEmpty()) request.mentionRoles(*message.mentionRoleIds.toTypedArray())
        val upload = message.image?.let { FileUpload.fromData(it.bytes, it.name) }
            ?: message.document?.let { FileUpload.fromData(it.bytes, it.name) }
        val rows = rows(message)
        if (message.card) {
            val children = mutableListOf<ContainerChildComponent>(TextDisplay.of(message.text))
            if (upload != null) children += MediaGallery.of(MediaGalleryItem.fromFile(upload))
            children += rows
            request.useComponentsV2().setComponents(Container.of(children).withAccentColor(BotMessage.SKY))
        } else {
            request.setContent(message.text)
            request.setComponents(rows)
            if (upload != null) request.setFiles(upload)
        }
    }

    private fun rows(message: BotMessage): List<ActionRow> {
        val rows = mutableListOf<ActionRow>()
        if (message.buttons.isNotEmpty()) rows += ActionRow.of(message.buttons.map(::button))
        message.menu?.let { rows += ActionRow.of(menu(it)) }
        return rows
    }

    private fun button(spec: ButtonSpec): Button {
        val button = when {
            spec.url != null -> Button.link(spec.url, spec.label)
            spec.style == ButtonStyle.PRIMARY -> Button.primary(spec.id, spec.label)
            spec.style == ButtonStyle.DANGER -> Button.danger(spec.id, spec.label)
            else -> Button.secondary(spec.id, spec.label)
        }
        return if (spec.disabled) button.asDisabled() else button
    }

    private fun menu(spec: MenuSpec): StringSelectMenu {
        val builder = StringSelectMenu.create(spec.id).setPlaceholder(spec.placeholder).setMaxValues(spec.maxValues)
        for (option in spec.options) {
            val description = option.description
            if (description == null) builder.addOption(option.label, option.value) else builder.addOption(option.label, option.value, description)
        }
        return builder.build()
    }

    fun modal(form: FormSpec): Modal {
        val builder = Modal.create(form.id, form.title)
        for (field in form.fields) {
            val input = TextInput.create(field.id, if (field.long) TextInputStyle.PARAGRAPH else TextInputStyle.SHORT).setRequired(field.required)
            field.maxLength?.let { input.setMaxLength(it) }
            field.placeholder?.let { input.setPlaceholder(it) }
            field.value?.let { input.setValue(it) }
            builder.addComponents(Label.of(field.label, input.build()))
        }
        return builder.build()
    }

    fun command(spec: CommandSpec): CommandData {
        if (spec.kind == CommandKind.MESSAGE) return permissions(Commands.message(spec.name), spec)
        val data = Commands.slash(spec.name, spec.description)
        for (option in spec.options) {
            val type = when (option.kind) {
                OptionKind.FICHIER -> OptionType.ATTACHMENT
                OptionKind.MEMBRE -> OptionType.USER
                OptionKind.TEXTE -> OptionType.STRING
            }
            val optionData = OptionData(type, option.name, option.description, option.required)
            option.choices.forEach { optionData.addChoice(it, it) }
            data.addOptions(optionData)
        }
        return permissions(data, spec)
    }

    private fun permissions(data: CommandData, spec: CommandSpec): CommandData {
        val permission = when (spec.defaultPermission) {
            CommandPermission.MANAGE_SERVER -> Permission.MANAGE_SERVER
            CommandPermission.MODERATE_MEMBERS -> Permission.MODERATE_MEMBERS
            null -> null
        }
        if (permission != null) data.setDefaultPermissions(DefaultMemberPermissions.enabledFor(permission))
        return data
    }
}
