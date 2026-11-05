package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Les mots du bot pour les giveaways (`docs/discord.md`, « Le serveur en direct »), **à valider par Kr**. Sans emoji,
 * sans mot-réclame (`design/README.md`, « Écrire sans slop »). Les réponses de la commande vont au staff seul ; la
 * carte et les réponses du bouton sont lues par les membres ; le journal du bot l'est par le staff seul. Un giveaway
 * est gratuit : aucun texte ne demande d'acheter, d'inviter ni de booster.
 */
object GiveawayTexts {
    const val COMMAND = "giveaway"
    const val COMMAND_DESCRIPTION = "Lance un giveaway dans le salon des giveaways"
    const val TITLE_OPTION = "titre"
    const val PRIZE_OPTION = "lot"
    const val PRIZE_DESCRIPTION = "Ce qui est offert : un cosmétique, des Gemmes ou des Lingots"
    const val AMOUNT_OPTION = "quantite"
    const val AMOUNT_DESCRIPTION = "Le nombre de Gemmes ou de Lingots (un entier positif ; ignoré pour un cosmétique)"
    const val COSMETIC_OPTION = "cosmetique"
    const val DURATION_OPTION = "duree"
    const val DURATION_DESCRIPTION = "La durée, en jours"
    const val MILESTONE_OPTION = "etape"
    const val MILESTONE_DESCRIPTION = "Un giveaway de grande étape, hors du compte du mois"
    const val MILESTONE_YES = "oui"

    /** Le titre et l'identifiant du cosmétique, en caractères : les colonnes de `discord_giveaways` en prennent 80. */
    const val TITLE_MAX = 80
    const val COSMETIC_MAX = 80

    /** Les durées proposées, en jours. */
    val DURATIONS = listOf(1, 3, 7)

    const val ENTER_PREFIX = "giveaway-participer:"
    const val ENTER_LABEL = "Participer"

    const val NOT_STAFF = "Cette commande est pour le staff."
    const val NO_CHANNEL = "Le salon des giveaways n'existe pas sur le serveur."
    const val EMPTY_TITLE = "Le titre est vide : rien n'est lancé."
    const val BAD_PRIZE = "Le lot doit être cosmetique, gemmes ou lingots. Rien n'est lancé."
    const val BAD_AMOUNT = "La quantité doit être un entier positif : le nombre de Gemmes ou de Lingots. Rien n'est lancé."
    const val NO_COSMETIC = "Un cosmétique demande son identifiant dans l'option cosmetique. Rien n'est lancé."
    const val BAD_DURATION = "La durée doit être de 1, 3 ou 7 jours. Rien n'est lancé."
    const val PUBLISH_FAILED = "Discord a refusé la carte : le giveaway n'est pas lancé. Relance la commande."

    const val FINISHED = "Ce giveaway est fini."
    const val ALREADY_ENTERED = "Tu participes déjà."
    const val NO_BOTS = "Les bots ne participent pas."
    const val NO_ONE = "Personne n'a participé : pas de tirage."

    /** Sous le lot de chaque carte : ce que le règlement publié sur le site dit en une ligne. */
    const val RULES = "Gratuit, sans achat, sans invitation ni boost. Un compte relié reçoit le lot en jeu ; sinon il attend la liaison."

    val TITLE_DESCRIPTION = "Le titre du giveaway, " + TITLE_MAX + " caractères au plus"
    val COSMETIC_DESCRIPTION = "L'identifiant du cosmétique offert, " + COSMETIC_MAX + " caractères au plus"

    fun titleTooLong(length: Int): String =
        "Le titre fait " + thousands(length.toLong()) + " caractères : " + TITLE_MAX + " au plus. Rien n'est lancé."

    fun cosmeticTooLong(length: Int): String =
        "L'identifiant du cosmétique fait " + thousands(length.toLong()) + " caractères : " + COSMETIC_MAX + " au plus. Rien n'est lancé."

    /** [nextMonth] : le mois suivant en lettres, « décembre ». */
    fun monthlyLimit(perMonth: Int, nextMonth: String): String =
        (if (perMonth == 1) "Un giveaway" else thousands(perMonth.toLong()) + " giveaways") + " par mois : le prochain à partir du 1er " +
            nextMonth + ". Pour une grande étape, ajoute l'option etape."

    fun launched(id: Int): String = "Giveaway n°" + id + " lancé."

    fun journalLaunched(id: Int, title: String, staffId: String, endsAt: Instant, zone: ZoneId): String =
        "Giveaway n°" + id + " « " + title + " » lancé par " + mention(staffId) + ", fin " + AdmissionTexts.moment(endsAt, zone) + "."

