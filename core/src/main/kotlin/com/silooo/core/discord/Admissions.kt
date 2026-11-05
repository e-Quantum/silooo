package com.silooo.core.discord

import com.silooo.core.db.Database
import com.silooo.core.registration.SiteRegistrationStore
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Les mots du bot pour les admissions (`docs/discord.md`, « Le staff »), **à valider par Kr**. Sans emoji, sans
 * mot-réclame (`design/README.md`, « Écrire sans slop »). Les cartes et le journal sont lus par le staff seul ;
 * l'invitation est le seul texte que l'inscrit lit. Les mentions sont des identifiants Discord, jamais des pings.
 */
object AdmissionTexts {
    const val ADMIT_PREFIX = "admission-admettre:"
    const val REFUSE_PREFIX = "admission-refuser:"
    const val LATER_PREFIX = "admission-plus-tard:"
    const val ADMIT_LABEL = "Admettre"
    const val REFUSE_LABEL = "Refuser"
    const val LATER_LABEL = "Plus tard"

    const val NOT_STAFF = "Ces boutons sont pour le staff."
    const val GONE = "Cette inscription n'est plus là."
    const val ALREADY_DONE = "Cette inscription est déjà traitée."

    /** Le message privé de l'inscrit admis. */
    const val INVITATION =
        "Tu es admis à la bêta de Silooo. Elle n'a pas encore ouvert : tu seras prévenu ici le jour venu, avec l'adresse et le port."

    /** Les notes du journal quand l'invitation ne part pas, avec la raison. */
    const val NOT_LINKED = "Sans liaison Discord : le rôle et l'invitation attendent la liaison."
    const val NO_ROLE = "Le rôle Bêta manque sur le serveur : l'invitation n'est pas partie."
    const val DIRECT_CLOSED = "Message privé fermé : l'invitation n'est pas partie."
    const val DISCORD_REFUSED = "Discord a refusé le rôle ou le message privé : l'invitation n'est pas partie."

    private val MONTHS = listOf(
        "janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre",
    )

    /** « le 5 novembre 2026 à 18 h 10 », dans le fuseau [zone] ; « le 1er » le premier du mois. */
    fun moment(at: Instant, zone: ZoneId): String {
        val time = at.atZone(zone)
        val day = if (time.dayOfMonth == 1) "1er" else time.dayOfMonth.toString()
        return "le " + day + " " + MONTHS[time.monthValue - 1] + " " + time.year + " à " + time.hour + " h " + time.minute.toString().padStart(2, '0')
    }

    /** La carte d'une inscription : qui, quand, et si le compte Discord est relié. */
    fun card(registration: Registration, zone: ZoneId): String =
        "Inscription de " + registration.gamertag + ", " + moment(registration.createdAt, zone) + ".\n" +
            (if (registration.discordUserId == null) "Discord : pas encore relié." else "Discord : relié.")

    fun admittedBy(staffId: String, at: Instant, zone: ZoneId): String = "Admis par " + mention(staffId) + " " + moment(at, zone) + "."

    fun refusedBy(staffId: String, at: Instant, zone: ZoneId): String = "Refusé par " + mention(staffId) + " " + moment(at, zone) + "."

    fun laterBy(staffId: String, at: Instant, zone: ZoneId): String =
        "Remis à plus tard par " + mention(staffId) + " " + moment(at, zone) + "."

    fun journalAdmitted(gamertag: String, staffId: String): String = "Admission : " + gamertag + " admis par " + mention(staffId) + "."

    fun journalRefused(gamertag: String, staffId: String): String = "Admission : " + gamertag + " refusé par " + mention(staffId) + "."

    fun journalLater(gamertag: String, staffId: String): String =
        "Admission : " + gamertag + " remis à plus tard par " + mention(staffId) + "."

    private fun mention(userId: String): String = "<@" + userId + ">"
}

/**
 * Les admissions (`docs/discord.md`, « Le staff »). Toutes les [AdmissionRules.refreshSeconds] secondes, le bot lit
 * les inscriptions de la page d'attente qui n'ont pas de ligne dans `discord_admissions` et poste une carte par
 * inscription dans `#admissions`, avec Admettre, Refuser et Plus tard. C'est la base qui dit qu'une carte est
 * postée : un redémarrage ne double rien. Les boutons vérifient le rôle Staff de celui qui clique.
 *
 * Admettre pose `admitted_at` sur l'inscription (le jeu laisse alors entrer le joueur), donne le rôle Bêta et écrit
 * l'invitation en privé. **Sans liaison Discord prouvée** (`discord_user_id` nul), l'inscrit attend : l'admission est
 * posée, mais le rôle et le message ne partent pas. Ils viendront à la liaison (D3, `/lier`), qui lira
 * `admitted_at` et donnera alors le rôle et écrira l'invitation. Refuser et Plus tard ne touchent pas à
 * l'inscription : Plus tard laisse les deux autres boutons. Chaque geste va dans `#journal-du-bot`.
 */
