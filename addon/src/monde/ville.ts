// Le plan de blocs de l'île principale : la plateforme de chaque zone que l'entreprise possède, et les bâtiments de sa
// ville en préfabriqués simples. Des fonctions pures qui rendent des segments d'opérations (remplir une boîte, poser un
// bloc, écrire sur une enseigne). Le constructeur les exécute, les tests les rejouent sur un faux monde.
//
// Rien n'est décidé ici. Ce que l'île contient vient de `CompanyState.city` (zones possédées, bâtiments, leur niveau,
// leur parcelle, leur rotation) ; où se trouve chaque zone vient de definitions/ville.json (`zones.ordre`) ; la
// plateforme (couches, matériaux, hauteur) vient de definitions/iles.json, la même que celle des mini-îles. Seul l'aspect
// d'un bâtiment (sa taille et son matériau) est écrit ici, dans `GABARITS` : le fichier de données ne le dit pas encore
// (addon/README.md, « Ce qui manque »). Les coordonnées du service sont en blocs depuis le coin de l'île principale.
//
// Une parcelle (un chunk, 16 blocs) reçoit un bâtiment de 11 blocs au plus, centré : l'enseigne, un bloc dehors, et la
// place où l'on se tient devant la porte, deux blocs dehors, restent sur la parcelle quelle que soit la rotation.

import type { CityBuildingId, IlesDefinitions, VilleDefinitions } from "../generated/definitions";
import { ZONE_MAX, type BuildingRotation, type CityBuilding, type CityState } from "../protocol";
import { batimentName, bonusLine, VILLE_TEXTES } from "../ui/textes";
import { surfaceY, type Box, type Pos, type Xz } from "./geometrie";
import type { BlockStates, Op } from "./plan";

/** Un morceau de chantier : les opérations, et la boîte à charger pour les exécuter. */
export interface Segment {
  readonly box: Box;
  readonly ops: readonly Op[];
}

function at(x: number, y: number, z: number): Pos {
  return { x, y, z };
}

function fill(from: Pos, to: Pos, block: string): Op {
  return { kind: "fill", from, to, block };
}

// ---------------------------------------------------------------- où est quoi

/** Le coin de l'île principale (le plus petit x et le plus petit z), d'où partent les coordonnées du service. */
export function cityCorner(origin: Xz, defs: IlesDefinitions): Xz {
  return {
    x: origin.x + defs.centreDansLaCase.x - defs.anneau.ilePrincipaleDemiCote,
    z: origin.z + defs.centreDansLaCase.z - defs.anneau.ilePrincipaleDemiCote,
  };
}

/** Combien de zones l'entreprise possède : toutes quand le service ne propose plus de zone à acheter, sinon celles avant la prochaine. */
export function ownedZoneCount(city: CityState): number {
  return city.nextZone === null ? ZONE_MAX : city.nextZone.zoneIndex - 1;
}

/** Le côté d'une zone, en blocs. */
export function zoneSize(ville: VilleDefinitions): number {
  return ville.zones.tailleChunks * ville.ile.blocsParChunk;
}

/** Le côté d'une parcelle (la place d'un bâtiment), en blocs. */
export function parcelSize(ville: VilleDefinitions): number {
  return ville.ile.parcelleChunks * ville.ile.blocsParChunk;
}

/** Le coin d'une zone (de 1 à 16) dans le monde, ou undefined si `zones.ordre` ne la connaît pas. */
export function zoneCorner(origin: Xz, defs: IlesDefinitions, ville: VilleDefinitions, zoneIndex: number): Xz | undefined {
  const entry = ville.zones.ordre[zoneIndex - 1];
  if (entry === undefined) return undefined;
  const corner = cityCorner(origin, defs);
  const size = zoneSize(ville);
  return { x: corner.x + entry.colonne * size, z: corner.z + entry.rangee * size };
}

/** Ce que le joueur occupe de l'île principale, vu de sa position : une parcelle, ou rien. */
export type ParcelReading = { readonly kind: "on"; readonly x: number; readonly z: number } | { readonly kind: "off" };

