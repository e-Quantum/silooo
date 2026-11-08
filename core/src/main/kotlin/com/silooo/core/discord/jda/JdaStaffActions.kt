package com.silooo.core.discord.jda

import com.silooo.core.discord.DiscordRefusal
import com.silooo.core.discord.ModerationTarget
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.requests.ErrorResponse

/** Le bot agit avec ses propres droits : ceux de l'humain sont donc revérifiés séparément juste avant chaque geste. */
internal class JdaStaffActions(private val guild: Guild) {
    private suspend fun member(id: String): Member? {
        if (!id.matches(Regex("[0-9]{1,20}"))) return null
        return try { guild.retrieveMemberById(id).useCache(false).submit().await() }
        catch (e: ErrorResponseException) {
            if (e.errorResponse == ErrorResponse.UNKNOWN_MEMBER || e.errorResponse == ErrorResponse.UNKNOWN_USER) null else throw e
        }
    }

    suspend fun roles(userId: String): Set<String> = member(userId)?.roles?.map { it.id }?.toSet().orEmpty()

    suspend fun target(actorId: String, targetId: String, allowedRoles: Set<String>): ModerationTarget? {
        val actor = member(actorId) ?: return null
        val target = member(targetId) ?: return null
        val actorAllowed = authorizedModerator(actor.roles.map { it.id }.toSet(), allowedRoles,
            actor.hasPermission(Permission.MODERATE_MEMBERS), actor.canInteract(target), actorId == targetId)
        return ModerationTarget(target.id, target.effectiveName, target.user.isBot, target.idLong == guild.ownerIdLong,
            actorAllowed, guild.selfMember.canInteract(target) && guild.selfMember.hasPermission(Permission.MODERATE_MEMBERS) &&
                !target.hasPermission(Permission.ADMINISTRATOR))
    }

    suspend fun timeout(actorId: String, targetId: String, until: Instant?, reason: String, allowedRoles: Set<String>) {
        val proof = target(actorId, targetId, allowedRoles)
        if (proof == null || proof.isOwner || proof.isBot || !proof.actorCanInteract || !proof.botCanInteract) {
            throw DiscordRefusal("Les permissions ou la hiérarchie ne permettent pas cette action.")
        }
        try {
            val action = if (until == null) guild.removeTimeout(UserSnowflake.fromId(targetId))
                else guild.timeoutUntil(UserSnowflake.fromId(targetId), until)
            action.reason(("Oooscar, membre du staff " + actorId + " : " + reason).take(512)).submit().await()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw DiscordRefusal("Discord a refusé l'action de modération.") }
    }

    suspend fun canRead(channelId: String, userId: String): Boolean {
        val member = member(userId) ?: return false
        val channel = guild.getThreadChannelById(channelId) ?: guild.getGuildChannelById(channelId) ?: return false
        if (!member.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)) return false
        val thread = guild.getThreadChannelById(channelId)
        // Une invitation explicite est nécessaire pour les fils privés : voir le parent ne suffit pas.
        if (thread != null && !thread.isPublic && !member.hasPermission(Permission.MANAGE_THREADS)) {
            return try { thread.retrieveThreadMemberById(userId).submit().await(); true }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
        }
        return true
    }
}

internal fun authorizedModerator(roles: Set<String>, allowedRoles: Set<String>, canModerate: Boolean, canInteract: Boolean, self: Boolean): Boolean =
    allowedRoles.isNotEmpty() && roles.any { it in allowedRoles } && canModerate && canInteract && !self
