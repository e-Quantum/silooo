package com.silooo.core.session

import com.silooo.core.api.CompanyState
import com.silooo.core.api.Factory
import com.silooo.core.api.Island

/**
 * Le schéma `CompanyState` du contrat, bâti à partir d'une ligne de `companies`, de ses usines et des chiffres du
 * moteur d'économie. `join`, `getCompany`, `collect`, `buyLevels` et `restoreStep` rendent tous le même état :
 * il se construit ici, une fois, pour qu'aucune route n'écrive sa propre copie des champs.
 *
 * Rien ici n'est calculé : chaque chiffre vient de [figures] (le moteur) ou de la base ([company], [factories],
 * [zonesOwned]).
 */
fun buildCompanyState(
    company: CompanyRecord,
    factories: List<FactoryRecord>,
    figures: CompanyFigures,
    zonesOwned: List<Int>,
): CompanyState = CompanyState(
    companyId = company.id,
    name = company.name,
    era = company.era,
    stars = company.stars,
    courseNumber = company.courseNumber,
    donar = company.donar,
    donarEarned = company.donarEarned,
    ratePerHour = figures.ratePerHour,
    multiplier = figures.multiplier,
    threshold = figures.threshold,
    factories = factories.map { factory ->
        val numbers = figures.forFactory(factory.factoryId)
        Factory(
            factoryId = factory.factoryId,
            state = factory.state,
            restoreStep = factory.restoreStep,
            level = factory.level,
            ratePerHour = numbers.ratePerHour,
            nextCost = numbers.nextCost,
            nextMilestone = numbers.nextMilestone,
            incidentId = factory.openIncidentId,
            incidentKind = factory.incidentKind,
            stoppedNoPower = numbers.stoppedNoPower,
        )
    },
    zonesOwned = zonesOwned,
    staff = figures.staff,
    island = Island(company.island.x, company.island.z),
    version = company.version,
)
