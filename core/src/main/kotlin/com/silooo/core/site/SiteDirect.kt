package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.site.pages.PlayersOnline
import com.silooo.core.vote.VoteRules
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** L'adresse du bandeau direct. Une route du site, pas de l'API : ni clé, ni `/v1`, ni contrat OpenAPI (docs/site.md en tient la table). */
internal const val DIRECT_URL = "/site/direct.json"

/**
 * Ce que `direct.js` relit toutes les trente secondes pour le bandeau de chaque page (docs/site.md, « Bandeau direct »).
 * Les champs sont toujours présents : un `null` dit que la donnée manque, le bandeau cache l'entrée.
 */
@Serializable
data class SiteDirect(
    /** Les joueurs en ligne ; `null` quand le serveur n'a pas donné signe de vie depuis deux minutes. */
    val joueurs: Int?,
    /** « ouvert » quand le serveur bat, « ferme » sinon. */
    val etat: String,
    /** La Vote party du jour ; `null` quand la base ne répond pas. */
    val voteParty: VotePartyDirect?,
    /** La version de Bedrock du serveur : le signal de vie ne la porte pas encore (voir docs/site-passation.md). */
    val version: String? = null,
    /** Le dernier prestige : aucune lecture ne l'expose encore. */
    val dernierPrestige: String? = null,
)

@Serializable
data class VotePartyDirect(
    /** Les votes reçus aujourd'hui (le jour des votes est en UTC, comme pour la récompense). */
    val votes: Int,
    /** `voteParty.votesParJour` de vote.json. */
    val objectif: Int,
    val atteinte: Boolean,
)

/**
 * Lit l'état du jeu pour le bandeau : la présence (en mémoire, toujours) et le compte des votes du jour (la base, si
 * elle répond). Une base qui ne répond pas ne casse pas le bandeau : la Vote party manque, le reste s'affiche.
 */
internal class SiteDirectSource(private val dependencies: Dependencies) {
    private val json = Json { encodeDefaults = true }

    suspend fun read(): SiteDirect {
        val online = playersOnline(dependencies.serverPresence, dependencies.clock)
        val count = (online as? PlayersOnline.Count)?.count
        val today = dependencies.clock.instant().atOffset(ZoneOffset.UTC).toLocalDate()
        val votes = try {
            dependencies.database.transaction { dependencies.voteStore.countOn(it, today) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val vote = dependencies.definitions.vote
        return SiteDirect(
            joueurs = count,
            etat = if (count != null) "ouvert" else "ferme",
            voteParty = votes?.let { VotePartyDirect(it, vote.party.votesPerDay, VoteRules.partyReached(it, vote)) },
        )
    }

    suspend fun readJson(): String = json.encodeToString(SiteDirect.serializer(), read())
}
