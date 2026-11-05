package com.silooo.core.discord

import java.time.Instant

/**
 * [AdmissionStore] en mémoire, avec les mêmes règles que le SQL : [registrations] tient la place de `site_registrations`,
 * une inscription est nouvelle tant qu'elle n'a pas de ligne dans [admissions], les plus anciennes d'abord.
 */
class InMemoryAdmissionStore : AdmissionStore {
    val registrations = mutableListOf<Registration>()
    val admissions = linkedMapOf<String, Admission>()

    /** L'instant de chaque carte, par clé : le SQL ne le garde pas, le faux oui, pour que les tests le voient passer. */
    val cardedAt = mutableMapOf<String, Instant>()

    override suspend fun newRegistrations(limit: Int): List<Registration> =
        registrations.filter { it.key !in admissions }.sortedWith(compareBy({ it.createdAt }, { it.key })).take(limit)

    override suspend fun registration(key: String): Registration? = registrations.firstOrNull { it.key == key }

    override suspend fun carded(key: String, messageId: String, at: Instant) {
        check(key !in admissions) { "la clé primaire refuse une seconde carte pour $key" }
        admissions[key] = Admission(key, messageId, AdmissionDecision.WAITING, null, null)
        cardedAt[key] = at
    }

    override suspend fun decide(key: String, decision: String, by: String, at: Instant) {
        val current = admissions[key] ?: return
        admissions[key] = current.copy(decision = decision, decidedBy = by, decidedAt = at)
    }

    override suspend fun admission(key: String): Admission? = admissions[key]
}
