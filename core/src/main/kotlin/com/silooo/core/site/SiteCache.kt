package com.silooo.core.site

import com.silooo.core.sitedata.CompanyCard
import com.silooo.core.sitedata.RankingPage
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Combien de temps une page de données vivantes se garde : une minute, un réglage technique du site, pas un chiffre du jeu. */
internal val SITE_CACHE_LIFETIME: Duration = Duration.ofMinutes(1)

/** Combien d'entrées un cache garde au plus : quand il est plein, la plus ancienne sort. */
internal const val SITE_CACHE_MAX_ENTRIES = 300

/**
 * Un petit cache en mémoire : une valeur par clé, la date où elle a été lue, l'horloge du service. Rien d'extérieur (ni Redis, ni
 * bibliothèque). Une valeur de plus d'une minute est relue ; au-delà de [maxEntries], l'entrée la plus ancienne sort.
 *
 * Il est partagé entre les requêtes. Le verrou ne tient jamais pendant la lecture dans la base : deux requêtes qui arrivent en même
 * temps sur une clé absente la lisent chacune une fois, puis la dernière gagne. [V] peut être `null` (une entreprise inconnue se garde aussi).
 */
class SiteCache<K : Any, V>(
    private val clock: Clock,
    private val lifetime: Duration = SITE_CACHE_LIFETIME,
    private val maxEntries: Int = SITE_CACHE_MAX_ENTRIES,
) {
    private class Entry<V>(val value: V, val readAt: Instant)

    private val entries = LinkedHashMap<K, Entry<V>>()

    suspend fun get(key: K, load: suspend () -> V): V {
        synchronized(this) {
            val entry = entries[key]
            if (entry != null) {
                if (Duration.between(entry.readAt, clock.instant()) < lifetime) return entry.value
                entries.remove(key)
            }
        }
        val value = load()
        synchronized(this) {
            entries.remove(key)
            entries[key] = Entry(value, clock.instant())
            while (entries.size > maxEntries) entries.remove(entries.keys.first())
        }
        return value
    }

    /** Le nombre d'entrées gardées (pour les tests). */
    @Synchronized
    fun size(): Int = entries.size
}

/** La clé d'une page du classement : le lundi de la semaine (UTC) et le numéro de la page. Une nouvelle semaine ne retombe jamais sur l'ancienne. */
data class RankingKey(val weekStart: Instant, val page: Int)

/** Les caches du site, créés avec [Dependencies][com.silooo.core.Dependencies] : un seul jeu pour tout le processus. */
class SiteCaches(clock: Clock) {
    val rankings = SiteCache<RankingKey, RankingPage>(clock)
    val companies = SiteCache<Long, CompanyCard?>(clock)
}
