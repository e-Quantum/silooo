package com.silooo.core.discord

import com.silooo.core.Dependencies
import com.silooo.core.economy.CompanyService
import com.silooo.core.findings.FindingService
import com.silooo.core.mission.MissionService

/** Même service de lecture que les routes de jeu, sans recalcul des prix ou de la progression dans Discord. */
internal fun gameCompanyService(game: Dependencies): CompanyService = CompanyService(
    game.sessionStore, game.companyStore, game.economy, game.economyFigures,
    MissionService(game.definitions, game.missionStore, game.pass),
    FindingService(game.definitions, game.findingStore), game.pass,
)
