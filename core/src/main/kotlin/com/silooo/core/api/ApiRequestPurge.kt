package com.silooo.core.api

import io.ktor.server.application.Application
import io.ktor.server.application.log
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Purge de `api_requests` : toutes les [every], les lignes plus vieilles que [keep] sont effacées. La boucle
 * vit dans la portée de l'application Ktor : elle s'arrête avec elle. Une panne de la base se note dans
 * les journaux et la boucle continue : une purge ratée se rattrape à la suivante.
 *
 * Le module ne la lance que si `Dependencies.apiRequestRetention` est posé (la production le pose depuis
 * `SILOOO_API_REQUESTS_RETENTION_HOURS`) ; un test sans base ne la lance pas.
 */
fun Application.launchApiRequestPurge(idempotency: Idempotency, keep: Duration, every: Duration = DEFAULT_PURGE_EVERY) {
    launch {
        while (isActive) {
            try {
                val deleted = idempotency.purge(keep)
                if (deleted > 0) log.info("api_requests : {} ligne(s) purgée(s).", deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("api_requests : purge impossible, nouvel essai à la prochaine échéance : {}", e.message)
            }
            delay(every.toMillis())
        }
    }
}

/** Une fois par heure : la rétention se compte en heures, inutile d'aller plus vite. */
val DEFAULT_PURGE_EVERY: Duration = Duration.ofHours(1)
