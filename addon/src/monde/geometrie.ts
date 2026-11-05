// La géométrie du monde : où sont les mini-îles, d'où l'on filme, où le joueur apparaît. Des fonctions pures sur les
// chiffres de definitions/iles.json et sur l'origine d'archipel donnée par le service (CompanyState.island).
// Les axes de Minecraft : x croît vers l'est, z vers le sud ; l'angle 0 est l'est, 90 le sud.
//
// Chaque mini-île a son repère : « avant » va vers le centre de l'île principale, « droite » est à droite quand on
// regarde vers l'avant. C'est dans ce repère que la mise en scène est écrite (cinématique, place d'Oooscar).

import type { IlesDefinitions } from "../generated/definitions";

export interface Xz {
  readonly x: number;
  readonly z: number;
}

export interface Pos {
  readonly x: number;
  readonly y: number;
  readonly z: number;
}

/** Une boîte de blocs, coins inclus. */
export interface Box {
  readonly from: Pos;
  readonly to: Pos;
}

export type UsineIle = IlesDefinitions["usines"][number];

export function usineIle(defs: IlesDefinitions, id: string): UsineIle | undefined {
  return defs.usines.find((usine) => usine.id === id);
}

/** Le centre de l'île principale (un bloc). */
export function mainCenter(origin: Xz, defs: IlesDefinitions): Xz {
  return { x: origin.x + defs.centreDansLaCase.x, z: origin.z + defs.centreDansLaCase.z };
}

/** Le bloc central de la mini-île d'une usine : sur le carré de l'anneau, dans la direction de son angle. */
export function islandCenter(origin: Xz, usine: Pick<UsineIle, "angle">, defs: IlesDefinitions): Xz {
  const centre = mainCenter(origin, defs);
  const rad = (usine.angle * Math.PI) / 180;
  const dx = Math.cos(rad);
  const dz = Math.sin(rad);
  const echelle = defs.anneau.demiCote / Math.max(Math.abs(dx), Math.abs(dz));
  return { x: centre.x + Math.round(dx * echelle), z: centre.z + Math.round(dz * echelle) };
}

/** Le repère d'une mini-île : le bloc central, « avant » (vers l'île principale) et « droite », en vecteurs unitaires. */
export interface Frame {
  readonly center: Xz;
  readonly avantX: number;
  readonly avantZ: number;
  readonly droiteX: number;
  readonly droiteZ: number;
}

export function frameOf(center: Xz, main: Xz): Frame {
  const dx = main.x - center.x;
  const dz = main.z - center.z;
  const longueur = Math.hypot(dx, dz);
  const avantX = longueur === 0 ? 1 : dx / longueur;
  const avantZ = longueur === 0 ? 0 : dz / longueur;
  // Quand on regarde vers (avantX, avantZ), la droite est à (-avantZ, avantX) : vers l'ouest, la droite est le nord.
  return { center, avantX, avantZ, droiteX: 0 - avantZ, droiteZ: avantX };
}

export function frameOfIsland(origin: Xz, usine: Pick<UsineIle, "angle">, defs: IlesDefinitions): Frame {
  return frameOf(islandCenter(origin, usine, defs), mainCenter(origin, defs));
}

/** Le point (x, z) à `avant` blocs vers l'île principale et `droite` blocs à droite du centre, en coordonnées de bloc. */
export function inFrame(frame: Frame, avant: number, droite: number): Xz {
  return {
    x: frame.center.x + avant * frame.avantX + droite * frame.droiteX,
    z: frame.center.z + avant * frame.avantZ + droite * frame.droiteZ,
  };
}

/** L'altitude où l'on se tient sur une plateforme : au-dessus du bloc de la face du dessus. */
export function surfaceY(defs: IlesDefinitions): number {
  return defs.hauteur + 1;
}

/** Un point de la mise en scène en coordonnées du monde (centre du bloc visé, `haut` au-dessus du sol de l'île). */
export function viewPoint(frame: Frame, vue: { avant: number; droite: number; haut: number }, defs: IlesDefinitions): Pos {
  const xz = inFrame(frame, vue.avant, vue.droite);
  return { x: xz.x + 0.5, y: surfaceY(defs) + vue.haut, z: xz.z + 0.5 };
}

