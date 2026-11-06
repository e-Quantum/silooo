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
    suspend fun knownUsers(): Set<String>
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
        try { context.server.nickname(p.discordId, nick) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { }
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
        for (key in old - desired) if (safeRole { context.server.takeRole(p.discordId, checkNotNull(context.ids.role(key))) }) actual.remove(key)
        for (key in desired - old) if (safeRole { context.server.giveRole(p.discordId, checkNotNull(context.ids.role(key))) }) actual.add(key)
        roles.replace(p.discordId, actual)
    }

    private suspend fun cleanup(id: String) {
        val remaining = mutableSetOf<String>()
        for (key in roles.roleKeys(id)) context.ids.role(key)?.let { if (!safeRole { context.server.takeRole(id, it) }) remaining.add(key) }
        try { context.server.nickname(id, null) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        roles.replace(id, remaining)
    }
    private suspend fun safeRole(action: suspend () -> Unit): Boolean = try { action(); true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
}

/** Les seuils de rôles cosmétiques viennent de definitions/discord.json. */
data class PrestigeRoleDefinitions(val tiers: List<PrestigeRoleTier>) {
    fun highest(value: Int): String? = tiers.filter { value >= it.minimum }.maxByOrNull { it.minimum }?.role
}
data class PrestigeRoleTier(val minimum: Int, val role: String)

