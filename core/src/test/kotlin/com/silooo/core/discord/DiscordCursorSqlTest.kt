package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import com.silooo.core.events.GameEventKind
import com.silooo.core.events.JdbcEventStore
import com.silooo.core.events.publishEvent
import java.sql.SQLException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** `discord_cursor` (migration V8) et le suiveur sur la vraie MariaDB. Ignoré sans Docker. */
class DiscordCursorSqlTest {
    private val database get() = MariaDbSupport.database
    private val cursors = JdbcDiscordCursorStore()

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    private fun read(): Long? = runBlocking { database.transaction { cursors.read(it) } }

    private fun write(id: Long) = runBlocking { database.transaction { cursors.write(it, id) } }

    private fun publish(kind: GameEventKind, n: Int, fill: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): Long =
        runBlocking { database.transaction { publishEvent(it, kind, null, buildJsonObject { put("n", n); fill() }) } }

    @Test
    @DisplayName("La migration V8 crée discord_cursor : une table de plus, vide au départ")
    fun theTableExistsAndStartsEmpty() {
        assertEquals(
            1L,
            MariaDbSupport.long("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'discord_cursor'"),
        )
        assertEquals(0L, MariaDbSupport.count("discord_cursor"))
        assertNull(read(), "pas de ligne : le suiveur n'a jamais tourné")
    }

    @Test
    @DisplayName("write crée la ligne unique la première fois, puis la met à jour : une seule ligne, la valeur la plus récente")
    fun writeUpsertsTheSingleRow() {
        write(7)
        assertEquals(7L, read())
        assertEquals(1L, MariaDbSupport.count("discord_cursor"))

        write(12)
        assertEquals(12L, read())
        assertEquals(1L, MariaDbSupport.count("discord_cursor"))
        assertEquals(1L, MariaDbSupport.long("SELECT id FROM discord_cursor"))
    }

    @Test
    @DisplayName("La base refuse une deuxième ligne (id différent de 1)")
    fun onlyOneRowIsAllowed() {
        assertThrows<SQLException> { MariaDbSupport.update("INSERT INTO discord_cursor (id, last_event_id) VALUES (2, 5)") }
        assertEquals(0L, MariaDbSupport.count("discord_cursor"))
    }

    @Test
    @DisplayName("latestEventId : 0 sans événement, puis l'identifiant du dernier")
    fun latestEventId() {
        assertEquals(0L, runBlocking { database.transaction { cursors.latestEventId(it) } })

        publish(GameEventKind.ANNONCE, 1)
        val last = publish(GameEventKind.ANNONCE, 2)

        assertEquals(last, runBlocking { database.transaction { cursors.latestEventId(it) } })
    }

    @Test
    @DisplayName("Le suiveur sur MariaDB : le premier passage pose le curseur sans rien envoyer, les événements suivants partent une fois, un redémarrage reprend")
    fun theFollowerEndToEnd() {
        publish(GameEventKind.PRESTIGE_ENTREPRISE, 1) { put("companyName", "Ancienne"); put("lingots", 1); put("prestigeCount", 1) }
        val sender = FakeSender()
        val follower = DiscordFollower(database, JdbcEventStore(), cursors, sender)

        assertEquals(0, runBlocking { follower.poll() })
        assertEquals(emptyList<String>(), sender.sent, "l'événement d'avant la mise en route n'est pas rejoué")
        assertEquals(1L, read())

        publish(GameEventKind.GEMMES_RECUES, 2)
        publish(GameEventKind.PRESTIGE_ENTREPRISE, 3) { put("companyName", "Les Forges"); put("lingots", 340); put("prestigeCount", 2) }
        publish(GameEventKind.BOOST_COLLECTIF, 4) { put("percent", 25); put("minutes", 60) }
        val last = publish(GameEventKind.ANNONCE, 5) { put("action", "voteParty"); put("votes", 500) }

        assertEquals(3, runBlocking { follower.poll() })
        assertEquals(
            listOf(
                "Les Forges a fait son 2e prestige et gagne 340 Lingots.",
                "Boost collectif : production +25 % pour tout le monde pendant une heure.",
                "Vote party : le serveur a atteint 500 votes.",
            ),
            sender.sent,
        )
        assertEquals(last, read())

        val restarted = DiscordFollower(database, JdbcEventStore(), JdbcDiscordCursorStore(), sender)
        assertEquals(0, runBlocking { restarted.poll() }, "après un redémarrage, rien n'est renvoyé")
        assertEquals(3, sender.sent.size)
    }
}
