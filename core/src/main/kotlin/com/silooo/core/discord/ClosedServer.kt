package com.silooo.core.discord

import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory

/**
 * Le serveur Discord tant que le QG n'est pas ouvert au public (`SILOOO_DISCORD_PUBLIC` à `non`). Il n'y a qu'un
 * serveur Discord, celui de Silooo, sans serveur de test : le bot y travaille pendant le chantier, alors que
 * personne d'autre ne doit en recevoir un effet de bord. Deux garde-fous, posés autour du vrai serveur [inner] :
 *
 * - [direct] : un message privé ne part que si le membre a un rôle de [staffRoleIds] ; sinon il est retenu (il ne
 *   part jamais), une ligne de journal du service le dit, et [held] le compte ;
 * - [post] et [edit] : les mentions de rôle se réduisent aux rôles de [staffRoleIds] ; les mentions de membres ne
 *   changent pas (un membre mentionné est un membre que l'action concerne déjà).
 *
 * Tout le reste passe à [inner] tel quel, [send] compris : il écrit du texte seul, sans aucune mention, et ne peut
 * donc rien mentionner. Si Discord refuse de lire les rôles d'un membre, [direct] lève le refus et rien ne part :
 * dans le doute, le message n'est pas envoyé.
 */
class ClosedServer(
    private val inner: DiscordServer,
    private val staffRoleIds: Set<String>,
) : DiscordServer by inner {
    private val heldCount = AtomicInteger()

    /** Combien de messages privés ont été retenus depuis le démarrage. */
    val held: Int get() = heldCount.get()

    override suspend fun direct(userId: String, message: BotMessage): Boolean {
        if (inner.rolesOf(userId).none { it in staffRoleIds }) {
            heldCount.incrementAndGet()
            log.info("Bot Discord, QG fermé : message privé à {} retenu.", userId)
            return false
        }
        return inner.direct(userId, message)
    }

    override suspend fun post(channelId: String, message: BotMessage): String = inner.post(channelId, onlyStaffRoles(message))

    override suspend fun edit(channelId: String, messageId: String, message: BotMessage) {
        inner.edit(channelId, messageId, onlyStaffRoles(message))
    }

    private fun onlyStaffRoles(message: BotMessage): BotMessage =
        if (message.mentionRoleIds.all { it in staffRoleIds }) message else message.copy(mentionRoleIds = message.mentionRoleIds intersect staffRoleIds)

    private companion object {
        val log = LoggerFactory.getLogger(ClosedServer::class.java)
    }
}
