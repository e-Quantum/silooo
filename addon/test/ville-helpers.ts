// Faux partagés par les tests de la ville : un bâtiment et une ville comme le service les rend. Rien ici n'importe @minecraft/*.

import type { CityBonuses, CityBuilding, CityState } from "../src/protocol";

/** La mairie telle que le service la pose à l'achat de la première zone : niveau 1 sur 16, au coin de départ, sans bonus. */
export function cityBuilding(overrides: Partial<CityBuilding> = {}): CityBuilding {
  return {
    buildingId: 1,
    buildingType: "mairie",
    level: 1,
    maxLevel: 16,
    zoneIndex: 1,
    x: 192,
    z: 192,
    rotation: 0,
    bonusType: null,
    bonus: null,
    nextLevel: { priceLingots: 2, mairieLevelRequired: 1 },
    ...overrides,
  };
}

/** Une maison de niveau 1 dans la zone 1, qui loge 1 personne. */
export function house(overrides: Partial<CityBuilding> = {}): CityBuilding {
  return cityBuilding({
    buildingId: 2,
    buildingType: "maison",
    level: 1,
    maxLevel: 3,
    x: 128,
    z: 128,
    bonusType: "LOGEMENTS",
    bonus: 1,
    nextLevel: { priceLingots: 6, mairieLevelRequired: 3 },
    ...overrides,
  });
}

export function noBonuses(overrides: Partial<CityBonuses> = {}): CityBonuses {
  return { housingPlaces: 0, housedStaff: 0, donarProductionPercent: 0, deliverySpeedPercent: 0, ...overrides };
}

/** Une ville avec sa première zone achetée : la mairie seule, et la zone 2 à acheter. */
export function cityState(overrides: Partial<CityState> = {}): CityState {
  return {
    mairieLevel: 1,
    nextZone: { zoneIndex: 2, priceLingots: 5, mairieLevelRequired: 2, activeFactoriesRequired: 0 },
    buildings: [cityBuilding()],
    bonuses: noBonuses(),
    ...overrides,
  };
}

/** Une ville avant la première zone : pas de bâtiment, la zone 1 à acheter avec ses deux usines actives. */
export function emptyCity(overrides: Partial<CityState> = {}): CityState {
  return cityState({
    mairieLevel: 1,
    nextZone: { zoneIndex: 1, priceLingots: 1, mairieLevelRequired: 1, activeFactoriesRequired: 2 },
    buildings: [],
    ...overrides,
  });
}
