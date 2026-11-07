// Le plan de blocs d'une mini-île : de quoi la construire, sans rien poser. Des fonctions pures qui rendent une liste
// d'opérations (remplir une boîte, poser un bloc, écrire sur une enseigne). Le constructeur les exécute, les tests les
// rejouent sur un faux monde. Tous les chiffres viennent de definitions/iles.json.
//
// Une île a deux parties. La base (la plateforme, ses couches de terre et de pierre) se pose une fois. L'état (le
// bâtiment, ses abords, l'enseigne) se refait à chaque changement d'état de l'usine : il dégage d'abord sa boîte, puis
// redessine la face du dessus, puis le bâtiment tel que l'état le demande.

import type { IlesDefinitions } from "../generated/definitions";
import type { FactoryState } from "../protocol";
import { factoryName } from "../ui/textes";
import { discCells, discRows, doorSide, footprint, frameOfIsland, hash32, islandCenter, islandRadius, type Box, type Pos, type UsineIle, type Xz } from "./geometrie";
import { carriereOps } from "./carriere";

export type BlockStates = Readonly<Record<string, string | number | boolean>>;

export type Op =
  | { readonly kind: "fill"; readonly from: Pos; readonly to: Pos; readonly block: string }
  | { readonly kind: "set"; readonly at: Pos; readonly block: string; readonly states?: BlockStates }
  | { readonly kind: "text"; readonly at: Pos; readonly text: string }
  | { readonly kind: "structure"; readonly at: Pos; readonly id: string };

export interface IslandPlan {
  /** Tout ce que le plan touche : la zone à charger. */
  readonly box: Box;
  /** La plateforme : à poser une fois par île. */
  readonly base: readonly Op[];
  /** Le bâtiment dans son état : à refaire quand l'état change. */
  readonly state: readonly Op[];
  readonly center: Xz;
  /** Préfabriqué complet quand le monde sait charger les structures du pack. */
  readonly structure?: { readonly id: string; readonly at: Pos };
}

function fill(from: Pos, to: Pos, block: string): Op {
  return { kind: "fill", from, to, block };
}

function at(x: number, y: number, z: number): Pos {
  return { x, y, z };
}

/** Le texte de l'enseigne : sur deux lignes dès que le nom dépasse la largeur d'une ligne. */
export function signText(name: string, lineWidth = 12): string {
  if (name.length <= lineWidth) return name;
  const cut = name.lastIndexOf(" ", lineWidth);
  if (cut <= 0) return name;
  return `${name.slice(0, cut)}\n${name.slice(cut + 1)}`;
}

/** Les blocs de la plateforme, couche par couche, du dessus vers le bas. */
function baseOps(defs: IlesDefinitions, center: Xz, islandRadius = defs.plateforme.rayon): Op[] {
  const ops: Op[] = [];
  defs.plateforme.couches.forEach((couche, index) => {
    const radius = islandRadius - couche.retrait;
    const y = defs.hauteur - index;
    const block = defs.materiaux[couche.materiau];
    for (const { dz, demiLargeur } of discRows(radius)) {
      ops.push(fill(at(center.x - demiLargeur, y, center.z + dz), at(center.x + demiLargeur, y, center.z + dz), block));
    }
  });
  return ops;
}

/** Vrai pour une part `percent` des clés, toujours les mêmes : le « hasard » d'une ruine ne change pas d'une pose à l'autre. */
function picked(key: string, percent: number): boolean {
  return hash32(key) % 100 < percent;
}

