package com.silooo.core.site

import java.time.Clock
import java.time.Duration

/**
 * Une limite de débit en mémoire : au plus [limit] essais par [window] et par adresse. Elle compte dans une fenêtre
 * fixe qui commence au premier essai de l'adresse. La table est bornée à [maxAddresses] : au-delà, la plus ancienne
 * adresse est oubliée (une inondation d'adresses ne fait pas grossir la mémoire, et ne bloque pas les autres visiteurs).
 * Elle ne survit pas à un redémarrage, c'est voulu.
 */
internal class RateLimiter(
    private val clock: Clock,
    private val limit: Int = 5,
    private val window: Duration = Duration.ofMinutes(1),
    private val maxAddresses: Int = 10_000,
) {
    private class Window(val startedAt: Long, var count: Int)

    private val windows = object : LinkedHashMap<String, Window>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Window>?): Boolean = size > maxAddresses
    }

    /** Compte un essai de [address] ; `false` quand la limite est dépassée. */
    @Synchronized
    fun allow(address: String): Boolean {
        val now = clock.millis()
        val current = windows[address]
        if (current == null || now - current.startedAt >= window.toMillis()) {
            windows.remove(address)
            windows[address] = Window(now, 1)
            return true
        }
        current.count++
        return current.count <= limit
    }
}
