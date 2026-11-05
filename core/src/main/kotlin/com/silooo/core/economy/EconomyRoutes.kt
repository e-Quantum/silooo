package com.silooo.core.economy

import com.silooo.core.Dependencies
import io.ktor.server.routing.Route

/**
 * Domaine « economy » : l'entreprise, ses usines, ses monnaies.
 *
 * Déjà là : le chargement de `definitions/` (paquet `definitions`, aucun chiffre du jeu dans le code) et le
 * moteur pur (`EconomyEngine`, `Notation`, `BuyAmount`) : prix des niveaux, production, paliers ×2,
 * multiplicateurs, hors ligne, prestige, notation. `Dependencies.economy` le donne aux routes ; les tests
 * dorés de `core/src/test/resources/golden/` le rejouent.
 *
 * Il n'y a aucune route d'économie en phase 1 : ce fichier reste le filet. Plus tard, ici :
 *  - Les routes `companies/{companyId}` : `getCompany`, `collect`, `buyLevels`, `restoreStep`,
 *    la résolution des incidents, le prestige. Chaque action porte un `requestId` (idempotence par
 *    `api_requests`), écrit avec le verrou optimiste `companies.version`, et les monnaies
 *    personnelles passent par `currency_ledger` dans la même transaction que le solde. Le moteur
 *    calcule, la route décide et écrit.
 *
 * Les autres groupes de l'OpenAPI (agence et personnel, associés, ville, commande mondiale,
 * marché, boutique, pass) auront chacun leur paquet à côté de celui-ci, quand leur phase arrivera.
 */
@Suppress("UNUSED_PARAMETER")
fun Route.economyRoutes(dependencies: Dependencies) {
    // Rien pour l'instant : voir le commentaire ci-dessus.
}
