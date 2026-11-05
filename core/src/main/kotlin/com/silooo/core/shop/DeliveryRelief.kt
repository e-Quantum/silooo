package com.silooo.core.shop

import com.silooo.core.db.Database
import com.silooo.core.db.Xuid
import com.silooo.core.db.isTransientConflict
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.PendingDeliveries
import io.ktor.server.application.Application
import io.ktor.server.application.log
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Le relevé des livraisons de Gemmes. Le site écrit une ligne dans `shop_deliveries` quand PayNow confirme un paiement (`order_id`,
 * pseudo saisi, Gemmes achetées) ; le service la verse au joueur dès qu'il le connaît, de deux façons :
 *  - [poll], à intervalle régulier ([launchDeliveryPoll]) : chaque livraison en attente dont le joueur est connu ;
 *  - [relieve], au `join` ([PendingDeliveries]) : celles du joueur qui arrive, sans attendre le passage suivant.
 *
 * Le joueur d'une ligne est trouvé par son XUID si la ligne en porte un que `players` connaît, sinon par son pseudo (le joueur vu en
 * dernier). Personne ne correspond : la ligne reste en attente, intacte, et le prochain passage la retente.
 *
 * Versée UNE fois, quoi qu'il arrive : dans une seule transaction, la ligne est marquée (`UPDATE ... WHERE delivered_at IS NULL`, qui
 * doit toucher une ligne), puis les Gemmes sont créditées (motif `livraison`, référence : la commande) et l'événement `GEMMES_RECUES`
 * est écrit. Deux passages en même temps ne versent donc pas deux fois : le second ne touche aucune ligne et ne verse rien.
 */
class DeliveryRelief(
    private val database: Database,
    private val store: ShopStore,
    private val clock: Clock = Clock.systemUTC(),
) : PendingDeliveries {

    /**
     * Un passage : verse les livraisons en attente dont le joueur est connu, [BATCH] au plus. Chacune a sa transaction : une qui échoue
     * (conflit de verrou, panne) est notée et retentée au passage suivant, sans retarder les autres. Rend le nombre de livraisons versées.
     */
    suspend fun poll(): Int {
        val pending = database.transaction { store.pendingDeliveries(it, BATCH) }
        var delivered = 0
        for (delivery in pending) {
            try {
                if (database.transaction { deliver(it, delivery) }) delivered++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isTransientConflict()) {
                    log.info("Livraisons : la commande {} était occupée, nouvel essai au prochain passage.", delivery.orderId)
                } else {
                    log.warn("Livraisons : la commande {} n'a pas pu être versée, nouvel essai au prochain passage : {}", delivery.orderId, e.message)
                }
            }
        }
        return delivered
    }

    /** `join` : verse les livraisons de [xuid] (par XUID, puis par pseudo [gamertag]) qui n'attendent plus que lui. */
    override fun relieve(connection: Connection, xuid: Xuid, gamertag: String) {
        for (delivery in store.pendingDeliveriesFor(connection, xuid, gamertag)) {
            // Une ligne qui porte le XUID d'un autre joueur connu, ou le pseudo d'un joueur vu plus récemment, n'est pas pour lui.
            if (store.recipientOf(connection, delivery) == xuid) credit(connection, delivery, xuid)
        }
    }

    private fun deliver(connection: Connection, delivery: PendingDelivery): Boolean {
        val recipient = store.recipientOf(connection, delivery) ?: return false
        return credit(connection, delivery, recipient)
    }

    /** Marque la livraison, crédite les Gemmes, écrit l'événement : tout ou rien. Faux (et rien d'écrit) si un autre passage l'a versée. */
    private fun credit(connection: Connection, delivery: PendingDelivery, recipient: Xuid): Boolean {
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        if (!store.markDelivered(connection, delivery.orderId, recipient, now)) return false
        val total = store.creditGemmes(connection, recipient, delivery.gemmes, REASON_DELIVERY, delivery.orderId)
        store.publish(
            connection,
            GameEventKind.GEMMES_RECUES,
            recipient,
            buildJsonObject {
                put("orderId", delivery.orderId)
                put("gemmes", delivery.gemmes)
                put("gemmesTotal", total)
            },
        )
        return true
    }

    companion object {
        /** Le motif du journal des monnaies (`currency_ledger.reason`, 32 caractères au plus) : la référence est la commande. */
        const val REASON_DELIVERY = "livraison"

        /** Livraisons versées par passage : de quoi vider un retard sans tenir la base longtemps. Un réglage technique, pas un chiffre du jeu. */
        const val BATCH = 100

        private val log = LoggerFactory.getLogger(DeliveryRelief::class.java)
    }
}

/**
 * Lance [relief] toutes les [every], dans la portée de l'application Ktor : la boucle s'arrête avec elle. Le premier passage a lieu
 * tout de suite (les commandes payées pendant un arrêt du service attendent déjà), puis après chaque pause. Une panne se note et la
 * boucle continue, comme la purge de `api_requests`. Le module ne la lance que si `Dependencies.deliveries` et
 * `Dependencies.deliveryPoll` sont posés.
 */
fun Application.launchDeliveryPoll(relief: DeliveryRelief, every: Duration) {
    launch {
        while (isActive) {
            try {
                val delivered = relief.poll()
                if (delivered > 0) log.info("Livraisons : {} commande(s) versée(s).", delivered)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Livraisons : passage impossible, nouvel essai à la prochaine échéance : {}", e.message)
            }
            delay(every.toMillis())
        }
    }
}
