// Le ménage du parcours, sans Bedrock : les blocs qu'un joueur laisse en partant. Quand il se déconnecte, son tronçon de
// monde se décharge avec lui et Bedrock refuse d'y poser de l'air ; ses blocs restaient donc en l'air pour toujours. Ils
// vont dans une file gardée par le monde (une propriété dynamique), que attente/main.ts vide en chargeant leur tronçon le
// temps de les retirer. Une fois, un balayage des premiers couloirs retire aussi ceux laissés avant que la file existe.
// Tout est pur et testé (test/attente.test.ts).

import type { Bloc } from "./parcours";
import { REGLES, type ReglesParcours } from "./regles";

/** Une boîte de blocs, coins inclus. */
export interface Zone {
  readonly from: Bloc;
  readonly to: Bloc;
}

/** La file ne grossit pas sans fin : au-delà, les plus anciens sont oubliés (le balayage reste là pour eux). */
export const FILE_MAX = 500;
const TRONCON = 16;

export function cleBloc(bloc: Bloc): string {
  return `${bloc.x},${bloc.y},${bloc.z}`;
}

function estEntier(v: unknown): v is number {
  return typeof v === "number" && Number.isInteger(v);
}

/** La file relue du monde, [[x, y, z], …] ; ce qui n'y ressemble pas est ignoré. */
export function lireFile(texte: unknown): Bloc[] {
  if (typeof texte !== "string") return [];
  let valeur: unknown;
  try {
    valeur = JSON.parse(texte);
  } catch {
    return [];
  }
  if (!Array.isArray(valeur)) return [];
  const blocs: Bloc[] = [];
  for (const v of valeur) {
    if (!Array.isArray(v) || v.length !== 3) continue;
    const [x, y, z] = v as unknown[];
    if (estEntier(x) && estEntier(y) && estEntier(z)) blocs.push({ x, y, z });
  }
  return blocs;
}

/** La file, compacte, pour la propriété dynamique (500 blocs tiennent loin sous sa limite de 32 767 caractères). */
export function ecrireFile(blocs: readonly Bloc[]): string {
  return JSON.stringify(blocs.map((b) => [b.x, b.y, b.z]));
}

/** La file avec [nouveaux] en plus, sans doublon, bornée à [max] (les plus récents gardés). */
export function ajouter(file: readonly Bloc[], nouveaux: readonly Bloc[], max = FILE_MAX): Bloc[] {
  const vus = new Set<string>();
  const tous: Bloc[] = [];
  for (const b of [...file, ...nouveaux]) {
    const cle = cleBloc(b);
    if (vus.has(cle)) continue;
    vus.add(cle);
    tous.push(b);
  }
  return tous.slice(Math.max(0, tous.length - max));
}

/** La file sans les blocs qu'un joueur en course utilise à nouveau (son couloir a été repris entre-temps). */
export function sansLesOccupes(file: readonly Bloc[], occupes: Iterable<Bloc>): Bloc[] {
  const pris = new Set<string>();
  for (const b of occupes) pris.add(cleBloc(b));
  return file.filter((b) => !pris.has(cleBloc(b)));
}

function tronconDe(n: number): number {
  return Math.floor(n / TRONCON) * TRONCON;
}

/**
 * Le tronçon du bloc et ses huit voisins, sur toute la bande du parcours : assez pour un bloc et son voisin de saut,
 * et pour les hologrammes d'un départ (ils débordent sur le tronçon d'à côté).
 */
export function zoneAutour(bloc: Bloc, regles: ReglesParcours = REGLES): Zone {
  const x = tronconDe(bloc.x);
  const z = tronconDe(bloc.z);
  return {
    from: { x: x - TRONCON, y: Math.min(regles.yMin, bloc.y), z: z - TRONCON },
    to: { x: x + 2 * TRONCON - 1, y: Math.max(regles.yMax, bloc.y), z: z + 2 * TRONCON - 1 },
  };
}

/** Le bloc est dans la zone. */
export function dansZone(bloc: Bloc, zone: Zone): boolean {
  return (
    bloc.x >= zone.from.x && bloc.x <= zone.to.x && bloc.y >= zone.from.y && bloc.y <= zone.to.y && bloc.z >= zone.from.z && bloc.z <= zone.to.z
  );
}

/**
 * Le balayage des couloirs 1 à [couloirs] : autour de chaque départ (x = couloir × [ecart], z = 0), [ecart] blocs de
 * large et de -64 à +191 en z (la course part au sud), sur la bande du parcours, en carrés de [cote] blocs alignés sur
 * les tronçons.
 */
export function zonesDeBalayage(couloirs: number, ecart = 256, cote = 32, regles: ReglesParcours = REGLES): Zone[] {
  const zones: Zone[] = [];
  for (let c = 1; c <= couloirs; c += 1) {
    const x0 = c * ecart - ecart / 2;
    for (let x = x0; x < x0 + ecart; x += cote) {
      for (let z = -64; z < 192; z += cote) {
        zones.push({ from: { x, y: regles.yMin, z }, to: { x: x + cote - 1, y: regles.yMax, z: z + cote - 1 } });
      }
    }
  }
  return zones;
}
