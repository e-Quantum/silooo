package com.silooo.core.discord

import java.time.Instant

/** Un membre tel que l'événement le montre, rien de plus. [roleIds] : ses rôles sur le serveur, vides en privé. */
data class DiscordUser(
    val id: String,
    val name: String,
    val isBot: Boolean = false,
    val roleIds: Set<String> = emptySet(),
)

/**
 * Ce que Discord dit au bot (ADR 0007), en identifiants Discord. Les gestes d'un membre (commande, bouton, menu,
 * formulaire) portent une [Reply], à utiliser sous trois secondes.
 */
sealed interface DiscordEvent {
    data class MemberJoined(val user: DiscordUser, val at: Instant) : DiscordEvent

    /** Le membre a fini l'accueil guidé de Discord : il a ses rôles de plateforme et de mention. */
    data class OnboardingCompleted(val user: DiscordUser, val at: Instant) : DiscordEvent

    data class MemberLeft(val userId: String, val at: Instant) : DiscordEvent

    /**
     * Un message écrit. [parentChannelId] : le forum ou le salon qui porte le fil, quand le message est écrit dans un fil
     * (un sujet de forum a son propre identifiant) ; `null` hors d'un fil.
     */
    data class MessagePosted(
        val channelId: String,
        val messageId: String,
        val author: DiscordUser,
        val text: String,
        val at: Instant,
        val parentChannelId: String? = null,
    ) : DiscordEvent

    /** Un message supprimé, quel qu'en soit l'auteur : Discord ne dit plus rien de lui. */
    data class MessageRemoved(val channelId: String, val messageId: String) : DiscordEvent

    /** [emoji] : le caractère d'un emoji de Discord, ou le nom d'un emoji du serveur. */
    data class ReactionAdded(val channelId: String, val messageId: String, val userId: String, val emoji: String) : DiscordEvent

    data class ReactionRemoved(val channelId: String, val messageId: String, val userId: String, val emoji: String) : DiscordEvent

    /** Un membre entre dans un vocal, en sort ou en change ; `null` : aucun vocal. */
    data class VoiceMoved(val userId: String, val fromChannelId: String?, val toChannelId: String?) : DiscordEvent

    /**
     * Une commande du bot ; [options] : ses options de texte par nom. [files] : ses fichiers joints par nom d'option,
     * déjà reçus ; un fichier refusé (trop gros, ni png ni gif) n'y est pas, et [options] porte alors la clé
     * `fichier-refuse` avec son nom.
     */
    data class CommandUsed(
        val name: String,
        val options: Map<String, String>,
        val user: DiscordUser,
        val channelId: String?,
        val reply: Reply,
        val files: Map<String, ImageFile> = emptyMap(),
    ) : DiscordEvent {
        companion object {
            /** La clé de [options] qui porte le nom d'un fichier refusé. */
            const val REFUSED_FILE = "fichier-refuse"
        }
    }

    data class ButtonPressed(
        val id: String,
        val user: DiscordUser,
        val channelId: String?,
        val messageId: String,
        val reply: Reply,
    ) : DiscordEvent

    data class MenuChosen(
        val id: String,
        val values: List<String>,
        val user: DiscordUser,
        val channelId: String?,
        val messageId: String,
        val reply: Reply,
    ) : DiscordEvent

    /** Un formulaire rendu ; [fields] : les champs par identifiant. */
    data class FormSubmitted(
        val id: String,
        val fields: Map<String, String>,
        val user: DiscordUser,
        val channelId: String?,
        val reply: Reply,
    ) : DiscordEvent
}

/** La réponse à un geste. Discord l'attend sous trois secondes, sinon le membre lit que le geste a échoué. */
interface Reply {
    /** Répond ; [onlyToUser] : visible du membre seul. */
    suspend fun send(message: BotMessage, onlyToUser: Boolean = true)

    /** Ouvre un formulaire ; rien d'autre n'est possible ensuite sur ce geste. */
    suspend fun form(form: FormSpec)

    /** Modifie le message qui portait le bouton ou le menu. */
    suspend fun edit(message: BotMessage)

    /** Accuse réception sans rien montrer ; le message du bouton reste tel quel. */
    suspend fun acknowledge()
}
