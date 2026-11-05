package com.silooo.core.economy

import com.silooo.core.Dependencies
import io.ktor.server.routing.Route

/**
 * Domaine « economy » : l'entreprise, ses usines, ses monnaies.
 *
 * Déjà là :
 *  - le chargement de `definitions/` (paquet `definitions`, aucun chiffre du jeu dans le code) et le moteur pur
 *    (`EconomyEngine`, `Notation`, `BuyAmount`) : prix des niveaux, production, paliers ×2, multiplicateurs, hors
 *    ligne, prestige, notation. `Dependencies.economy` le donne aux routes ; les tests dorés de
 *    `core/src/test/resources/golden/` le rejouent ;
 *  - les routes de la phase 3, dans [companyRoutes] : `getCompany`, `collect`, `buyLevels`, `restoreStep`.
 *    Chaque action porte un `requestId` (idempotence par `api_requests`), s'écrit avec le verrou optimiste
 *    `companies.version`, et verse d'abord la production en attente. Le moteur calcule, [CompanyService] décide et écrit.
 *
 * Plus tard, ici : la résolution des incidents et le prestige. Les monnaies personnelles passeront par
 * `currency_ledger`, dans la même transaction que le solde.
 *
 * Les autres groupes de l'OpenAPI (agence et personnel, associés, ville, commande mondiale, marché, boutique, pass)
 * auront chacun leur paquet à côté de celui-ci, quand leur phase arrivera.
 */
fun Route.economyRoutes(dependencies: Dependencies) {
    companyRoutes(dependencies)
}
