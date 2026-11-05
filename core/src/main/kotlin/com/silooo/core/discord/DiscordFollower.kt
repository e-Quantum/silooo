package com.silooo.core.discord

import com.silooo.core.db.Database
import com.silooo.core.db.Xuid
import com.silooo.core.events.EventStore
import io.ktor.server.application.Application
import io.ktor.server.application.log
import java.sql.Connection
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Le curseur du suiveur (`discord_cursor`, migration V8), derrière une interface pour que les tests tournent sans base : jusqu'où
 * `game_events` a été lu pour Discord. Une seule ligne.
 */
interface DiscordCursorStore {
    /** L'identifiant du dernier événement lu, ou `null` tant que le suiveur n'a jamais tourné (aucune ligne). */
    fun read(connection: Connection): Long?

    /** Pose le curseur sur [lastEventId] (crée la ligne la première fois). */
    fun write(connection: Connection, lastEventId: Long)

    /** L'identifiant du dernier événement de `game_events`, ou 0 s'il n'y en a aucun. */
    fun latestEventId(connection: Connection): Long
}

class JdbcDiscordCursorStore : DiscordCursorStore {
    override fun read(connection: Connection): Long? =
        connection.prepareStatement("SELECT last_event_id FROM discord_cursor WHERE id = 1").use { statement ->
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }

    override fun write(connection: Connection, lastEventId: Long) {
        connection.prepareStatement(
            "INSERT INTO discord_cursor (id, last_event_id) VALUES (1, ?) " +
                "ON DUPLICATE KEY UPDATE last_event_id = ?, updated_at = UTC_TIMESTAMP(3)",
        ).use {
            it.setLong(1, lastEventId)
            it.setLong(2, lastEventId)
            it.executeUpdate()
        }
    }

    override fun latestEventId(connection: Connection): Long =
        connection.prepareStatement("SELECT COALESCE(MAX(id), 0) FROM game_events").use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next()) { "game_events : MAX(id) n'a rien rendu" }
                rows.getLong(1)
            }
        }
}

/**
 * Le suiveur d'événements : un passage ([poll]) lit `game_events` après son curseur et envoie un message Discord pour ceux qui
 * regardent tout le serveur ([DiscordMessages.pick] : prestige d'une entreprise, boost collectif, Vote party, et le courant qui
 * manque à un joueur présent). Les autres événements sont lus et passés.
 *
 *  - **Éteint sans adresse** : si [sender] est éteint ([DiscordOff]), [poll] ne lit rien et n'écrit rien.
 *  - **Le premier passage** pose le curseur sur le dernier événement existant et n'envoie rien : les événements d'avant la mise en
 *    route ne sont jamais rejoués sur Discord. Le curseur vit en base ([DiscordCursorStore]) : un redémarrage reprend où le suivi
 *    s'était arrêté.
 *  - **Un message qui ne part pas** (après la nouvelle tentative de [DiscordWebhook]) est perdu, noté, et le curseur avance : un
 *    webhook cassé ne doit pas bloquer le suivi ni faire tomber, au retour, un flot de messages périmés.
 *  - **Le curseur** s'écrit après chaque message envoyé (un plantage ne le renvoie pas) et à la fin de chaque page lue. S'il y a
 *    une panne entre l'envoi et l'écriture, le message repart au passage suivant : mieux vaut une fois de trop qu'une de moins.
 *  - **Pas de rafale** : au plus [MAX_MESSAGES] messages par passage ; le reste attend le passage suivant, curseur gardé.
 *
 * [online] donne les joueurs connectés (le signal de vie du serveur) : seule l'alerte de courant en dépend.
 */
class DiscordFollower(
    private val database: Database,
    private val events: EventStore,
    private val cursors: DiscordCursorStore,
    private val sender: DiscordSender,
    private val online: () -> Set<Xuid> = { emptySet() },
) {
    /** Faux : pas d'adresse, le suiveur ne tourne pas. */
    val enabled: Boolean get() = sender.enabled

    /** Un passage. Rend le nombre de messages que Discord a pris. */
    suspend fun poll(): Int {
        if (!sender.enabled) return 0
        var cursor = database.transaction { cursors.read(it) } ?: return start()

        var written = cursor
        var sent = 0
        var tried = 0
        var pages = 0
        while (pages < MAX_PAGES && tried < MAX_MESSAGES) {
            pages++
            val page = database.transaction { events.readAfter(it, cursor, PAGE) }
            if (page.isEmpty()) break
            val present: Set<String> by lazy { online().map { it.text }.toSet() }
            var handled = cursor
            for (event in page) {
                if (tried >= MAX_MESSAGES) break
                when (val pick = DiscordMessages.pick(event) { it in present }) {
                    DiscordPick.Nothing -> Unit
                    is DiscordPick.Broken -> log.warn("Discord : l'événement {} ({}) n'a pas de message, {}.", event.id, event.kind, pick.reason)
                    is DiscordPick.Say -> {
                        tried++
                        if (sender.send(pick.text)) sent++ else log.warn("Discord : l'événement {} ({}) n'a pas pu être annoncé.", event.id, event.kind)
                        database.transaction { cursors.write(it, event.id) }
                        written = event.id
                    }
                }
                handled = event.id
            }
            cursor = handled
            if (handled != written) {
                database.transaction { cursors.write(it, handled) }
                written = handled
            }
            if (page.size < PAGE) break
        }
        return sent
    }

    /** Le premier passage de la vie du suiveur : le curseur part du dernier événement, rien n'est envoyé. */
    private suspend fun start(): Int {
        val latest = database.transaction { connection ->
            cursors.latestEventId(connection).also { cursors.write(connection, it) }
        }
        log.info("Discord : premier passage, le suivi part de l'événement {} (rien d'ancien n'est envoyé).", latest)
        return 0
    }

    companion object {
        /** Événements lus par page : le plafond de `GET /v1/events`. Un réglage technique, pas un chiffre du jeu. */
        const val PAGE = 500

        /** Pages lues par passage au plus : de quoi vider un retard sans tenir la base longtemps. */
        const val MAX_PAGES = 20

        /** Messages envoyés par passage au plus : Discord limite un webhook à 5 messages par 2 secondes, et un salon n'est pas un flux. */
        const val MAX_MESSAGES = 20

        private val log = LoggerFactory.getLogger(DiscordFollower::class.java)
    }
}

/**
 * Lance [follower] toutes les [every], dans la portée de l'application Ktor : la boucle s'arrête avec elle. Sans adresse de webhook
 * ([DiscordFollower.enabled] faux), une ligne de journal le dit au démarrage et rien ne tourne. Une panne se note et la boucle
 * continue, comme la purge de `api_requests`. Le module ne la lance que si `Dependencies.discord` et `Dependencies.discordPoll` sont posés.
 */
fun Application.launchDiscordFollower(follower: DiscordFollower, every: Duration) {
    if (!follower.enabled) {
        log.info("Discord : SILOOO_DISCORD_WEBHOOK n'est pas posée, les messages du serveur sont éteints.")
        return
    }
    log.info("Discord : les messages du serveur sont suivis toutes les {} secondes.", every.seconds)
    launch {
        while (isActive) {
            try {
                val sent = follower.poll()
                if (sent > 0) log.info("Discord : {} message(s) envoyé(s).", sent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Discord : passage impossible, nouvel essai à la prochaine échéance : {}", e.message)
            }
            delay(every.toMillis())
        }
    }
}
