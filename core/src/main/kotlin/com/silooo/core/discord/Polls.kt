package com.silooo.core.discord

import com.silooo.core.db.Database
import com.silooo.core.db.Xuid
import com.silooo.core.db.setXuid
import com.silooo.core.discord.linking.GameDiscordLinks
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PollOption(val answer: String, val consequence: String)
data class CommunityPoll(
    val id: String,
    val channelId: String,
    val messageId: String?,
    val question: String,
    val options: List<PollOption>,
    val endsAt: Instant,
    val createdBy: String,
    val closedAt: Instant? = null,
    val resultPublishedAt: Instant? = null,
)
data class PollTally(val counts: List<Int>, val total: Int)

interface PollStore {
    suspend fun create(poll: CommunityPoll)
    suspend fun active(): List<CommunityPoll>
    suspend fun poll(id: String): CommunityPoll?
    suspend fun attachMessage(id: String, messageId: String)
    /** One current vote per proved XUID. Re-voting replaces that player's choice. */
    suspend fun vote(id: String, xuid: String, option: Int, at: Instant): Boolean
    suspend fun tally(id: String): PollTally
    suspend fun close(id: String, at: Instant): CommunityPoll?
    suspend fun resultPublished(id: String, at: Instant)
}

/** Store des sondages et des votes uniques par XUID prouvé (migration V16). */
class JdbcPollStore(private val database: Database) : PollStore {
    override suspend fun create(poll: CommunityPoll) {
        database.transaction { c ->
            c.prepareStatement("insert into discord_polls (poll_id, channel_id, message_id, question, options_json, ends_at, created_by, created_at, closed_at, result_published_at) values (?, ?, ?, ?, ?, ?, ?, ?, null, null)").use {
                it.setString(1, poll.id); it.setString(2, poll.channelId); it.setString(3, poll.messageId); it.setString(4, poll.question)
                it.setString(5, json.encodeToString(poll.options)); it.setObject(6, poll.endsAt.sql()); it.setString(7, poll.createdBy); it.setObject(8, LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)); it.executeUpdate()
            }
        }
    }

    override suspend fun active(): List<CommunityPoll> = database.transaction { c ->
        c.prepareStatement("select * from discord_polls where result_published_at is null order by created_at asc").use { ps -> ps.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.poll()) } } }
    }
    override suspend fun poll(id: String): CommunityPoll? = database.transaction { c ->
        c.prepareStatement("select * from discord_polls where poll_id = ?").use { it.setString(1, id); it.executeQuery().use { rows -> if (rows.next()) rows.poll() else null } }
    }
    override suspend fun attachMessage(id: String, messageId: String) { database.transaction { c ->
        c.prepareStatement("update discord_polls set message_id = ? where poll_id = ?").use { it.setString(1, messageId); it.setString(2, id); it.executeUpdate() }
    } }
    override suspend fun vote(id: String, xuid: String, option: Int, at: Instant): Boolean {
        val player = Xuid.parse(xuid) ?: return false
        return database.transaction { c ->
        c.prepareStatement("select options_json, closed_at, ends_at from discord_polls where poll_id = ? for update").use { it.setString(1, id); it.executeQuery().use { rows ->
            if (!rows.next() || rows.getObject("closed_at") != null || !at.isBefore(rows.getObject("ends_at", LocalDateTime::class.java).toInstant(ZoneOffset.UTC))) return@transaction false
            val options = json.decodeFromString<List<PollOption>>(rows.getString("options_json"))
            if (option !in options.indices) return@transaction false
        } }
        c.prepareStatement("insert into discord_poll_votes (poll_id, player_xuid, option_index, voted_at) values (?, ?, ?, utc_timestamp(3)) on duplicate key update option_index = values(option_index), voted_at = values(voted_at)").use {
            it.setString(1, id); it.setXuid(2, player); it.setInt(3, option); it.executeUpdate(); true
        }
    }
    }
    override suspend fun tally(id: String): PollTally = database.transaction { c ->
        val count = c.prepareStatement("select options_json from discord_polls where poll_id = ?").use { it.setString(1, id); it.executeQuery().use { rows -> if (rows.next()) json.decodeFromString<List<PollOption>>(rows.getString(1)).size else 0 } }
        if (count == 0) return@transaction PollTally(emptyList(), 0)
        val values = MutableList(count) { 0 }
        c.prepareStatement("select option_index, count(*) n from discord_poll_votes where poll_id = ? group by option_index").use { it.setString(1, id); it.executeQuery().use { rows -> while (rows.next()) rows.getInt(1).takeIf { n -> n in values.indices }?.let { values[it] = rows.getInt(2) } } }
        PollTally(values, values.sum())
    }
    override suspend fun close(id: String, at: Instant): CommunityPoll? = database.transaction { c ->
        c.prepareStatement("update discord_polls set closed_at = ? where poll_id = ? and closed_at is null").use { it.setObject(1, at.sql()); it.setString(2, id); it.executeUpdate() }
        c.prepareStatement("select * from discord_polls where poll_id = ?").use { it.setString(1, id); it.executeQuery().use { rows -> if (rows.next()) rows.poll() else null } }
    }
    override suspend fun resultPublished(id: String, at: Instant) { database.transaction { c ->
        c.prepareStatement("update discord_polls set result_published_at = ? where poll_id = ? and closed_at is not null and result_published_at is null").use { it.setObject(1, at.sql()); it.setString(2, id); it.executeUpdate() }
    } }

    private fun java.sql.ResultSet.poll() = CommunityPoll(getString("poll_id"), getString("channel_id"), getString("message_id"), getString("question"), json.decodeFromString(getString("options_json")), getObject("ends_at", LocalDateTime::class.java).toInstant(ZoneOffset.UTC), getString("created_by"), getObject("closed_at", LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC), getObject("result_published_at", LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC))
    private fun Instant.sql() = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
    private companion object { val json = Json; }
}