/**
 * La parcelle où se tient le joueur : sa position moins le coin de l'île principale, arrondie vers le bas à la grille
 * d'une parcelle. Rend `off` hors de l'île. Le service juge la suite (zone possédée, emplacement libre).
 */
export function parcelAt(position: Pos, origin: Xz, defs: IlesDefinitions, ville: VilleDefinitions): ParcelReading {
  const corner = cityCorner(origin, defs);
  const side = ville.zones.colonnes * zoneSize(ville);
  const step = parcelSize(ville);
  const dx = Math.floor(position.x) - corner.x;
  const dz = Math.floor(position.z) - corner.z;
  if (dx < 0 || dz < 0 || dx >= side || dz >= side) return { kind: "off" };
  return { kind: "on", x: Math.floor(dx / step) * step, z: Math.floor(dz / step) * step };
}

// ---------------------------------------------------------------- les marques

/** L'identifiant du bâtiment mairie dans le catalogue de definitions/ville.json. */
export const HALL = "mairie";

/** La clé de marque de la plateforme d'une zone. Les clés de la ville commencent par « ville: » : celles des îles sont des identifiants d'usine. */
export function zoneKey(zoneIndex: number): string {
  return `ville:zone:${zoneIndex}`;
}

/** La clé de marque d'un bâtiment, par son identifiant du service. */
export function buildingKey(buildingId: number): string {
  return `ville:b:${buildingId}`;
}

function originText(origin: Xz): string {
  return `${origin.x},${origin.z}`;
}

/** Ce qu'on retient d'une plateforme posée : l'origine d'archipel, pour la refaire si elle change. */
export function zoneMark(origin: Xz): string {
  return `ok@${originText(origin)}`;
}

/** Ce qu'on retient d'un bâtiment posé : son type, son niveau, sa parcelle, sa rotation et l'origine d'archipel. */
export function buildingMark(building: CityBuilding, origin: Xz): string {
  return `${building.buildingType}:${building.level}:${building.x},${building.z}:${building.rotation}@${originText(origin)}`;
}

// ---------------------------------------------------------------- la plateforme d'une zone

/** Les rangées d'une plateforme de zone sont posées par bandes de deux chunks : une zone chargée à la fois reste petite. */
const ZONE_BAND_CHUNKS = 2;

/**
 * La plateforme de la zone `zoneIndex` : les mêmes couches que celles des mini-îles (herbe, terre, pierre), mais carrées
 * et pleines sur toute la zone. Un segment par bande de rangées. Vide si la zone n'est pas dans `zones.ordre`.
 */
export function zoneSegments(defs: IlesDefinitions, ville: VilleDefinitions, origin: Xz, zoneIndex: number): Segment[] {
  const corner = zoneCorner(origin, defs, ville, zoneIndex);
  if (corner === undefined) return [];
  const size = zoneSize(ville);
  const band = ZONE_BAND_CHUNKS * ville.ile.blocsParChunk;
  const deepest = defs.hauteur - (defs.plateforme.couches.length - 1);
  const segments: Segment[] = [];
  for (let dz = 0; dz < size; dz += band) {
    const last = corner.z + Math.min(dz + band, size) - 1;
    const first = corner.z + dz;
    const ops = defs.plateforme.couches.map((couche, index) => {
      const y = defs.hauteur - index;
      return fill(at(corner.x, y, first), at(corner.x + size - 1, y, last), defs.materiaux[couche.materiau]);
    });
    segments.push({ box: { from: at(corner.x, deepest, first), to: at(corner.x + size - 1, defs.hauteur, last) }, ops });
  }
  return segments;
}

// ---------------------------------------------------------------- les préfabriqués

