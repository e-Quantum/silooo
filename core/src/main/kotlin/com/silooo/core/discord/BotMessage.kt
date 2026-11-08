package com.silooo.core.discord

/**
 * Un message d'Oooscar, sans rien de JDA. [card] : un conteneur avec la barre ciel à gauche (`docs/discord.md`,
 * « Les cartes et les tableaux »), le texte, l'image et les boutons dedans. Aucune mention ne part hors de
 * [mentionUserIds] et [mentionRoleIds] : jamais tout le monde, jamais un rôle que le membre n'a pas choisi.
 */
data class BotMessage(
    val text: String,
    val image: ImageFile? = null,
    val buttons: List<ButtonSpec> = emptyList(),
    val menu: MenuSpec? = null,
    val card: Boolean = false,
    val mentionUserIds: Set<String> = emptySet(),
    val mentionRoleIds: Set<String> = emptySet(),
    val document: TextFile? = null,
    val nonce: String? = null,
) {
    init {
        require(document == null || (image == null && !card)) { "un document texte ne se combine pas avec une image ou une carte" }
        require(nonce == null || nonce.matches(Regex("[a-z0-9]{1,25}"))) { "une référence d’envoi invalide" }
        require(text.isNotBlank()) { "un message vide" }
        require(text.length <= MAX_TEXT) { "Discord refuse un message de plus de " + MAX_TEXT + " caractères" }
        require(buttons.size <= MAX_BUTTONS) { "Discord met au plus " + MAX_BUTTONS + " boutons sur une ligne" }
        require(buttons.map { it.id }.distinct().size == buttons.size) { "deux boutons portent le même identifiant" }
    }

    companion object {
        const val MAX_TEXT = 2000
        const val MAX_BUTTONS = 5

        /** Le ciel de `design/tokens.json`, seule couleur de la marque sur Discord : la barre des cartes. */
        const val SKY = 0x8fb8d6
    }
}

/** Une image dessinée par le service, jointe au message ; [name] : `nom.png` ou `nom.gif`, en minuscules. */
class ImageFile(val name: String, val bytes: ByteArray) {
    init {
        require(NAME.matches(name)) { "un nom d'image en minuscules, png ou gif" }
        require(bytes.isNotEmpty()) { "une image vide" }
    }

    private companion object {
        val NAME = Regex("[a-z0-9-]+\\.(png|gif)")
    }
}

/** Un transcript UTF-8, sans exécutable et sous le plafond retenu pour les envois du bot. */
class TextFile(val name: String, val bytes: ByteArray) {
    init {
        require(name.matches(Regex("[a-z0-9-]{1,100}\\.txt")))
        require(bytes.size in 1..MAX_BYTES)
    }
    companion object { const val MAX_BYTES = 8_000_000 }
}

/** Un bouton ; [url] : un lien plutôt qu'un geste, alors [id] n'est pas envoyé à Discord. */
data class ButtonSpec(
    val id: String,
    val label: String,
    val style: ButtonStyle = ButtonStyle.NEUTRAL,
    val url: String? = null,
    val disabled: Boolean = false,
) {
    init {
        require(id.length in 1..MAX_ID) { "un identifiant de bouton de 1 à " + MAX_ID + " caractères" }
        require(label.length in 1..MAX_LABEL) { "un libellé de bouton de 1 à " + MAX_LABEL + " caractères" }
    }

    companion object {
        const val MAX_ID = 100
        const val MAX_LABEL = 80
    }
}

enum class ButtonStyle { NEUTRAL, PRIMARY, DANGER }

/** Un menu déroulant ; [maxValues] : combien de choix à la fois. */
data class MenuSpec(val id: String, val placeholder: String, val options: List<MenuOption>, val maxValues: Int = 1) {
    init {
        require(options.size in 1..MAX_OPTIONS) { "un menu de 1 à " + MAX_OPTIONS + " choix" }
        require(options.map { it.value }.distinct().size == options.size) { "deux choix portent la même valeur" }
        require(maxValues in 1..options.size) { "maxValues entre 1 et le nombre de choix" }
    }

    companion object {
        const val MAX_OPTIONS = 25
    }
}

data class MenuOption(val value: String, val label: String, val description: String? = null)

/** Un formulaire (une fenêtre de Discord) : de un à cinq champs de texte. */
data class FormSpec(val id: String, val title: String, val fields: List<FormField>) {
    init {
        require(fields.size in 1..MAX_FIELDS) { "un formulaire de 1 à " + MAX_FIELDS + " champs" }
        require(title.length in 1..MAX_TITLE) { "un titre de formulaire de 1 à " + MAX_TITLE + " caractères" }
    }

    companion object {
        const val MAX_FIELDS = 5
        const val MAX_TITLE = 45
    }
}

/** [long] : un champ de plusieurs lignes ; [value] : le texte déjà rempli. */
data class FormField(
    val id: String,
    val label: String,
    val long: Boolean = false,
    val required: Boolean = true,
    val maxLength: Int? = null,
    val placeholder: String? = null,
    val value: String? = null,
)

/** Une commande du bot ; [staffOnly] : cachée aux membres sans le droit de gérer le serveur (le bot revérifie le rôle). */
data class CommandSpec(
    val name: String,
    val description: String,
    val options: List<CommandOption> = emptyList(),
    val staffOnly: Boolean = false,
    val defaultPermission: CommandPermission? = if (staffOnly) CommandPermission.MANAGE_SERVER else null,
    val kind: CommandKind = CommandKind.SLASH,
) {
    init {
        if (kind == CommandKind.SLASH) {
            require(NAME.matches(name)) { "un nom de commande en minuscules, sans espace" }
            require(description.length in 1..MAX_DESCRIPTION) { "une description de commande de 1 à " + MAX_DESCRIPTION + " caractères" }
        } else {
            require(name.length in 1..32 && name.none { it.isISOControl() }) { "un nom de menu de 1 à 32 caractères" }
            require(description.isEmpty() && options.isEmpty()) { "un menu de message n'a ni description ni options" }
        }
        require(defaultPermission == null || staffOnly) { "une permission de commande implique une commande réservée au staff" }
        var optionalSeen = false
        for (option in options) {
            require(!optionalSeen || !option.required) {
                "commande /" + name + " : l'option obligatoire « " + option.name + " » doit précéder les options facultatives"
            }
            if (!option.required) optionalSeen = true
        }
    }

    companion object {
        const val MAX_DESCRIPTION = 100
        val NAME = Regex("[a-z0-9-]{1,32}")
    }
}

/**
 * Une option de commande ; [choices] : les seules valeurs acceptées, s'il y en a (texte seulement). [kind] :
 * un texte, ou un fichier joint que le bot reçoit dans [DiscordEvent.CommandUsed.files].
 */
data class CommandOption(
    val name: String,
    val description: String,
    val required: Boolean = false,
    val choices: List<String> = emptyList(),
    val kind: OptionKind = OptionKind.TEXTE,
)

enum class OptionKind { TEXTE, FICHIER, MEMBRE }
enum class CommandKind { SLASH, MESSAGE }
enum class CommandPermission { MANAGE_SERVER, MODERATE_MEMBERS, KICK_MEMBERS, BAN_MEMBERS, MANAGE_MESSAGES, MANAGE_CHANNELS }