object PollTexts {
    const val COMMAND = "sondage"
    const val KEY_PREFIX = "community-poll:"
    const val VOTE_PREFIX = "community-poll-vote:"
    const val UNVERIFIED = "Relie ton compte Minecraft avec /lier pour voter."
    const val CLOSED = "Ce sondage est terminé."
    const val UNAVAILABLE = "Le sondage est indisponible. Réessaie plus tard."
    fun card(poll: CommunityPoll, tally: PollTally? = null): BotMessage {
        val lines = poll.options.mapIndexed { i, option ->
            val count = tally?.counts?.getOrNull(i)
            val suffix = if (tally == null) "" else " — ${count ?: 0} voix"
            "${i + 1}. ${option.answer}$suffix\n   ${option.consequence}"
        }
        val ended = poll.closedAt != null
        val buttons = if (ended) emptyList() else poll.options.mapIndexed { i, _ -> ButtonSpec(VOTE_PREFIX + poll.id + ":" + i, "Voter " + (i + 1), ButtonStyle.PRIMARY) }
        val resultLine = if (ended) "\nRésultat : ${tally?.total ?: 0} voix." else "\nFin : ${poll.endsAt}."
        return BotMessage(poll.question + "\n\n" + lines.joinToString("\n") + resultLine, buttons = buttons, card = true)
    }
}