function stateOps(defs: IlesDefinitions, usine: UsineIle, center: Xz, state: FactoryState, door: { axis: "x" | "z"; sign: 1 | -1 }): Op[] {
  const m = defs.materiaux;
  const top = defs.hauteur;
  const floor = top + 1;
  const wallTop = top + usine.hauteur;
  const fp = footprint(center, usine);
  const wall = m[usine.murs];
  const debris = usine.murs === "pierre" ? m.gravats : m.mousse;
  const ops: Op[] = [];

  // 1. Dégager la boîte du bâtiment et de ses abords, jusqu'au-dessus du toit et de la lanterne.
  ops.push(fill(at(fp.x0 - 1, floor, fp.z0 - 1), at(fp.x1 + 1, wallTop + 2, fp.z1 + 1), m.vide));

  // 2. Redessiner la face du dessus : l'herbe entière, puis les plaques arrachées d'une ruine.
  const dessus = defs.plateforme.couches[0];
  if (dessus !== undefined) {
    const radius = defs.plateforme.rayon - dessus.retrait;
    for (const { dz, demiLargeur } of discRows(radius)) {
      ops.push(fill(at(center.x - demiLargeur, top, center.z + dz), at(center.x + demiLargeur, top, center.z + dz), m[dessus.materiau]));
    }
    if (state === "EN_RUINE") {
      for (const { dx, dz } of discCells(radius)) {
        if (picked(`${usine.id}:sol:${dx}:${dz}`, defs.ruine.partSolAbimePourcent)) {
          ops.push({ kind: "set", at: at(center.x + dx, top, center.z + dz), block: m.mousse });
        }
      }
    }
  }

  // 3. Les quatre murs, pleins ; 4. la porte, deux blocs de haut, dans le mur tourné vers l'île principale.
  ops.push(fill(at(fp.x0, floor, fp.z0), at(fp.x1, wallTop, fp.z0), wall));
  ops.push(fill(at(fp.x0, floor, fp.z1), at(fp.x1, wallTop, fp.z1), wall));
  ops.push(fill(at(fp.x0, floor, fp.z0), at(fp.x0, wallTop, fp.z1), wall));
  ops.push(fill(at(fp.x1, floor, fp.z0), at(fp.x1, wallTop, fp.z1), wall));
  const doorX = door.axis === "x" ? (door.sign > 0 ? fp.x1 : fp.x0) : center.x;
  const doorZ = door.axis === "z" ? (door.sign > 0 ? fp.z1 : fp.z0) : center.z;
  ops.push(fill(at(doorX, floor, doorZ), at(doorX, floor + 1, doorZ), m.vide));

  // 5. Les murs abîmés d'une ruine : une part des blocs manque ou n'est plus que des gravats. La porte, le bloc
  //    sous l'enseigne et le premier rang des coins restent debout.
  if (state === "EN_RUINE") {
    for (let y = floor; y <= wallTop; y += 1) {
      for (let x = fp.x0; x <= fp.x1; x += 1) {
        for (const z of [fp.z0, fp.z1]) damage(x, y, z);
      }
      for (let z = fp.z0 + 1; z < fp.z1; z += 1) {
        for (const x of [fp.x0, fp.x1]) damage(x, y, z);
      }
    }
  }
  function damage(x: number, y: number, z: number): void {
    const onDoor = x === doorX && z === doorZ && y <= floor + 2;
    if (onDoor) return;
    if (y === floor && (x === fp.x0 || x === fp.x1) && (z === fp.z0 || z === fp.z1)) return;
    const hash = hash32(`${usine.id}:mur:${x - center.x}:${y - top}:${z - center.z}`);
    if (hash % 100 >= defs.ruine.partMursAbimesPourcent) return;
    ops.push({ kind: "set", at: at(x, y, z), block: (hash >>> 8) % 2 === 0 ? m.vide : debris });
  }

  // 6. Ce que l'état ajoute : rien pour une ruine, des poteaux de chantier, ou un toit et une lanterne.
  if (state === "REMISE_EN_ETAT") {
    for (const x of [fp.x0 - 1, fp.x1 + 1]) {
      for (const z of [fp.z0 - 1, fp.z1 + 1]) ops.push(fill(at(x, floor, z), at(x, wallTop + 1, z), m.echafaudage));
    }
  }
  if (state === "ACTIVE") {
    ops.push(fill(at(fp.x0, wallTop + 1, fp.z0), at(fp.x1, wallTop + 1, fp.z1), m.toit));
    ops.push({ kind: "set", at: at(center.x, wallTop + 2, center.z), block: m.lumiere });
  }

  // 7. L'enseigne, au-dessus de la porte, côté dehors : le nom de l'usine, quel que soit l'état.
  const signX = door.axis === "x" ? doorX + door.sign : doorX;
  const signZ = door.axis === "z" ? doorZ + door.sign : doorZ;
  const facing = door.axis === "x" ? (door.sign > 0 ? 5 : 4) : door.sign > 0 ? 3 : 2;
  const signAt = at(signX, floor + 2, signZ);
  ops.push({ kind: "set", at: signAt, block: m.enseigne, states: { facing_direction: facing } });
  ops.push({ kind: "text", at: signAt, text: signText(factoryName(usine.id)) });
  return ops;
}

/** Le plan de la mini-île d'une usine pour une origine d'archipel et un état donnés. */
export function planIsland(defs: IlesDefinitions, usine: UsineIle, origin: Xz, state: FactoryState): IslandPlan {
  const center = islandCenter(origin, usine, defs);
  const frame = frameOfIsland(origin, usine, defs);
  const radius = islandRadius(defs, usine);
  const reach = Math.max(radius, Math.floor(usine.largeur / 2) + 2, Math.floor(usine.profondeur / 2) + 2);
  const deepest = defs.hauteur - (defs.plateforme.couches.length - 1);
  return {
    center,
    box: { from: at(center.x - reach, deepest, center.z - reach), to: at(center.x + reach, defs.hauteur + usine.hauteur + 3, center.z + reach) },
    base: baseOps(defs, center, radius),
    ...(usine.id === defs.apparition.usine && state === "ACTIVE" ? { structure: { id: defs.atelier.structure, at: at(center.x - reach, deepest, center.z - reach) } } : {}),
    state: usine.id === defs.apparition.usine ? carriereOps(defs, frame, state, radius) : stateOps(defs, usine, center, state, doorSide(frame)),
  };
}
