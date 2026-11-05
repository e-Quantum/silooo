package com.silooo.core.discord

import com.silooo.core.wording.AntiIa
import java.time.Duration
import kotlinx.coroutines.CancellationException

/**
 * Les mots du bot pour `/annonce` (`docs/discord.md`, « Le staff »), **à valider par Kr**. Sans emoji, sans
 * mot-réclame (`design/README.md`, « Écrire sans slop »). Les réponses vont au staff seul ; l'annonce publiée est
 * le texte du staff, relu par le garde anti-IA avant l'aperçu.
 */
object AnnounceTexts {
    const val COMMAND = "annonce"
    const val COMMAND_DESCRIPTION = "Publie une annonce dans le salon des annonces"
    const val TITLE_OPTION = "titre"
    const val TEXT_OPTION = "texte"
    const val IMAGE_OPTION = "image"
    const val IMAGE_DESCRIPTION = "Une image png ou gif, 16 Mo au plus"
    const val MENTION_OPTION = "mention"
    const val MENTION_DESCRIPTION = "Mentionner le rôle Actus (non par défaut)"
    const val MENTION_YES = "oui"
    const val MENTION_NO = "non"

    /** Le titre et le texte, en caractères : avec le gras et la mention du rôle, l'annonce tient dans les 2 000 d'un message. */
    const val TITLE_MAX = 100
    const val TEXT_MAX = 1800

    const val PUBLISH_ID = "annonce-publier"
    const val CANCEL_ID = "annonce-annuler"
    const val PUBLISH_LABEL = "Publier"
    const val CANCEL_LABEL = "Annuler"

    const val NOT_STAFF = "Cette commande est pour le staff."
    const val EMPTY_TITLE = "Le titre est vide : rien n'est gardé."
    const val EMPTY_TEXT = "Le texte est vide : rien n'est gardé."
    const val NO_DRAFT = "Aucune annonce en attente : relance /annonce."
    const val NO_CHANNEL = "Le salon des annonces n'existe pas sur le serveur."
    const val CANCELLED = "Annulé."

    val TITLE_DESCRIPTION = "Le titre de l'annonce, " + WelcomeTexts.thousands(TITLE_MAX) + " caractères au plus"
    val TEXT_DESCRIPTION = "Le texte de l'annonce, " + WelcomeTexts.thousands(TEXT_MAX) + " caractères au plus (collé, il garde ses lignes)"

    fun titleTooLong(length: Int): String =
        "Le titre fait " + WelcomeTexts.thousands(length) + " caractères : " + WelcomeTexts.thousands(TITLE_MAX) + " au plus. Rien n'est gardé."

    fun textTooLong(length: Int): String =
        "Le texte fait " + WelcomeTexts.thousands(length) + " caractères : " + WelcomeTexts.thousands(TEXT_MAX) + " au plus. Rien n'est gardé."

    fun refusedFile(name: String): String = "Le fichier « " + name + " » est refusé : png ou gif, 16 Mo au plus. Rien n'est gardé."

    /** [summary] : la phrase du garde anti-IA, qui nomme les règles touchées. */
    fun tics(summary: String): String = "L'annonce n'est pas gardée. " + summary + " Corrige et relance /annonce."

    /** L'annonce telle qu'elle partira : la mention du rôle (seule prévenue) si elle est demandée, le titre en gras, le texte. */
    fun post(title: String, text: String, roleId: String?): String =
        (if (roleId == null) "" else "<@&" + roleId + "> ") + "**" + title + "**\n\n" + text

    /** [channel] : la mention du salon. */
    fun published(channel: String): String = "Publié dans " + channel + "."

    /** La ligne du journal du bot ; la mention du staff n'y prévient personne. */
    fun journalPublished(staffId: String, title: String): String = "Annonce publiée par <@" + staffId + "> : " + title + "."
}

/**
 * `/annonce` (`docs/discord.md`, « Le staff »). Le staff donne un titre, un texte, une image facultative et choisit de
 * mentionner le rôle Actus ou non. Le garde anti-IA relit le titre et le texte : à la moindre trouvaille, rien n'est
 * gardé. Sinon le staff voit l'annonce telle qu'elle partira, lui seul, avec Publier et Annuler ; Publier la poste
 * dans `#annonces` et l'écrit au journal du bot. Le brouillon vit en mémoire, un par membre du staff (le nouveau
 * remplace l'ancien), et expire au bout de [DRAFT_LIFETIME] : un redémarrage du bot le perd, le staff relance la commande.
 */
