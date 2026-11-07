// Le saut infini de la salle d'attente, sans Bedrock : où poser le bloc suivant, quand un saut est réussi, quand le
// joueur est tombé. Tout est pur et testé (test/attente.test.ts) ; attente/main.ts n'y ajoute que les blocs et les joueurs.
//
// Un saut de Minecraft Bedrock, à pied et sans effet : un trou de 3 blocs se franchit en courant, 4 en descendant d'un
// bloc. En montant d'un bloc, 2 au plus. Les écarts tirés restent dans ces bornes, et un pas de côté n'est permis que sur
// les petits trous (1 en montant, 2 sinon) : tout bloc tiré est atteignable.

export interface Bloc {
  readonly x: number;
  readonly y: number;
  readonly z: number;
}

/** Les quatre directions du parcours, sur le plan horizontal. */
export type Cap = "nord" | "est" | "sud" | "ouest";

const AVANCE: Readonly<Record<Cap, { readonly dx: number; readonly dz: number }>> = {
  nord: { dx: 0, dz: -1 },
  est: { dx: 1, dz: 0 },
  sud: { dx: 0, dz: 1 },
  ouest: { dx: -1, dz: 0 },
};
const VIRAGES: Readonly<Record<Cap, readonly [Cap, Cap]>> = {
  nord: ["ouest", "est"],
  est: ["nord", "sud"],
  sud: ["est", "ouest"],
  ouest: ["sud", "nord"],
};

export interface ReglesParcours {
  /** Hauteur la plus basse et la plus haute d'un bloc : le parcours ondule dans cette bande. */
  readonly yMin: number;
  readonly yMax: number;
  /** Chance, de 0 à 1, que le parcours tourne d'un quart de tour au bloc suivant. */
  readonly chanceVirage: number;
  /** Le joueur est tombé quand ses pieds passent à cette distance sous le dernier bloc atteint. */
  readonly chute: number;
}

export const REGLES: ReglesParcours = { yMin: 90, yMax: 130, chanceVirage: 0.15, chute: 4 };

/** Le trou le plus grand (en blocs d'air entre deux blocs) selon la marche : +1 monte, 0 à plat, -1 descend. */
export function trouMax(marche: -1 | 0 | 1): number {
  return marche === 1 ? 2 : marche === 0 ? 3 : 4;
}

export interface Pas {
  readonly bloc: Bloc;
  readonly cap: Cap;
}

/** Un entier tiré de min à max inclus. [hasard] rend un nombre de [0, 1[ (Math.random, ou une suite fixe en test). */
function tirer(hasard: () => number, min: number, max: number): number {
  return min + Math.min(max - min, Math.floor(hasard() * (max - min + 1)));
}

/**
 * Le bloc suivant : un trou de 1 à 4 blocs selon la marche, un pas de côté d'au plus un bloc sur un trou de 2 au plus,
 * parfois un virage. La hauteur reste dans [yMin, yMax] : en haut on ne monte plus, en bas on ne descend plus.
 */
export function prochainBloc(depuis: Pas, hasard: () => number, regles: ReglesParcours = REGLES): Pas {
  const bas = depuis.bloc.y <= regles.yMin ? 0 : -1;
  const haut = depuis.bloc.y >= regles.yMax ? 0 : 1;
  const marche = tirer(hasard, bas, haut) as -1 | 0 | 1;
  const trou = tirer(hasard, 1, trouMax(marche));
  let cap = depuis.cap;
  if (hasard() < regles.chanceVirage) cap = VIRAGES[cap][tirer(hasard, 0, 1) as 0 | 1];
  // Le pas de côté allonge le saut : seulement sur un petit trou (1 en montant, 2 sinon).
  const cote = trou <= (marche === 1 ? 1 : 2) ? tirer(hasard, -1, 1) : 0;
  const avant = AVANCE[cap];
  const lateral = { dx: -avant.dz, dz: avant.dx };
  const pas = trou + 1;
  return {
    cap,
    bloc: {
      x: depuis.bloc.x + avant.dx * pas + lateral.dx * cote,
      y: depuis.bloc.y + marche,
      z: depuis.bloc.z + avant.dz * pas + lateral.dz * cote,
    },
  };
}

export interface Position {
  readonly x: number;
  readonly y: number;
  readonly z: number;
}

/** Les pieds du joueur reposent sur [bloc] : même colonne, et à moins d'un demi-bloc au-dessus de sa face. */
export function surLeBloc(pieds: Position, bloc: Bloc, auSol: boolean): boolean {
  if (!auSol) return false;
  if (Math.floor(pieds.x) !== bloc.x || Math.floor(pieds.z) !== bloc.z) return false;
  const dessus = bloc.y + 1;
  return pieds.y >= dessus - 0.01 && pieds.y <= dessus + 0.5;
}

/** Le joueur est tombé : ses pieds sont passés sous le dernier bloc atteint, de [regles.chute] blocs. */
export function estTombe(pieds: Position, dernier: Bloc, regles: ReglesParcours = REGLES): boolean {
  return pieds.y < dernier.y - regles.chute;
}

/**
 * Le départ d'un joueur : un couloir à lui, pour que deux joueurs ne se marchent pas dessus. Les couloirs sont espacés
 * de [ecart] blocs le long de x, à mi-hauteur de la bande, cap au sud.
 */
export function depart(couloir: number, ecart = 256, regles: ReglesParcours = REGLES): Pas {
  return { cap: "sud", bloc: { x: couloir * ecart, y: Math.round((regles.yMin + regles.yMax) / 2), z: 0 } };
}

/** Le plus petit numéro de couloir libre. */
export function couloirLibre(pris: Iterable<number>): number {
  const occupes = new Set(pris);
  let n = 1;
  while (occupes.has(n)) n += 1;
  return n;
}
