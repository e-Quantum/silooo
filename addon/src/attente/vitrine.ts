// Ce que la salle d'attente montre autour du départ et dans la barre latérale, sans Bedrock : le texte et la place des
// hologrammes, les lignes du classement (une par joueur, telles que le service les rend), et les rappels mesurés.

import type { LobbyBoard } from "../protocol";
import type { Bloc } from "./parcours";
import { ATTENTE, type ReglesAttente } from "./regles";
import { HOLOGRAMME_TEXTES, ACCUEIL_TEXTES } from "./textes";

export interface Hologramme {
  /** Le rôle de l'hologramme, gardé dans une étiquette de l'entité : le ménage et les mises à jour le retrouvent. */
  readonly role: "silooo" | "liens" | "infos" | "semaine";
  /** Décalage depuis le bloc de départ du couloir (le parcours part vers le sud, +z). */
  readonly dx: number;
  readonly dy: number;
  readonly dz: number;
}

/**
 * Quatre hologrammes de part et d'autre du départ, à hauteur des yeux, un peu en avant : le joueur les voit en arrivant,
 * et le premier saut part entre eux. Le nom d'une entité fait toujours face à la caméra : l'orientation n'importe pas.
 */
export const HOLOGRAMMES: readonly Hologramme[] = [
  { role: "silooo", dx: -3, dy: 3, dz: 3 },
  { role: "liens", dx: 3, dy: 3, dz: 3 },
  { role: "infos", dx: -4, dy: 2, dz: 7 },
  { role: "semaine", dx: 4, dy: 2, dz: 7 },
];

export function positionHologramme(depart: Bloc, h: Hologramme): { x: number; y: number; z: number } {
  return { x: depart.x + h.dx + 0.5, y: depart.y + h.dy, z: depart.z + h.dz + 0.5 };
}

/** Le texte d'un hologramme ; sans tableau (service muet), les deux derniers gardent leur titre seul. */
export function texteHologramme(role: Hologramme["role"], tableau: LobbyBoard | undefined, regles: ReglesAttente = ATTENTE): string {
  switch (role) {
    case "silooo":
      return HOLOGRAMME_TEXTES.silooo;
    case "liens":
      return HOLOGRAMME_TEXTES.liens;
    case "infos": {
      const titres = (tableau?.news ?? []).slice(0, regles.classement.actus).map((n) => `§7${n.title}`);
      return [HOLOGRAMME_TEXTES.infos, ...titres].join("\n");
    }
    case "semaine": {
      if (tableau === undefined) return HOLOGRAMME_TEXTES.semaine;
      const lignes = tableau.week.slice(0, regles.classement.hologramme).map((l) => HOLOGRAMME_TEXTES.ligne(l.rank, l.gamertag, l.score));
      if (lignes.length === 0) lignes.push(HOLOGRAMME_TEXTES.aucunSaut);
      if (tableau.pioneers > 0) lignes.push(ACCUEIL_TEXTES.pionniers(tableau.pioneers));
      return [HOLOGRAMME_TEXTES.semaine, ...lignes].join("\n");
    }
  }
}

/**
 * Les lignes de la barre latérale : le classement de tous les temps tel que le service le rend (une ligne par XUID),
 * coupé à la taille du fichier de définitions. Deux lignes au même nom (deux XUID au même gamertag, cas d'école) n'en
 * font qu'une : la barre latérale ne garde qu'un score par nom.
 */
export function lignesClassement(tableau: LobbyBoard, regles: ReglesAttente = ATTENTE): Array<{ nom: string; score: number }> {
  const vus = new Set<string>();
  const lignes: Array<{ nom: string; score: number }> = [];
  for (const ligne of tableau.allTime) {
    if (vus.has(ligne.gamertag)) continue;
    vus.add(ligne.gamertag);
    lignes.push({ nom: ligne.gamertag, score: ligne.score });
    if (lignes.length >= regles.classement.taille) break;
  }
  return lignes;
}

/** Les rappels Discord d'un joueur : au plus un par intervalle, aucun pour un joueur relié. */
export interface Rappels {
  /** Le score franchit un multiple de `tousLesPoints` et l'intervalle est passé depuis le dernier rappel. */
  auScore(score: number, maintenant: number, relie: boolean): boolean;
  /** Le premier record battu de la session (et l'intervalle passé) : un message de tchat. */
  auRecord(maintenant: number, relie: boolean): boolean;
}

export function creerRappels(regles: ReglesAttente = ATTENTE): Rappels {
  let dernier = Number.NEGATIVE_INFINITY;
  let recordDit = false;
  const libre = (maintenant: number) => maintenant - dernier >= regles.rappels.intervalleSecondes * 1000;
  return {
    auScore(score, maintenant, relie) {
      if (relie || score <= 0 || score % regles.rappels.tousLesPoints !== 0 || !libre(maintenant)) return false;
      dernier = maintenant;
      return true;
    },
    auRecord(maintenant, relie) {
      if (relie || recordDit || !libre(maintenant)) return false;
      recordDit = true;
      dernier = maintenant;
      return true;
    },
  };
}