    /** Le lot en toutes lettres : « 500 Gemmes », « 2 000 Lingots », « le cosmétique nom ». */
    fun prize(kind: String, amount: Long, label: String?): String =
        when (kind) {
            GiveawayPrize.GEMS -> thousands(amount) + (if (amount > 1) " Gemmes" else " Gemme")
            GiveawayPrize.INGOTS -> thousands(amount) + (if (amount > 1) " Lingots" else " Lingot")
            else -> "le cosmétique " + label.orEmpty()
        }

    /** « 0 participant », « 1 participant », « 37 participants » : zéro et un restent au singulier. */
    fun participants(count: Int): String = thousands(count.toLong()) + (if (count > 1) " participants" else " participant")

    /** La carte d'un giveaway ouvert : le rôle (seul prévenu) s'il y en a un, le titre, le lot, la fin, la règle, le compte. */
    fun card(giveaway: Giveaway, roleId: String?, count: Int, zone: ZoneId): String =
        (if (roleId == null) "" else "<@&" + roleId + "> ") + head(giveaway, zone) + "\n\n" + RULES + "\n\n" + participants(count)

    /** La carte d'un giveaway tiré : plus de rôle ni de bouton, et [ending] : le gagnant ou l'absence de participants. */
    fun closedCard(giveaway: Giveaway, ending: String, zone: ZoneId): String = head(giveaway, zone) + "\n\n" + ending

    fun winnerLine(winnerId: String): String = "Gagné par " + mention(winnerId) + "."

    fun entered(endsAt: Instant, zone: ZoneId): String = "Tu participes. Tirage " + AdmissionTexts.moment(endsAt, zone) + "."

    /** Le message privé du gagnant. */
    fun winnerDirect(title: String, prize: String): String =
        "Tu as gagné le giveaway « " + title + " » : " + prize +
            ". Le lot arrive en jeu sur ton compte relié ; sinon, relie ton compte avec /lier et il suivra."

    /** Les lignes du journal du bot pour un tirage : le résultat, puis les participants en mentions (qui ne préviennent personne). */
    fun journalDrawn(giveaway: Giveaway, entries: List<String>, winnerId: String?): List<String> {
        val head = "Giveaway n°" + giveaway.id + " « " + giveaway.title + " » : " + participants(entries.size)
        if (winnerId == null) return listOf(head + ", pas de tirage.")
        return listOf(head + ", gagné par " + mention(winnerId) + ".") + participantMessages(entries)
    }

