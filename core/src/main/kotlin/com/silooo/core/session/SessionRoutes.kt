package com.silooo.core.session

import com.silooo.core.Dependencies
import io.ktor.server.routing.Route

/**
 * Domaine « session » : l'arrivée et le départ d'un joueur.
 *
 * Ce que la session « routes de session » met ici (voir la recette `silooo-route`, dans l'ordre :
 * OpenAPI, test de contrat, puis code) :
 *  - `POST /v1/session/join` (`join`) : crée le joueur (`players`) et son entreprise au premier
 *    passage, rend `JoinResponse`. Le XUID reste une chaîne dans le JSON, un `BIGINT UNSIGNED` en base.
 *  - `POST /v1/session/leave` (`leave`) : fige `players.last_seen_at`, base du calcul hors ligne.
 *  - L'idempotence par `requestId` (table `api_requests`), si une de ces routes en a besoin.
 *
 * Tant qu'une route n'est pas écrite ici, le filet de `NotDone.kt` répond 501 `PAS_FAIT`. Écrire la
 * route suffit à la remplacer : rien à retirer ailleurs.
 */
@Suppress("UNUSED_PARAMETER")
fun Route.sessionRoutes(dependencies: Dependencies) {
    // Rien pour l'instant : voir le commentaire ci-dessus.
}
