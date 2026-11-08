package com.silooo.core.discord.rest

import com.silooo.core.discord.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import java.io.IOException
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.dv8tion.jda.api.Permission
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChannelModerationRestTest {
    private val now = Instant.parse("2026-11-08T18:00:00Z")
    private val rules = ChannelModerationRules(listOf("discussion"), 100, 100, 13, listOf(0, 10, 30), 300)
    private val native = ChannelModerationRest.MANAGE_MESSAGES or ChannelModerationRest.MANAGE_CHANNEL or ChannelModerationRest.MANAGE_ROLES
    private fun snowflake(at: Instant, suffix: Long = 0) = BigInteger.valueOf(at.toEpochMilli() - 1420070400000L).shiftLeft(22).add(BigInteger.valueOf(suffix)).toString()
    private fun message(id: String, pinned: Boolean = false, type: Int = 0) = buildJsonObject { put("id", id); put("pinned", pinned); put("type", type) }
    private inner class Setup : AutoCloseable {
        var actorNative = native
        var botNative = native
        var actorStaff = true
        var actorBot = false
        var actorExtra = ""
        var botExtra = ""
        var kind = 0
        var foreignGuild = false
        var slow = 0
        var permission = ChannelPermissionValue(false)
        val overrides = mutableListOf<JsonObject>()
        var history = listOf(message(snowflake(now.minusSeconds(10))))
        var mutationStatus = HttpStatusCode.NoContent
        var failMutation = false
        val mutations = mutableListOf<Pair<String, JsonObject?>>()
        val queries = mutableListOf<Url>()
        private fun overwrite(id: String, type: Int, allow: Long, deny: Long) = buildJsonObject {
            put("id", id); put("type", type); put("allow", allow.toString()); put("deny", deny.toString())
        }
        fun extraRole(id: String = "33", allow: Long = CHANNEL_WRITING_MASK, deny: Long = 0) { overrides += overwrite(id, 0, allow, deny) }
        fun memberOverride(id: String, allow: Long, deny: Long) { overrides += overwrite(id, 1, allow, deny) }
        private val client = HttpClient(MockEngine { request ->
            queries += request.url
            val path = request.url.encodedPath
            if (request.method != HttpMethod.Get) {
                val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it).jsonObject }
                mutations += path to body
                assertEquals("Bot test-token", request.headers[HttpHeaders.Authorization])
                assertTrue(request.headers["X-Audit-Log-Reason"]!!.contains("111"))
                if (failMutation) throw IOException("requête privée test-token")
                if (mutationStatus.value !in 200..299) return@MockEngine respond("{}", mutationStatus, JSON)
                when {
                    request.method == HttpMethod.Put -> permission = ChannelPermissionValue(true, body!!.getValue("allow").jsonPrimitive.content.toLong(), body.getValue("deny").jsonPrimitive.content.toLong())
                    request.method == HttpMethod.Patch -> slow = body!!.getValue("rate_limit_per_user").jsonPrimitive.int
                    path.endsWith("/permissions/99") -> permission = ChannelPermissionValue(false)
                }
                return@MockEngine respond("", mutationStatus)
            }
            val body = when (path) {
                "/guilds/99" -> buildJsonObject {
                    put("id", "99"); put("owner_id", "888")
                    put("roles", buildJsonArray {
                        add(buildJsonObject { put("id", "99"); put("permissions", (ChannelModerationRest.VIEW or ChannelModerationRest.HISTORY or (1L shl 11)).toString()) })
                        for ((id, bits) in listOf("11" to actorNative, "22" to botNative, "33" to 0L)) add(buildJsonObject { put("id", id); put("permissions", bits.toString()) })
                    })
                }
                "/guilds/99/members/111", "/guilds/99/members/999" -> {
                    val actor = path.endsWith("/111")
                    buildJsonObject {
                        put("user", buildJsonObject { put("id", if (actor) "111" else "999"); put("bot", actor && actorBot) })
                        put("roles", buildJsonArray {
                            if (!actor || actorStaff) add(JsonPrimitive(if (actor) "11" else "22"))
                            val extra = if (actor) actorExtra else botExtra
                            if (extra.isNotEmpty()) add(JsonPrimitive(extra))
                        })
                    }
                }
                "/channels/77" -> buildJsonObject {
                    put("id", "77"); put("guild_id", if (foreignGuild) "88" else "99"); put("type", kind); put("rate_limit_per_user", slow)
                    put("permission_overwrites", JsonArray(overrides + if (permission.exists) listOf(overwrite("99", 0, permission.allow, permission.deny)) else emptyList()))
                }
                "/channels/77/messages" -> {
                    val before = request.url.parameters["before"]?.let(::BigInteger)
                    JsonArray(history.filter { before == null || BigInteger(it.getValue("id").jsonPrimitive.content) < before }.take(request.url.parameters["limit"]!!.toInt()))
                }
                else -> error("Route inattendue : $path")
            }
            respond(body.toString(), HttpStatusCode.OK, JSON)
        })
        val rest = ChannelModerationRest(BotToken("test-token"), client, "https://discord.test/api".removeSuffix("/api"))
        suspend fun snapshot(action: ChannelAction) = rest.snapshot("99", "999", "111", "77", action, setOf("11"))
        suspend fun execute(plan: ChannelActionPlan) = rest.execute("99", "999", ChannelModerationCase("case", "111", "77", "Motif", now, now.plusSeconds(600), plan), setOf("11"), rules, now)
        suspend fun candidates(count: Int = 100) = rest.candidates("99", "999", "111", "77", count, setOf("11"), rules, now)
        override fun close() = client.close()
    }

    @Test fun purgeCapturesOnlyEligibleIdsAndNeverSubstitutesNewMessages() = runBlocking {
        Setup().use { s ->
            val normal = snowflake(now.minusSeconds(100))
            val repinned = snowflake(now.minusSeconds(101))
            val old = snowflake(now.minus(Duration.ofDays(13)))
            s.history = listOf(message(normal), message(repinned), message(snowflake(now.minusSeconds(102)), true), message(old), message(snowflake(now.minusSeconds(103)), type = 6))
            val before = s.snapshot(ChannelAction.PURGE)
            val ids = s.candidates()
            assertEquals(listOf(normal, repinned), ids)
            val new = snowflake(now.minusSeconds(1))
            s.history = listOf(message(new), message(normal), message(repinned, true), message(old))
            assertEquals(1, s.execute(ChannelActionPlan(ChannelAction.PURGE, before, before, ids)))
            assertEquals("/channels/77/messages/$normal", s.mutations.single().first)
            assertEquals(BigInteger(normal).add(BigInteger.ONE).toString(), s.queries.last { it.encodedPath.endsWith("/messages") }.parameters["before"])
        }
    }

    @Test fun multipleEligibleMessagesUseExactlyOneBulkRequest() = runBlocking {
        Setup().use { s ->
            s.history = (1..3).map { message(snowflake(now.minusSeconds(it.toLong()))) }
            val state = s.snapshot(ChannelAction.PURGE); val ids = s.candidates(2)
            assertEquals(2, s.execute(ChannelActionPlan(ChannelAction.PURGE, state, state, ids)))
            val mutation = s.mutations.single()
            assertTrue(mutation.first.endsWith("/bulk-delete"))
            assertEquals(ids, mutation.second!!.getValue("messages").jsonArray.map { it.jsonPrimitive.content })
        }
    }

    @Test fun roleAndNativePermissionLossAreReadAgainBeforeEveryMutation() = runBlocking {
        for (action in ChannelAction.entries) for (loss in listOf("role", "actor", "bot")) Setup().use { s ->
            val state = s.snapshot(action)
            when (loss) { "role" -> s.actorStaff = false; "actor" -> s.actorNative = 0; else -> s.botNative = 0 }
            val after = state.copy(slowSeconds = 10, everyone = ChannelPermissionValue(true, 0, CHANNEL_WRITING_MASK))
            try { s.execute(ChannelActionPlan(action, state, after, listOf(snowflake(now.minusSeconds(10))))); fail<Unit>("Action interdite") } catch (_: ChannelActionRefused) { }
            assertTrue(s.mutations.isEmpty())
        }
    }

    @Test fun effectiveMemberDenialsAndPrivateOrWrongChannelsPreventMutation() = runBlocking {
        for (refusal in listOf("private", "thread", "foreign", "member", "bot")) Setup().use { s ->
            when (refusal) {
                "private" -> s.permission = ChannelPermissionValue(true, 0, ChannelModerationRest.VIEW)
                "thread" -> s.kind = 11; "foreign" -> s.foreignGuild = true
                "member" -> s.memberOverride("111", 0, ChannelModerationRest.MANAGE_MESSAGES)
                "bot" -> s.actorBot = true
            }
            try { s.snapshot(ChannelAction.PURGE); fail<Unit>("Accès interdit") } catch (_: ChannelActionRefused) { }
            assertTrue(s.mutations.isEmpty())
        }
    }

    @Test fun lockPreservesUnrelatedBitsAndUnlockRemovesOnlyAnOriginallyAbsentOverride() = runBlocking {
        Setup().use { s ->
            val before = s.snapshot(ChannelAction.LOCK)
            val after = before.copy(everyone = ChannelPermissionValue(true, 0, CHANNEL_WRITING_MASK))
            s.execute(ChannelActionPlan(ChannelAction.LOCK, before, after))
            assertEquals(after.everyone, s.permission)
            val locked = s.snapshot(ChannelAction.UNLOCK)
            val restored = locked.copy(everyone = restoreChannelWriting(locked.everyone, before.everyone, after.everyone, CHANNEL_WRITING_MASK))
            s.execute(ChannelActionPlan(ChannelAction.UNLOCK, locked, restored))
            assertFalse(s.permission.exists)
        }
        Setup().use { s ->
            val unrelated = 1L shl 60
            s.permission = ChannelPermissionValue(true, unrelated, 1L shl 11)
            val before = s.snapshot(ChannelAction.LOCK)
            val after = before.copy(everyone = ChannelPermissionValue(true, unrelated, CHANNEL_WRITING_MASK))
            s.execute(ChannelActionPlan(ChannelAction.LOCK, before, after))
            assertEquals(unrelated, s.permission.allow)
            val current = s.snapshot(ChannelAction.UNLOCK)
            val restored = current.copy(everyone = restoreChannelWriting(current.everyone, before.everyone, after.everyone, CHANNEL_WRITING_MASK))
            s.execute(ChannelActionPlan(ChannelAction.UNLOCK, current, restored))
            assertEquals(before.everyone, s.permission)
        }
    }

    @Test fun newPermissionExceptionsOrManualChangesSincePreviewPreventLockAndSlowmode() = runBlocking {
        Setup().use { s ->
            val before = s.snapshot(ChannelAction.LOCK)
            s.extraRole()
            assertFalse(s.snapshot(ChannelAction.LOCK).lockable)
            try { s.execute(ChannelActionPlan(ChannelAction.LOCK, before, before.copy(everyone = ChannelPermissionValue(true, 0, CHANNEL_WRITING_MASK)))); fail<Unit>("Exception nouvelle") } catch (_: ChannelActionRefused) { }
            assertTrue(s.mutations.isEmpty())
        }
        Setup().use { s ->
            val before = s.snapshot(ChannelAction.SLOW); s.slow = 30
            try { s.execute(ChannelActionPlan(ChannelAction.SLOW, before, before.copy(slowSeconds = 10))); fail<Unit>("Mode modifié") } catch (_: ChannelActionRefused) { }
            assertTrue(s.mutations.isEmpty())
        }
    }

    @Test fun alreadyRestoredPermissionsNeedNoHttpMutation() = runBlocking {
        Setup().use { s ->
            val state = s.snapshot(ChannelAction.UNLOCK)
            assertEquals(1, s.execute(ChannelActionPlan(ChannelAction.UNLOCK, state, state)))
            assertTrue(s.mutations.isEmpty())
        }
    }

    @Test fun slowmodeSendsOnlyItsOwnFieldAndDoesNotAlterPermissions() = runBlocking {
        Setup().use { s ->
            val before = s.snapshot(ChannelAction.SLOW)
            s.execute(ChannelActionPlan(ChannelAction.SLOW, before, before.copy(slowSeconds = 10)))
            assertEquals(setOf("rate_limit_per_user"), s.mutations.single().second!!.keys)
            assertEquals(10, s.slow); assertFalse(s.permission.exists)
        }
    }

    @Test fun rateLimitAndNetworkUncertaintyNeverTriggerAutomaticRetryOrLeakToken() = runBlocking {
        for (network in listOf(false, true)) Setup().use { s ->
            val state = s.snapshot(ChannelAction.SLOW)
            s.failMutation = network; s.mutationStatus = HttpStatusCode.TooManyRequests
            try { s.execute(ChannelActionPlan(ChannelAction.SLOW, state, state.copy(slowSeconds = 10))); fail<Unit>("Erreur attendue") }
            catch (e: DiscordRefusal) { assertFalse(e.message!!.contains("test-token")); assertEquals(!network, e is ChannelActionRefused) }
            assertEquals(1, s.mutations.size)
        }
    }

    @Test fun permissionPrecedenceMatchesDiscordAndNativeBitsMatchJda() {
        assertEquals(Permission.MESSAGE_SEND.rawValue or Permission.MESSAGE_SEND_IN_THREADS.rawValue or Permission.CREATE_PUBLIC_THREADS.rawValue or Permission.CREATE_PRIVATE_THREADS.rawValue, CHANNEL_WRITING_MASK)
        val bit = Permission.MESSAGE_MANAGE.rawValue
        val roles = mapOf("99" to bit, "11" to 0L, "22" to 0L)
        val overrides = listOf(RawChannelOverride("99", 0, 0, bit), RawChannelOverride("11", 0, 0, bit), RawChannelOverride("22", 0, bit, 0))
        assertEquals(bit, effectiveChannelPermissions("99", "111", setOf("11", "22"), roles, overrides, false))
        assertEquals(0L, effectiveChannelPermissions("99", "111", setOf("11", "22"), roles, overrides + RawChannelOverride("111", 1, 0, bit), false))
        assertEquals(Long.MAX_VALUE, effectiveChannelPermissions("99", "111", emptySet(), roles, overrides, true))
    }
    private companion object { val JSON = headersOf(HttpHeaders.ContentType, "application/json") }
}