    /** Les mentions de [userIds] séparées d'une espace, en messages de [BotMessage.MAX_TEXT] caractères au plus. */
    fun participantMessages(userIds: List<String>): List<String> {
        val messages = mutableListOf<String>()
        val current = StringBuilder()
        for (userId in userIds) {
            val mention = mention(userId)
            if (current.isNotEmpty() && current.length + 1 + mention.length > BotMessage.MAX_TEXT) {
                messages += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(mention)
        }
        if (current.isNotEmpty()) messages += current.toString()
        return messages
    }

    /** Le titre, le lot et la fin : le haut de la carte, ouverte ou tirée. */
    private fun head(giveaway: Giveaway, zone: ZoneId): String =
        "Giveaway : " + giveaway.title + "\n\nLot : " + prize(giveaway.prizeKind, giveaway.prizeAmount, giveaway.prizeLabel) +
            "\nFin " + AdmissionTexts.moment(giveaway.endsAt, zone) + "."

    private fun mention(userId: String): String = "<@" + userId + ">"

    private fun thousands(number: Long): String = number.toString().reversed().chunked(3).joinToString(" ").reversed()
}

/**
 * Les giveaways (`docs/discord.md`, « Le serveur en direct »). Gratuits : on participe d'un bouton, sans achat,
 * invitation ni boost. `/giveaway` (staff) lance une carte dans `#giveaways` avec le rôle Giveaways pour seule
 * mention ; sans l'option etape, le compte du mois ([GiveawayRules.perMonth]) borne les lancements. À la fin, le bot
 * tire un gagnant au hasard parmi les inscrits, écrit le lot sur son identifiant Discord (`discord_prizes`, crédité
 * en jeu à la liaison par le contrat du jeu, pas ici), modifie la carte, écrit au gagnant en privé et met le tirage
 * et les participants dans `#journal-du-bot`.
 *
 * Le tirage est en base avant les messages : si Discord refuse un message, le bot reprend une minute plus tard là où
 * il s'est arrêté, sans tirer deux fois et sans doubler un message déjà parti. Ces étapes restantes vivent en mémoire :
 * un redémarrage entre le tirage et les messages garde le gagnant et le lot, pas la fin des messages. Au démarrage,
 * chaque giveaway ouvert reprend sa minuterie, et un giveaway échu est tiré tout de suite.
 */
class GiveawayFeature(
    private val context: BotContext,
    private val rules: GiveawayRules,
    private val zone: ZoneId,
    private val store: GiveawayStore,
    private val journal: BotJournal,
    private val random: Random = Random.Default,
) : Feature {
    override val name = "giveaways"

    override val commands = listOf(
        CommandSpec(
            GiveawayTexts.COMMAND,
            GiveawayTexts.COMMAND_DESCRIPTION,
            options = listOf(
                CommandOption(GiveawayTexts.TITLE_OPTION, GiveawayTexts.TITLE_DESCRIPTION, required = true),
                CommandOption(GiveawayTexts.PRIZE_OPTION, GiveawayTexts.PRIZE_DESCRIPTION, required = true, choices = GiveawayPrize.ALL),
                CommandOption(GiveawayTexts.AMOUNT_OPTION, GiveawayTexts.AMOUNT_DESCRIPTION),
                CommandOption(GiveawayTexts.COSMETIC_OPTION, GiveawayTexts.COSMETIC_DESCRIPTION),
                CommandOption(
                    GiveawayTexts.DURATION_OPTION,
                    GiveawayTexts.DURATION_DESCRIPTION,
                    required = true,
                    choices = GiveawayTexts.DURATIONS.map { it.toString() },
                ),
                CommandOption(GiveawayTexts.MILESTONE_OPTION, GiveawayTexts.MILESTONE_DESCRIPTION, choices = listOf(GiveawayTexts.MILESTONE_YES)),
            ),
            staffOnly = true,
        ),
    )

    /** Ce que le staff a demandé, une fois les options vérifiées. */
    private class Order(
        val title: String,
        val kind: String,
        val amount: Long,
        val label: String?,
        val days: Int,
        val milestone: Boolean,
    )

    private sealed interface Parsed {
        class Valid(val order: Order) : Parsed

        class Refused(val text: String) : Parsed
    }

    /** Un tirage commencé : le gagnant est en base, et ce qui reste à faire se lit dans les drapeaux. */
    private class Draw(val giveaway: Giveaway, val entries: List<String>, val winner: String?, val at: Instant) {
        val journalLines = GiveawayTexts.journalDrawn(giveaway, entries, winner)
        var prized = false
        var carded = false
        var told = false
        var journaled = 0
    }

    /** Un seul lancement à la fois (le compte du mois se lit puis s'écrit), et un seul tirage à la fois. */
    private val launchGate = Mutex()
    private val drawGate = Mutex()

    /** Les tirages dont les messages ne sont pas tous partis, par numéro de giveaway. */
    private val finishing = mutableMapOf<Int, Draw>()

    /** Les tentatives ratées de chaque tirage en cours, pour ne pas insister sans fin. */
    private val failures = mutableMapOf<Int, Int>()

    override suspend fun start() {
        for (giveaway in store.open()) scheduleDraw(giveaway)
    }

    override suspend fun handle(event: DiscordEvent) {
        when {
            event is DiscordEvent.CommandUsed && event.name == GiveawayTexts.COMMAND -> launch(event)
            event is DiscordEvent.ButtonPressed && event.id.startsWith(GiveawayTexts.ENTER_PREFIX) -> enter(event)
        }
    }

    private suspend fun launch(event: DiscordEvent.CommandUsed) {
        val staff = context.ids.role(STAFF)
        if (staff != null && staff !in event.user.roleIds) return refuse(event, GiveawayTexts.NOT_STAFF)
        val order = when (val parsed = parse(event.options)) {
            is Parsed.Refused -> return refuse(event, parsed.text)
            is Parsed.Valid -> parsed.order
        }
        val channel = context.ids.channel(GIVEAWAYS) ?: return refuse(event, GiveawayTexts.NO_CHANNEL)

        launchGate.withLock {
            val now = context.timer.now()
            if (!order.milestone) {
                val firstOfMonth = now.atZone(zone).toLocalDate().withDayOfMonth(1)
                val nextMonth = firstOfMonth.plusMonths(1)
                val launched = store.countCreatedBetween(firstOfMonth.atStartOfDay(zone).toInstant(), nextMonth.atStartOfDay(zone).toInstant())
                if (launched >= rules.perMonth) {
                    val month = nextMonth.month.getDisplayName(TextStyle.FULL, Locale.FRENCH)
                    return refuse(event, GiveawayTexts.monthlyLimit(rules.perMonth, month))
                }
            }
            val endsAt = now.atZone(zone).plusDays(order.days.toLong()).toInstant()
            val id = store.create(order.title, order.kind, order.amount, order.label, channel, endsAt, event.user.id, now)
            val created = Giveaway(id, order.title, order.kind, order.amount, order.label, channel, null, endsAt, null, null, event.user.id, now)
            val messageId = try {
                context.server.post(channel, card(created, 0, ping = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Sans carte, personne ne peut participer : le giveaway est clos, et il ne compte pas dans le mois.
                store.drawn(id, null, now)
                event.reply.send(BotMessage(GiveawayTexts.PUBLISH_FAILED), onlyToUser = true)
                throw e
            }
            store.carded(id, messageId)
            scheduleDraw(created.copy(messageId = messageId))
            event.reply.send(BotMessage(GiveawayTexts.launched(id)), onlyToUser = true)
            journal.line(GiveawayTexts.journalLaunched(id, order.title, event.user.id, endsAt, zone))
        }
    }

    private fun parse(options: Map<String, String>): Parsed {
        val title = options[GiveawayTexts.TITLE_OPTION].orEmpty().trim()
        if (title.isEmpty()) return Parsed.Refused(GiveawayTexts.EMPTY_TITLE)
        if (title.length > GiveawayTexts.TITLE_MAX) return Parsed.Refused(GiveawayTexts.titleTooLong(title.length))
        val kind = options[GiveawayTexts.PRIZE_OPTION].orEmpty().trim()
        if (kind !in GiveawayPrize.ALL) return Parsed.Refused(GiveawayTexts.BAD_PRIZE)
        var amount = 0L
        var label: String? = null
        if (kind == GiveawayPrize.COSMETIC) {
            val cosmetic = options[GiveawayTexts.COSMETIC_OPTION].orEmpty().trim()
            if (cosmetic.isEmpty()) return Parsed.Refused(GiveawayTexts.NO_COSMETIC)
            if (cosmetic.length > GiveawayTexts.COSMETIC_MAX) return Parsed.Refused(GiveawayTexts.cosmeticTooLong(cosmetic.length))
            label = cosmetic
        } else {
            amount = options[GiveawayTexts.AMOUNT_OPTION].orEmpty().filterNot { it.isWhitespace() }.toLongOrNull()?.takeIf { it > 0 }
                ?: return Parsed.Refused(GiveawayTexts.BAD_AMOUNT)
        }
        val days = options[GiveawayTexts.DURATION_OPTION]?.trim()?.toIntOrNull()?.takeIf { it in GiveawayTexts.DURATIONS }
            ?: return Parsed.Refused(GiveawayTexts.BAD_DURATION)
        val milestone = options[GiveawayTexts.MILESTONE_OPTION]?.trim() == GiveawayTexts.MILESTONE_YES
        return Parsed.Valid(Order(title, kind, amount, label, days, milestone))
    }

    private suspend fun enter(event: DiscordEvent.ButtonPressed) {
        if (event.user.isBot) return tell(event, GiveawayTexts.NO_BOTS)
        val id = event.id.removePrefix(GiveawayTexts.ENTER_PREFIX).toIntOrNull() ?: return tell(event, GiveawayTexts.FINISHED)
        val giveaway = store.open().firstOrNull { it.id == id && it.endsAt.isAfter(context.timer.now()) }
            ?: return tell(event, GiveawayTexts.FINISHED)
        if (!store.enter(id, event.user.id)) return tell(event, GiveawayTexts.ALREADY_ENTERED)
        event.reply.send(BotMessage(GiveawayTexts.entered(giveaway.endsAt, zone)), onlyToUser = true)
        val messageId = giveaway.messageId ?: return
        // L'inscription est faite : un refus de Discord sur le compte de la carte ne la défait pas, la carte rattrape au suivant.
        try {
            context.server.edit(giveaway.channelId, messageId, card(giveaway, store.entries(id).size, ping = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Bot Discord, giveaways : {}", e.toString())
        }
    }

    /** Programme le tirage de [giveaway] à sa fin, tout de suite si elle est passée. */
    private fun scheduleDraw(giveaway: Giveaway) {
        val wait = Duration.between(context.timer.now(), giveaway.endsAt)
        context.timer.after(if (wait.isNegative) Duration.ZERO else wait) { settle(giveaway.id) }
    }

    /** Tire le giveaway [id] ou reprend ses messages ; un échec programme une nouvelle tentative, [MAX_ATTEMPTS] au plus. */
    private suspend fun settle(id: Int) {
        drawGate.withLock {
            try {
                val draw = finishing[id] ?: begin(id) ?: return@withLock
                finish(draw)
                finishing.remove(id)
                failures.remove(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Bot Discord, giveaways : {}", e.toString())
                retry(id)
            }
        }
    }

    /** Un tirage commence : les inscrits, le hasard, puis le gagnant en base avant tout message ; `null` : déjà tiré. */
    private suspend fun begin(id: Int): Draw? {
        val giveaway = store.open().firstOrNull { it.id == id } ?: return null
        val entries = store.entries(id)
        val winner = if (entries.isEmpty()) null else entries[random.nextInt(entries.size)]
        val at = context.timer.now()
        store.drawn(id, winner, at)
        return Draw(giveaway, entries, winner, at).also { finishing[id] = it }
    }

    /** Les étapes une à une, chacune notée quand elle est faite : une reprise ne les refait pas. */
    private suspend fun finish(draw: Draw) {
        val giveaway = draw.giveaway
        val winner = draw.winner
        if (!draw.prized) {
            if (winner != null) store.addPrize(winner, giveaway.id, giveaway.prizeKind, giveaway.prizeAmount, giveaway.prizeLabel, draw.at)
            draw.prized = true
        }
        if (!draw.carded) {
            val messageId = giveaway.messageId
            if (messageId != null) {
                val ending = if (winner == null) GiveawayTexts.NO_ONE else GiveawayTexts.winnerLine(winner)
                val message = BotMessage(GiveawayTexts.closedCard(giveaway, ending, zone), mentionUserIds = setOfNotNull(winner))
                context.server.edit(giveaway.channelId, messageId, message)
            }
            draw.carded = true
        }
        if (!draw.told) {
            if (winner != null) {
                val prize = GiveawayTexts.prize(giveaway.prizeKind, giveaway.prizeAmount, giveaway.prizeLabel)
                // Messages privés fermés : le bot n'insiste pas, le lot attend en base.
                context.server.direct(winner, BotMessage(GiveawayTexts.winnerDirect(giveaway.title, prize)))
            }
            draw.told = true
        }
        while (draw.journaled < draw.journalLines.size) {
            journal.line(draw.journalLines[draw.journaled])
            draw.journaled++
        }
    }

    private fun retry(id: Int) {
        val failed = (failures[id] ?: 0) + 1
        if (failed >= MAX_ATTEMPTS) {
            log.warn("Bot Discord, giveaways : le giveaway {} est abandonné après {} tentatives", id, failed)
            finishing.remove(id)
            failures.remove(id)
            return
        }
        failures[id] = failed
        context.timer.after(RETRY) { settle(id) }
    }

    /** La carte d'un giveaway ouvert avec son bouton ; [ping] : seul le premier envoi prévient le rôle, une modification jamais. */
    private fun card(giveaway: Giveaway, count: Int, ping: Boolean): BotMessage {
        val role = context.ids.role(GIVEAWAYS)
        return BotMessage(
            GiveawayTexts.card(giveaway, role, count, zone),
            buttons = listOf(ButtonSpec(GiveawayTexts.ENTER_PREFIX + giveaway.id, GiveawayTexts.ENTER_LABEL, ButtonStyle.PRIMARY)),
            mentionRoleIds = if (ping) setOfNotNull(role) else emptySet(),
        )
    }

    private suspend fun refuse(event: DiscordEvent.CommandUsed, text: String) {
        event.reply.send(BotMessage(text), onlyToUser = true)
    }

    private suspend fun tell(event: DiscordEvent.ButtonPressed, text: String) {
        event.reply.send(BotMessage(text), onlyToUser = true)
    }

    private companion object {
        const val GIVEAWAYS = "giveaways"
        const val STAFF = "staff"

        /** Un délai d'interface, pas un chiffre du jeu : Discord a refusé, on reprend une minute plus tard. */
        val RETRY: Duration = Duration.ofMinutes(1)

        /** Une demi-heure de refus, puis le bot lâche les messages : le gagnant et le lot sont déjà en base. */
        const val MAX_ATTEMPTS = 30

        val log = LoggerFactory.getLogger(GiveawayFeature::class.java)
    }
}