class AdmissionFeature(
    private val context: BotContext,
    private val rules: AdmissionRules,
    private val zone: ZoneId,
    private val store: AdmissionStore,
    private val registrations: SiteRegistrationStore,
    private val database: Database,
    private val journal: BotJournal,
) : Feature {
    override val name = "admissions"

    override suspend fun start() {
        if (channel() == null) return
        context.timer.every(Duration.ofSeconds(rules.refreshSeconds.toLong())) { postNewCards() }
    }

    override suspend fun handle(event: DiscordEvent) {
        if (event is DiscordEvent.ButtonPressed) button(event)
    }

    /** Une carte par inscription sans ligne ; la ligne s'écrit tout de suite après la carte. */
    private suspend fun postNewCards() {
        val channel = channel() ?: return
        for (registration in store.newRegistrations(BATCH)) {
            val message = BotMessage(
                AdmissionTexts.card(registration, zone),
                buttons = listOf(admitButton(registration.key), refuseButton(registration.key), laterButton(registration.key)),
                card = true,
            )
            val messageId = context.server.post(channel, message)
            store.carded(registration.key, messageId, context.timer.now())
        }
    }

    private suspend fun button(event: DiscordEvent.ButtonPressed) {
        val action = when {
            event.id.startsWith(AdmissionTexts.ADMIT_PREFIX) -> Action.ADMIT
            event.id.startsWith(AdmissionTexts.REFUSE_PREFIX) -> Action.REFUSE
            event.id.startsWith(AdmissionTexts.LATER_PREFIX) -> Action.LATER
            else -> return
        }
        val staff = context.ids.role(STAFF) ?: return tell(event, STAFF_ROLE_UNAVAILABLE)
        if (staff !in event.user.roleIds) return tell(event, AdmissionTexts.NOT_STAFF)
        val key = event.id.removePrefix(action.prefix)
        val admission = store.admission(key)
        val registration = store.registration(key)
        if (admission == null || registration == null) return tell(event, AdmissionTexts.GONE)
        if (admission.decision == AdmissionDecision.ADMITTED || admission.decision == AdmissionDecision.REFUSED) {
            return tell(event, AdmissionTexts.ALREADY_DONE)
        }
        val now = context.timer.now()
        val by = event.user.id
        when (action) {
            Action.ADMIT -> admit(event, registration, now)
            Action.REFUSE -> {
                store.decide(key, AdmissionDecision.REFUSED, by, now)
                event.reply.edit(BotMessage(AdmissionTexts.card(registration, zone) + "\n" + AdmissionTexts.refusedBy(by, now, zone), card = true))
                journal.line(AdmissionTexts.journalRefused(registration.gamertag, by))
            }
            Action.LATER -> {
                store.decide(key, AdmissionDecision.LATER, by, now)
                val kept = listOf(admitButton(key), refuseButton(key))
                event.reply.edit(BotMessage(AdmissionTexts.card(registration, zone) + "\n" + AdmissionTexts.laterBy(by, now, zone), buttons = kept, card = true))
                journal.line(AdmissionTexts.journalLater(registration.gamertag, by))
            }
        }
    }

    /**
     * L'admission est posée et notée d'abord, la carte change tout de suite (Discord attend la réponse trois secondes),
     * puis le rôle et le message privé suivent, quand l'inscrit a relié son Discord.
     */
    private suspend fun admit(event: DiscordEvent.ButtonPressed, registration: Registration, now: Instant) {
        val by = event.user.id
        database.transaction { connection -> registrations.admit(connection, registration.key) }
        store.decide(registration.key, AdmissionDecision.ADMITTED, by, now)
        event.reply.edit(BotMessage(AdmissionTexts.card(registration, zone) + "\n" + AdmissionTexts.admittedBy(by, now, zone), card = true))
        val discordUserId = registration.discordUserId
        val betaRole = context.ids.role(BETA)
        val note = when {
            discordUserId == null -> AdmissionTexts.NOT_LINKED
            betaRole == null -> AdmissionTexts.NO_ROLE
            else -> invite(discordUserId, betaRole)
        }
        val line = AdmissionTexts.journalAdmitted(registration.gamertag, by)
        journal.line(if (note == null) line else line + " " + note)
    }

    /** Donne le rôle Bêta puis écrit l'invitation ; rend la note du journal quand elle n'est pas partie, `null` sinon. */
    private suspend fun invite(discordUserId: String, betaRole: String): String? =
        try {
            context.server.giveRole(discordUserId, betaRole)
            if (context.server.direct(discordUserId, BotMessage(AdmissionTexts.INVITATION))) null else AdmissionTexts.DIRECT_CLOSED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Bot Discord, admissions : {}", e.toString())
            AdmissionTexts.DISCORD_REFUSED
        }

    private suspend fun tell(event: DiscordEvent.ButtonPressed, text: String) {
        event.reply.send(BotMessage(text), onlyToUser = true)
    }

    private fun admitButton(key: String) = ButtonSpec(AdmissionTexts.ADMIT_PREFIX + key, AdmissionTexts.ADMIT_LABEL, ButtonStyle.PRIMARY)

    private fun refuseButton(key: String) = ButtonSpec(AdmissionTexts.REFUSE_PREFIX + key, AdmissionTexts.REFUSE_LABEL, ButtonStyle.DANGER)

    private fun laterButton(key: String) = ButtonSpec(AdmissionTexts.LATER_PREFIX + key, AdmissionTexts.LATER_LABEL)

    private fun channel(): String? = context.ids.channel(ADMISSIONS)

    private enum class Action(val prefix: String) {
        ADMIT(AdmissionTexts.ADMIT_PREFIX),
        REFUSE(AdmissionTexts.REFUSE_PREFIX),
        LATER(AdmissionTexts.LATER_PREFIX),
    }

    private companion object {
        const val ADMISSIONS = "admissions"
        const val STAFF = "staff"
        const val BETA = "beta"

        /** Les cartes postées par tic : un afflux se vide en quelques minutes, sans noyer le salon ni le débit de Discord. */
        const val BATCH = 20

        val log = LoggerFactory.getLogger(AdmissionFeature::class.java)
    }
}
