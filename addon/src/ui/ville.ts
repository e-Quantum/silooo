// Les écrans de la ville de l'île principale : la mairie et les bonus, la prochaine zone, la liste des bâtiments, la fiche
// d'un bâtiment, et la pose d'un bâtiment neuf.
//
//   MENU ── VILLE ─┬─ Acheter la zone n
//                  ├─ BÂTIMENTS ── un bâtiment ── Améliorer
//                  └─ CONSTRUIRE ── un type de bâtiment ── POSER (Poser ici, Tourner)
//
// Des fonctions pures, comme celles de menu.ts : une vue de l'entreprise (ce que le service a dit) entre, un écran sort.
// Rien n'y est calculé. Les bonus, les prix du niveau suivant, la prochaine zone viennent de `CompanyState.city`, telle
// que le service la donne ; le prix d'un bâtiment neuf (le niveau 1) vient de definitions/ville.json, comme les types
// qu'on peut poser. Le service reste juge de tout le reste : l'add-on demande, et la bulle d'Oooscar dit le refus.
// Un écran rend `undefined` quand ce qu'il montre n'existe plus (bâtiment disparu, type qu'on ne pose pas) : le parcours
// remonte alors au parent.

import type { VilleDefinitions } from "../generated/definitions";
import { VILLE } from "../monde/definitions";
import { HALL, ownedZoneCount, type ParcelReading } from "../monde/ville";
import { BUILDING_ROTATIONS, type BuildingRotation, type CityBonuses, type CityBuilding, type CityState } from "../protocol";
import type { ActionOutcome } from "./actions";
import { COLOR, colored, ICON } from "./apparence";
import { MAIN, bodyOf, button, go, lockedTile, pageOf, screenOf, type Entry, type Notice, type Screen } from "./ecran";
import type { CompanyView } from "./etat";
import { formatCount } from "./nombres";
import { MENU_TEXTES, USINES_TEXTES, VILLE_TEXTES, batimentName, bonusLine } from "./textes";

const VILLE_ROUTE = { screen: "ville" } as const;

// ---------------------------------------------------------------- les tuiles du menu et de la carte

/**
 * La tuile « Ville » du menu : ouverte dès que le service donne une ville. Avant la première zone, elle dit ce qu'il faut
 * (les usines actives que le service demande) ; ensuite, le niveau de la mairie. Sans ville dans l'état, elle est fermée
 * et dit ce qu'il faut pour la première zone, d'après definitions/ville.json.
 */
export function villeTile(view: CompanyView, ville: VilleDefinitions = VILLE): Entry {
  const city = view.state.city;
  if (city === undefined) {
    return lockedTile(MENU_TEXTES.ville, VILLE_TEXTES.tuile.verrouillee(formatCount(ville.zones.premiere.usinesActivesMin)));
  }
  const detail =
    ownedZoneCount(city) === 0 && city.nextZone !== null
      ? VILLE_TEXTES.tuile.premiere(formatCount(city.nextZone.activeFactoriesRequired))
      : VILLE_TEXTES.mairie(formatCount(city.mairieLevel));
  return { text: button(`${ICON.ville} ${MENU_TEXTES.ville}`, detail), target: go(VILLE_ROUTE) };
}

/** La mairie de la ville, si l'état en a une (elle est posée à l'achat de la première zone). */
export function hallOf(city: CityState | undefined): CityBuilding | undefined {
  return city?.buildings.find((building) => building.buildingType === HALL);
}

/**
 * La ligne « Île principale » de la carte : un voyage à la mairie quand l'entreprise a sa première zone et sa mairie, sinon
 * une tuile fermée qui dit comment l'ouvrir. La carte donne le verrou, `mairie` le niveau à écrire en détail.
 */
export function ilePrincipaleTile(view: CompanyView, label: string, lockedDetail: string): Entry {
  const city = view.state.city;
  const hall = hallOf(city);
  if (city === undefined || hall === undefined || ownedZoneCount(city) === 0) {
    return lockedTile(label, lockedDetail);
  }
  return { text: button(`${ICON.ville} ${label}`, VILLE_TEXTES.mairie(formatCount(city.mairieLevel))), target: { kind: "travelCity" } };
}

