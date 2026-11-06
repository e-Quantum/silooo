package com.silooo.core.discord

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Rapport privé. Les formulations restent à valider par Kr. Aucun historique de message n'est relu. */
class ReportFeature(
    private val context: BotContext,
    private val rules: ReportRules,
    private val zone: ZoneId,
    private val channels: List<String>,
    private val store: ReportStore,
) : Feature {
    override val name = "rapport"
    private lateinit var captureFrom: Instant

    override suspend fun start() {
        captureFrom = store.beginCapture(context.timer.now())
        arm()
        val monday = latestMonday(context.timer.now())
        if (captureFrom.isBefore(at(monday))) publish(monday)
    }

    override suspend fun handle(event: DiscordEvent) {
        when (event) {
            is DiscordEvent.MemberJoined -> if (!event.user.isBot) store.joined(event.user.id, event.at, rules.returnedAfterDays)
            is DiscordEvent.MemberLeft -> store.left(event.userId, event.at)
            is DiscordEvent.OnboardingCompleted -> if (!event.user.isBot) store.onboardingCompleted(event.user.id, event.at)
            is DiscordEvent.MessagePosted -> {
                val tracked = channels.mapNotNull(context.ids::channel)
                if (!event.author.isBot && (event.channelId in tracked || event.parentChannelId in tracked)) {
                    store.message(event.messageId, event.author.id, event.channelId, event.at)
                }
            }
            else -> {}
        }
    }

    private fun arm() {
        val monday = latestMonday(context.timer.now()).plusWeeks(1)
        context.timer.after(Duration.between(context.timer.now(), at(monday))) {
            try { publish(monday) } finally { arm() }
        }
    }

    private suspend fun publish(monday: LocalDate) {
        val channel = context.ids.channel(ServerPlan.JOURNAL_ID) ?: return
        val until = at(monday)
        val from = maxOf(at(monday.minusWeeks(1)), captureFrom)
        val counts = store.counts(from, until, rules.returnedAfterDays, zone)
        val text = buildString {
            append("Rapport du lundi ").append(monday).append("\n")
            append("Période : ").append(from.atZone(zone)).append(" au ").append(until.atZone(zone)).append(" (fin exclue).\n")
            append("Événements reçus par le bot uniquement ; les interruptions de capture ne sont pas reconstituées.\n")
            append("Arrivées : ").append(counts.arrivals).append("\n")
            append("Accueils terminés : ").append(counts.onboardingCompleted).append("\n")
            append("Retours après un départ observé d'au moins ").append(rules.returnedAfterDays)
                .append(" jours : ").append(counts.returnsAfterThreshold).append("\n")
            append("Tutos refusés en message privé : ").append(counts.tutorialDmFailures).append("\n")
            append("Messages humains reçus dans les salons de discussion suivis (messages supprimés inclus) :\n")
            var day = from.atZone(zone).toLocalDate()
            while (!day.isAfter(until.atZone(zone).toLocalDate())) {
                append(day).append(" : ").append(counts.messagesByDay[day] ?: 0).append("\n")
                day = day.plusDays(1)
            }
            append("Comptes reliés sous ").append(rules.linkedWithinDays)
                .append(" jours : indisponible (date de liaison non mesurée).\n")
            append("Signalements : indisponible (événements non mesurés).\n")
            append("Provenance des arrivants : indisponible (source non mesurée).")
        }
        // Au plus une tentative : un refus ou un arrêt après réservation ne provoque pas de doublon.
        val week = monday.minusWeeks(1)
        if (!store.claim(week, context.timer.now())) return
        try {
            context.server.send(channel, text)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            store.finished(week, sent = false)
            org.slf4j.LoggerFactory.getLogger(ReportFeature::class.java)
                .warn("Rapport Discord {} : échec d'envoi, intervention requise, aucun renvoi automatique", week)
            throw e
        }
        store.finished(week, sent = true)
    }

    internal fun latestMonday(now: Instant): LocalDate {
        val monday = now.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return if (at(monday).isAfter(now)) monday.minusWeeks(1) else monday
    }

    private fun at(monday: LocalDate): Instant = monday.atTime(rules.hour, 0).atZone(zone).toInstant()
}
