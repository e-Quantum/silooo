package com.silooo.core.discord.jda

import com.silooo.core.discord.DiscordRefusal
import com.silooo.core.discord.ModerationTarget
import com.silooo.core.discord.ModerationAction
import java.util.concurrent.TimeUnit
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

    suspend fun target(actorId: String, targetId: String, allowedRoles: Set<String>): ModerationTarget? =
        targetForAction(actorId, targetId, ModerationAction.TIMEOUT, allowedRoles)

    suspend fun targetForAction(actorId: String, targetId: String, action: ModerationAction, allowedRoles: Set<String>): ModerationTarget? {
        val actor = member(actorId) ?: return null
        val bot = member(guild.selfMember.id) ?: return null
        val permission = nativeModerationPermission(action)
        if (!authorizedModerator(actor.roles.map { it.id }.toSet(), allowedRoles,
                actor.hasPermission(permission), true, actorId == targetId) || !bot.hasPermission(permission)) return null
        val target = member(targetId)
        if (action == ModerationAction.UNBAN) {
            if (target != null) return null
            val ban = try { guild.retrieveBan(UserSnowflake.fromId(targetId)).submit().await() }
                catch (e: ErrorResponseException) { if (e.errorResponse == ErrorResponse.UNKNOWN_BAN) return null else throw e }
            return ModerationTarget(ban.user.id, ban.user.name, ban.user.isBot, ban.user.idLong == guild.ownerIdLong, true, true)
        }
        target ?: return null
        val canTimeout = action !in setOf(ModerationAction.WARN, ModerationAction.TIMEOUT, ModerationAction.RESTORE) ||
            !target.hasPermission(Permission.ADMINISTRATOR)
        return ModerationTarget(target.id, target.effectiveName, target.user.isBot, target.idLong == guild.ownerIdLong,
            actor.canInteract(target), bot.canInteract(target) && canTimeout)
    }

    suspend fun historyTarget(actorId: String, targetId: String, allowedRoles: Set<String>): ModerationTarget? {
        val actor = member(actorId) ?: return null
        if (!authorizedModerator(actor.roles.map { it.id }.toSet(), allowedRoles,
                actor.hasPermission(Permission.MODERATE_MEMBERS), true, actorId == targetId)) return null
        val target = member(targetId)
        return if (target == null) ModerationTarget(targetId, targetId, false, targetId == guild.ownerId, true, true)
        else ModerationTarget(target.id, target.effectiveName, target.user.isBot, target.idLong == guild.ownerIdLong,
            actor.canInteract(target), true)
    }

    suspend fun membership(actorId: String, targetId: String, action: ModerationAction, reason: String, allowedRoles: Set<String>) {
        require(action in setOf(ModerationAction.KICK, ModerationAction.BAN, ModerationAction.UNBAN))
        val proof = targetForAction(actorId, targetId, action, allowedRoles)
        if (proof == null || actorId == targetId || proof.isOwner || proof.isBot || !proof.actorCanInteract || !proof.botCanInteract) {
            throw DiscordRefusal("Les permissions ou la hiérarchie ne permettent pas cette action.")
        }
        try {
            val user = UserSnowflake.fromId(targetId)
            val request = when (action) {
                ModerationAction.KICK -> guild.kick(user)
                ModerationAction.BAN -> guild.ban(user, 0, TimeUnit.SECONDS)
                ModerationAction.UNBAN -> guild.unban(user)
                else -> error("Action non prise en charge")
            }
            request.reason(("Oooscar, membre du staff " + actorId + " : " + reason).take(512)).submit().await()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw DiscordRefusal("Discord a refusé l'action de modération.") }
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

internal fun nativeModerationPermission(action: ModerationAction): Permission = when (action) {
    ModerationAction.KICK -> Permission.KICK_MEMBERS
    ModerationAction.BAN, ModerationAction.UNBAN -> Permission.BAN_MEMBERS
    ModerationAction.WARN, ModerationAction.TIMEOUT, ModerationAction.RESTORE -> Permission.MODERATE_MEMBERS
}