/** L'aspect d'un bâtiment : une boîte de `largeur` × `profondeur` blocs (impairs, 11 au plus) qui prend un étage tous les `niveauxParEtage` niveaux. */
interface Gabarit {
  readonly largeur: number;
  readonly profondeur: number;
  /** Hauteur des murs au niveau 1 (4 au moins : la porte fait deux blocs et l'enseigne est au-dessus). */
  readonly hauteur: number;
  readonly niveauxParEtage: number;
  readonly etage: number;
  readonly murs: "pierre" | "planches";
  /** Une lanterne sur le toit. */
  readonly lanterne: boolean;
}

const GABARITS: Partial<Readonly<Record<CityBuildingId, Gabarit>>> = {
  mairie: { largeur: 11, profondeur: 11, hauteur: 6, niveauxParEtage: 2, etage: 1, murs: "pierre", lanterne: true },
  maison: { largeur: 7, profondeur: 7, hauteur: 4, niveauxParEtage: 1, etage: 1, murs: "planches", lanterne: false },
  immeuble: { largeur: 9, profondeur: 9, hauteur: 6, niveauxParEtage: 1, etage: 2, murs: "pierre", lanterne: false },
  commerce: { largeur: 11, profondeur: 9, hauteur: 4, niveauxParEtage: 1, etage: 1, murs: "planches", lanterne: true },
  garage: { largeur: 9, profondeur: 7, hauteur: 4, niveauxParEtage: 1, etage: 1, murs: "pierre", lanterne: false },
};

/** Le type que cet add-on ne connaît pas encore : une petite boîte de planches, qui porte tout de même le nom que le service lui donne. */
const GABARIT_INCONNU: Gabarit = { largeur: 7, profondeur: 7, hauteur: 4, niveauxParEtage: 1, etage: 1, murs: "planches", lanterne: false };

/** Le type du bâtiment qui n'a pas de boîte : une bande de gravats au ras du sol. */
const ROUTE = "route";

function hasGabarit(type: string): type is CityBuildingId {
  return Object.prototype.hasOwnProperty.call(GABARITS, type);
}

/** Vrai si le type a un aspect connu de cet add-on (une boîte, ou la route). Le constructeur le dit une fois au journal sinon. */
export function isKnownBuilding(type: string): boolean {
  return type === ROUTE || hasGabarit(type);
}

function gabaritOf(type: string): Gabarit {
  return hasGabarit(type) ? (GABARITS[type] ?? GABARIT_INCONNU) : GABARIT_INCONNU;
}

/** Le côté où est la porte, pour une rotation : 0 au sud (z croissant), 90 à l'ouest, 180 au nord, 270 à l'est. */
function doorOf(rotation: BuildingRotation): { readonly axis: "x" | "z"; readonly sign: 1 | -1 } {
  switch (rotation) {
    case 0:
      return { axis: "z", sign: 1 };
    case 90:
      return { axis: "x", sign: -1 };
    case 180:
      return { axis: "z", sign: -1 };
    case 270:
      return { axis: "x", sign: 1 };
  }
}

interface Layout {
  readonly parcel: Xz;
  readonly x0: number;
  readonly x1: number;
  readonly z0: number;
  readonly z1: number;
  readonly cx: number;
  readonly cz: number;
  readonly wallTop: number;
  readonly door: { readonly axis: "x" | "z"; readonly sign: 1 | -1 };
  readonly doorX: number;
  readonly doorZ: number;
  readonly gabarit: Gabarit;
}

function layoutOf(origin: Xz, defs: IlesDefinitions, ville: VilleDefinitions, building: CityBuilding): Layout {
  const corner = cityCorner(origin, defs);
  const parcel = { x: corner.x + building.x, z: corner.z + building.z };
  const gabarit = gabaritOf(building.buildingType);
  const turned = building.rotation === 90 || building.rotation === 270;
  const width = turned ? gabarit.profondeur : gabarit.largeur;
  const depth = turned ? gabarit.largeur : gabarit.profondeur;
  const middle = Math.floor((parcelSize(ville) - 1) / 2);
  const cx = parcel.x + middle;
  const cz = parcel.z + middle;
  const x0 = cx - (width - 1) / 2;
  const x1 = cx + (width - 1) / 2;
  const z0 = cz - (depth - 1) / 2;
  const z1 = cz + (depth - 1) / 2;
  const door = doorOf(building.rotation);
  const floors = Math.floor((Math.max(1, building.level) - 1) / gabarit.niveauxParEtage);
  return {
    parcel,
    x0,
    x1,
    z0,
    z1,
    cx,
    cz,
    wallTop: defs.hauteur + gabarit.hauteur + floors * gabarit.etage,
    door,
    doorX: door.axis === "x" ? (door.sign > 0 ? x1 : x0) : cx,
    doorZ: door.axis === "z" ? (door.sign > 0 ? z1 : z0) : cz,
    gabarit,
  };
}

