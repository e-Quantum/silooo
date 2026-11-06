package com.silooo.core.discord

import com.silooo.core.Dependencies
import com.silooo.core.api.GameEvent
import com.silooo.core.db.Xuid
import com.silooo.core.discord.linking.GameDiscordLinks
import com.silooo.core.economy.CompanyService
import com.silooo.core.findings.FindingService
import com.silooo.core.mission.MissionService
import com.silooo.core.events.GameEventKind
import com.silooo.core.site.NEWS_URL
import com.silooo.core.site.NewsArticle
import com.silooo.core.site.NewsArticles
import com.silooo.core.sitedata.RankedPlayer
import com.silooo.core.vote.VoteRules
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface GameEventCursorStore {
    suspend fun current(): Long?
    suspend fun initializeToLatest(): Long
    suspend fun advance(eventId: Long)
}

/** Curseur durable sur `game_events`, migration V16. */
class JdbcGameEventCursorStore(private val database: com.silooo.core.db.Database) : GameEventCursorStore {
    override suspend fun current(): Long? = database.transaction { c ->
        c.prepareStatement("select event_id from discord_game_event_cursor where cursor_key = 'records'").use { it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null } }
    }
    override suspend fun initializeToLatest(): Long = database.transaction { c ->
        val latest = c.createStatement().use { st -> st.executeQuery("select coalesce(max(id), 0) from game_events").use { rows -> rows.next(); rows.getLong(1) } }
        c.prepareStatement("insert into discord_game_event_cursor (cursor_key, event_id, updated_at) values ('records', ?, utc_timestamp(3)) on duplicate key update event_id = event_id").use { it.setLong(1, latest); it.executeUpdate() }
        c.prepareStatement("select event_id from discord_game_event_cursor where cursor_key = 'records'").use { it.executeQuery().use { rows -> rows.next(); rows.getLong(1) } }
    }
    override suspend fun advance(eventId: Long) { database.transaction { c ->
        c.prepareStatement("insert into discord_game_event_cursor (cursor_key, event_id, updated_at) values ('records', ?, utc_timestamp(3)) on duplicate key update event_id = greatest(event_id, values(event_id)), updated_at = values(updated_at)").use { it.setLong(1, eventId); it.executeUpdate() }
    } }
}

interface CommunityStateStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
}

class JdbcCommunityStateStore(private val database: com.silooo.core.db.Database) : CommunityStateStore {
    override suspend fun get(key: String): String? = database.transaction { c ->
        c.prepareStatement("select state_value from discord_community_state where state_key = ?").use { it.setString(1, key); it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null } }
    }
    override suspend fun put(key: String, value: String) { database.transaction { c ->
        c.prepareStatement("insert into discord_community_state (state_key, state_value, updated_at) values (?, ?, utc_timestamp(3)) on duplicate key update state_value = values(state_value), updated_at = values(updated_at)").use { it.setString(1, key); it.setString(2, value); it.executeUpdate() }
    } }
}

object CommunityBoardTexts {
    const val SILO_KEY = "community-live-silo"
    const val PANTHEON_KEY = "community-weekly-pantheon"
    const val PATCH_CURSOR = "patch-notes-last-slug"
    const val RECORDS_POLL = "records-last-poll"
    const val RECORDS_TITLE = "Ce qui se passe dans Silooo"
    const val NO_DATA = "Les données du jeu ne sont pas encore disponibles."
    fun live(lines: List<String>, siteUrl: String): BotMessage = BotMessage(
        lines.ifEmpty { listOf(NO_DATA) }.joinToString("\n"),
        buttons = listOf(
            ButtonSpec("silo-rejoindre", "Rejoindre", url = siteUrl.trimEnd('/') + "/rejoindre/"),
            ButtonSpec("silo-vote", "Voter", url = siteUrl.trimEnd('/') + "/vote/"),
            ButtonSpec("silo-entreprise", "Mon entreprise"),
        ), card = true,
    )
}

