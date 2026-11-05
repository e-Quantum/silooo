package com.silooo.core.discord

import com.silooo.core.api.GameEvent
import com.silooo.core.events.GameEventKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Ce que le suiveur fait d'un événement. */
sealed interface DiscordPick {
    /** L'événement ne regarde pas Discord (ou pas ce coup-ci) : rien à dire, rien à noter. */
    data object Nothing : DiscordPick

    /** L'événement regarde Discord, mais il manque un champ ou il est faux : il est sauté et noté dans le journal. */
    data class Broken(val reason: String) : DiscordPick

    /** Le message à envoyer. */
    data class Say(val text: String) : DiscordPick
}

/**
 * Choisit, pour un événement de `game_events`, le message Discord qu'il mérite. Quatre cas, et seulement eux :
 *
 *  - `PRESTIGE_ENTREPRISE` (pour tout le serveur) : `companyName`, `lingots` (le total gagné, avant partage), `prestigeCount` (le
 *    numéro de la course qui se termine). C'est `CompanyService.prestige` qui le publie.
 *  - `BOOST_COLLECTIF` : `percent` (le bonus de production), `minutes` (la durée), `buyerName` (facultatif : absent pour un boost
 *    gagné). Rien ne le publie encore : la boutique en jeu et la Vote party l'écriront avec ces champs.
 *  - `ANNONCE` dont `payload.action` vaut `voteParty` : `votes` (le nombre atteint), `lingots` (facultatif : la récompense en
 *    Lingots de chaque joueur présent). Rien ne la publie encore.
 *  - `ANNONCE` dont `payload.action` vaut `sansCourant` : `playerName`, `factoryName`. Adressée à un joueur (`xuid`) ; elle ne part
 *    sur Discord que si ce joueur est [present] (connecté) quand le suiveur la lit.
 *
 * Tout le reste (Gemmes reçues, incident, invitation, les autres `ANNONCE` adressées à un joueur…) ne regarde pas Discord.
 * Les textes sont dans [DiscordTexts] ; les chiffres, ceux de l'événement.
 */
object DiscordMessages {
    const val ACTION_VOTE_PARTY = "voteParty"
    const val ACTION_NO_POWER = "sansCourant"

    fun pick(event: GameEvent, present: (String) -> Boolean): DiscordPick {
        val payload = event.payload
        return when (event.kind) {
            GameEventKind.PRESTIGE_ENTREPRISE.name -> prestige(payload)
            GameEventKind.BOOST_COLLECTIF.name -> boost(payload)
            GameEventKind.ANNONCE.name -> when (payload.text("action")) {
                ACTION_VOTE_PARTY -> voteParty(payload)
                ACTION_NO_POWER -> if (event.xuid != null && present(event.xuid)) noPower(payload) else DiscordPick.Nothing
                else -> DiscordPick.Nothing
            }
            else -> DiscordPick.Nothing
        }
    }

    private fun prestige(payload: JsonObject): DiscordPick {
        val company = payload.text("companyName")?.let { DiscordTexts.name(it) } ?: return broken("companyName")
        val lingots = payload.whole("lingots")?.takeIf { it >= 0 } ?: return broken("lingots")
        val count = payload.whole("prestigeCount")?.takeIf { it >= 1 } ?: return broken("prestigeCount")
        return DiscordPick.Say(DiscordTexts.prestige(company, lingots, count))
    }

    private fun boost(payload: JsonObject): DiscordPick {
        val percent = payload.number("percent")?.takeIf { it.isFinite() && it > 0.0 } ?: return broken("percent")
        val minutes = payload.whole("minutes")?.takeIf { it >= 1 } ?: return broken("minutes")
        val buyer = payload.text("buyerName")?.let { DiscordTexts.name(it) }
        return DiscordPick.Say(DiscordTexts.collectiveBoost(buyer, percent, minutes))
    }

    private fun voteParty(payload: JsonObject): DiscordPick {
        val votes = payload.whole("votes")?.takeIf { it >= 1 } ?: return broken("votes")
        return DiscordPick.Say(DiscordTexts.voteParty(votes, payload.whole("lingots")))
    }

    private fun noPower(payload: JsonObject): DiscordPick {
        val player = payload.text("playerName")?.let { DiscordTexts.name(it) } ?: return broken("playerName")
        val factory = payload.text("factoryName")?.let { DiscordTexts.name(it) } ?: return broken("factoryName")
        return DiscordPick.Say(DiscordTexts.noPower(player, factory))
    }

    private fun broken(field: String) = DiscordPick.Broken("champ « $field » absent ou invalide")

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Double? = primitive(key)?.doubleOrNull

    /** Un entier JSON (25, pas 25.5 ni "25"). */
    private fun JsonObject.whole(key: String): Long? = primitive(key)?.longOrNull

    private fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }
}