/** Le texte de l'enseigne : le nom, le niveau, puis le bonus tel que le service le donne (rien pour la mairie). */
function signLines(building: CityBuilding): string {
  const lines = [batimentName(building.buildingType), VILLE_TEXTES.enseigne.niveau(String(building.level))];
  const bonus = bonusLine(building.bonusType, building.bonus);
  if (bonus !== undefined) lines.push(bonus);
  return lines.join("\n");
}

/** La direction d'une enseigne murale (`facing_direction`) : 2 nord, 3 sud, 4 ouest, 5 est. */
function signFacing(door: Layout["door"]): number {
  if (door.axis === "z") return door.sign > 0 ? 3 : 2;
  return door.sign > 0 ? 5 : 4;
}

/**
 * Une route : une bande de gravats au ras du sol, le long de l'axe de la rotation, au milieu de la parcelle. Un niveau de
 * plus l'élargit de deux blocs (huit au plus), toujours autour du même axe : le niveau suivant recouvre le précédent.
 */
function roadSegments(defs: IlesDefinitions, ville: VilleDefinitions, origin: Xz, building: CityBuilding): Segment[] {
  const corner = cityCorner(origin, defs);
  const parcel = { x: corner.x + building.x, z: corner.z + building.z };
  const side = parcelSize(ville);
  const half = Math.min(Math.max(1, building.level), 4);
  const middle = Math.floor((side - 1) / 2);
  const along = building.rotation === 0 || building.rotation === 180 ? "z" : "x";
  const top = defs.hauteur;
  const from = along === "z" ? at(parcel.x + middle + 1 - half, top, parcel.z) : at(parcel.x, top, parcel.z + middle + 1 - half);
  const to = along === "z" ? at(parcel.x + middle + half, top, parcel.z + side - 1) : at(parcel.x + side - 1, top, parcel.z + middle + half);
  return [{ box: { from, to }, ops: [fill(from, to, defs.materiaux.gravats)] }];
}

/**
 * Un bâtiment préfabriqué sur sa parcelle : on dégage la parcelle, puis quatre murs pleins, la porte (deux blocs de haut)
 * sur la face que dit la rotation, un toit de dalles, une lanterne pour certains, et l'enseigne au-dessus de la porte.
 * La route n'a ni mur ni enseigne : une bande de gravats.
 */
