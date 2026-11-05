package com.silooo.core.incident

import com.silooo.core.api.Refusal
import com.silooo.core.db.Database
import com.silooo.core.db.Xuid
import com.silooo.core.db.isTransientConflict
import com.silooo.core.session.SessionStore
import io.ktor.server.application.Application
import io.ktor.server.application.log
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Le minuteur des incidents d'usine. Aucun appel de l'add-on ne revient à intervalle régulier : c'est le service qui regarde, à
 * chaque [tick], quelles entreprises ont un membre en ligne et joue le tirage de leur créneau ([IncidentService.tick]).
 *
 * Un incident ne s'ouvre que pendant qu'un membre est connecté, et le service ne le sait que par le signal de vie du serveur
 * Bedrock (`onlineXuids`). Or un joueur apparaît dans ce signal AVANT que son `join` ait versé son absence au régime hors ligne.
 * Ouvrir un incident à cet instant verserait l'absence au revenu plein. Le minuteur ne tire donc que pour les joueurs vus à
 * deux passages de suite : le `join` a eu tout un intervalle pour passer.
 *
 * Une entreprise qui échoue (conflit de verrou, panne de la base) est notée et laissée à la fin : le tirage de son créneau ne
 * change pas, le passage suivant le rejoue. Les autres entreprises ne sont pas retardées.
 */
class IncidentTicker(
    private val database: Database,
    private val sessions: SessionStore,
    private val service: IncidentService,
    private val onlineXuids: () -> Set<Xuid>,
) {
    private var previous: Set<Xuid> = emptySet()

    /** Un passage. Rend les incidents qu'il vient d'ouvrir, par entreprise croissante. */
    suspend fun tick(): List<OpenedIncident> {
        val current = onlineXuids()
        val steady = current.intersect(previous)
        previous = current
        if (steady.isEmpty()) return emptyList()

        val onlineByCompany = database.transaction { connection ->
            steady.mapNotNull { xuid -> sessions.membershipOf(connection, xuid)?.let { it.companyId to xuid } }
        }.groupBy({ it.first }, { it.second })

        val opened = mutableListOf<OpenedIncident>()
        for ((companyId, online) in onlineByCompany.toSortedMap()) {
            try {
                database.transaction { connection -> service.tick(connection, companyId, online.sortedBy { it.number }) }?.let { opened += it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is Refusal || e.isTransientConflict()) {
                    log.info("Incidents : l'entreprise {} était occupée, nouvel essai au prochain passage.", companyId)
                } else {
                    log.warn("Incidents : tirage impossible pour l'entreprise {}, nouvel essai au prochain passage : {}", companyId, e.message)
                }
            }
        }
        return opened
    }

    private companion object {
        val log = LoggerFactory.getLogger(IncidentTicker::class.java)
    }
}

/**
 * L'intervalle du minuteur en production : le signal de vie du serveur bat toutes les 10 secondes et vieillit au bout de 30, et un
 * joueur doit être vu à deux passages de suite. Une durée technique de liaison, pas un chiffre du jeu : la cadence des incidents,
 * elle, est `frequence.creneauMinutes` de `incidents.json`.
 */
val INCIDENT_TICK_EVERY: Duration = Duration.ofSeconds(30)

/**
 * Lance [ticker] toutes les [every], dans la portée de l'application Ktor : la boucle s'arrête avec elle. Une panne se note et
 * la boucle continue, comme la purge de `api_requests`. Le module ne la lance que si `Dependencies.incidentTick` est posé.
 */
fun Application.launchIncidentTicker(ticker: IncidentTicker, every: Duration) {
    launch {
        while (isActive) {
            delay(every.toMillis())
            try {
                for (incident in ticker.tick()) {
                    log.info("Incident {} ({}) ouvert sur l'usine {}.", incident.incidentId, incident.kind, incident.factoryId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Incidents : passage impossible, nouvel essai à la prochaine échéance : {}", e.message)
            }
        }
    }
}
