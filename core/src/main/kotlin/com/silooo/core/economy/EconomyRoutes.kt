package com.silooo.core.economy

import com.silooo.core.Dependencies
import io.ktor.server.routing.Route

/**
 * Domaine « economy » : l'entreprise, ses usines, ses monnaies.
 *
 * Ce que la session « moteur d'économie » met ici :
 *  - Le chargement de `definitions/` au démarrage (usines, économie, boutique) : aucun chiffre du
 *    jeu dans le code, tout vient de ces fichiers. Attention : l'image Docker se construit
 *    aujourd'hui avec `core/` pour seul contexte, donc sans `definitions/` ; cette session choisit
 *    entre un contexte à la racine du dépôt et une copie des fichiers dans les ressources.
 *  - Le moteur : prix des niveaux, production, paliers ×2, prestige, notation, rejoués par les tests
 *    dorés de `core/src/test/resources/golden/`.
 *  - Les routes `companies/{companyId}` : `getCompany`, `collect`, `buyLevels`, `restoreStep`,
 *    la résolution des incidents, le prestige. Chaque action porte un `requestId` (idempotence par
 *    `api_requests`), écrit avec le verrou optimiste `companies.version`, et les monnaies
 *    personnelles passent par `currency_ledger` dans la même transaction que le solde.
 *
 * Les autres groupes de l'OpenAPI (agence et personnel, associés, ville, commande mondiale,
 * marché, boutique, pass) auront chacun leur paquet à côté de celui-ci, quand leur phase arrivera.
 */
@Suppress("UNUSED_PARAMETER")
fun Route.economyRoutes(dependencies: Dependencies) {
    // Rien pour l'instant : voir le commentaire ci-dessus.
}
