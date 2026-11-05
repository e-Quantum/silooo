package com.silooo.core.site

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

private val MONTHS = listOf(
    "janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre",
)

/** `1er novembre 2026`, `5 novembre 2026` : une date écrite à la française, sans dépendre de la langue de la machine. */
internal fun frenchDate(date: LocalDate): String = "${frenchDay(date)} ${MONTHS[date.monthValue - 1]} ${date.year}"

private fun frenchDay(date: LocalDate): String = if (date.dayOfMonth == 1) "1er" else date.dayOfMonth.toString()

/**
 * La semaine ISO d'un instant, en UTC : du lundi 00:00 (inclus) au lundi suivant 00:00 (exclu), c'est-à-dire jusqu'au dimanche
 * 23:59:59. Les dates de la base sont en UTC, la semaine du site aussi.
 */
internal class SiteWeek private constructor(val monday: LocalDate) {
    val start: Instant = monday.atStartOfDay(ZoneOffset.UTC).toInstant()

    /** Le lundi suivant à minuit : la borne exclue de la semaine. */
    val end: Instant = monday.plusWeeks(1).atStartOfDay(ZoneOffset.UTC).toInstant()

    val sunday: LocalDate = monday.plusDays(6)

    /** `Semaine du 2 au 8 novembre 2026`, `Semaine du 30 novembre au 6 décembre 2026`, `Semaine du 28 décembre 2026 au 3 janvier 2027`. */
    fun title(): String {
        val first = when {
            monday.year != sunday.year -> frenchDate(monday)
            monday.month != sunday.month -> "${frenchDay(monday)} ${MONTHS[monday.monthValue - 1]}"
            else -> frenchDay(monday)
        }
        return "Semaine du $first au ${frenchDate(sunday)}"
    }

    companion object {
        fun of(instant: Instant): SiteWeek =
            SiteWeek(instant.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
    }
}
