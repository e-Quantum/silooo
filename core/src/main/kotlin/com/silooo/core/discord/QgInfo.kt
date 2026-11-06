package com.silooo.core.discord

import com.silooo.core.Config

/** Les textes du QG, à valider par Kr. Le règlement reprend docs/discord-guide.md. */
class QgInfoFeature(
    private val context: BotContext,
    private val messages: BotMessageStore,
    private val siteUrl: String = Config.DEFAULT_SITE_URL,
) : Feature {
    override val name = "informations-du-qg"

    override suspend fun start() {
        val rules = context.server.rulesChannelId()
        LiveMessage(context, messages, "qg-reglement", rules).show(BotMessage(
            "Le règlement du QG\n" +
                "Le Discord est pour les 13 ans et plus. On parle sans insulte, sans pub et sans lien douteux.\n" +
                "Garde tes informations personnelles pour toi. Ne partage ni mot de passe ni code de connexion.\n" +
                "Pour une question sur Silooo, écris dans " + mention("aide") + ". " +
                "Pour un problème à traiter en privé avec la modération, ouvre un ticket dans " + mention("tickets") + ".",
            card = true,
        ))
        val join = context.ids.channel("rejoindre")?.takeUnless { it == rules }
        val instructions = if (context.phase == ServerPhase.ATTENTE) {
            "Le jeu est en construction. L'accès Minecraft n'est pas encore ouvert.\n" +
                "Suis les avancées dans " + mention("coulisses") + " et les nouvelles dans " + mention("annonces") + ".\n" +
                "Pour demander une place de pionnier, inscris-toi sur " + siteUrl.trimEnd('/') + ".\n" +
                "Tu peux déjà discuter dans " + mention("discussion") + " ou poser une question dans " + mention("aide") + "."
        } else {
            "Silooo se joue dans Minecraft Bedrock.\n" +
                "Sur téléphone et PC Windows, ajoute un serveur : play.silooo.com, port 19142.\n" +
                "Sur console, ajoute HubyOnMC en ami, puis rejoins sa partie depuis ta liste d'amis.\n" +
                "L'accès dépend de ton admission. Pour une difficulté de connexion, écris dans " + mention("aide") + "."
        }
        LiveMessage(context, messages, "qg-rejoindre", join).show(BotMessage("Rejoindre Silooo\n" + instructions, card = true))
    }

    private fun mention(id: String): String = context.ids.channel(id)?.let { "<#" + it + ">" } ?: ("#" + id)
}