// ---------------------------------------------------------------- la ville

/** Ce que les bâtiments donnent ensemble, tel que le service le dit. Une ligne par bonus qui n'est pas nul. */
function bonusLines(bonuses: CityBonuses): string[] {
  const texts = VILLE_TEXTES.bonus;
  const lines: string[] = [];
  if (bonuses.housingPlaces > 0) lines.push(texts.logement(formatCount(bonuses.housedStaff), formatCount(bonuses.housingPlaces)));
  if (bonuses.donarProductionPercent > 0) lines.push(texts.types.PRODUCTION_DONAR(bonuses.donarProductionPercent));
  if (bonuses.deliverySpeedPercent > 0) lines.push(texts.types.LIVRAISON_VITESSE(bonuses.deliverySpeedPercent));
  return lines;
}

/** La prochaine zone : son numéro, son prix, la mairie et les usines qu'elle demande. */
function nextZoneLines(city: CityState): string[] {
  const next = city.nextZone;
  const texts = VILLE_TEXTES.zone;
  if (next === null) return [texts.toutes];
  const lines = [
    colored(COLOR.paper, texts.titre),
    `${COLOR.bold}${texts.numero(formatCount(next.zoneIndex))}${COLOR.reset}`,
    colored(COLOR.lingots, texts.prix(formatCount(next.priceLingots))),
    texts.mairieRequise(formatCount(next.mairieLevelRequired)),
  ];
  if (next.activeFactoriesRequired > 0) lines.push(texts.usinesRequises(formatCount(next.activeFactoriesRequired)));
  return lines;
}

/**
 * L'écran de la ville : le niveau de la mairie et les bonus (avant la première zone, une phrase), la prochaine zone avec son
 * bouton d'achat, puis les bâtiments et la construction. Rend undefined si l'état n'a pas de ville.
 */
export function villeScreen(view: CompanyView, notice?: Notice): Screen | undefined {
  const city = view.state.city;
  if (city === undefined) return undefined;
  const owned = ownedZoneCount(city);
  const texts = VILLE_TEXTES;

  const lines: string[] = [];
  if (owned === 0) {
    lines.push(texts.zone.aucune, "");
  } else {
    const bonuses = bonusLines(city.bonuses);
    lines.push(
      `${COLOR.bold}${texts.mairie(formatCount(city.mairieLevel))}${COLOR.reset}`,
      "",
      colored(COLOR.paper, texts.bonus.titre),
      ...(bonuses.length === 0 ? [texts.bonus.aucun] : bonuses),
      "",
    );
  }
  lines.push(...nextZoneLines(city));

  const entries: Entry[] = [];
  if (city.nextZone !== null) {
    entries.push({
      text: button(texts.zone.acheter(formatCount(city.nextZone.zoneIndex)), texts.zone.prix(formatCount(city.nextZone.priceLingots))),
      target: { kind: "buyZone", zoneIndex: city.nextZone.zoneIndex },
    });
  }
  if (owned > 0) {
    entries.push(
      {
        text: button(texts.batiments.bouton, texts.batiments.nombre(formatCount(city.buildings.length))),
        target: go({ screen: "batiments", page: 0 }),
      },
      { text: texts.construire.bouton, target: go({ screen: "construire" }) },
    );
  }
  entries.push({ text: texts.retour, target: go(MAIN) });
  return screenOf(texts.titre, bodyOf(notice, lines), entries);
}

// ---------------------------------------------------------------- les bâtiments

/** Les bâtiments dans l'ordre d'affichage : la mairie d'abord, les autres dans l'ordre du service. */
function listed(city: CityState): CityBuilding[] {
  return [...city.buildings].sort((a, b) => Number(b.buildingType === HALL) - Number(a.buildingType === HALL));
}

/** Le bonus d'un bâtiment sur une ligne, tel que le service le donne ; rien pour la mairie et les routes. */
function bonusOf(building: CityBuilding): string | undefined {
  return bonusLine(building.bonusType, building.bonus);
}