/** Sondages staff, voix Minecraft prouvées et résultats édités sur la carte initiale. */
class PollsFeature(
    private val context: BotContext,
    private val store: PollStore,
    private val links: GameDiscordLinks,
) : Feature {
    override val name = "sondages-communaute"
    override val commands = listOf(CommandSpec("sondage", "Ouvrir un sondage avec ses conséquences en jeu", listOf(
        CommandOption("question", "Question", true), CommandOption("duree-minutes", "Durée en minutes", true),
        CommandOption("consequence", "Ce que change chaque réponse", true), CommandOption("reponse1", "Réponse obligatoire 1", true),
        CommandOption("reponse2", "Réponse obligatoire 2", true), CommandOption("reponse3", "Réponse facultative 3"),
        CommandOption("reponse4", "Réponse facultative 4"), CommandOption("reponse5", "Réponse facultative 5"),
    ), staffOnly = true))
    private val timers = mutableMapOf<String, Scheduled>()

    override suspend fun start() {
        for (poll in store.active()) {
            if (poll.messageId == null) publish(poll) else if (poll.closedAt != null) publishResult(poll) else scheduleClose(poll)
        }
    }
    override suspend fun handle(event: DiscordEvent) {
        when (event) {
            is DiscordEvent.CommandUsed -> if (event.name == "sondage") create(event)
            is DiscordEvent.ButtonPressed -> if (event.id.startsWith(PollTexts.VOTE_PREFIX)) vote(event)
            else -> Unit
        }
    }
    private suspend fun create(event: DiscordEvent.CommandUsed) {
        val channel = context.ids.channel("sondages") ?: run { event.reply.send(BotMessage(PollTexts.UNAVAILABLE)); return }
        val answerText = (1..5).mapNotNull { n -> event.options["reponse$n"]?.trim()?.takeIf(String::isNotEmpty)?.let { it to event.options["consequence"].orEmpty().trim() } }
        val minutes = event.options["duree-minutes"]?.toLongOrNull()
        if (answerText.size !in 2..5 || answerText.any { it.first.length > 80 || it.second.isBlank() } || minutes == null || minutes !in 1..10080 || event.options["question"].orEmpty().isBlank() || event.options.getValue("question").length > 200 || event.options.getValue("consequence").length > 200) {
            event.reply.send(BotMessage("Il faut une question, deux à cinq réponses et une durée de 1 à 10 080 minutes.")); return
        }
        if (context.ids.role("staff") !in event.user.roleIds && context.ids.role("moderation") !in event.user.roleIds) {
            event.reply.send(BotMessage("Cette commande est réservée au staff.")); return
        }
        val poll = CommunityPoll(UUID.randomUUID().toString(), channel, null, event.options.getValue("question").trim(), answerText.map { PollOption(it.first, it.second) }, context.timer.now().plus(Duration.ofMinutes(minutes)), event.user.id)
        try { store.create(poll); publish(poll); event.reply.send(BotMessage("Le sondage est ouvert dans #sondages.")) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { event.reply.send(BotMessage(PollTexts.UNAVAILABLE)) }
    }
    private suspend fun publish(poll: CommunityPoll) {
        val channel = context.ids.channel("sondages") ?: poll.channelId
        val id = context.server.post(channel, PollTexts.card(poll))
        store.attachMessage(poll.id, id)
        scheduleClose(poll)
    }
    private fun scheduleClose(poll: CommunityPoll) {
        timers.remove(poll.id)?.cancel()
        val wait = if (context.timer.now().isBefore(poll.endsAt)) Duration.between(context.timer.now(), poll.endsAt) else Duration.ZERO
        timers[poll.id] = context.timer.after(wait) { close(poll.id) }
    }
    private suspend fun vote(event: DiscordEvent.ButtonPressed) {
        val bits = event.id.removePrefix(PollTexts.VOTE_PREFIX).split(':')
        val poll = bits.getOrNull(0)?.let { store.poll(it) }
        val option = bits.getOrNull(1)?.toIntOrNull()
        if (poll == null || option == null || poll.closedAt != null || !context.timer.now().isBefore(poll.endsAt)) { event.reply.send(BotMessage(PollTexts.CLOSED)); return }
        try {
            val link = links.findByDiscord(event.user.id)
            if (link == null) { event.reply.send(BotMessage(PollTexts.UNVERIFIED)); return }
            if (!store.vote(poll.id, link.xuid, option, context.timer.now())) { event.reply.send(BotMessage(PollTexts.CLOSED)); return }
            event.reply.send(BotMessage("Ton vote est enregistré."))
        } catch (e: CancellationException) { throw e } catch (_: Exception) { event.reply.send(BotMessage(PollTexts.UNAVAILABLE)) }
    }
    private suspend fun close(id: String) {
        val poll = store.close(id, context.timer.now()) ?: return
        publishResult(poll)
    }
    private suspend fun publishResult(poll: CommunityPoll) {
        val channel = poll.channelId; val messageId = poll.messageId ?: return
        val tally = store.tally(poll.id)
        val message = PollTexts.card(poll, tally)
        try {
            context.server.edit(channel, messageId, message)
        } catch (e: CancellationException) {
            throw e
        } catch (_: DiscordMessageMissing) {
            store.attachMessage(poll.id, context.server.post(channel, message))
        }
        store.resultPublished(poll.id, context.timer.now())
    }
}