/** Le salon vivant lit la présence, la commande du jour, les votes et le classement depuis les tables et services canoniques. */
class CommunityGameBoardsFeature(
    private val context: BotContext,
    private val game: Dependencies,
    private val links: GameDiscordLinks,
    private val messages: BotMessageStore,
    private val cursors: GameEventCursorStore,
    private val siteUrl: String,
) : Feature {
    override val name = "tableaux-jeu-discord"
    private var refresh: Scheduled? = null
    override suspend fun start() {
        refreshSilo()
        refreshPantheon()
        refresh = context.timer.every(LIVE_REFRESH) { refreshSilo() }
        context.timer.every(LIVE_REFRESH) { refreshPantheon() }
        cursors.current() ?: cursors.initializeToLatest()
        publishNewEvents()
        context.timer.every(EVENT_POLL) { publishNewEvents() }
    }
    override suspend fun handle(event: DiscordEvent) {
        if (event is DiscordEvent.ButtonPressed && event.id == "silo-entreprise") showCompany(event)
    }

    private suspend fun showCompany(event: DiscordEvent.ButtonPressed) {
        try {
            val proof = links.findByDiscord(event.user.id)
            val xuid = proof?.xuid?.let { Xuid.parse(it) }
            if (xuid == null) { event.reply.send(BotMessage("Relie ton compte Minecraft avec /lier pour voir ton entreprise.")); return }
            val membership = game.database.transaction { game.sessionStore.membershipOf(it, xuid) }
            if (membership == null) { event.reply.send(BotMessage("Tu n'es membre d'aucune entreprise.")); return }
            val service = CompanyService(
                sessions = game.sessionStore,
                companies = game.companyStore,
                engine = game.economy,
                figures = game.economyFigures,
                missions = MissionService(game.definitions, game.missionStore, game.pass),
                findings = FindingService(game.definitions, game.findingStore),
                pass = game.pass,
            )
            val state = game.database.transaction { service.read(it, membership.companyId) }
            if (state == null) { event.reply.send(BotMessage("Cette entreprise n'existe plus.")); return }
            val members = state.members.joinToString("\n") { "${it.name} · ${it.role.name.lowercase()}" }
            event.reply.send(BotMessage("${state.name}\nÉpoque ${state.era} · ${state.stars} étoiles\nMembres\n$members", card = true))
        } catch (e: CancellationException) { throw e } catch (_: Exception) { event.reply.send(BotMessage("Les données de l'entreprise sont indisponibles.")) }
    }

    private suspend fun refreshSilo() {
        val channel = context.ids.channel("le-silo") ?: return
        try {
            val text = liveSnapshot()
            showCommunityLiving(context.server, messages, CommunityBoardTexts.SILO_KEY, channel, text)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Le prochain relevé lira à nouveau les sources. */ }
    }

    private suspend fun refreshPantheon() {
        val channel = context.ids.channel("pantheon") ?: return
        try {
            val today = game.clock.instant().atOffset(ZoneOffset.UTC).toLocalDate()
            val top = weeklyTop(today, 10)
            val body = if (top.isEmpty()) "Personne n'a encore gagné de Lingots cette semaine." else top.joinToString("\n") { "${it.rank}. ${it.gamertag}" }
            showCommunityLiving(context.server, messages, CommunityBoardTexts.PANTHEON_KEY, channel, BotMessage("Panthéon de la semaine\n$body", card = true))
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Le relevé suivant relira le classement. */ }
    }

    private suspend fun liveSnapshot(): BotMessage {
        val lines = mutableListOf<String>()
        val online = game.serverPresence.onlineXuids().size
        val alive = game.serverPresence.all().any {
            !it.receivedAt.isBefore(game.clock.instant().minus(com.silooo.core.server.ServerPresence.DEFAULT_MAX_BEAT_AGE))
        }
        lines += "${if (alive) "Serveur ouvert" else "Serveur sans signal récent"} · $online joueur${if (online == 1) "" else "s"} en ligne"
        val today = game.clock.instant().atOffset(ZoneOffset.UTC).toLocalDate()
        val votes = game.database.transaction { game.voteStore.countOn(it, today) }
        val party = game.definitions.vote.party
        lines += "Vote party · $votes / ${party.votesPerDay} votes" + if (VoteRules.partyReached(votes, game.definitions.vote)) " · objectif atteint" else ""
        val order = game.database.transaction { game.orderStore.find(it, today) }
        if (order != null) lines += "Commande du jour · ${order.resourceId} · ${order.delivered} / ${order.target}" + if (order.completed) " · terminée" else ""
        val ranked = weeklyTop(today)
        if (ranked.isNotEmpty()) lines += "Panthéon de la semaine · " + ranked.joinToString(" · ") { "${it.rank}. ${it.gamertag}" }
        val latest = latestGlobalPrestige()
        if (latest != null) lines += "Dernier prestige · ${latest.payload["companyName"]?.jsonPrimitive?.content.orEmpty()}"
        return CommunityBoardTexts.live(lines, siteUrl)
    }

    private suspend fun weeklyTop(today: LocalDate, limit: Int = 3): List<RankedPlayer> {
        val monday = today.minusDays(((today.dayOfWeek.value + 6) % 7).toLong())
        return game.database.transaction { game.siteStatsStore.weeklyRanking(it, monday.atStartOfDay().toInstant(ZoneOffset.UTC), monday.plusDays(7).atStartOfDay().toInstant(ZoneOffset.UTC), limit, 0) }
    }
    private suspend fun latestGlobalPrestige(): GameEvent? = game.database.transaction { c ->
        c.prepareStatement("select id, xuid, kind, payload from game_events where kind = 'PRESTIGE_ENTREPRISE' and xuid is null order by id desc limit 1").use { it.executeQuery().use { rows ->
            if (!rows.next()) null else GameEvent(rows.getLong("id"), null, rows.getString("kind"), com.silooo.core.api.SiloooJson.parseToJsonElement(rows.getString("payload")).jsonObject)
        } }
    }

    private suspend fun publishNewEvents() {
        val channel = context.ids.channel("records") ?: return
        var after = cursors.current() ?: cursors.initializeToLatest()
        while (true) {
            val events = game.database.transaction { game.eventStore.readAfter(it, after, EVENT_BATCH) }
            if (events.isEmpty()) return
            for (event in events) {
                recordText(event)?.let { text -> showRecord(channel, event.id, text) }
                after = event.id
                cursors.advance(after)
            }
            if (events.size < EVENT_BATCH) return
        }
    }
    private suspend fun recordText(event: GameEvent): Pair<String, String>? {
        val kind = runCatching { GameEventKind.valueOf(event.kind) }.getOrNull() ?: return null
        val payload = event.payload
        return when {
            kind == GameEventKind.PRESTIGE_ENTREPRISE || (kind == GameEventKind.ANNONCE && payload["action"]?.jsonPrimitive?.content == "prestige") -> {
                val hour = eventHour(event.id) ?: return null
                val companies = prestigeCompanies(hour)
                "discord-record-prestige-$hour" to "Cette heure, ${companies.size} entreprise${if (companies.size == 1) "" else "s"} a${if (companies.size == 1) "" else "nt"} passé un prestige."
            }
            kind == GameEventKind.COMMANDE_TERMINEE -> {
                val day = payload["day"]?.jsonPrimitive?.content ?: return null
                "discord-record-world-order-$day" to "La commande du jour du $day est terminée."
            }
            kind == GameEventKind.BOOST_COLLECTIF -> {
                val minutes = payload["minutes"]?.jsonPrimitive?.content ?: return null
                val time = eventHour(event.id) ?: return null
                "discord-record-boost-$time" to "Un boost collectif de $minutes minutes a été activé."
            }
            kind == GameEventKind.INCIDENT -> {
                val action = payload["action"]?.jsonPrimitive?.content ?: return null
                val companyId = payload["companyId"]?.jsonPrimitive?.content ?: return null
                val incidentId = payload["incidentId"]?.jsonPrimitive?.content ?: return null
                if (action !in setOf("opened", "resolved")) return null
                "discord-record-incident-$companyId-$incidentId-$action" to if (action == "opened") "Un incident d'usine a été signalé." else "Un incident d'usine a été résolu."
            }
            kind == GameEventKind.ANNONCE && event.xuid == null && payload["action"]?.jsonPrimitive?.content == "voteParty" -> {
                val day = payload["day"]?.jsonPrimitive?.content ?: return null
                val votes = payload["votes"]?.jsonPrimitive?.content ?: return null
                "discord-record-vote-party-$day" to "La Vote party est déclenchée : $votes votes."
            }
            else -> null
        }
    }
    private suspend fun eventHour(eventId: Long): String? = game.database.transaction { c ->
        c.prepareStatement("select created_at from game_events where id = ?").use { it.setLong(1, eventId); it.executeQuery().use { rows ->
            if (!rows.next()) null else rows.getObject(1, java.time.LocalDateTime::class.java).toInstant(ZoneOffset.UTC).toString().substring(0, 13)
        } }
    }
    private suspend fun prestigeCompanies(hour: String): Set<String> = game.database.transaction { c ->
        val start = java.time.LocalDateTime.parse(hour + ":00:00")
        val end = start.plusHours(1)
        c.prepareStatement("select kind, payload from game_events where created_at >= ? and created_at < ? and kind in ('PRESTIGE_ENTREPRISE', 'ANNONCE')").use { ps ->
            ps.setObject(1, start); ps.setObject(2, end)
            ps.executeQuery().use { rows ->
                buildSet {
                    while (rows.next()) {
                        val kind = rows.getString("kind")
                        val payload = com.silooo.core.api.SiloooJson.parseToJsonElement(rows.getString("payload")).jsonObject
                        if (kind == "PRESTIGE_ENTREPRISE" || payload["action"]?.jsonPrimitive?.content == "prestige") {
                            payload["companyId"]?.jsonPrimitive?.content?.let(::add)
                        }
                    }
                }
            }
        }
    }
    private suspend fun showRecord(channel: String, eventId: Long, publication: Pair<String, String>) {
        val (key, text) = publication
        if (key.startsWith("discord-record-prestige-")) {
            showCommunityLiving(context.server, messages, key, channel, BotMessage(text, card = true))
            return
        }
        if (messages.find(key) != null) return
        val messageId = context.server.post(channel, BotMessage(text, card = true))
        messages.save(key, channel, messageId)
    }
    companion object {
        val LIVE_REFRESH: Duration = Duration.ofSeconds(30)
        val EVENT_POLL: Duration = Duration.ofSeconds(30)
        const val EVENT_BATCH = 100
    }
}