/** La liste des bâtiments, une page à la fois : le nom et le niveau, puis le bonus (ou la parcelle quand il n'y en a pas). */
export function batimentsScreen(view: CompanyView, page: number, tilesPerScreen: number, notice?: Notice): Screen | undefined {
  const city = view.state.city;
  if (city === undefined) return undefined;
  const texts = VILLE_TEXTES;
  const rows: Entry[] = listed(city).map((building) => ({
    text: button(
      `${ICON.ville} ${texts.batiments.ligne(batimentName(building.buildingType), formatCount(building.level))}`,
      bonusOf(building) ?? texts.poser.parcelle(formatCount(building.x), formatCount(building.z)),
    ),
    target: go({ screen: "batiment", buildingId: building.buildingId, page: 0 }),
  }));
  // Les tuiles savent d'où elles viennent : la fiche remonte à la page de la liste où elle a été choisie.
  const paged = pageOf(rows, page, tilesPerScreen, (target) => ({ screen: "batiments", page: target }));
  const entries = paged.entries.map((entry) =>
    entry.target.kind === "go" && entry.target.route.screen === "batiment"
      ? { ...entry, target: go({ ...entry.target.route, page: paged.current }) }
      : entry,
  );
  entries.push({ text: texts.retour, target: go(VILLE_ROUTE) });

  const lines: string[] = [];
  if (rows.length === 0) lines.push(texts.batiments.vide);
  if (paged.pages > 1) lines.push(colored(COLOR.muted, USINES_TEXTES.page(paged.current + 1, paged.pages)));
  return screenOf(texts.titre, bodyOf(notice, lines), entries);
}

/**
 * La fiche d'un bâtiment : son nom, son niveau, son bonus et sa parcelle, puis le niveau suivant (prix et mairie requise) avec
 * Améliorer, ou « Niveau maximum ». Rend undefined si le bâtiment n'est plus dans la ville.
 */
export function batimentScreen(view: CompanyView, buildingId: number, page: number, notice?: Notice): Screen | undefined {
  const building = view.state.city?.buildings.find((candidate) => candidate.buildingId === buildingId);
  if (building === undefined) return undefined;
  const texts = VILLE_TEXTES;
  const next = building.nextLevel ?? null;

  const lines = [
    `${COLOR.bold}${batimentName(building.buildingType)}${COLOR.reset}`,
    texts.fiche.niveau(formatCount(building.level), formatCount(building.maxLevel)),
  ];
  const bonus = bonusOf(building);
  if (bonus !== undefined) lines.push(bonus);
  lines.push(texts.poser.parcelle(formatCount(building.x), formatCount(building.z)), "");
  if (next === null) {
    lines.push(texts.fiche.maximum);
  } else {
    lines.push(colored(COLOR.lingots, texts.fiche.suivant(formatCount(next.priceLingots))), texts.zone.mairieRequise(formatCount(next.mairieLevelRequired)));
  }

  const entries: Entry[] = [];
  if (next !== null) {
    entries.push({
      text: button(texts.fiche.ameliorer, texts.zone.prix(formatCount(next.priceLingots))),
      target: { kind: "upgrade", buildingId },
    });
  }
  entries.push({ text: texts.retour, target: go({ screen: "batiments", page }) });
  return screenOf(texts.titre, bodyOf(notice, lines), entries);
}

// ---------------------------------------------------------------- construire

/** Les bâtiments que le joueur pose (catalogue de definitions/ville.json), avec leur niveau 1 : prix et bonus. */
function placeable(ville: VilleDefinitions): Array<{ readonly id: string; readonly price: number; readonly bonus: string | undefined }> {
  const found: Array<{ id: string; price: number; bonus: string | undefined }> = [];
  for (const building of ville.batiments) {
    const first = building.niveaux[0];
    if (!building.posable || first === undefined) continue;
    found.push({ id: building.id, price: first.prixLingots, bonus: bonusLine(building.typeBonus, first.bonus) });
  }
  return found;
}

