package com.silooo.core.discord

import java.time.Instant

/**
 * Tout ce que le bot peut faire sur le serveur Discord, et rien d'autre (ADR 0007). Le paquet `discord/jda/`
 * l'implémente avec JDA ; les tests posent un faux en mémoire. Les identifiants sont ceux de Discord, en texte.
 *
 * Aucune méthode ne supprime un rôle ni une catégorie, et [deleteChannel] ne touche qu'un salon de texte ou un vocal
 * : le bot ne l'appelle que pour les salons qu'il a créés lui-même (un ticket, un vocal de membre), jamais pour ceux
 * du plan. L'interface ne sait pas effacer ce que Kr voit.
 */
interface DiscordServer {
    /** Le surnom du membre ; null le retire. Le bot respecte la hiérarchie de Discord. */
    suspend fun nickname(userId: String, nickname: String?)

    /** Place les seuls rôles de jeu, du moins au plus élevé, au-dessus de la couleur d'accueil. */
    suspend fun positionGameRoles(orderedRoleIds: List<String>, aboveRoleId: String) {}

    /** Lecture fraîche de la cible et de la hiérarchie, pour les seules actions de modération. */
    suspend fun moderationTarget(actorId: String, targetId: String, allowedRoleIds: Set<String>): ModerationTarget? = null

    /** Lectures propres à chaque permission native ; les implémentations non équipées refusent par défaut. */
    suspend fun moderationActionTarget(actorId: String, targetId: String, action: ModerationAction, allowedRoleIds: Set<String>): ModerationTarget? = when (action) {
        ModerationAction.WARN, ModerationAction.TIMEOUT, ModerationAction.RESTORE -> moderationTarget(actorId, targetId, allowedRoleIds)
        else -> null
    }

    /** Le dossier reste consultable par identifiant après le départ de sa cible. */
    suspend fun moderationHistoryTarget(actorId: String, targetId: String, allowedRoleIds: Set<String>): ModerationTarget? =
        moderationTarget(actorId, targetId, allowedRoleIds)

    /** Expulsion/bannissement/débannissement, avec contrôle natif final et sans effacement des messages. */
    suspend fun membershipAction(actorId: String, targetId: String, action: ModerationAction, reason: String, allowedRoleIds: Set<String>) {
        throw DiscordRefusal("La modération des membres n'est pas disponible.")
    }

    /** Dernière vérification des droits avant l'action native. null retire l'exclusion temporaire. */
    suspend fun timeoutMember(actorId: String, targetId: String, until: Instant?, reason: String, allowedRoleIds: Set<String>) {
        throw DiscordRefusal("La modération des membres n'est pas disponible.")
    }

    /** Droits effectifs du membre, et pas seulement ceux du bot, pour lire un message signalé. */
    suspend fun canReadChannel(channelId: String, userId: String): Boolean = false

    /** Les rôles, catégories et salons tels qu'ils sont maintenant. */
    suspend fun snapshot(): ServerSnapshot

    suspend fun createRole(role: RoleSpec): String

    suspend fun updateRole(id: String, role: RoleSpec)

    suspend fun createCategory(category: CategorySpec): String

    suspend fun createChannel(categoryId: String, channel: ChannelSpec): String

    /** Change le sujet d'un salon existant ; jamais son nom, son type ni ses droits (posés à la création, et à Kr ensuite). */
    suspend fun updateChannel(id: String, channel: ChannelSpec)

    /** Vérifie et, sauf à blanc, réconcilie les bits de permission gérés par le plan. */
    suspend fun reconcilePlanPermissions(id: String, viewers: Set<String>?, writers: Set<String>?, locked: Boolean, managedViewers: Set<String>, dryRun: Boolean): Boolean

    /** Bureau public : seuls les membres courants et le staff écrivent. N'affecte aucun autre salon. */
    suspend fun syncPublicBureauWriters(id: String, members: Set<String>, staffRoles: Set<String>) {
        throw DiscordRefusal("les permissions des bureaux ne sont pas prises en charge")
    }

    /** Permissions effectives du seul rôle @everyone dans un salon existant. */
    suspend fun publicChannelAccess(id: String): PublicChannelAccess

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