/** Les statuts sont posés par le staff dans le fil forum de la suggestion et gardés avec le message vivant. */
class SuggestionStatusFeature(
    private val context: BotContext,
    private val state: CommunityStateStore,
    private val messages: BotMessageStore,
) : Feature {
    override val name = "statuts-suggestions"
    override val commands = listOf(CommandSpec("suggestion", "Mettre à jour le statut de la suggestion de ce fil", listOf(
        CommandOption("statut", "Nouveau statut", required = true, choices = listOf("lue", "acceptee", "prevue", "faite")),
    ), staffOnly = true))

    override suspend fun handle(event: DiscordEvent) {
        if (event !is DiscordEvent.CommandUsed || event.name != "suggestion") return
        val thread = event.channelId
        val parent = context.ids.channel("suggestions")
        if (thread == null || parent == null || thread == parent) {
            event.reply.send(BotMessage("Utilise cette commande dans le fil de la suggestion.")); return
        }
        if (context.server.parentChannelId(thread) != parent) {
            event.reply.send(BotMessage("Utilise cette commande dans un fil de #suggestions.")); return
        }
        if (context.ids.role("staff") !in event.user.roleIds && context.ids.role("moderation") !in event.user.roleIds) {
            event.reply.send(BotMessage("Cette commande est réservée au staff.")); return
        }
        val label = when (event.options["statut"]) {
            "lue" -> "lue"
            "acceptee" -> "acceptée"
            "prevue" -> "prévue"
            "faite" -> "faite"
            else -> { event.reply.send(BotMessage("Choisis un statut proposé par la commande.")); return }
        }
        try {
            val key = "suggestion-status:$thread"
            val id = "suggestion-status-$thread"
            val text = BotMessage("Statut de la suggestion : $label", card = true)
            val existing = messages.find(id)
            if (existing == null) {
                val messageId = context.server.post(thread, text)
                messages.save(id, thread, messageId)
            } else {
                showCommunityLiving(context.server, messages, id, thread, text)
            }
            state.put(key, label)
            event.reply.send(BotMessage("Le statut est passé à « $label »."))
        } catch (e: CancellationException) { throw e } catch (_: Exception) { event.reply.send(BotMessage("Le statut n'a pas pu être mis à jour.")) }
    }
}

