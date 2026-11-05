package com.silooo.core.discord

import com.silooo.core.api.GameEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Quels événements de `game_events` méritent un message, et lequel. Les chiffres du message sont ceux de la charge utile. */
class DiscordMessagesTest {
    private val kr = "2535428000000001"
    private val nobody: (String) -> Boolean = { false }

    private fun event(kind: String, xuid: String? = null, id: Long = 1, fill: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        GameEvent(id, xuid, kind, buildJsonObject(fill))

    private fun pick(event: GameEvent, present: (String) -> Boolean = nobody) = DiscordMessages.pick(event, present)

    private fun prestige(fill: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = event("PRESTIGE_ENTREPRISE", fill = fill)

    private fun say(text: String) = DiscordPick.Say(text)

    // --- PRESTIGE_ENTREPRISE

    @Test
    @DisplayName("PRESTIGE_ENTREPRISE : le nom, les Lingots et le rang viennent de la charge utile")
    fun prestigeEvent() {
        val result = pick(prestige { put("companyId", 4); put("companyName", "Les Forges"); put("lingots", 340); put("prestigeCount", 2) })

        assertEquals(say("Les Forges a fait son 2e prestige et gagne 340 Lingots."), result)
    }

    @Test
    @DisplayName("PRESTIGE_ENTREPRISE : un nom à rallonge ou sur plusieurs lignes est nettoyé avant d'entrer dans la phrase")
    fun prestigeNameIsCleaned() {
        val result = pick(prestige { put("companyName", "  Les\nForges  "); put("lingots", 1); put("prestigeCount", 1) })

        assertEquals(say("Les Forges a fait son 1er prestige et gagne 1 Lingot."), result)
    }

    @Test
    @DisplayName("PRESTIGE_ENTREPRISE : un champ absent, vide ou du mauvais type donne un événement cassé, noté et sauté, jamais un message faux")
    fun prestigeBrokenPayloads() {
        val good = buildJsonObject { put("companyName", "Les Forges"); put("lingots", 10); put("prestigeCount", 1) }
        assertEquals(say("Les Forges a fait son 1er prestige et gagne 10 Lingots."), pick(prestige { good.forEach { (key, value) -> put(key, value) } }))

        val broken = listOf(
            buildJsonObject { put("lingots", 10); put("prestigeCount", 1) },
            buildJsonObject { put("companyName", "   "); put("lingots", 10); put("prestigeCount", 1) },
            buildJsonObject { put("companyName", "X"); put("prestigeCount", 1) },
            buildJsonObject { put("companyName", "X"); put("lingots", -1); put("prestigeCount", 1) },
            buildJsonObject { put("companyName", "X"); put("lingots", "10"); put("prestigeCount", 1) },
            buildJsonObject { put("companyName", "X"); put("lingots", 10.5); put("prestigeCount", 1) },
            buildJsonObject { put("companyName", "X"); put("lingots", 10) },
            buildJsonObject { put("companyName", "X"); put("lingots", 10); put("prestigeCount", 0) },
            buildJsonObject { put("companyName", 12); put("lingots", 10); put("prestigeCount", 1) },
            JsonObject(emptyMap()),
        )
        for (payload in broken) {
            assertTrue(pick(prestige { payload.forEach { (key, value) -> put(key, value) } }) is DiscordPick.Broken, payload.toString())
        }
    }

    // --- BOOST_COLLECTIF

    @Test
    @DisplayName("BOOST_COLLECTIF : le bonus et la durée viennent de l'événement ; l'acheteur est nommé s'il y en a un")
    fun boostEvent() {
        assertEquals(
            say("Zoé offre un boost collectif : production +25 % pour tout le monde pendant une heure."),
            pick(event("BOOST_COLLECTIF") { put("percent", 25); put("minutes", 60); put("buyerName", "Zoé") }),
        )
        assertEquals(
            say("Boost collectif : production +10 % pour tout le monde pendant 15 minutes."),
            pick(event("BOOST_COLLECTIF") { put("percent", 10.0); put("minutes", 15) }),
        )
    }

    @Test
    @DisplayName("BOOST_COLLECTIF : un bonus nul ou négatif, une durée absente ou nulle donne un événement cassé")
    fun boostBrokenPayloads() {
        for (fill in listOf<kotlinx.serialization.json.JsonObjectBuilder.() -> Unit>(
            { put("minutes", 60) },
            { put("percent", 25) },
            { put("percent", 0); put("minutes", 60) },
            { put("percent", -5); put("minutes", 60) },
            { put("percent", 25); put("minutes", 0) },
            { put("percent", "25"); put("minutes", 60) },
            { put("percent", 25); put("minutes", 1.5) },
        )) {
            assertTrue(pick(event("BOOST_COLLECTIF", fill = fill)) is DiscordPick.Broken, buildJsonObject(fill).toString())
        }
    }

    // --- ANNONCE voteParty

    @Test
    @DisplayName("ANNONCE voteParty : le nombre de votes, et la récompense si l'événement en porte une")
    fun voteParty() {
        assertEquals(
            say("Vote party : le serveur a atteint 500 votes, chaque joueur présent reçoit 5 Lingots."),
            pick(event("ANNONCE") { put("action", "voteParty"); put("votes", 500); put("lingots", 5) }),
        )
        assertEquals(
            say("Vote party : le serveur a atteint 500 votes."),
            pick(event("ANNONCE") { put("action", "voteParty"); put("votes", 500) }),
        )
        assertTrue(pick(event("ANNONCE") { put("action", "voteParty") }) is DiscordPick.Broken)
        assertTrue(pick(event("ANNONCE") { put("action", "voteParty"); put("votes", 0) }) is DiscordPick.Broken)
    }

    // --- ANNONCE sansCourant

    @Test
    @DisplayName("ANNONCE sansCourant : un message seulement si le joueur visé est connecté")
    fun noPowerOnlyForAPresentPlayer() {
        val away = event("ANNONCE", kr) { put("action", "sansCourant"); put("playerName", "Zoé"); put("factoryName", "Verrerie") }

        assertEquals(DiscordPick.Nothing, pick(away, present = nobody))
        assertEquals(DiscordPick.Nothing, pick(away) { it == "2535428000000002" }, "un autre joueur connecté ne suffit pas")
        assertEquals(say("L'usine Verrerie de Zoé est à l'arrêt faute de courant."), pick(away) { it == kr })
    }

    @Test
    @DisplayName("ANNONCE sansCourant : sans destinataire, ou sans nom de joueur ou d'usine, rien ne part")
    fun noPowerNeedsAnAddresseeAndNames() {
        val payload: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = { put("action", "sansCourant"); put("playerName", "Zoé"); put("factoryName", "Verrerie") }
        assertEquals(DiscordPick.Nothing, pick(event("ANNONCE", null, fill = payload)) { true })

        assertTrue(pick(event("ANNONCE", kr) { put("action", "sansCourant"); put("factoryName", "Verrerie") }) { true } is DiscordPick.Broken)
        assertTrue(pick(event("ANNONCE", kr) { put("action", "sansCourant"); put("playerName", "Zoé") }) { true } is DiscordPick.Broken)
    }

    // --- tout le reste

    @Test
    @DisplayName("Les autres sortes d'événements, et les autres ANNONCE (prestige d'un membre, restoreStep…), ne regardent pas Discord")
    fun everythingElseIsIgnored() {
        val ignored = listOf(
            event("GEMMES_RECUES", kr) { put("gemmes", 275) },
            event("INCIDENT", kr) { put("factoryId", "carriere") },
            event("INVITATION", kr) { put("companyId", 4) },
            event("ENTREPRISE_EN_COURS", kr),
            event("ANNONCE", kr) { put("action", "prestige"); put("lingots", 340); put("lingotsTotal", 340) },
            event("ANNONCE", kr) { put("action", "restoreStep"); put("step", 4) },
            event("ANNONCE", kr) { put("action", 12) },
            event("ANNONCE", kr),
            event("SORTE_DU_FUTUR") { put("votes", 500) },
        )
        for (event in ignored) {
            assertEquals(DiscordPick.Nothing, pick(event) { true }, "${event.kind} ${event.payload}")
        }
    }
}
