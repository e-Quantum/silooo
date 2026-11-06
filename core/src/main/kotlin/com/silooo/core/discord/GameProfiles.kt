package com.silooo.core.discord

import java.time.Duration
import kotlinx.coroutines.CancellationException

/** Une photo de profil ne peut exister que derrière la preuve Discord/Minecraft active. */
data class GameProfile(
    val discordId: String,
    val xuid: String,
    val gamertag: String,
    val companyId: Long?,
    val courseNumber: Int?,
    val companyRole: String?,
    val pioneer: Boolean,
    val beta: Boolean,
    val prestige: Int,
)

interface GameProfileQueries {
    suspend fun profiles(): List<GameProfile>
    suspend fun profile(discordId: String): GameProfile?
}

interface ManagedGameRoleStore {
    suspend fun roleKeys(discordId: String): Set<String>
    suspend fun replace(discordId: String, roleKeys: Set<String>)
    suspend fun appliedNickname(discordId: String): String?
    suspend fun appliedNickname(discordId: String, nickname: String?)
    suspend fun knownUsers(): Set<String>
    /** Claim atomique avant le premier DM d'admission; l'échec ou le DM fermé ne sera jamais retenté. */
    suspend fun claimInvitation(discordId: String, at: java.time.Instant): Boolean = false
    suspend fun finishInvitation(discordId: String, status: String) {}
}

/** Synchronise périodiquement l'état prouvé. Discord refusant un surnom/rôle (hiérarchie notamment), la prochaine
 * passe retente; aucune exception transitoire ne désactive la synchronisation. */
class GameProfileFeature(
    private val context: BotContext,
    private val game: GameProfileQueries,
    private val roles: ManagedGameRoleStore,
    private val prestige: PrestigeRoleDefinitions,
    private val syncInterval: Duration,
) : Feature {
    override val name = "profils-jeu"
    private var timer: Scheduled? = null

    override suspend fun start() {
        syncAll()
        timer = context.timer.every(syncInterval) { syncAll() }
    }

    override suspend fun handle(event: DiscordEvent) {
        if (event is DiscordEvent.MemberJoined) syncOne(event.user.id)
    }

    private suspend fun syncAll() {
        try {
            val active = game.profiles().associateBy { it.discordId }
            for (profile in active.values) {
                try { sync(profile) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* retente à la passe suivante */ }
            }
            // Nettoyage des liens retirés même si le membre ne revient pas.
            val known = roles.knownUsers()
            for (id in known - active.keys) cleanup(id)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* reprise à la prochaine passe */ }
    }

    private suspend fun syncOne(id: String) {
        try { val profile = game.profile(id); if (profile == null) cleanup(id) else sync(profile) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* retentable */ }
    }

    private suspend fun sync(p: GameProfile) {
        val nick = (p.gamertag + (p.courseNumber?.let { " · P$it" } ?: "")).take(32)
        if (context.server.memberName(p.discordId) != nick) {
            if (safeRole { context.server.nickname(p.discordId, nick) }) roles.appliedNickname(p.discordId, nick)
        }
        val desired = buildSet {
            add("joueur-relie")
            if (p.companyRole == "PDG") add("pdg")
            if (p.companyRole == "ASSOCIE") add("associe")
            if (p.pioneer) add("pionnier")
            if (p.beta) add("beta")
            prestige.highest(p.prestige)?.let(::add)
        }.filterTo(mutableSetOf()) { context.ids.role(it) != null }
        val old = roles.roleKeys(p.discordId)
        val actual = old.toMutableSet()
        val actualIds = context.server.rolesOf(p.discordId).toMutableSet()
        for (key in old - desired) {
            val id = context.ids.role(key)
            if (id == null || id !in actualIds) actual.remove(key)
            else if (safeRole { context.server.takeRole(p.discordId, id) }) { actual.remove(key); actualIds.remove(id) }
        }
        for (key in desired) {
            val id = context.ids.role(key) ?: continue
            if (id in actualIds) { actual.add(key); continue }
            if (safeRole { context.server.giveRole(p.discordId, id) }) { actual.add(key); actualIds.add(id) }
        }
        roles.replace(p.discordId, actual)
        val betaRole = context.ids.role("beta")
        if (p.beta && betaRole != null && betaRole in actualIds && roles.claimInvitation(p.discordId, context.timer.now())) {
            val status = try {
                if (context.server.direct(p.discordId, BotMessage(AdmissionTexts.INVITATION))) "sent" else "dm_closed"
            } catch (e: CancellationException) { throw e } catch (_: Exception) { "discord_refused" }
            roles.finishInvitation(p.discordId, status)
        }
    }

    private suspend fun cleanup(id: String) {
        val remaining = mutableSetOf<String>()
        val actualIds = context.server.rolesOf(id)
        for (key in roles.roleKeys(id)) context.ids.role(key)?.let {
            if (it in actualIds && !safeRole { context.server.takeRole(id, it) }) remaining.add(key)
        }
        val applied = roles.appliedNickname(id)
        if (applied != null) {
            if (context.server.memberName(id) == applied) {
                if (safeRole { context.server.nickname(id, null) }) roles.appliedNickname(id, null)
            } else {
                // Le membre a changé son surnom lui-même; ne pas l'écraser à la déliaison.
                roles.appliedNickname(id, null)
            }
        }
        roles.replace(id, remaining)
    }
    private suspend fun safeRole(action: suspend () -> Unit): Boolean = try { action(); true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
}

/** Les seuils de rôles cosmétiques viennent de definitions/discord.json. */
data class PrestigeRoleDefinitions(val tiers: List<PrestigeRoleTier>) {
    fun highest(value: Int): String? = tiers.filter { value >= it.minimum }.maxByOrNull { it.minimum }?.role
}
data class PrestigeRoleTier(val minimum: Int, val role: String)

