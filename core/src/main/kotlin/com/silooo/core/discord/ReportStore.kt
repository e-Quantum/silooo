package com.silooo.core.discord

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Une photo des mesures disponibles pour un rapport hebdomadaire. Aucune ligne ne contient le texte d'un message. */
data class ReportCounts(
    val arrivals: Long,
    val onboardingCompleted: Long,
    val returnsAfterThreshold: Long,
    val tutorialDmFailures: Long,
    val messagesByDay: Map<LocalDate, Long>,
)

/** L'historique minimal nécessaire au rapport du lundi, avec des clés d'événements pour absorber les rejeux. */
interface ReportStore {
    /** Première mise en service mesurée ; après redémarrage, la première date ne bouge pas. */
    suspend fun beginCapture(at: Instant): Instant

    /** Une arrivée Discord, et le cas échéant un retour après [returnAfterDays]. */
    suspend fun joined(userId: String, at: Instant, returnAfterDays: Int)

    suspend fun left(userId: String, at: Instant)

    suspend fun onboardingCompleted(userId: String, at: Instant)

    suspend fun tutorialDmFailed(userId: String, at: Instant)

    /** Un message humain dans un salon suivi ; seul son identifiant, son auteur et son heure sont gardés. */
    suspend fun message(messageId: String, userId: String, channelId: String, at: Instant)

    suspend fun counts(
        activityFrom: Instant,
        activityUntil: Instant,
        returnAfterDays: Int,
        zone: ZoneId,
    ): ReportCounts

    /** Premières liaisons prouvées de la cohorte arrivée dans la période, observées dans le délai demandé. */
    suspend fun linkedWithin(activityFrom: Instant, activityUntil: Instant, days: Int): Long? = null

    /** Réserve une semaine une seule fois avant tout envoi externe : doublons et redémarrages ne repostent pas. */
    suspend fun claim(weekStart: LocalDate, at: Instant): Boolean

    /** État durable de la tentative ; un échec reste visible sans renvoi automatique. */
    suspend fun finished(weekStart: LocalDate, sent: Boolean)
}