/** Le choix du bâtiment à poser : le nom, le prix du niveau 1 et son bonus. */
export function construireScreen(notice?: Notice, ville: VilleDefinitions = VILLE): Screen {
  const texts = VILLE_TEXTES.construire;
  const entries: Entry[] = placeable(ville).map((building) => ({
    text: button(
      batimentName(building.id),
      building.bonus === undefined ? texts.detailSansBonus(formatCount(building.price)) : texts.detail(formatCount(building.price), building.bonus),
    ),
    target: go({ screen: "poser", buildingType: building.id, rotation: 0 }),
  }));
  entries.push({ text: VILLE_TEXTES.retour, target: go(VILLE_ROUTE) });
  return screenOf(texts.titre, bodyOf(notice, [texts.phrase]), entries);
}

/** La rotation d'après : un quart de tour, puis retour à zéro. */
export function nextRotation(rotation: BuildingRotation): BuildingRotation {
  const at = BUILDING_ROTATIONS.indexOf(rotation);
  return BUILDING_ROTATIONS[(at + 1) % BUILDING_ROTATIONS.length] ?? 0;
}

/**
 * La pose d'un bâtiment : le nom, le prix du niveau 1, la parcelle où se tient le joueur (ou pourquoi on ne la connaît
 * pas), le côté de la porte ou le sens de la route. `parcel` est la lecture faite à l'ouverture de l'écran ; le clic de
 * « Poser ici » la refait. Rend undefined si le type n'est pas un bâtiment qu'on pose.
 */
export function poserScreen(
  buildingType: string,
  rotation: BuildingRotation,
  parcel: ParcelReading | undefined,
  notice?: Notice,
  ville: VilleDefinitions = VILLE,
): Screen | undefined {
  const building = placeable(ville).find((candidate) => candidate.id === buildingType);
  if (building === undefined) return undefined;
  const texts = VILLE_TEXTES.poser;

  let where: string;
  if (parcel === undefined) where = texts.inconnue;
  else if (parcel.kind === "off") where = texts.ailleurs;
  else where = texts.parcelle(formatCount(parcel.x), formatCount(parcel.z));

  const lines = [
    `${COLOR.bold}${batimentName(buildingType)}${COLOR.reset}`,
    colored(COLOR.lingots, texts.prix(formatCount(building.price))),
    where,
    buildingType === "route" ? texts.sens[rotation] : texts.porte[rotation],
  ];
  const entries: Entry[] = [
    { text: texts.poser, target: { kind: "place", buildingType, rotation } },
    { text: texts.tourner, target: go({ screen: "poser", buildingType, rotation: nextRotation(rotation) }) },
    { text: VILLE_TEXTES.retour, target: go({ screen: "construire" }) },
  ];
  return screenOf(texts.titre, bodyOf(notice, lines), entries);
}

// ---------------------------------------------------------------- ce que dit Oooscar

export type VilleAction = "buyZone" | "placeBuilding" | "upgradeBuilding";

/**
 * La bulle d'un refus du service pour une action de la ville. Les codes ne disent pas la même chose selon l'action :
 * ZONE_VERROUILLEE est « il faut plus d'usines » pour la première zone, « monte la mairie » pour une autre zone ou une
 * amélioration, « zone fermée » pour une pose ; DEJA_POSSEDE est une zone déjà à soi, un bâtiment au maximum de son
 * catalogue, ou un niveau maximum. Rend undefined pour les autres refus (Lingots qui manquent, conflit) : le menu les
 * laisse à `refusalBubble`.
 */
export function villeNotice(outcome: ActionOutcome, action: VilleAction, zoneIndex?: number): Notice | undefined {
  if (outcome.kind !== "refused") return undefined;
  const bulles = VILLE_TEXTES.bulles;
  switch (outcome.reason) {
    case "ZONE_VERROUILLEE":
      if (action === "placeBuilding") return { bubble: bulles.parcelleFermee };
      return { bubble: action === "buyZone" && zoneIndex === 1 ? bulles.premiereZone : bulles.mairieBasse };
    case "EMPLACEMENT_OCCUPE":
      return action === "placeBuilding" ? { bubble: bulles.emplacementOccupe } : undefined;
    case "DEJA_POSSEDE":
      if (action === "buyZone") return { bubble: bulles.zoneDejaPossedee };
      return { bubble: action === "placeBuilding" ? bulles.maximumAtteint : bulles.niveauMax };
    default:
      return undefined;
  }
}
