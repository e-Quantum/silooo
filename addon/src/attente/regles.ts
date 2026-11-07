// Les chiffres de la salle d'attente : definitions/attente.json, recopié dans le script par esbuild. Le fichier est vérifié
// contre son schéma par tools/verifier_definitions.mjs ; ici, il est seulement lu et typé. Aucun chiffre ailleurs.

import brut from "../../../definitions/attente.json";

export interface Palier {
  /** Le palier s'applique dès ce score. */
  readonly des: number;
  /** Chance, de 0 à 1, que le parcours tourne d'un quart de tour au bloc suivant. */
  readonly chanceVirage: number;
  /** Chance, de 0 à 1, de tirer le plus grand trou de la marche (sinon un trou plus petit, au hasard). */
  readonly chanceGrandTrou: number;
}

export interface ReglesParcours {
  readonly yMin: number;
  readonly yMax: number;
  /** Le joueur est tombé quand ses pieds passent à cette distance sous le dernier bloc atteint. */
  readonly chute: number;
  /** Le plus grand trou (en blocs d'air) selon la marche. */
  readonly trouMax: { readonly monte: number; readonly plat: number; readonly descend: number };
  /** Du premier (des 0) au dernier, dans l'ordre croissant. */
  readonly paliers: readonly Palier[];
}

export interface ReglesAttente {
  readonly parcours: ReglesParcours;
  readonly classement: { readonly taille: number; readonly actus: number; readonly hologramme: number };
  readonly rappels: { readonly tousLesPoints: number; readonly intervalleSecondes: number };
  readonly rythmes: {
    readonly accueilSecondes: number;
    readonly accueilEssais: number;
    readonly lienSecondes: number;
    readonly tableauSecondes: number;
    readonly boussoleSecondes: number;
  };
}

export const ATTENTE: ReglesAttente = brut;
export const REGLES: ReglesParcours = ATTENTE.parcours;

/** Le palier du score [score] : le dernier dont le seuil est atteint. */
export function palierDe(score: number, regles: ReglesParcours = REGLES): Palier {
  let trouve = regles.paliers[0]!;
  for (const palier of regles.paliers) if (score >= palier.des) trouve = palier;
  return trouve;
}

/** Le score franchit un palier (autre que le premier) en passant de [avant] à [apres]. */
export function nouveauPalier(avant: number, apres: number, regles: ReglesParcours = REGLES): boolean {
  return regles.paliers.some((palier) => palier.des > 0 && avant < palier.des && apres >= palier.des);
}
