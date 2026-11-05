package com.silooo.core.findings

import com.silooo.core.api.FactoryState
import com.silooo.core.api.Finding
import com.silooo.core.api.FindingBook
import com.silooo.core.db.Xuid
import com.silooo.core.definitions.Definitions
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.FactoryRecord
import java.sql.Connection
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Le Carnet de trouvailles du joueur : les ressources qu'il a vues sortir d'une de ses usines (`player_findings`).
 *
 * Le game design ne cite le Carnet qu'en passant (phase 4 du plan) : les règles ci-dessous sont les plus simples qui le font
 * vivre, toutes **à valider par Kr**.
 *  - Il y a une trouvaille par ressource du jeu ([Definitions.resources], les produits de `usines.json`).
 *  - Le service inscrit lui-même ([record], appelé depuis la transaction de `collect`) : au premier encaissement qui rapporte
 *    du Donar, chaque ressource que produit une usine EN MARCHE (`ACTIVE`) de l'entreprise entre dans le Carnet de celui qui
 *    encaisse, si elle n'y est pas. Une usine en ruine ou en remise en état ne produit rien et ne trouve rien. Il n'y a pas
 *    de route pour réclamer une trouvaille, et aucun hasard : la première fois qu'une usine produit, c'est trouvé.
 *  - Chaque trouvaille verse une fois `lingotsHorsPrestige.trouvaille` Lingots (`economie.json`) et publie un événement `ANNONCE`
 *    dont `payload.action` vaut `trouvaille`. Une trouvaille ne se perd pas au prestige : le Carnet est au joueur, pas à l'entreprise.
 *
 * Aucun chiffre du jeu ici : la liste des ressources vient de `usines.json`, la prime de `economie.json`.
 */
class FindingService(
    private val definitions: Definitions,
    private val store: FindingStore,
) {
    private val reward: Long get() = definitions.economy.lingotsOutsidePrestige.finding

    /** Le Carnet de [xuid], ou `null` si le joueur n'existe pas. Rien n'est écrit. */
    fun bookOf(connection: Connection, xuid: Xuid): FindingBook? {
        if (!store.playerExists(connection, xuid)) return null
        return FindingBook(
            found = store.load(connection, xuid).map { Finding(it.resourceId, it.foundAt.toString()) },
            total = definitions.resources.size,
            rewardLingots = reward,
        )
    }

    /**
     * Inscrit au Carnet de [actor] les ressources que produisent les usines en marche de [factories] et qu'il n'avait pas
     * encore, paie la prime de chacune et publie son événement. À appeler dans la transaction de l'encaissement : si
     * l'encaissement échoue ou est rejoué par un conflit, les trouvailles repartent avec lui.
     */
    fun record(connection: Connection, actor: Xuid, factories: List<FactoryRecord>) {
        val running = factories.filter { it.state == FactoryState.ACTIVE }.map { it.factoryId }.toSet()
        val candidates = definitions.factories.factories.filter { it.id in running }.flatMap { it.products }.distinct()
        if (candidates.isEmpty()) return

        val known = store.load(connection, actor).map { it.resourceId }.toSet()
        for (resourceId in candidates) {
            // `add` dit qui a gagné si deux encaissements du même joueur se croisent : un seul paie la prime.
            if (resourceId in known || !store.add(connection, actor, resourceId)) continue
            if (reward > 0) store.creditLingots(connection, actor, reward, REASON, resourceId)
            store.publish(
                connection,
                EVENT_KIND,
                actor,
                buildJsonObject {
                    put("action", ACTION)
                    put("resourceId", resourceId)
                    put("rewardLingots", reward)
                },
            )
        }
    }

    companion object {
        /**
         * Le type d'événement d'une trouvaille, adressé au joueur. L'OpenAPI n'a pas de type propre au Carnet et en ajouter un
         * casserait `ContractTest.codesMatchTheContract` : `ANNONCE` est le type générique, le détail est dans `payload.action`.
         */
        val EVENT_KIND = GameEventKind.ANNONCE

        /** Valeur de `payload.action` d'une trouvaille. */
        const val ACTION = "trouvaille"

        /** `currency_ledger.reason` des Lingots d'une trouvaille ; `ref_id` porte l'identifiant de la ressource. */
        const val REASON = "trouvaille"
    }
}