    /**
     * Supprime un salon que le bot a créé (un ticket, un vocal de membre) ; salon disparu, autre chose qu'un salon de
     * texte ou un vocal, droit manquant : [DiscordRefusal].
     */
    suspend fun deleteChannel(id: String)

    /**
     * Les [limit] derniers messages de [channelId], du plus ancien au plus récent. Une pièce jointe se réduit à son nom
     * entre crochets. Salon disparu ou droit manquant : [DiscordRefusal].
     */
    suspend fun history(channelId: String, limit: Int): List<ChannelMessage>

    /** Passage en lecture seule du ticket avant sa capture, sans changer les droits de lecture. */
    suspend fun freezeTicketForArchive(channelId: String) {
        throw DiscordRefusal("Le verrouillage du ticket n'est pas disponible.")
    }

    /** Une page de l'historique complet, strictement avant le curseur, sans tronquer le contenu. */
    suspend fun transcriptPage(channelId: String, beforeId: String?, limit: Int): TranscriptPage {
        throw DiscordRefusal("La lecture paginée n'est pas disponible.")
    }

    /** Recherche un envoi propre au bot depuis son point de réservation. Une recherche incomplète interdit de reposter. */
    suspend fun findArchivePart(channelId: String, reference: String, fileName: String, anchorId: String?, maxPages: Int): ArchiveLookup = ArchiveLookup.Incomplete

    /** Suppression du seul ticket déjà archivé ; un 404 confirmé vaut succès, un refus reste un échec. */
    suspend fun removeArchivedTicketChannel(channelId: String) { deleteChannel(channelId) }

    /** Déplace [userId] dans le vocal [channelId] ; le membre n'est pas en vocal, ou Discord refuse : [DiscordRefusal]. */
    suspend fun moveToVoice(userId: String, channelId: String)

    /** Combien de membres sont dans le vocal [channelId] en ce moment. */
    suspend fun voiceMembers(channelId: String): Int

    /** Le nom que le serveur affiche pour [userId] (son surnom, sinon son nom) ; `null` s'il n'est plus là. */
    suspend fun memberName(userId: String): String?

    /** Lecture explicite sans cache pour les décisions de gestion du staff. */
    suspend fun freshRolesOf(userId: String): Set<String> = rolesOf(userId)

    /** Les identifiants des rôles du membre [userId] ; vide s'il n'est plus là. */
    suspend fun rolesOf(userId: String): Set<String>

    /** Épingle le message [messageId] du salon [channelId]. */
    suspend fun pin(channelId: String, messageId: String)

    /** Pose l'emoji du serveur [emojiName] sur le message ; `false` si le serveur n'a pas cet emoji. */
    suspend fun react(channelId: String, messageId: String, emojiName: String): Boolean

    /** Supprime le message [messageId] du salon [channelId] ; un message déjà parti ne gêne pas. */
    suspend fun deleteMessage(channelId: String, messageId: String)

    /** Le message [messageId] du salon [channelId] ; `null` s'il n'existe plus. */
    suspend fun message(channelId: String, messageId: String): ChannelMessage?

    /** Le forum parent d’un fil, null pour un salon ordinaire. */
    suspend fun parentChannelId(channelId: String): String? = null

    /** Le lien que Discord ouvre sur le message [messageId] du salon [channelId]. */
    fun messageLink(channelId: String, messageId: String): String

    /** Pose les commandes du bot sur le serveur, à la place des précédentes. */
    suspend fun installCommands(commands: List<CommandSpec>)

    /** Le salon officiel des règles de la communauté, s'il est configuré. */
    suspend fun rulesChannelId(): String? = null

    /** L'accueil guidé tel qu'il est ; un serveur qui n'en a jamais eu rend [OnboardingState.NONE]. */
    suspend fun onboarding(): OnboardingState

    /** Remplace l'accueil guidé par [state] et rend ce que Discord a gardé, ses identifiants compris. */
    suspend fun setOnboarding(state: OnboardingState): OnboardingState

    /** Les noms des règles AutoMod posées sur le serveur, celles du bot et celles de Kr. Droit manquant : [DiscordRefusal]. */
    suspend fun automodRules(): List<String>

