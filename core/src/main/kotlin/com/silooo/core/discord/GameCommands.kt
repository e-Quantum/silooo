package com.silooo.core.discord

import com.silooo.core.db.Database
import com.silooo.core.db.Xuid
import com.silooo.core.db.setXuid
import com.silooo.core.economy.CompanyService
import com.silooo.core.sitedata.SiteStatsStore
import java.time.Duration
import java.time.Instant
import java.time.Clock
import java.time.DayOfWeek
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlinx.coroutines.CancellationException

data class GameCard(val title: String, val text: String)
interface GameCommandQueries {
    suspend fun company(xuid: String): GameCard?
    suspend fun factory(xuid: String, factory: String?): GameCard?
    suspend fun leaderboard(xuid: String): GameCard
    suspend fun votes(xuid: String): GameCard
}

/** Adapter de lectures: CompanyService pour l'état économique, SiteStatsStore pour le classement, VoteService pour le vote. */
class JdbcGameCommandQueries(
    private val database: Database,
    private val companies: CompanyService,
    private val stats: SiteStatsStore,
    private val votes: com.silooo.core.vote.VoteService,
    private val clock: Clock,
) : GameCommandQueries {
    override suspend fun company(xuid: String): GameCard? = database.transaction { connection ->
        val companyId = companyId(connection, xuid) ?: return@transaction null
        val state = companies.read(connection, companyId) ?: return@transaction null
        GameCard("Entreprise · ${state.name}", "Époque ${state.era} · ${state.stars} étoiles · P${state.courseNumber - 1}\n" +
            "Usines : ${state.factories.size} · Production : ${"%.2f".format(java.util.Locale.ROOT, state.ratePerHour)}/h")
    }

    override suspend fun factory(xuid: String, factory: String?): GameCard? = database.transaction { connection ->
        val companyId = companyId(connection, xuid) ?: return@transaction null
        val state = companies.read(connection, companyId) ?: return@transaction null
        val factories = state.factories.filter { factory == null || it.factoryId.equals(factory, ignoreCase = true) }
        if (factories.isEmpty()) return@transaction null
        val text = factories.joinToString("\n") { "${it.factoryId} · niveau ${it.level} · ${"%.2f".format(java.util.Locale.ROOT, it.ratePerHour)}/h" }
        GameCard("Usines · ${state.name}", text)
    }

    override suspend fun leaderboard(xuid: String): GameCard = database.transaction { connection ->
        val today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate()
        val from = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(ZoneOffset.UTC).toInstant()
        val rows = stats.weeklyRanking(connection, from, from.plus(Duration.ofDays(7)), 10, 0)
        GameCard("Classement de la semaine", if (rows.isEmpty()) "Aucun Lingot gagné cette semaine." else rows.joinToString("\n") {
            "${it.rank}. ${it.gamertag} · ${it.lingots} Lingots"
        })
    }

    override suspend fun votes(xuid: String): GameCard = database.transaction { connection ->
        val viewer = Xuid.parse(xuid) ?: return@transaction GameCard("Votes", "Profil invalide.")
        val today = votes.today(connection, viewer)
        GameCard("Votes du jour", "${today.mine.votes} vote(s) aujourd'hui · série de ${today.mine.streak} jour(s) · ${today.votes}/${today.goal} votes serveur")
    }

    private fun companyId(connection: java.sql.Connection, rawXuid: String): Long? {
        val xuid = Xuid.parse(rawXuid) ?: return null
        return connection.prepareStatement("select company_id from company_members where xuid = ?").use {
            it.setXuid(1, xuid); it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }
    }
}

/** Commandes publiques uniquement sur demande explicite. Le rendu privé et le bouton public sont liés à l'identité
 * et à la version de preuve; aucune carte ne mentionne ni ne ping un membre. */
class GameCommandsFeature(
    private val context: BotContext,
    private val game: GameCommandQueries,
    private val links: DiscordLinkGateway,
) : Feature {
    override val name = "commandes-jeu"
    override val commands = listOf(
        CommandSpec("entreprise", "Voir l'entreprise de ton compte relié"),
        CommandSpec("usine", "Voir une usine de ton entreprise", listOf(CommandOption("usine", "Identifiant d'usine", required = false))),
        CommandSpec("classement", "Voir le classement du jeu"),
        CommandSpec("vote", "Voir les votes et ta série"),
    )
    private data class Pending(val userId: String, val linkedAt: Instant, val message: BotMessage, val expires: Instant)
    private val pending = mutableMapOf<String, Pending>()

    override suspend fun handle(event: DiscordEvent) {
        when (event) {
            is DiscordEvent.CommandUsed -> if (event.name in setOf("entreprise", "usine", "classement", "vote")) command(event)
            is DiscordEvent.ButtonPressed -> if (event.id.startsWith(SHOW_PREFIX)) publish(event)
            else -> Unit
        }
    }

    private suspend fun command(e: DiscordEvent.CommandUsed) {
        val proof = try { links.findByDiscord(e.user.id) } catch (x: CancellationException) { throw x } catch (_: Exception) {
            e.reply.send(BotMessage(LinkingTexts.UNAVAILABLE), onlyToUser = true); return
        }
        if (proof == null) { e.reply.send(BotMessage(LinkingTexts.NOT_LINKED), onlyToUser = true); return }
        val card = try { when (e.name) {
            "entreprise" -> game.company(proof.xuid)
            "usine" -> game.factory(proof.xuid, e.options["usine"])
            "classement" -> game.leaderboard(proof.xuid)
            else -> game.votes(proof.xuid)
        } } catch (x: CancellationException) { throw x } catch (_: Exception) { null }
        if (card == null) { e.reply.send(BotMessage("Ces données ne sont pas disponibles pour le moment."), onlyToUser = true); return }
        val nonce = UUID.randomUUID().toString()
        val text = "**${card.title}**\n${card.text}"
        val message = BotMessage(text, image = GameCardImages.render(text), card = true)
        pending.entries.removeAll { !it.value.expires.isAfter(context.timer.now()) }
        if (pending.size >= 1000) pending.remove(pending.keys.first())
        pending[nonce] = Pending(e.user.id, proof.linkedAt, message, context.timer.now().plus(Duration.ofMinutes(5)))
        e.reply.send(message.copy(buttons = listOf(ButtonSpec(SHOW_PREFIX + nonce, "Montrer"))), onlyToUser = true)
    }

    private suspend fun publish(e: DiscordEvent.ButtonPressed) {
        val nonce = e.id.removePrefix(SHOW_PREFIX)
        val item = pending[nonce]
        if (item == null || !item.expires.isAfter(context.timer.now())) { pending.remove(nonce); e.reply.send(BotMessage("Cette carte a expiré. Relance la commande."), onlyToUser = true); return }
        if (item.userId != e.user.id) { e.reply.send(BotMessage("Seul le membre qui a demandé cette carte peut l'afficher."), onlyToUser = true); return }
        pending.remove(nonce)
        val current = try { links.findByDiscord(e.user.id) } catch (x: CancellationException) { throw x } catch (_: Exception) { null }
        if (current == null || current.linkedAt != item.linkedAt) { e.reply.send(BotMessage(LinkingTexts.STALE), onlyToUser = true); return }
        try {
            // Réponse publique volontaire; message déjà capturé, sans mentions.
            e.reply.send(item.message, onlyToUser = false)
        } catch (x: CancellationException) { throw x } catch (_: Exception) {
            e.reply.send(BotMessage("Discord n'a pas accepté cette carte. Réessaie avec la commande."), onlyToUser = true)
        }
    }

    private companion object { const val SHOW_PREFIX = "jeu-montrer:" }
}