/** Les actus publiées deviennent des cartes. Au premier démarrage, le curseur est posé sur la plus récente : aucun historique n'est republié. */
class PatchNotesFeature(
    private val context: BotContext,
    private val siteUrl: String,
    private val state: CommunityStateStore,
    private val articles: List<NewsArticle> = NewsArticles.fromClasspath(),
) : Feature {
    override val name = "patch-notes-discord"
    private var timer: Scheduled? = null
    override suspend fun start() {
        if (context.phase == ServerPhase.ATTENTE) return
        val published = NewsArticles.published(articles)
        val previous = state.get(CommunityBoardTexts.PATCH_CURSOR)
        if (previous == null) {
            published.firstOrNull()?.let { state.put(CommunityBoardTexts.PATCH_CURSOR, it.slug) }
        } else {
            publishAfter(previous, published)
        }
        timer = context.timer.every(PATCH_REFRESH) {
            val cursor = state.get(CommunityBoardTexts.PATCH_CURSOR)
            if (cursor != null) publishAfter(cursor, NewsArticles.published(articles))
        }
    }
    override suspend fun handle(event: DiscordEvent) { }
    private suspend fun publishAfter(previous: String, published: List<NewsArticle>) {
        val channel = context.ids.channel("patch-notes") ?: return
        val index = published.indexOfFirst { it.slug == previous }
        val news = if (index <= 0) emptyList() else published.take(index).reversed()
        for (article in news) {
            val link = siteUrl.trimEnd('/') + NEWS_URL + article.slug + "/"
            context.server.post(channel, BotMessage("${article.title}\n${article.description}\n$link", buttons = listOf(ButtonSpec("news-${article.slug}", "Lire la mise à jour", url = link)), card = true))
            state.put(CommunityBoardTexts.PATCH_CURSOR, article.slug)
        }
    }
    companion object { val PATCH_REFRESH: Duration = Duration.ofMinutes(5) }
}

/** Un tableau persistant n'est republié que si Discord confirme que le message n'existe plus. */
private suspend fun showCommunityLiving(
    server: DiscordServer,
    messages: BotMessageStore,
    key: String,
    channel: String,
    message: BotMessage,
): BotMessageRef {
    val known = messages.find(key)
    if (known != null && known.channelId == channel) {
        try {
            server.edit(channel, known.messageId, message)
            return known
        } catch (e: CancellationException) {
            throw e
        } catch (_: DiscordMessageMissing) {
            // Le remplacement est réservé à la suppression confirmée par Discord.
        }
    }
    val created = BotMessageRef(channel, server.post(channel, message))
    messages.save(key, channel, created.messageId)
    return created
}