/** Le bloc d'une position, au centre duquel se tenir. */
function standingAt(xz: Xz, defs: IlesDefinitions): Pos {
  return { x: Math.round(xz.x) + 0.5, y: surfaceY(defs), z: Math.round(xz.z) + 0.5 };
}

export interface Arrival {
  readonly factoryId: string;
  /** Où le joueur apparaît, pieds au sol. */
  readonly position: Pos;
  /** Ce qu'il regarde en arrivant : le bâtiment, à hauteur d'yeux. */
  readonly facing: Pos;
  /** Où se tient Oooscar. */
  readonly oooscar: Pos;
  readonly frame: Frame;
}

/** Le point d'apparition du joueur : sur la mini-île de départ, du côté de l'île principale, face au bâtiment. */
export function arrivalOf(origin: Xz, defs: IlesDefinitions): Arrival | undefined {
  const usine = usineIle(defs, defs.apparition.usine);
  if (usine === undefined) return undefined;
  const frame = frameOfIsland(origin, usine, defs);
  const { distance, oooscar } = defs.apparition;
  return {
    factoryId: usine.id,
    position: standingAt(inFrame(frame, distance, 0), defs),
    facing: { x: frame.center.x + 0.5, y: surfaceY(defs) + 1.6, z: frame.center.z + 0.5 },
    oooscar: standingAt(inFrame(frame, oooscar.avant, oooscar.droite), defs),
    frame,
  };
}

/** Les rangées d'un disque de rayon `radius` : pour chaque décalage `dz`, la demi-largeur en x. Rond pour les petits rayons aussi. */
export function discRows(radius: number): Array<{ readonly dz: number; readonly demiLargeur: number }> {
  const rows: Array<{ dz: number; demiLargeur: number }> = [];
  const limite = radius * radius + radius;
  for (let dz = -radius; dz <= radius; dz += 1) {
    const reste = limite - dz * dz;
    if (reste < 0) continue;
    rows.push({ dz, demiLargeur: Math.floor(Math.sqrt(reste)) });
  }
  return rows;
}

/** Les cellules (dx, dz) d'un disque de rayon `radius`, dans l'ordre des rangées. */
export function discCells(radius: number): Array<{ readonly dx: number; readonly dz: number }> {
  return discRows(radius).flatMap(({ dz, demiLargeur }) => {
    const cells: Array<{ dx: number; dz: number }> = [];
    for (let dx = -demiLargeur; dx <= demiLargeur; dx += 1) cells.push({ dx, dz });
    return cells;
  });
}

/** Où est la porte d'un bâtiment : sur la face tournée vers l'île principale (l'axe où l'avant est le plus marqué). */
export interface DoorSide {
  readonly axis: "x" | "z";
  /** +1 : la porte est du côté des x ou z croissants du bâtiment ; -1 : de l'autre côté. */
  readonly sign: 1 | -1;
}

export function doorSide(frame: Pick<Frame, "avantX" | "avantZ">): DoorSide {
  if (Math.abs(frame.avantX) >= Math.abs(frame.avantZ)) return { axis: "x", sign: frame.avantX >= 0 ? 1 : -1 };
  return { axis: "z", sign: frame.avantZ >= 0 ? 1 : -1 };
}

/** L'emprise d'un bâtiment centré en (cx, cz) : coins inclus. Largeur et profondeur impaires. */
export function footprint(center: Xz, usine: Pick<UsineIle, "largeur" | "profondeur">): { x0: number; x1: number; z0: number; z1: number } {
  const demiX = Math.floor(usine.largeur / 2);
  const demiZ = Math.floor(usine.profondeur / 2);
  return { x0: center.x - demiX, x1: center.x + demiX, z0: center.z - demiZ, z1: center.z + demiZ };
}

/** Un hachage entier stable d'un texte (FNV-1a 32 bits) : le « hasard » des ruines, le même à chaque pose. */
export function hash32(text: string): number {
  let hash = 0x811c9dc5;
  for (let index = 0; index < text.length; index += 1) {
    hash ^= text.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return hash >>> 0;
}
