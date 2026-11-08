package com.silooo.core.discord.rest

import com.silooo.core.discord.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import java.math.BigInteger
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Appels frais : ni le cache des permissions du salon, ni celui des rôles n'autorise une mutation. */
internal class ChannelModerationRest(private val token: BotToken, private val client: HttpClient, private val api: String) {
    suspend fun snapshot(guildId: String, botId: String, actorId: String, channelId: String, action: ChannelAction, staffRoles: Set<String>): ChannelModerationSnapshot {
        listOf(guildId, botId, actorId, channelId).forEach(::id)
        if (staffRoles.isEmpty() || actorId == botId) throw ChannelActionRefused(ModerationTexts.NOT_STAFF)
        val guild = readObject("/guilds/$guildId")
        if (guild.string("id") != guildId) throw ChannelActionRefused("Serveur Discord invalide.")
        val roles = guild.array("roles").associate { item -> item.obj().let { it.string("id") to it.bits("permissions") } }
        val owner = guild.string("owner_id")
        val actor = readObject("/guilds/$guildId/members/$actorId")
        val bot = readObject("/guilds/$guildId/members/$botId")
        if (actor["user"]?.jsonObject?.get("bot")?.jsonPrimitive?.booleanOrNull == true) throw ChannelActionRefused(ModerationTexts.NOT_STAFF)
        if (actor.obj("user").string("id") != actorId || bot.obj("user").string("id") != botId) throw ChannelActionRefused("Identité Discord non confirmée.")
        val actorRoles = actor.array("roles").map { it.jsonPrimitive.content }.toSet()
        val botRoles = bot.array("roles").map { it.jsonPrimitive.content }.toSet()
        if (actorRoles.none { it in staffRoles }) throw ChannelActionRefused(ModerationTexts.NOT_STAFF)
        if (!roles.keys.containsAll(actorRoles + botRoles + guildId)) throw ChannelActionRefused("Les rôles courants n'ont pas pu être vérifiés.")
        val channel = readObject("/channels/$channelId")
        if (channel.string("id") != channelId || channel.string("guild_id") != guildId || channel.int("type") != 0) throw ChannelActionRefused("Seuls les salons de texte de ce serveur sont autorisés.")
        val overwrites = channel.array("permission_overwrites").map { it.obj().let { item -> RawChannelOverride(item.string("id"), item.int("type"), item.bits("allow"), item.bits("deny")) } }
        if (overwrites.map { it.id }.distinct().size != overwrites.size) throw ChannelActionRefused("Permissions ambiguës.")
        val everyone = overwrites.firstOrNull { it.id == guildId && it.type == 0 }
        val publicBits = applyOverwrite(roles.getValue(guildId), everyone)
        if (publicBits and VIEW == 0L) throw ChannelActionRefused("Ce salon n'est pas public.")
        val required = VIEW or when (action) {
            ChannelAction.PURGE -> HISTORY or MANAGE_MESSAGES
            ChannelAction.SLOW -> MANAGE_CHANNEL
            ChannelAction.LOCK, ChannelAction.UNLOCK -> MANAGE_CHANNEL or MANAGE_ROLES
        }
        for ((memberId, memberRoles) in listOf(actorId to actorRoles, botId to botRoles)) {
            val bits = effectiveChannelPermissions(guildId, memberId, memberRoles, roles, overwrites, memberId == owner)
            if (bits and required != required) throw ChannelActionRefused("Les droits natifs de l'humain ou du bot ne permettent pas cette action dans ce salon.")
        }
        val lockable = overwrites.none { it.id != guildId && it.allow and CHANNEL_WRITING_MASK != 0L &&
            !(it.type == 0 && it.id in staffRoles) && !(it.type == 1 && it.id in setOf(botId, actorId)) }
        val slow = (channel["rate_limit_per_user"] as? JsonPrimitive)?.intOrNull ?: 0
        if (slow !in 0..21600) throw ChannelActionRefused("Délai du salon invalide.")
        return ChannelModerationSnapshot(everyone?.let { ChannelPermissionValue(true, it.allow, it.deny) } ?: ChannelPermissionValue(false), slow, lockable)
    }

