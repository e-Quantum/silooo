package com.silooo.core.discord

import com.silooo.core.Dependencies
import com.silooo.core.db.Xuid
import com.silooo.core.db.Database
import com.silooo.core.discord.linking.GameDiscordLinks
import java.sql.Connection
import java.time.Duration
import kotlinx.coroutines.CancellationException

data class CompanyBureau(val companyId: Long, val channelId: String)

interface BureauStore {
    suspend fun all(): List<CompanyBureau>
    suspend fun find(companyId: Long): CompanyBureau?
    suspend fun save(companyId: Long, channelId: String)
}

/** Mapping des salons d'entreprise, migration V16. */
class JdbcBureauStore(private val database: com.silooo.core.db.Database) : BureauStore {
    override suspend fun all(): List<CompanyBureau> = database.transaction { c ->
        c.prepareStatement("select company_id, channel_id from discord_company_bureaus order by company_id").use { it.executeQuery().use { rows -> buildList { while (rows.next()) add(CompanyBureau(rows.getLong(1), rows.getString(2))) } } }
    }
    override suspend fun find(companyId: Long): CompanyBureau? = database.transaction { c ->
        c.prepareStatement("select company_id, channel_id from discord_company_bureaus where company_id = ?").use { it.setLong(1, companyId); it.executeQuery().use { rows -> if (rows.next()) CompanyBureau(rows.getLong(1), rows.getString(2)) else null } }
    }
    override suspend fun save(companyId: Long, channelId: String) { database.transaction { c ->
        c.prepareStatement("insert into discord_company_bureaus (company_id, channel_id, updated_at) values (?, ?, utc_timestamp(3)) on duplicate key update channel_id = values(channel_id), updated_at = values(updated_at)").use { it.setLong(1, companyId); it.setString(2, channelId); it.executeUpdate() }
    } }
}

/** Les identifiants Discord des membres actuels dont la paire de preuve est encore confirmée par l'inscription du site. */
internal suspend fun verifiedCompanyDiscordMembers(database: Database, companyId: Long): Set<String> = database.transaction { c ->
    c.prepareStatement(
        "select distinct g.discord_user_id from company_members m join discord_game_links g on g.player_xuid = m.xuid " +
            "join site_registrations s on s.gamertag_key = g.gamertag_key and s.discord_user_id = g.discord_user_id where m.company_id = ?",
    ).use { it.setLong(1, companyId); it.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } } }
}

/** Le callback crée un salon public ou synchronise ses écrivains sans jamais verrouiller le bureau. */
fun interface EnsureCompanyBureau {
    suspend fun ensure(companyId: Long, name: String, currentChannelId: String?, writers: Set<String>): String
}

object BureauTexts {
    const val COMMAND = "bureau"
    const val NOT_LINKED = "Relie ton compte Minecraft avec /lier pour ouvrir ton bureau d'entreprise."
    const val NO_COMPANY = "Tu n'es membre d'aucune entreprise."
    const val UNAVAILABLE = "Le bureau n'est pas disponible pour le moment."
}

/** Bureaux visibles par tous. Les membres reliés actuels et le staff peuvent y écrire. */
class BureauxFeature(
    private val context: BotContext,
    private val game: Dependencies,
    private val links: GameDiscordLinks,
    private val store: BureauStore,
    private val ensureBureau: EnsureCompanyBureau,
) : Feature {
    override val name = "bureaux-entreprise"
    override val commands = listOf(CommandSpec("bureau", "Ouvrir le bureau public de ton entreprise"))
    private var sync: Scheduled? = null

    override suspend fun start() {
        if (context.phase == ServerPhase.ATTENTE) return
        resyncAll()
        sync = context.timer.every(RESYNC) { resyncAll() }
    }
    override suspend fun handle(event: DiscordEvent) {
        if (event is DiscordEvent.CommandUsed && event.name == BureauTexts.COMMAND) open(event)
    }
    private suspend fun open(event: DiscordEvent.CommandUsed) {
        if (context.phase == ServerPhase.ATTENTE) { event.reply.send(BotMessage(BureauTexts.UNAVAILABLE)); return }
        try {
            val verified = links.findByDiscord(event.user.id)
            if (verified == null) { event.reply.send(BotMessage(BureauTexts.NOT_LINKED)); return }
            val xuid = Xuid.parse(verified.xuid) ?: run { event.reply.send(BotMessage(BureauTexts.NOT_LINKED)); return }
            val membership = game.database.transaction { game.sessionStore.membershipOf(it, xuid) }
            if (membership == null) { event.reply.send(BotMessage(BureauTexts.NO_COMPANY)); return }
            val company = game.database.transaction { game.sessionStore.loadCompany(it, membership.companyId) }
            val previous = store.find(membership.companyId)
            val channel = ensureBureau.ensure(membership.companyId, company.name, previous?.channelId, writers(membership.companyId))
            store.save(membership.companyId, channel)
            event.reply.send(BotMessage("Le bureau de ${company.name} est prêt : <#$channel>."))
        } catch (e: CancellationException) { throw e } catch (_: Exception) { event.reply.send(BotMessage(BureauTexts.UNAVAILABLE)) }
    }
    private suspend fun resyncAll() {
        if (context.phase == ServerPhase.ATTENTE) return
        for (bureau in store.all()) {
            try {
                val company = game.database.transaction { game.sessionStore.loadCompany(it, bureau.companyId) }
                val current = store.find(bureau.companyId)
                val id = ensureBureau.ensure(bureau.companyId, company.name, current?.channelId, writers(bureau.companyId))
                if (id != bureau.channelId) store.save(bureau.companyId, id)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Le prochain passage réessaie. */ }
        }
    }
    private suspend fun writers(companyId: Long): Set<String> {
        return verifiedCompanyDiscordMembers(game.database, companyId)
    }
    companion object { val RESYNC: Duration = Duration.ofSeconds(30) }
}