    /**
     * Pose [rule] sur le serveur : crée la règle, ou remplace celle qui porte le même nom. Elle bloque le message et
     * alerte dans [AutomodRule.alertChannelId] s'il y en a un. Le droit « Gérer le serveur » manque, ou Discord refuse
     * la règle : [DiscordRefusal].
     */
    suspend fun setAutomodRule(rule: AutomodRule)

    /** Remplace l'icône du serveur par [image] (png ou gif). Droit manquant, image refusée : [DiscordRefusal]. */
    suspend fun setIcon(image: ImageFile)

    /** Le serveur a déjà une icône, posée par le bot ou à la main. */
    suspend fun hasIcon(): Boolean
}

/**
 * Une règle AutoMod posée par le bot ; le nom sert de clé : une règle du même nom est remplacée. [alertChannelId] :
 * le salon où Discord alerte le staff, `null` sans alerte. [exemptRoleIds] : les rôles que la règle ne touche pas.
 */
data class AutomodRule(
    val name: String,
    val trigger: AutomodTrigger,
    val alertChannelId: String?,
    val exemptRoleIds: Set<String>,
)

/** Ce qui déclenche une règle AutoMod. */
sealed interface AutomodTrigger {
    /** Des mots, avec les jokers de Discord ; [allowed] : ce qui ne déclenche jamais la règle. */
    data class Keywords(val words: List<String>, val allowed: List<String>) : AutomodTrigger

    /** Des expressions régulières (la syntaxe de Discord, sans retour arrière) ; [allowed] : comme pour [Keywords]. */
    data class Patterns(val regexes: List<String>, val allowed: List<String>) : AutomodTrigger

    /** Un message qui mentionne [limit] membres ou plus. */
    data class MentionSpam(val limit: Int) : AutomodTrigger
}

/** Discord a refusé quelque chose que le bot sait expliquer ; le message ne contient jamais le jeton. */
open class DiscordRefusal(message: String) : Exception(message)

/** Discord confirme que ce message a disparu : une nouvelle tentative ne le restaurera pas. */
class DiscordMessageMissing(val channelId: String, val messageId: String) :
    DiscordRefusal("le message $messageId du salon $channelId n’existe plus")

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
data class ExistingRole(
    val id: String,
    val name: String,
    val color: String?,
    val permissions: Set<ManagedRolePermission> = emptySet(),
)

data class ExistingCategory(val id: String, val name: String)

data class ExistingChannel(
    val id: String,
    val name: String,
    val kind: ChannelKind,
    val categoryId: String?,
    val topic: String?,
)

data class RoleSpec(
    val name: String,
    val color: String?,
    val hoisted: Boolean,
    val mentionable: Boolean,
    val permissions: Set<ManagedRolePermission> = emptySet(),
)

/** [viewers] : les identifiants Discord des rôles qui voient la catégorie ; `null`, tout le monde. */
data class CategorySpec(val name: String, val viewers: Set<String>?)

/**
 * [writers] : les rôles qui écrivent ; vide, le bot seul ; `null`, tout le monde. [viewers] : comme [CategorySpec].
 * [locked] : un vocal où personne ne se connecte. [memberViewers] : les membres (identifiants Discord) qui voient et
 * écrivent dans le salon, ou se connectent et parlent dans un vocal, en plus des rôles de [viewers] et [writers] ; un
 * salon qui en a reste caché à tout le monde sauf eux, le bot et les rôles de [viewers], même si [viewers] est `null`.
 */
data class ChannelSpec(
    val name: String,
    val kind: ChannelKind,
    val topic: String?,
    val writers: Set<String>?,
    val viewers: Set<String>?,
    val locked: Boolean,
    val memberViewers: Set<String> = emptySet(),
)

/** Un message d'un salon tel que le bot le relit ; [text] porte les pièces jointes réduites à leur nom entre crochets. */
data class ChannelMessage(val authorId: String, val authorName: String, val text: String, val at: Instant, val bot: Boolean = false)

/** Droits effectifs d'@everyone, après les overrides du salon. */
data class PublicChannelAccess(val view: Boolean, val history: Boolean, val send: Boolean, val forumWrite: Boolean = true)
