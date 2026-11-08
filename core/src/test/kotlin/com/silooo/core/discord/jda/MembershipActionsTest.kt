package com.silooo.core.discord.jda

import com.silooo.core.discord.*
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.requests.Response
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import net.dv8tion.jda.api.requests.restaction.CacheRestAction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Traverse le véritable adaptateur JDA et intercepte ses appels réseau, y compris la relecture finale. */
class MembershipActionsTest {
    private val actorId = "76543210987654321"
    private val targetId = "12345678901234567"
    private val botId = "99999999999999999"
    private val actions = listOf(ModerationAction.KICK, ModerationAction.BAN, ModerationAction.UNBAN)
    private inline fun <reified T> fake(noinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T

    private inner class Setup {
        var actorPermissions = setOf(Permission.KICK_MEMBERS, Permission.BAN_MEMBERS, Permission.MODERATE_MEMBERS)
        var botPermissions = actorPermissions
        var actorRoles = setOf("staff")
        var actorHierarchy = true
        var botHierarchy = true
        var present = true
        var isBot = false
        var ownerId = "88888888888888888"
        var banReadFails = false
        var banGone = false
        var targetAdmin = false
        val reads = mutableListOf<String>()
        val requests = mutableListOf<String>()
        var reason = ""
        var banWindow = -1
        var banUnit: TimeUnit? = null
        private fun user(id: String) = fake<User> { method, _ -> when (method) {
            "getId" -> id; "getIdLong" -> id.toLong(); "getName" -> "Compte"
            "isBot" -> id == targetId && isBot
            else -> error("User.$method")
        } }
        private fun member(id: String): SelfMember = fake { method, args -> when (method) {
            "getId" -> id; "getIdLong" -> id.toLong(); "getUser" -> user(id); "getEffectiveName" -> "Compte"
            "getRoles" -> (if (id == actorId) actorRoles else emptySet()).map { roleId -> fake<Role> { name, _ -> if (name == "getId") roleId else error("Role.$name") } }
            "hasPermission" -> (when (val requested = args.single()) { is Array<*> -> requested.toList(); is Collection<*> -> requested.toList(); else -> error("Permissions inconnues") }).all {
                it in when (id) { actorId -> actorPermissions; botId -> botPermissions; else -> if (targetAdmin) setOf(Permission.ADMINISTRATOR) else emptySet() }
            }
            "canInteract" -> if (id == actorId) actorHierarchy else botHierarchy
            else -> error("Member.$method")
        } }
        private fun apiError(error: ErrorResponse): ErrorResponseException {
            val raw = okhttp3.Response.Builder().request(okhttp3.Request.Builder().url("https://discord.com/api/v10/").build())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(404).message("Not Found")
                .body(("{\"code\":" + error.code + ",\"message\":\"Unknown\"}").toResponseBody("application/json".toMediaType())).build()
            return ErrorResponseException.create(error, Response(raw, 0L, emptySet()))
        }
        val guild: Guild = fake { method, args -> when (method) {
            "getSelfMember" -> member(botId)
            "getOwnerId" -> ownerId; "getOwnerIdLong" -> ownerId.toLong()
            "retrieveMemberById" -> {
                val id = args.single().toString(); reads += id
                lateinit var request: CacheRestAction<Member>
                request = fake { name, a -> when (name) {
                    "useCache" -> { assertEquals(false, a.single()); request }
                    "submit" -> if (id == targetId && !present) CompletableFuture.failedFuture<Member>(apiError(ErrorResponse.UNKNOWN_MEMBER)) else CompletableFuture.completedFuture(member(id))
                    else -> error("CacheRestAction.$name")
                } }; request
            }
            "retrieveBan" -> {
                assertEquals(targetId, (args.single() as UserSnowflake).id)
                fake<RestAction<Guild.Ban>> { name, _ -> when (name) {
                    "submit" -> if (banReadFails) CompletableFuture.failedFuture<Guild.Ban>(IllegalStateException("Ban API unavailable"))
                        else if (banGone) CompletableFuture.failedFuture<Guild.Ban>(apiError(ErrorResponse.UNKNOWN_BAN))
                        else CompletableFuture.completedFuture(Guild.Ban(user(targetId), "Ancien motif"))
                    else -> error("RestAction.$name")
                } }
            }
            "kick", "ban", "unban" -> {
                assertEquals(targetId, (args[0] as UserSnowflake).id)
                if (method == "ban") { banWindow = args[1] as Int; banUnit = args[2] as TimeUnit }
                lateinit var request: AuditableRestAction<Void>
                request = fake { name, a -> when (name) {
                    "reason" -> { reason = a.single() as String; request }
                    "submit" -> { requests += method; CompletableFuture.completedFuture<Void>(null) }
                    else -> error("AuditableRestAction.$name")
                } }; request
            }
            else -> error("Guild.$method")
        } }
        val adapter = JdaStaffActions(guild)
        suspend fun proof(action: ModerationAction) = adapter.targetForAction(actorId, targetId, action, setOf("staff"))
        suspend fun execute(action: ModerationAction) = adapter.membership(actorId, targetId, action, "Motif", setOf("staff"))
        suspend fun refused(action: ModerationAction) {
            try { execute(action); fail<Unit>("L'action doit être refusée") } catch (_: DiscordRefusal) { }
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun adapterUsesNativePermissionsAndRechecksAllThreeMembersBeforeMutation() = runBlocking {
        for (action in actions) {
            val s = Setup(); s.present = action != ModerationAction.UNBAN
            assertNotNull(s.proof(action))
            s.reads.clear(); s.execute(action)
            assertEquals(listOf(actorId, botId, targetId), s.reads)
            assertEquals(listOf(when (action) { ModerationAction.KICK -> "kick"; ModerationAction.BAN -> "ban"; else -> "unban" }), s.requests)
            assertTrue(s.reason.contains(actorId)); assertTrue(s.reason.contains("Motif"))
            if (action == ModerationAction.BAN) { assertEquals(0, s.banWindow); assertEquals(TimeUnit.SECONDS, s.banUnit) }
        }
    }

    @Test fun nativePermissionRoleAndHierarchyLossAfterPreviewPreventApiCall() = runBlocking {
        for (action in actions) for (lost in listOf("actor", "bot", "role", "actorHierarchy", "botHierarchy")) {
            if (action == ModerationAction.UNBAN && lost.endsWith("Hierarchy")) continue
            val s = Setup(); s.present = action != ModerationAction.UNBAN
            assertNotNull(s.proof(action))
            when (lost) {
                "actor" -> s.actorPermissions = emptySet(); "bot" -> s.botPermissions = emptySet(); "role" -> s.actorRoles = emptySet()
                "actorHierarchy" -> s.actorHierarchy = false; "botHierarchy" -> s.botHierarchy = false
            }
            s.refused(action)
        }
    }

    @Test fun timeoutPowerCannotExpelOrBanAndKickPowerCannotUnban() = runBlocking {
        for (action in actions) {
            val s = Setup(); s.present = action != ModerationAction.UNBAN
            s.actorPermissions = setOf(Permission.MODERATE_MEMBERS)
            s.refused(action)
        }
        val s = Setup(); s.present = false; s.actorPermissions = setOf(Permission.KICK_MEMBERS); s.refused(ModerationAction.UNBAN)
    }

    @Test fun protectedUsersAndAbsentMembersAreRejectedAtNativeBoundary() = runBlocking {
        for (action in actions) for (protected in listOf("owner", "bot")) {
            val s = Setup(); s.present = action != ModerationAction.UNBAN
            if (protected == "owner") s.ownerId = targetId else s.isBot = true
            s.refused(action)
        }
        for (action in listOf(ModerationAction.KICK, ModerationAction.BAN)) {
            val s = Setup(); s.present = false; s.refused(action)
        }
        Setup().refused(ModerationAction.UNBAN)
    }

    @Test fun disappearedBanBetweenPreviewAndFinalCheckCannotBeUnbanned() = runBlocking {
        val s = Setup(); s.present = false
        assertNotNull(s.proof(ModerationAction.UNBAN))
        s.banGone = true
        assertNull(s.proof(ModerationAction.UNBAN))
        s.refused(ModerationAction.UNBAN)
    }

    @Test fun unavailableBanLookupFailsClosedWithoutUnbanning() = runBlocking {
        val s = Setup(); s.present = false; s.banReadFails = true
        try { s.execute(ModerationAction.UNBAN); fail<Unit>("Lecture en échec") } catch (_: IllegalStateException) { }
        assertTrue(s.requests.isEmpty())
    }

    @Test fun departedHistoryNeedsCurrentStaffRoleAndNativePermissionButNoLiveTarget() = runBlocking {
        val s = Setup(); s.present = false
        assertEquals(targetId, s.adapter.historyTarget(actorId, targetId, setOf("staff"))?.id)
        s.actorRoles = emptySet()
        assertNull(s.adapter.historyTarget(actorId, targetId, setOf("staff")))
        s.actorRoles = setOf("staff"); s.actorPermissions = emptySet()
        assertNull(s.adapter.historyTarget(actorId, targetId, setOf("staff")))
    }
}