    suspend fun candidates(guildId: String, botId: String, actorId: String, channelId: String, count: Int, staffRoles: Set<String>, rules: ChannelModerationRules, now: Instant): List<String> {
        if (count !in 1..rules.purgeMax) throw ChannelActionRefused("Nombre de messages invalide.")
        snapshot(guildId, botId, actorId, channelId, ChannelAction.PURGE, staffRoles)
        return messages(channelId, rules.scanLimit, null).filter { eligible(it, rules, now) }.take(count).map { it.string("id") }
    }

    suspend fun execute(guildId: String, botId: String, case: ChannelModerationCase, staffRoles: Set<String>, rules: ChannelModerationRules, now: Instant): Int {
        val plan = case.plan
        val current = snapshot(guildId, botId, case.actorId, case.channelId, plan.action, staffRoles)
        val reason = ("Oooscar, staff ${case.actorId} : ${case.reason}").take(512)
        return when (plan.action) {
            ChannelAction.PURGE -> {
                val ids = plan.messageIds
                if (ids.isEmpty() || ids.size > rules.purgeMax || ids.distinct().size != ids.size) throw ChannelActionRefused("Sélection de messages invalide.")
                ids.forEach(::id)
                val anchor = ids.maxOf { BigInteger(it) }.add(BigInteger.ONE).toString()
                val fresh = messages(case.channelId, rules.scanLimit, anchor)
                val eligible = fresh.filter { it.string("id") in ids && eligible(it, rules, now) }.map { it.string("id") }
                if (eligible.isEmpty()) throw ChannelActionRefused("Les messages sélectionnés ne sont plus supprimables.")
                // Une seule mutation, aucun repli vers les vieux messages ni vers une suppression individuelle en série.
                if (eligible.size == 1) mutate(HttpMethod.Delete, "/channels/${case.channelId}/messages/${eligible.single()}", null, reason)
                else mutate(HttpMethod.Post, "/channels/${case.channelId}/messages/bulk-delete", buildJsonObject { put("messages", JsonArray(eligible.map(::JsonPrimitive))) }, reason)
                eligible.size
            }
            ChannelAction.SLOW -> {
                if (current.slowSeconds != plan.before.slowSeconds || plan.after.slowSeconds !in rules.slowModes) throw ChannelActionRefused("Le mode lent a changé depuis l'aperçu. Recommence la commande.")
                mutate(HttpMethod.Patch, "/channels/${case.channelId}", buildJsonObject { put("rate_limit_per_user", plan.after.slowSeconds) }, reason)
                1
            }
            ChannelAction.LOCK, ChannelAction.UNLOCK -> {
                if (current.everyone != plan.before.everyone) throw ChannelActionRefused("Les permissions ont changé depuis l'aperçu. Recommence la commande.")
                if (plan.action == ChannelAction.LOCK && !current.lockable) throw ChannelActionRefused("Une exception de permission contourne le verrouillage.")
                val target = plan.after.everyone
                if (plan.action == ChannelAction.LOCK && target != ChannelPermissionValue(true, current.everyone.allow and CHANNEL_WRITING_MASK.inv(), current.everyone.deny or CHANNEL_WRITING_MASK))
                    throw ChannelActionRefused("Plan de verrouillage invalide.")
                // L'adaptateur refuse aussi les plans qui toucheraient des permissions étrangères à l'écriture.
                if ((current.everyone.allow xor target.allow) and CHANNEL_WRITING_MASK.inv() != 0L ||
                    (current.everyone.deny xor target.deny) and CHANNEL_WRITING_MASK.inv() != 0L) throw ChannelActionRefused("Le plan modifie des permissions étrangères à l'écriture.")
                if (current.everyone != target) {
                    if (target.exists) mutate(HttpMethod.Put, "/channels/${case.channelId}/permissions/$guildId", buildJsonObject {
                        put("type", 0); put("allow", target.allow.toString()); put("deny", target.deny.toString())
                    }, reason)
                    else mutate(HttpMethod.Delete, "/channels/${case.channelId}/permissions/$guildId", null, reason)
                }
                1
            }
        }
    }