class AnnounceFeature(
    private val context: BotContext,
    private val journal: BotJournal,
) : Feature {
    override val name = "annonces"

    override val commands = listOf(
        CommandSpec(
            AnnounceTexts.COMMAND,
            AnnounceTexts.COMMAND_DESCRIPTION,
            options = listOf(
                CommandOption(AnnounceTexts.TITLE_OPTION, AnnounceTexts.TITLE_DESCRIPTION, required = true),
                CommandOption(AnnounceTexts.TEXT_OPTION, AnnounceTexts.TEXT_DESCRIPTION, required = true),
                CommandOption(AnnounceTexts.IMAGE_OPTION, AnnounceTexts.IMAGE_DESCRIPTION, kind = OptionKind.FICHIER),
                CommandOption(
                    AnnounceTexts.MENTION_OPTION,
                    AnnounceTexts.MENTION_DESCRIPTION,
                    choices = listOf(AnnounceTexts.MENTION_YES, AnnounceTexts.MENTION_NO),
                ),
            ),
            staffOnly = true,
        ),
    )

    /** Une annonce en attente de Publier ; [expiry] : la minuterie qui l'efface. */
    private class Draft(val title: String, val text: String, val image: ImageFile?, val mention: Boolean) {
        var expiry: Scheduled? = null
    }

    /** Les brouillons par identifiant du staff. La boucle du bot est un seul fil : aucun verrou. */
    private val drafts = mutableMapOf<String, Draft>()

    override suspend fun handle(event: DiscordEvent) {
        when {
            event is DiscordEvent.CommandUsed && event.name == AnnounceTexts.COMMAND -> draft(event)
            event is DiscordEvent.ButtonPressed && event.id == AnnounceTexts.PUBLISH_ID -> publish(event)
            event is DiscordEvent.ButtonPressed && event.id == AnnounceTexts.CANCEL_ID -> cancel(event)
        }
    }

    private suspend fun draft(event: DiscordEvent.CommandUsed) {
        val staff = context.ids.role(STAFF)
        if (staff != null && staff !in event.user.roleIds) return refuse(event, AnnounceTexts.NOT_STAFF)
        val title = event.options[AnnounceTexts.TITLE_OPTION].orEmpty().trim()
        val text = event.options[AnnounceTexts.TEXT_OPTION].orEmpty().trim()
        if (title.isEmpty()) return refuse(event, AnnounceTexts.EMPTY_TITLE)
        if (title.length > AnnounceTexts.TITLE_MAX) return refuse(event, AnnounceTexts.titleTooLong(title.length))
        if (text.isEmpty()) return refuse(event, AnnounceTexts.EMPTY_TEXT)
        if (text.length > AnnounceTexts.TEXT_MAX) return refuse(event, AnnounceTexts.textTooLong(text.length))
        event.options[DiscordEvent.CommandUsed.REFUSED_FILE]?.let { return refuse(event, AnnounceTexts.refusedFile(it)) }
        val findings = AntiIa.check(title + "\n" + text)
        if (findings.isNotEmpty()) return refuse(event, AnnounceTexts.tics(AntiIa.summary(findings)))

        val mention = event.options[AnnounceTexts.MENTION_OPTION]?.trim() == AnnounceTexts.MENTION_YES
        val draft = Draft(title, text, event.files[AnnounceTexts.IMAGE_OPTION], mention)
        keep(event.user.id, draft)
        event.reply.send(preview(draft), onlyToUser = true)
    }

    private suspend fun publish(event: DiscordEvent.ButtonPressed) {
        val userId = event.user.id
        val draft = drafts[userId] ?: return tell(event, AnnounceTexts.NO_DRAFT)
        val channel = context.ids.channel(ANNONCES) ?: return tell(event, AnnounceTexts.NO_CHANNEL)
        // Le brouillon sort de la mémoire avant l'envoi : l'expiration ne le retire pas pendant que Discord répond.
        drafts.remove(userId)
        draft.expiry?.cancel()
        try {
            context.server.post(channel, message(draft))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            keep(userId, draft)
            throw e
        }
        event.reply.edit(BotMessage(AnnounceTexts.published("<#" + channel + ">")))
        journal.line(AnnounceTexts.journalPublished(userId, draft.title))
    }

    private suspend fun cancel(event: DiscordEvent.ButtonPressed) {
        drafts.remove(event.user.id)?.expiry?.cancel()
        event.reply.edit(BotMessage(AnnounceTexts.CANCELLED))
    }

    /** Garde [draft] pour [userId] à la place de l'ancien, et le laisse expirer après [DRAFT_LIFETIME]. */
    private fun keep(userId: String, draft: Draft) {
        drafts.put(userId, draft)?.expiry?.cancel()
        draft.expiry = context.timer.after(DRAFT_LIFETIME) {
            if (drafts[userId] === draft) drafts.remove(userId)
        }
    }

    /** Le message tel qu'il sera publié : seul le rôle Actus peut être prévenu, et seulement si le staff l'a demandé. */
    private fun message(draft: Draft): BotMessage {
        val role = if (draft.mention) context.ids.role(ACTUS) else null
        return BotMessage(AnnounceTexts.post(draft.title, draft.text, role), draft.image, mentionRoleIds = setOfNotNull(role))
    }

    /** L'aperçu : le même texte et la même image, sans aucune mention (le staff seul le lit, personne n'est prévenu), et les deux boutons. */
    private fun preview(draft: Draft): BotMessage {
        val published = message(draft)
        return BotMessage(
            published.text,
            published.image,
            buttons = listOf(
                ButtonSpec(AnnounceTexts.PUBLISH_ID, AnnounceTexts.PUBLISH_LABEL, ButtonStyle.PRIMARY),
                ButtonSpec(AnnounceTexts.CANCEL_ID, AnnounceTexts.CANCEL_LABEL),
            ),
        )
    }

    private suspend fun refuse(event: DiscordEvent.CommandUsed, text: String) {
        event.reply.send(BotMessage(text), onlyToUser = true)
    }

    private suspend fun tell(event: DiscordEvent.ButtonPressed, text: String) {
        event.reply.send(BotMessage(text), onlyToUser = true)
    }

    private companion object {
        const val ANNONCES = "annonces"
        const val ACTUS = "actus"
        const val STAFF = "staff"

        /** Combien de temps un brouillon attend Publier : un délai d'interface, pas un chiffre du jeu. */
        val DRAFT_LIFETIME: Duration = Duration.ofMinutes(15)
    }
}