export function buildingSegments(defs: IlesDefinitions, ville: VilleDefinitions, origin: Xz, building: CityBuilding): Segment[] {
  if (building.buildingType === ROUTE) return roadSegments(defs, ville, origin, building);
  const m = defs.materiaux;
  const layout = layoutOf(origin, defs, ville, building);
  const { parcel, x0, x1, z0, z1, wallTop, door, doorX, doorZ, gabarit } = layout;
  const side = parcelSize(ville);
  const floor = defs.hauteur + 1;
  const wall = gabarit.murs === "pierre" ? m.pierre : m.planches;
  const ops: Op[] = [];

  ops.push(fill(at(parcel.x, floor, parcel.z), at(parcel.x + side - 1, wallTop + 3, parcel.z + side - 1), m.vide));
  ops.push(fill(at(x0, floor, z0), at(x1, wallTop, z0), wall));
  ops.push(fill(at(x0, floor, z1), at(x1, wallTop, z1), wall));
  ops.push(fill(at(x0, floor, z0), at(x0, wallTop, z1), wall));
  ops.push(fill(at(x1, floor, z0), at(x1, wallTop, z1), wall));
  ops.push(fill(at(doorX, floor, doorZ), at(doorX, floor + 1, doorZ), m.vide));
  ops.push(fill(at(x0, wallTop + 1, z0), at(x1, wallTop + 1, z1), m.toit));
  if (gabarit.lanterne) ops.push({ kind: "set", at: at(layout.cx, wallTop + 2, layout.cz), block: m.lumiere });

  const signAt = at(door.axis === "x" ? doorX + door.sign : doorX, floor + 2, door.axis === "z" ? doorZ + door.sign : doorZ);
  const states: BlockStates = { facing_direction: signFacing(door) };
  ops.push({ kind: "set", at: signAt, block: m.enseigne, states });
  ops.push({ kind: "text", at: signAt, text: signLines(building) });

  const box = { from: at(parcel.x, defs.hauteur, parcel.z), to: at(parcel.x + side - 1, wallTop + 3, parcel.z + side - 1) };
  return [{ box, ops }];
}

/** Où se tenir devant la porte d'un bâtiment (deux blocs dehors) et ce qu'il faut regarder : la porte, à hauteur d'yeux. */
export function doorArrival(
  origin: Xz,
  defs: IlesDefinitions,
  ville: VilleDefinitions,
  building: CityBuilding,
): { readonly position: Pos; readonly facing: Pos } {
  const { door, doorX, doorZ } = layoutOf(origin, defs, ville, building);
  const outX = door.axis === "x" ? doorX + 2 * door.sign : doorX;
  const outZ = door.axis === "z" ? doorZ + 2 * door.sign : doorZ;
  return {
    position: at(outX + 0.5, surfaceY(defs), outZ + 0.5),
    facing: at(doorX + 0.5, surfaceY(defs) + 1.6, doorZ + 0.5),
  };
}

// ---------------------------------------------------------------- les travaux de la ville

/** Un travail du constructeur : une plateforme de zone ou un bâtiment, avec la marque qu'on aura une fois posé. */
export interface CityWork {
  readonly markKey: string;
  readonly mark: string;
  readonly kind: "zone" | "bâtiment";
  /** Le numéro de la zone ou l'identifiant du bâtiment, pour le journal. */
  readonly ref: string;
  /** La clé de marque d'un travail à finir avant celui-ci : un bâtiment attend la plateforme de sa zone. */
  readonly after: string | undefined;
  readonly segments: () => Segment[];
}

/**
 * Tout ce que la ville de l'entreprise demande : la plateforme de chaque zone possédée, dans l'ordre des zones, puis les
 * bâtiments, la mairie d'abord. Le constructeur compare chaque marque à ce qu'il a déjà posé et ne refait que le reste.
 */
export function cityWorks(defs: IlesDefinitions, ville: VilleDefinitions, origin: Xz, city: CityState): CityWork[] {
  const works: CityWork[] = [];
  const owned = Math.min(ownedZoneCount(city), ville.zones.ordre.length);
  for (let zoneIndex = 1; zoneIndex <= owned; zoneIndex += 1) {
    works.push({
      markKey: zoneKey(zoneIndex),
      mark: zoneMark(origin),
      kind: "zone",
      ref: String(zoneIndex),
      after: undefined,
      segments: () => zoneSegments(defs, ville, origin, zoneIndex),
    });
  }
  const buildings = [...city.buildings].sort((a, b) => Number(b.buildingType === HALL) - Number(a.buildingType === HALL));
  for (const building of buildings) {
    works.push({
      markKey: buildingKey(building.buildingId),
      mark: buildingMark(building, origin),
      kind: "bâtiment",
      ref: String(building.buildingId),
      after: zoneKey(building.zoneIndex),
      segments: () => buildingSegments(defs, ville, origin, building),
    });
  }
  return works;
}