    private suspend fun messages(channel: String, limit: Int, before: String?): List<JsonObject> {
        val suffix = "?limit=$limit" + (before?.let { "&before=$it" } ?: "")
        return read("/channels/$channel/messages$suffix").jsonArray.map { it.obj() }
    }
    private fun eligible(message: JsonObject, rules: ChannelModerationRules, now: Instant): Boolean {
        if ((message["pinned"] as? JsonPrimitive)?.booleanOrNull != false) return false
        if ((message["type"] as? JsonPrimitive)?.intOrNull !in setOf(0, 19)) return false
        val snowflake = message.string("id").also(::id)
        val at = Instant.ofEpochMilli(BigInteger(snowflake).shiftRight(22).longValueExact() + 1420070400000L)
        return at > now.minus(Duration.ofDays(rules.maxAgeDays.toLong())) && at <= now
    }
    private suspend fun readObject(path: String) = read(path).jsonObject
    private suspend fun read(path: String): JsonElement {
        val response = try { client.get(api + path) { auth() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw DiscordRefusal("Discord n'a pas confirmé la lecture des permissions ou des messages.") }
        if (response.status != HttpStatusCode.OK) throw ChannelActionRefused("Discord a refusé la lecture nécessaire à cette action (HTTP ${response.status.value}).")
        return try { Json.parseToJsonElement(response.bodyAsText()) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw ChannelActionRefused("La réponse Discord est illisible.") }
    }
    private suspend fun mutate(method: HttpMethod, path: String, body: JsonObject?, reason: String) {
        val response = try { client.request(api + path) {
            this.method = method; auth()
            header("X-Audit-Log-Reason", URLEncoder.encode(reason, StandardCharsets.UTF_8))
            if (body != null) { contentType(ContentType.Application.Json); setBody(body.toString()) }
        } } catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw DiscordRefusal("Discord n'a pas confirmé la mutation ; aucun nouvel essai automatique.") }
        if (response.status.value in setOf(400, 401, 403, 404, 429)) throw ChannelActionRefused("Discord a refusé l'action (HTTP ${response.status.value}).")
        if (response.status.value !in setOf(200, 204)) throw DiscordRefusal("Résultat Discord incertain ; vérifie le salon.")
    }
    private fun HttpRequestBuilder.auth() { header(HttpHeaders.Authorization, "Bot " + token.reveal()) }
    private fun id(value: String) { require(value.matches(Regex("[0-9]{1,20}"))) }
    private fun JsonElement.obj() = this as? JsonObject ?: throw ChannelActionRefused("Objet Discord invalide.")
    private fun JsonObject.obj(key: String) = this[key]?.obj() ?: throw ChannelActionRefused("Objet Discord manquant.")
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull ?: throw ChannelActionRefused("Champ Discord manquant.")
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull ?: throw ChannelActionRefused("Champ Discord invalide.")
    private fun JsonObject.bits(key: String) = string(key).toLongOrNull()?.takeIf { it >= 0L } ?: throw ChannelActionRefused("Permission Discord invalide.")
    private fun JsonObject.array(key: String) = this[key] as? JsonArray ?: throw ChannelActionRefused("Liste Discord manquante.")
    companion object {
        const val VIEW = 1L shl 10
        const val HISTORY = 1L shl 16
        const val MANAGE_MESSAGES = 1L shl 13
        const val MANAGE_CHANNEL = 1L shl 4
        const val MANAGE_ROLES = 1L shl 28
    }
}

internal data class RawChannelOverride(val id: String, val type: Int, val allow: Long, val deny: Long) {
    init { require(type in 0..1 && allow >= 0 && deny >= 0 && allow and deny == 0L) }
}
private fun applyOverwrite(bits: Long, overwrite: RawChannelOverride?): Long =
    if (overwrite == null) bits else (bits and overwrite.deny.inv()) or overwrite.allow

internal fun effectiveChannelPermissions(guildId: String, memberId: String, memberRoles: Set<String>, roles: Map<String, Long>, overwrites: List<RawChannelOverride>, owner: Boolean): Long {
    val base = (memberRoles + guildId).fold(0L) { all, id -> all or roles.getValue(id) }
    if (owner || base and (1L shl 3) != 0L) return Long.MAX_VALUE
    var result = applyOverwrite(base, overwrites.firstOrNull { it.type == 0 && it.id == guildId })
    val shared = overwrites.filter { it.type == 0 && it.id in memberRoles && it.id != guildId }
    result = (result and shared.fold(0L) { all, o -> all or o.deny }.inv()) or shared.fold(0L) { all, o -> all or o.allow }
    return applyOverwrite(result, overwrites.firstOrNull { it.type == 1 && it.id == memberId })
}
